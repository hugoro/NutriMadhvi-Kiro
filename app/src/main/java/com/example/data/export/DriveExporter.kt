package com.example.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.health.BmrSnapshot
import com.example.data.health.BmrSource
import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.BmrSnapshotEntity
import com.example.data.local.DailyActivitySnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.remote.GeminiRepository
import com.example.ui.ReportActivityRow
import com.example.ui.buildReportActivityRows
import kotlinx.coroutines.flow.firstOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single source of truth for writing the canonical `diario_nutricao.xlsx` blob into
 * the user-chosen Drive folder. Reused by:
 *  - the manual "Export to Drive" button on the Reports tab (via [ChatViewModel])
 *  - the [DriveBackupWorker] daily background job + the catch-up one-shot fired
 *    when the app opens on a new day.
 *
 * The folder URI lives in the existing `nutrition_export` SharedPreferences file
 * (key `folder_uri`). The success timestamp is persisted in the same file under
 * `last_export_at_ms` so the UI's "Last export: …" row and the catch-up trigger in
 * [MainActivity] both read the same value regardless of who wrote it last.
 *
 * Defensive against the historical 0K bug: writes go through `flush()`, the
 * resulting file size is read back via the ContentResolver, and a zero-byte
 * outcome is reported as an error instead of being mis-celebrated as a success.
 */
object DriveExporter {

    private const val TAG = "DriveExporter"
    const val PREFS_NAME = "nutrition_export"
    const val KEY_FOLDER_URI = "folder_uri"
    const val KEY_FOLDER_NAME = "folder_name"
    const val KEY_LAST_EXPORT_AT_MS = "last_export_at_ms"
    const val DEFAULT_FILE_NAME = "diario_nutricao.xlsx"
    private const val MIME_XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** Result of a single export attempt. [bytesWritten] is the on-disk size read back
     *  via the ContentResolver after [java.io.OutputStream.flush]; if the document
     *  provider produced a 0-byte stub (Hipótese A in the plan) we surface this as
     *  an error rather than as a fake success. */
    sealed class ExportResult {
        /** [fileUri] is the SAF document URI of the file that was just written. The
         *  Reports tab passes it to `Intent.ACTION_VIEW` so tapping the success
         *  banner opens the freshly-uploaded XLSX in Google Sheets / Drive. Stored
         *  as a String so the type stays trivially serialisable across the
         *  ViewModel boundary. */
        data class Success(
            val fileName: String,
            val folderName: String?,
            val bytesWritten: Long,
            val fileUri: String
        ) : ExportResult()
        data class Failure(val reason: Failure.Reason, val message: String) : ExportResult() {
            enum class Reason { NO_FOLDER, FOLDER_UNREACHABLE, BUILDER_EMPTY, CREATE_FAILED, OPEN_OUTPUT_FAILED, ZERO_BYTES_AFTER_WRITE, PERMISSION_REVOKED, IO, UNKNOWN }
        }
    }

    /** All inputs the XLSX builder needs. Filled by the ViewModel from its in-memory
     *  Samsung Health flows on the manual path; the Worker passes empty SH maps so
     *  the resulting workbook covers DB rows only. The format itself is unchanged
     *  in either case — the SH columns are simply blank when unavailable.
     *
     *  The [dailyActivitySnapshots] / [shWorkoutsRaw] / [shDailyByDate] /
     *  [bmrSnapshotsAll] / [shAvailable] block is the input surface the
     *  [NutritionXlsxExporter] uses to compute the per-day "Gasto Total (kcal)"
     *  column via [com.example.data.activity.DailyActivityResolver] +
     *  [com.example.data.bmr.BmrResolver] — mirroring the Análise-tab invariant
     *  exactly. Defaults keep old call sites source-compatible. */
    data class ExportInputs(
        val foods: List<FoodEntry>,
        val activitiesManual: List<ActivityEntry>,
        val mergedActivityRows: List<ReportActivityRow>,
        val stepsByDate: Map<LocalDate, Long>,
        val shWorkoutsByDate: Map<LocalDate, List<ReportActivityRow>>,
        val bmrSnapshot: BmrSnapshot?,
        val targetsByDay: Map<LocalDate, com.example.data.local.TargetSnapshot>,
        val language: String,
        /** Frozen daily snapshots. `DailyActivityResolver` prefers these for past days. */
        val dailyActivitySnapshots: List<DailyActivitySnapshotEntity> = emptyList(),
        /** Live SH workouts (all of them; resolver re-buckets by day). Empty when SH unavailable. */
        val shWorkoutsRaw: List<ShWorkout> = emptyList(),
        /** Live SH daily aggregate per day. Empty when SH unavailable. */
        val shDailyByDate: Map<LocalDate, ShDailyActivity> = emptyMap(),
        /** Full BMR history (sorted ascending by effective day per DAO contract).
         *  Preferred over the single-value [bmrSnapshot] when non-empty — the
         *  exporter uses [com.example.data.bmr.BmrResolver.resolveBmrByDay] over
         *  this list so historic days pick the BMR in force that day. */
        val bmrSnapshotsAll: List<BmrSnapshotEntity> = emptyList(),
        /** True when SH was authenticated at export time. Worker always passes false. */
        val shAvailable: Boolean = false,
    )

