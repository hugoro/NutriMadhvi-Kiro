package com.example.data.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.activity.DailyActivityResolver
import com.example.data.local.ChatDatabase
import com.example.data.local.DailyActivitySnapshotEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs once per day at ~04:00 to freeze a snapshot of yesterday's active-calories
 * total into the [DailyActivitySnapshotEntity] table. The snapshot is canonical
 * for that day going forward — the dashboard and reports tab read it verbatim
 * instead of re-deriving from Samsung Health.
 *
 * ## Why 04:00 and not 02:00
 *
 * Samsung Health does settlement of the day's "active calories" aggregate after
 * the user finishes interacting with the device for the day. Running at 02:00
 * sometimes captures partial values that change later when the watch/phone sync
 * the last batch of HR/movement bins. 04:00 gives a comfortable buffer and the
 * user is still asleep so we don't compete with the foreground refresh.
 *
 * ## Why snapshot rather than re-derive
 *
 * Samsung Health on some devices (Pixel 10 observed 2026-06-26) shifts workout
 * timestamps and edits exercise records after the fact. A user looking at last
 * Tuesday's report expects to see what they saw on Tuesday, not whatever SH has
 * decided Tuesday looks like today. Freezing once, on the day the value was
 * current, removes that drift.
 *
 * ## Source of truth: the SH daily aggregate
 *
 * We snapshot `TOTAL_ACTIVE_CALORIES_BURNED` (the value the SH home tile shows),
 * falling back to `workouts.sum` only when the aggregate is zero. See
 * [DailyActivityResolver.computeLiveKcalForDay] for the order of preference.
 *
 * Earlier (2026-06-26) this worker computed `workouts.sum + steps × 0.015` —
 * that hand-rolled formula double-counted in-workout steps on high-step days
 * and was retired on 2026-06-27.
 *
 * ## Back-fill behaviour
 *
 * Each run snapshots **D-1** (yesterday in local time). Additionally, if the
 * snapshot table is empty or missing days between D-1 and the latest existing
 * row, the worker back-fills up to [MAX_BACKFILL_DAYS] days. SH typically keeps
 * detailed exercise/step data for ~30-90 days; we don't try to go further back.
 *
 * ## Failure modes
 *
 *  - SH SDK not available / connection refused → return Result.success() (no
 *    snapshot for this day; we'll try again tomorrow). Don't retry aggressively;
 *    the user might have uninstalled SH or revoked permissions.
 *  - Empty workout list AND aggregate=0 → still write a snapshot with
 *    activeKcal=0. A genuine zero-activity day is data, not noise.
 *
 * Enqueued from `MainActivity.scheduleDailyActivitySnapshot`. Cadence: daily,
 * initial delay calculated to hit ~04:00 local on next run.
 */
class DailyActivitySnapshotWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DailyActSnap"
        const val UNIQUE_NAME = "daily_activity_snapshot"

        /** Hard cap on back-fill — SH doesn't keep workout data forever, and
         *  pulling more than this on a single run risks timing out. */
        private const val MAX_BACKFILL_DAYS = 30
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val database = ChatDatabase.getInstance(ctx)
        val dao = database.dailyActivitySnapshotDao
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)

        // Determine which days need a snapshot. Always include yesterday;
        // optionally back-fill earlier missing days up to MAX_BACKFILL_DAYS.
        val daysToSnapshot = mutableListOf<LocalDate>()
        val latestStoredEpoch = dao.latestDay()
        val backfillStart = if (latestStoredEpoch == null) {
            // First run on this install — back-fill the last week so the
            // dashboard's weekly view has data immediately.
            yesterday.minusDays(6L)
        } else {
            val latestStored = LocalDate.ofEpochDay(latestStoredEpoch)
            val nextMissing = latestStored.plusDays(1)
            // Cap how far we'd back-fill to avoid runaway on a long-dormant install.
            val maxBack = yesterday.minusDays(MAX_BACKFILL_DAYS.toLong() - 1)
            if (nextMissing.isBefore(maxBack)) maxBack else nextMissing
        }
        var d = backfillStart
        while (!d.isAfter(yesterday)) {
            if (dao.findByDay(d.toEpochDay()) == null) {
                daysToSnapshot.add(d)
            }
            d = d.plusDays(1)
        }
        if (daysToSnapshot.isEmpty()) {
            Log.i(TAG, "no missing snapshots to capture (latest=$latestStoredEpoch, yesterday=$yesterday)")
            return Result.success()
        }

        // Connect to Samsung Health and pull workouts + daily summary + steps
        // for the full range in one shot — cheaper than per-day calls and
        // matches the SDK's batched aggregate semantics.
        val repo = try {
            SamsungHealthRepository(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "SH SDK not available, skipping snapshot: ${e.message}")
            return Result.success()
        }
        val status = repo.status()
        if (status != SamsungHealthRepository.Status.AVAILABLE) {
            Log.w(TAG, "SH not available (status=$status), skipping")
            return Result.success()
        }

        val rangeStart = daysToSnapshot.first()
        val rangeEnd = daysToSnapshot.last()
        // Use the start of `rangeStart` and the end of `rangeEnd` in local time.
        // SH SDK reads via LocalTimeFilter so this is the natural unit.
        val startInstant = rangeStart.atStartOfDay(zone).toInstant()
        val endInstant = rangeEnd.plusDays(1).atStartOfDay(zone).toInstant()
        val workouts = try {
            repo.readWorkouts(startInstant, endInstant)
        } catch (e: Exception) {
            Log.w(TAG, "readWorkouts failed: ${e.message}")
            emptyList()
        }
        val summaryByDay = try {
            repo.readDailyActivitySummary(startInstant, endInstant)
        } catch (e: Exception) {
            Log.w(TAG, "readDailyActivitySummary failed: ${e.message}")
            emptyMap()
        }
        val stepsByDay = try {
            repo.readDailySteps(startInstant, endInstant)
        } catch (e: Exception) {
            Log.w(TAG, "readDailySteps failed: ${e.message}")
            emptyMap()
        }

        // Compute and persist each missing day.
        for (day in daysToSnapshot) {
            val steps = stepsByDay[day] ?: 0L
            val summary = summaryByDay[day]
            val live = DailyActivityResolver.computeLiveKcalForDay(day, workouts, summary, steps, zone)
            val entity = DailyActivitySnapshotEntity(
                dateEpochDay = day.toEpochDay(),
                activeKcal = live.activeKcal,
                workoutsKcal = live.workoutsKcal,
                steps = live.steps,
                activeMinutes = live.activeMinutes,
                capturedAtMs = System.currentTimeMillis()
            )
            dao.upsert(entity)
            // Diagnostic reconciliation log — Hugo compares this against the SH
            // home tile screenshot on the same day to assess aggregate-vs-tile
            // drift over time. Tag aligns with the [[samsung-health-debug-logging]]
            // taxonomy memory.
            Log.i(
                "SHD#kcalReconcile",
                "day=$day activeKcal=${live.activeKcal} aggregate=${live.aggregateKcal} " +
                    "workoutsSum=${live.workoutsKcal} steps=${live.steps} " +
                    "activeMin=${live.activeMinutes} " +
                    "device=${android.os.Build.MODEL}"
            )
            Log.i(
                TAG,
                "snapshot $day: activeKcal=${live.activeKcal} " +
                    "(aggregate=${live.aggregateKcal}, workouts=${live.workoutsKcal}) " +
                    "steps=${live.steps} activeMin=${live.activeMinutes}"
            )
        }

        return Result.success()
    }
}

