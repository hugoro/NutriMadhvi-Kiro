package com.example.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.ChatDatabase
import java.io.FileInputStream

/**
 * Companion to [DriveExporter] — pushes a snapshot of the live Room database
 * (`gemini_chat_database`) into the same SAF folder the user already chose for
 * the XLSX. The Reports tab's "Repor toda a aplicação" flow restores from this
 * file via [DbRestoreImporter].
 *
 * Policy (decided 2026-06-15 with Hugo):
 *  - Single rotating file `nutrimadhvi.db`. The Drive provider keeps prior
 *    versions internally, so the user has history through Drive's own UI; we
 *    don't accumulate timestamped duplicates locally.
 *  - Runs after a successful XLSX export, both manual and via [DriveBackupWorker].
 *  - WAL is checkpointed before the read so the backup is self-contained — a
 *    plain copy of `gemini_chat_database` without `.shm`/`.wal` is enough to
 *    restore on the receiving end.
 *
 * Defensive against the same 0K trap [DriveExporter] documents: writes go
 * through `flush()` and the resulting size is read back via the
 * ContentResolver. A zero-byte outcome is reported as a failure instead of
 * mis-celebrated as a success.
 */
object DbBackupExporter {

    private const val TAG = "DbBackupExporter"
    const val DEFAULT_FILE_NAME = "nutrimadhvi.db"
    private const val MIME_OCTET = "application/octet-stream"

    sealed class Result {
        data class Success(val bytesWritten: Long) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Copies the live database file into the user's Drive folder. No-op
     * (returns Failure with a sentinel message) when no folder has been
     * chosen yet — callers are expected to have run an XLSX export first,
     * which is gated on the same prefs key.
     */
    suspend fun export(context: Context): Result {
        val prefs = context.getSharedPreferences(DriveExporter.PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriStr = prefs.getString(DriveExporter.KEY_FOLDER_URI, null)
            ?: return Result.Failure("no folder selected")

        val folderDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
            ?: return Result.Failure("Drive folder unreachable")

        return try {
            // 1. Force WAL checkpoint so every committed page is flushed into
            //    the main DB file. Without this, recent writes still living in
            //    `gemini_chat_database-wal` would be missing from the backup.
            //    PASSIVE is enough: it doesn't block readers but flushes
            //    everything the writer is willing to release. We don't need
            //    FULL — we're about to read with the writer paused on the
            //    same coroutine.
            try {
                ChatDatabase.getInstance(context).openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(PASSIVE)").use { /* drain */ }
            } catch (e: Exception) {
                Log.w(TAG, "wal_checkpoint failed (continuing)", e)
            }

            val dbFile = context.getDatabasePath("gemini_chat_database")
            if (!dbFile.exists()) return Result.Failure("database file not found")

            // 2. Replace any prior backup so we don't end up with
            //    `nutrimadhvi (1).db` from the SAF Drive provider's
            //    name-collision behaviour.
            folderDoc.findFile(DEFAULT_FILE_NAME)?.delete()
            val newFile = folderDoc.createFile(MIME_OCTET, DEFAULT_FILE_NAME)
                ?: return Result.Failure("could not create backup file")

            val opened = context.contentResolver.openOutputStream(newFile.uri, "w")
                ?: return Result.Failure("could not open backup file for writing")
            opened.use { os ->
                FileInputStream(dbFile).use { input ->
                    input.copyTo(os)
                }
                os.flush()
                if (os is java.io.FileOutputStream) {
                    try { os.fd.sync() } catch (_: Exception) { /* ignore */ }
                }
            }

            // 3. Verify what landed on disk matches what we expected. statSize
            //    catches the same 0K provider stub bug DriveExporter guards.
            val written = try {
                context.contentResolver.openFileDescriptor(newFile.uri, "r")?.use { it.statSize }
            } catch (_: Exception) { null }
            if (written == null || written <= 0L) {
                return Result.Failure("backup file appears empty after write")
            }
            Log.i(TAG, "DB backup ok: $written bytes")
            Result.Success(written)
        } catch (e: SecurityException) {
            Log.w(TAG, "permission revoked", e)
            Result.Failure("permission revoked: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "IO failure", e)
            Result.Failure("IO: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "unknown failure", e)
            Result.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