    /**
     * Writes the XLSX into the previously selected Drive folder. On success persists
     * the current epoch ms into `last_export_at_ms` so all consumers — the UI and the
     * daily catch-up trigger — see a consistent timestamp.
     */
    suspend fun export(
        context: Context,
        inputs: ExportInputs,
        filenameOverride: String? = null
    ): ExportResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriStr = prefs.getString(KEY_FOLDER_URI, null)
        val folderName = prefs.getString(KEY_FOLDER_NAME, null)
        if (folderUriStr.isNullOrBlank()) {
            return ExportResult.Failure(ExportResult.Failure.Reason.NO_FOLDER, "No Drive folder selected")
        }

        return try {
            val folderDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
                ?: return ExportResult.Failure(
                    ExportResult.Failure.Reason.FOLDER_UNREACHABLE,
                    "Drive folder unreachable"
                )

            val bytes = NutritionXlsxExporter.build(
                foods = inputs.foods,
                activities = inputs.mergedActivityRows,
                stepsByDate = inputs.stepsByDate,
                shWorkoutsByDate = inputs.shWorkoutsByDate,
                bmrSnapshot = inputs.bmrSnapshot,
                language = inputs.language,
                targetsByDay = inputs.targetsByDay,
                activitiesManual = inputs.activitiesManual,
                dailyActivitySnapshots = inputs.dailyActivitySnapshots,
                shWorkoutsRaw = inputs.shWorkoutsRaw,
                shDailyByDate = inputs.shDailyByDate,
                bmrSnapshotsAll = inputs.bmrSnapshotsAll,
                shAvailable = inputs.shAvailable,
            )
            Log.d(TAG, "builder produced bytes=${bytes.size}")
            if (bytes.isEmpty()) {
                return ExportResult.Failure(
                    ExportResult.Failure.Reason.BUILDER_EMPTY,
                    "XLSX builder returned 0 bytes"
                )
            }

            val fileName = filenameOverride ?: DEFAULT_FILE_NAME
            // Drop the previous version so the new one replaces it. Without the delete
            // some Drive providers keep both and rename to "diario_nutricao (1).xlsx".
            folderDoc.findFile(fileName)?.delete()
            val newFile = folderDoc.createFile(MIME_XLSX, fileName)
                ?: return ExportResult.Failure(
                    ExportResult.Failure.Reason.CREATE_FAILED,
                    "Could not create the file in the chosen folder"
                )

            val opened = context.contentResolver.openOutputStream(newFile.uri, "w")
                ?: return ExportResult.Failure(
                    ExportResult.Failure.Reason.OPEN_OUTPUT_FAILED,
                    "Could not open the file for writing"
                )
            opened.use { os ->
                os.write(bytes)
                os.flush()
                // best-effort fsync — only meaningful when the provider hands us a real
                // FileOutputStream. SAF wrappers usually don't, in which case we simply
                // skip the sync and rely on the upcoming verification step to catch any
                // truncation.
                if (os is java.io.FileOutputStream) {
                    try { os.fd.sync() } catch (_: Exception) { /* ignore */ }
                }
            }

            // Verify what actually landed on disk. statSize() reflects the provider's
            // own view of the file, so if the Drive provider produced a 0-byte stub
            // (Hipótese A) we now see it instead of mis-celebrating success.
            val writtenLen = readFileSizeOrNull(context, newFile.uri)
            Log.d(TAG, "writtenLen=$writtenLen for ${newFile.uri}")
            if (writtenLen == null || writtenLen <= 0L) {
                return ExportResult.Failure(
                    ExportResult.Failure.Reason.ZERO_BYTES_AFTER_WRITE,
                    "File appears empty after write"
                )
            }

            val nowMs = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_EXPORT_AT_MS, nowMs).apply()
            ExportResult.Success(fileName, folderName, writtenLen, newFile.uri.toString())
        } catch (e: SecurityException) {
            Log.w(TAG, "permission revoked", e)
            ExportResult.Failure(ExportResult.Failure.Reason.PERMISSION_REVOKED, e.message ?: "Permission revoked")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "IO failure", e)
            ExportResult.Failure(ExportResult.Failure.Reason.IO, "IO: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "unknown failure", e)
            ExportResult.Failure(ExportResult.Failure.Reason.UNKNOWN, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readFileSizeOrNull(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Headless input builder used by [DriveBackupWorker]: loads everything the XLSX
     * needs straight from the database — no Samsung Health (the Worker can't
     * authenticate interactively, and SH data is only ever held in memory by the
     * ViewModel anyway). The Reports tab path keeps using its richer in-memory SH
     * snapshot via [ExportInputs] directly.
     *
     * The XLSX format itself is identical in both paths; SH columns simply degrade
     * to blanks/zeros when SH wasn't available, which is the same behaviour you
     * already see for users who never granted SH permission.
     */
    suspend fun buildInputsFromRepo(
        repository: GeminiRepository,
        language: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExportInputs {
        val foods = repository.allFoods.firstOrNull() ?: emptyList()
        val activities = repository.allActivities.firstOrNull() ?: emptyList()
        val mergedActivityRows = buildReportActivityRows(
            manuals = activities,
            shWorkouts = emptyList(),
            shDaily = emptyMap(),
            shAvailable = false,
            zoneId = zoneId
        )

        val targetSnapshotsList = repository.listTargetSnapshots()
        val targetsByDay: Map<LocalDate, com.example.data.local.TargetSnapshot> =
            if (foods.isEmpty() || targetSnapshotsList.isEmpty()) emptyMap()
            else {
                val datesPresent = foods
                    .map { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }
                    .toSortedSet()
                com.example.data.targets.TargetResolver.resolveTargetsByDay(
                    targetSnapshotsList,
                    datesPresent.first()..datesPresent.last()
                )
            }

        // Latest BMR snapshot, mapped back to the BmrSnapshot shape the exporter
        // expects. null when the table is empty (fresh install) — the exporter
        // already handles that case.
        val bmrSnapshotsList = repository.bmrSnapshots.firstOrNull() ?: emptyList()
        val latestBmrRow = bmrSnapshotsList.lastOrNull()
        val bmrSnapshot = latestBmrRow?.let { row ->
            BmrSnapshot(
                value = row.valueKcal,
                source = if (row.source == com.example.data.local.BmrSnapshotEntity.SOURCE_SH)
                    BmrSource.SAMSUNG_HEALTH else BmrSource.MANUAL,
                timestamp = row.sourceTimestampMs?.let { Instant.ofEpochMilli(it) }
            )
        }

        // Frozen daily activity snapshots — the exporter's Gasto Total path
        // prefers these for any past day (snapshot-first invariant from
        // DailyActivityResolver). Empty on fresh install → past days fall
        // through to the live path which, in the worker, has no SH data
        // → 0 kcal SH-active contribution for those days (BMR + manuals still
        // land in the total).
        val dailyActivitySnapshotsList =
            repository.dailyActivitySnapshots.firstOrNull() ?: emptyList()

        return ExportInputs(
            foods = foods,
            activitiesManual = activities,
            mergedActivityRows = mergedActivityRows,
            stepsByDate = emptyMap(),
            shWorkoutsByDate = emptyMap(),
            bmrSnapshot = bmrSnapshot,
            targetsByDay = targetsByDay,
            language = language,
            dailyActivitySnapshots = dailyActivitySnapshotsList,
            shWorkoutsRaw = emptyList(),
            shDailyByDate = emptyMap(),
            bmrSnapshotsAll = bmrSnapshotsList,
            shAvailable = false,
        )
    }

    /** Reads the stored timestamp; null if no successful export ever happened. */
    fun loadLastExportAtMs(context: Context): Long? {
        val ms = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_EXPORT_AT_MS, 0L)
        return if (ms == 0L) null else ms
    }

    /** True when there has never been a successful export, or the last successful
     *  export was on a calendar day strictly before today. Used by the catch-up
     *  trigger in [MainActivity] to decide whether to enqueue an immediate
     *  one-shot Worker on app start. */
    fun shouldRunDailyCatchUp(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_FOLDER_URI, null).isNullOrBlank()) return false
        val lastMs = loadLastExportAtMs(context) ?: return true
        val lastDay = Instant.ofEpochMilli(lastMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return lastDay.isBefore(today)
    }
}