/**
 * Helper used by the dashboard / report layers to also opportunistically capture
 * a missing snapshot whenever the user opens the app — covers the case where the
 * scheduled worker hasn't fired yet (e.g. user opens the app at 01:00).
 *
 * Additionally, if a snapshot for D-1 already exists but the SH aggregate has
 * drifted >10% from the snapshot value since (Samsung's known retroactive
 * back-fill behaviour on the Pixel 10), the snapshot is refreshed in place.
 * This keeps the dashboard coherent with what the user sees in the SH app
 * without requiring a manual re-sync.
 *
 * Idempotent: if the day already has a snapshot and the drift is below the
 * threshold, this is a no-op DB read.
 */
suspend fun maybeCaptureSnapshotForYesterdayInline(
    ctx: Context,
    repo: SamsungHealthRepository
) {
    val database = ChatDatabase.getInstance(ctx)
    val dao = database.dailyActivitySnapshotDao
    val zone = ZoneId.systemDefault()
    val yesterday = LocalDate.now(zone).minusDays(1)
    val existing = dao.findByDay(yesterday.toEpochDay())
    Log.i("DailyActSnap", "inline entered for $yesterday (existing=${existing?.activeKcal})")

    val status = try { repo.status() } catch (e: Exception) {
        Log.w("DailyActSnap", "inline status() failed: ${e.message}")
        null
    }
    if (status != SamsungHealthRepository.Status.AVAILABLE) {
        Log.i("DailyActSnap", "inline skipped: SH status=$status")
        return
    }

    val startInstant = yesterday.atStartOfDay(zone).toInstant()
    val endInstant = yesterday.plusDays(1).atStartOfDay(zone).toInstant()
    val workouts = try { repo.readWorkouts(startInstant, endInstant) } catch (_: Exception) { return }
    val summaryByDay = try { repo.readDailyActivitySummary(startInstant, endInstant) } catch (_: Exception) { emptyMap() }
    val stepsByDay = try { repo.readDailySteps(startInstant, endInstant) } catch (_: Exception) { return }
    val steps = stepsByDay[yesterday] ?: 0L
    val summary = summaryByDay[yesterday]
    val live = DailyActivityResolver.computeLiveKcalForDay(yesterday, workouts, summary, steps, zone)

    // If we already have a snapshot, only refresh when the drift is non-trivial.
    if (existing != null) {
        val driftPct = if (existing.activeKcal > 0) {
            kotlin.math.abs(existing.activeKcal - live.activeKcal).toDouble() / existing.activeKcal
        } else if (live.activeKcal > 0) 1.0 else 0.0
        if (driftPct < 0.10) return
        Log.i(
            "DailyActSnap",
            "re-sync D-1 ($yesterday): snapshot=${existing.activeKcal} -> live=${live.activeKcal} " +
                "(drift=${"%.1f".format(driftPct * 100)}%)"
        )
    }

    dao.upsert(
        DailyActivitySnapshotEntity(
            dateEpochDay = yesterday.toEpochDay(),
            activeKcal = live.activeKcal,
            workoutsKcal = live.workoutsKcal,
            steps = live.steps,
            activeMinutes = live.activeMinutes,
            capturedAtMs = System.currentTimeMillis()
        )
    )
    Log.i(
        "SHD#kcalReconcile",
        "day=$yesterday activeKcal=${live.activeKcal} aggregate=${live.aggregateKcal} " +
            "workoutsSum=${live.workoutsKcal} steps=${live.steps} " +
            "activeMin=${live.activeMinutes} " +
            "device=${android.os.Build.MODEL} src=inline"
    )
    Log.i(
        "DailyActSnap",
        "inline snapshot $yesterday: activeKcal=${live.activeKcal} " +
            "(aggregate=${live.aggregateKcal}, workouts=${live.workoutsKcal})"
    )
}
