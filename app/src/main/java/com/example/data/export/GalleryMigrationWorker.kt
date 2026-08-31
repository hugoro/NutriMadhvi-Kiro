package com.example.data.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-shot worker that moves all previously-mirrored meal photos from
 * `DCIM/Camera/` (old destination) into `DCIM/NutriMadhvi/` (new destination).
 *
 * Runs once at first app launch after the update. Identified by the filename
 * prefix `NutriMadhvi_` — that prefix is app-exclusive and not used by any
 * stock camera app. Safe to retry: photos already moved are gone from
 * `DCIM/Camera/` and therefore invisible to the query.
 *
 * On success, writes a SharedPreferences flag so [com.example.MainActivity]
 * never enqueues this worker again.
 */
class GalleryMigrationWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val moved = moveNutriMadhviPhotos()
            placeNoMedia()
            Log.i(TAG, "gallery migration complete: moved $moved photo(s)")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DONE, true)
                .commit()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "gallery migration failed, will retry", e)
            Result.retry()
        }
    }

    private fun moveNutriMadhviPhotos(): Int {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection =
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("NutriMadhvi_%", "DCIM/Camera/")

        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        ) ?: return 0

        var moved = 0
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol)
                val mime = c.getString(mimeCol) ?: "image/jpeg"
                val srcUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                val destValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/NutriMadhvi")
                }
                val destUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, destValues
                ) ?: continue
                try {
                    resolver.openInputStream(srcUri)!!.use { input ->
                        resolver.openOutputStream(destUri)!!.use { output ->
                            input.copyTo(output)
                        }
                    }
                    resolver.delete(srcUri, null, null)
                    moved++
                } catch (e: Exception) {
                    // leave the source intact and remove the empty dest entry
                    Log.w(TAG, "failed to move $name, leaving in Camera", e)
                    resolver.delete(destUri, null, null)
                }
            }
        }
        return moved
    }

    private fun placeNoMedia() {
        val candidates = listOf(
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NutriMadhvi"),
            File(Environment.getExternalStorageDirectory(), "DCIM/NutriMadhvi"),
        )
        for (dir in candidates) {
            try {
                if (dir.exists()) File(dir, ".nomedia").createNewFile()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "GalleryMigration"
        const val PREFS_NAME = "nutrimadhvi_prefs"
        const val KEY_DONE = "gallery_migration_v1_done"
    }
}
