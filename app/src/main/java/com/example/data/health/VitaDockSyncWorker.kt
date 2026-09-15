package com.example.data.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.ChatDatabase

private const val TAG = "VitaDockSyncWorker"
const val PREFS_VITADOCK = "vitadock_prefs"
const val KEY_VITADOCK_EMAIL = "email"
const val KEY_VITADOCK_PASSWORD = "password"

/**
 * One-shot worker that fetches TargetScale readings from VitaDock Online using
 * a fresh login each time (no session cookies to expire).
 *
 * Fetch-all + upsert: the JSON dataset is small (~220 KB / ~180 readings), so
 * we fetch the whole thing and reconcile by source timestamp rather than doing
 * a strict `ts > latest` delta. This lets the worker *self-heal* rows that an
 * earlier build stored weight-only (the old HTML-scrape bug left recent
 * weigh-ins with null fat/muscle/water): when the portal now returns a complete
 * reading for a timestamp we already have as weight-only, we replace it. Rows
 * that are already complete are left untouched; genuinely new rows are inserted.
 */
class VitaDockSyncWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<VitaDockSyncWorker>().build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences(PREFS_VITADOCK, Context.MODE_PRIVATE)
        val email = prefs.getString(KEY_VITADOCK_EMAIL, null)
        val password = prefs.getString(KEY_VITADOCK_PASSWORD, null)

        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            Log.i(TAG, "no VitaDock credentials configured — skipping")
            return Result.success()
        }

        val dao = ChatDatabase.getInstance(context).bodyCompositionSnapshotDao

        // Fetch all (afterTimestampMs = 0) so we can upgrade stale weight-only
        // rows, not just append rows newer than the latest.
        return when (val result = VitaDockScraper.fetchDelta(context, email, password, 0L)) {
            is VitaDockResult.Error -> {
                Log.w(TAG, "fetch error: ${result.message}")
                Result.retry()
            }

            is VitaDockResult.Success -> {
                var inserted = 0
                var upgraded = 0
                for (reading in result.readings) {
                    val existing = dao.getForSourceTimestamp(reading.timestampMs)
                    if (existing == null) {
                        dao.insert(reading.toEntity())
                        inserted++
                        continue
                    }
                    // Replace only if the stored row is weight-only AND the new
                    // reading actually carries composition to add.
                    val storedIsWeightOnly = existing.bodyFatPct == null &&
                        existing.muscleMassKg == null &&
                        existing.totalBodyWaterKg == null
                    val fetchedHasComposition = reading.bodyFatPct != null ||
                        reading.muscleMassPct != null ||
                        reading.bodyWaterPct != null
                    if (storedIsWeightOnly && fetchedHasComposition) {
                        dao.deleteForSourceTimestamp(reading.timestampMs)
                        dao.insert(reading.toEntity())
                        upgraded++
                    }
                }
                Log.i(TAG, "VitaDock sync: inserted $inserted, upgraded $upgraded (of ${result.readings.size} fetched)")
                Result.success()
            }
        }
    }
}

private fun VitaDockReading.toEntity() = BodyCompositionSnapshotEntity(
    sourceTimestampMs = timestampMs,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    muscleMassKg = muscleMassPct?.let { pct -> weightKg * (pct / 100f) },
    totalBodyWaterKg = bodyWaterPct?.let { pct -> weightKg * (pct / 100f) },
    bmiValue = bmi,
)
