package com.example.data.export

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.ChatDatabase
import com.example.ui.ChatImageStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic sweep of `filesDir/food_images/` and `filesDir/chat_images/` to
 * delete files that are no longer referenced by any row in Room AND have
 * been sitting on disk for more than [MIN_AGE_MS]. The age gate is the
 * safety net: a freshly captured photo lives on disk for a few seconds
 * before its `FoodEntry` / `ChatMessage` row is written, and a user
 * editing a meal-log entry might leave a half-saved attachment hanging
 * for minutes. A 30-day floor makes that race impossible to lose.
 *
 * ## Why this exists
 *
 * Both [com.example.ui.FoodImageStore] and [com.example.ui.ChatImageStore]
 * write bytes verbatim into the app's `filesDir` and never clean up. When
 * the user deletes a meal entry, or aborts a chat-image attachment, or
 * picks 3 photos and then dismisses the analysis screen, those files
 * stay forever — accumulating quietly over months. This worker reclaims
 * that disk, runs weekly so it never produces a noticeable IO spike.
 *
 * ## Reference resolution
 *
 *  - FoodEntry rows: one nullable `imagePath` column → at most one
 *    `file://...` URI per row. Read in bulk via
 *    [com.example.data.local.FoodDao.allImagePaths].
 *  - ChatMessage rows: legacy `imageBase64` column actually stores either
 *    a single `file://` path, a JSON array of paths, or a pre-v15 base64
 *    blob. [ChatImageStore.decode] handles all three; we keep only the
 *    entries that look like real paths ([ChatImageStore.isPath]).
 *
 * The union of those two sets is the **referenced set**. Anything in the
 * image directories whose path is not in that set AND whose
 * `lastModified()` is older than the age floor is deleted.
 *
 * ## Safety properties
 *
 *  - **Age floor**: a brand-new file from a half-completed flow is
 *    untouchable for 30 days.
 *  - **Path-set match by absolute path**: we don't trust filename
 *    similarity. We compare `File(uri).absolutePath` against every disk
 *    file's `absolutePath` so a name collision across the two dirs
 *    cannot misclassify either side.
 *  - **Per-file try/catch**: a single failed delete (locked file, broken
 *    SELinux label) logs and is skipped; the rest of the sweep still
 *    progresses. Worker reports success regardless — the next periodic
 *    slot retries.
 *
 * Enqueued from `MainActivity.scheduleOrphanImageCleanup`. Weekly cadence,
 * no constraints (doesn't need network), executes in background.
 */
class OrphanImageCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "OrphanImgCleanup"
        const val UNIQUE_NAME = "orphan_image_cleanup"

        /** Files newer than 30 days are exempt regardless of whether
         *  they appear referenced. Belt-and-suspenders for in-flight
         *  flows the worker can't observe directly. */
        private val MIN_AGE_MS = TimeUnit.DAYS.toMillis(30)

        private val IMAGE_DIRS = listOf("food_images", "chat_images")
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val database = ChatDatabase.getInstance(ctx)

        val referenced: Set<String> = try {
            buildReferencedSet(database)
        } catch (e: Exception) {
            Log.w(TAG, "failed to build referenced set; aborting sweep", e)
            return Result.success()
        }

        val ageCutoffMs = System.currentTimeMillis() - MIN_AGE_MS
        var scanned = 0
        var deleted = 0
        var bytesFreed = 0L
        var skippedYoung = 0
        var skippedReferenced = 0

        for (dirName in IMAGE_DIRS) {
            val dir = File(ctx.filesDir, dirName)
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                scanned++
                if (file.lastModified() > ageCutoffMs) {
                    skippedYoung++
                    continue
                }
                if (file.absolutePath in referenced) {
                    skippedReferenced++
                    continue
                }
                val size = file.length()
                val ok = runCatching { file.delete() }.getOrDefault(false)
                if (ok) {
                    deleted++
                    bytesFreed += size
                } else {
                    Log.w(TAG, "could not delete ${file.absolutePath}")
                }
            }
        }

        Log.i(
            TAG,
            "sweep done: scanned=$scanned deleted=$deleted freed=${bytesFreed}B " +
                "skippedYoung=$skippedYoung skippedRef=$skippedReferenced"
        )
        return Result.success()
    }

    /**
     * Walks both DAOs and produces the union of every on-disk path
     * currently referenced. Returned as `absolutePath` strings so the
     * caller can do a plain `Set.contains` against `File.absolutePath`.
     *
     * Both DAO calls run on Room's own dispatcher; this method is invoked
     * from [doWork] which is already on [androidx.work.CoroutineWorker]'s
     * IO context.
     */
    private suspend fun buildReferencedSet(db: ChatDatabase): Set<String> {
        val out = HashSet<String>()

        // FoodEntry side: single nullable column, one path per row.
        for (uri in db.foodDao.allImagePaths()) {
            uriToAbsolutePath(uri)?.let { out.add(it) }
        }

        // ChatMessage side: column may hold a path, a JSON array of paths,
        // or a legacy base64 blob. ChatImageStore.decode normalises the
        // three shapes; we then keep only entries that look like a file
        // path on this device.
        for (payload in db.chatDao.allImagePayloads()) {
            for (entry in ChatImageStore.decode(payload)) {
                if (!ChatImageStore.isPath(entry)) continue
                uriToAbsolutePath(entry)?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * Strips the `file://` scheme from a stored URI and returns the
     * canonical absolute path the on-disk [File] would report. Returns
     * null on any parse failure so a malformed row can never poison the
     * set with an entry that no real file would match (which would in
     * turn cause that real file to be wrongly preserved — the opposite
     * failure mode is much worse than dropping a malformed entry).
     */
    private fun uriToAbsolutePath(uri: String): String? {
        return try {
            if (uri.startsWith("file://")) {
                File(android.net.Uri.parse(uri).path ?: return null).absolutePath
            } else {
                // content:// URIs never resolve to a plain absolute path
                // we can match. They aren't produced by either ImageStore,
                // so seeing one here is a legacy / external-share corner
                // case — skip it.
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
