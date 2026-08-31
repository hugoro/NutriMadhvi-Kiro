package com.example.data.export

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.ChatDatabase
import com.example.data.remote.GeminiRepository

/**
 * Daily background backup of `diario_nutricao.xlsx` into the Drive folder the
 * user previously chose via SAF. Triggered two ways:
 *  1. **PeriodicWorkRequest** — enqueued once in [com.example.MainActivity.onCreate]
 *     with a 1-day repeat, runs whenever Android schedules the slot after midnight
 *     (Doze can push it forward by hours; that's fine — the file is overwritten,
 *     not appended, so there's no time-sensitive correctness).
 *  2. **OneTimeWorkRequest catch-up** — also enqueued from [MainActivity.onCreate]
 *     when [DriveExporter.shouldRunDailyCatchUp] returns true, i.e. the app opens
 *     on a calendar day strictly after the last successful export's day. This
 *     covers the case where the device was offline / Doze-killed across the
 *     periodic slot.
 *
 * No-ops cleanly when the user has never picked a Drive folder — the user is in
 * full control via the Reports tab; we never surface a permission prompt from
 * a background context.
 *
 * Failures escalate via [androidx.work.ListenableWorker.Result.retry] up to
 * [MAX_RETRIES]; after that we [androidx.work.ListenableWorker.Result.failure]
 * and let the next periodic slot try again — instead of repeatedly hammering the
 * Drive provider when something is fundamentally broken (e.g. revoked SAF
 * permission). The user's manual button always works as the in-app escape hatch.
 */
class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DriveBackupWorker"
        const val UNIQUE_NAME = "drive_daily_backup"
        private const val MAX_RETRIES = 3
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(DriveExporter.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(DriveExporter.KEY_FOLDER_URI, null).isNullOrBlank()) {
            Log.d(TAG, "no folder selected — nothing to back up")
            return Result.success()
        }

        return try {
            val database = ChatDatabase.getInstance(ctx)
            val repository = GeminiRepository(
                database.chatDao,
                database.foodDao,
                database.activityDao,
                database.foodHabitDao,
                database.targetSnapshotDao,
                database.bmrSnapshotDao,
                database.bodyCompositionSnapshotDao,
                database.dailyActivitySnapshotDao,
                database.userInsightDao
            )
            // Language for spreadsheet headers comes from the user profile prefs
            // (same key that ChatViewModel reads). Defaults to pt-PT to match the
            // app default. Backup never blocks on UI state.
            val language = ctx.getSharedPreferences("user_profile_pref", Context.MODE_PRIVATE)
                .getString("profile_language", "pt-PT") ?: "pt-PT"

            val inputs = DriveExporter.buildInputsFromRepo(repository, language)
            val result = DriveExporter.export(ctx, inputs)
            when (result) {
                is DriveExporter.ExportResult.Success -> {
                    Log.i(TAG, "backup ok: ${result.fileName} ${result.bytesWritten}B")
                    // Piggy-back the DB snapshot onto the XLSX slot. A failure
                    // here doesn't fail the worker — the XLSX is the user-facing
                    // contract; the .db is a safety net.
                    when (val dbResult = DbBackupExporter.export(ctx)) {
                        is DbBackupExporter.Result.Success ->
                            Log.i(TAG, "db backup ok: ${dbResult.bytesWritten}B")
                        is DbBackupExporter.Result.Failure ->
                            Log.w(TAG, "db backup failed: ${dbResult.message}")
                    }
                    Result.success()
                }
                is DriveExporter.ExportResult.Failure -> {
                    Log.w(TAG, "backup failed: ${result.reason} ${result.message}")
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "unexpected", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }
}
