package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Phase C: persistent storage for chat-attached images.
 *
 * Mirrors [[persistent-food-images-pattern]] — bytes copied to
 * `filesDir/chat_images/<uuid>.jpg` inside the picker callback while the
 * temporary content:// grant is still alive. The DB stores a `file://` URI
 * string (or a JSON array of them when there are 2+).
 *
 * This avoids inflating the Room database with base64 (a single photo can be
 * ~300KB encoded; multi-image messages would 5x that). It also dodges the
 * cacheDir purge and the content:// permission expiry that bit the original
 * food-image flow.
 */
object ChatImageStore {

    private const val DIR_NAME = "chat_images"

    /**
     * Lossless persistence: copies the source URI's raw bytes (typically a
     * JPEG/HEIC from the gallery picker or a freshly written camera capture)
     * into `filesDir/chat_images/<uuid>.<ext>` WITHOUT decoding/re-encoding.
     *
     * Preserves the exact quality the file had on disk — critical for
     * OCR-style use cases (food packaging labels, nutrition tables) where any
     * JPEG re-encode destroys fine text detail. Phase 7 of the chat-redesign
     * programme: Hugo was getting markedly worse analyses inside the app vs
     * pasting the same photo into Gemini Studio, and the re-encode in
     * [persist] was the smoking gun.
     *
     * Returns the resulting `file://` URI as a string, or null on failure.
     */
    fun persistFromUri(context: Context, sourceUri: Uri): String? {
        return try {
            val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
            // Sniff extension from the source mime so HEIC stays HEIC, PNG
            // stays PNG, etc. Falls back to .jpg when the picker doesn't tag
            // a mime (rare but possible with some Samsung/Pixel pickers).
            val mime = context.contentResolver.getType(sourceUri)
            val ext = when {
                mime == null -> "jpg"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("heic") || mime.contains("heif") -> "heic"
                else -> "jpg"
            }
            val file = File(dir, "chat_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "openInputStream returned null for $sourceUri" }
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Persists a bitmap to disk and returns the resulting `file://` URI as a
     * string, or null on failure. Quality matches the food-image pattern (90)
     * so re-encoding loss is comparable.
     *
     * **Deprecated for the gallery path** — the lossless [persistFromUri]
     * should be preferred whenever a content:// or file:// stream is
     * available. Kept for legacy callers that already have a Bitmap in hand
     * (e.g. drawn-on-canvas content) where there is no source file to copy.
     */
    @Deprecated(
        "Re-encodes the JPEG and degrades quality. Use persistFromUri(context, sourceUri) when you have a content:// or file:// URI.",
        ReplaceWith("persistFromUri(context, sourceUri)")
    )
    fun persist(context: Context, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
            val file = File(dir, "chat_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Marshals a list of image paths into a single string for storage in the
     * legacy `ChatMessage.imageBase64` column.
     *
     *  - empty list → null (no attachment)
     *  - 1 element  → that path verbatim (binary-compatible with old single-image rows
     *                 — old code that called `.toBitmap()` won't crash, it just won't
     *                 decode a path; we take care of that in [decode])
     *  - 2+ elements → JSON array string `["file://...","file://..."]`
     *
     * The [decode] function is the inverse and tolerates legacy base64 blobs.
     */
    fun encode(paths: List<String>): String? = when {
        paths.isEmpty() -> null
        paths.size == 1 -> paths[0]
        else -> JSONArray(paths).toString()
    }

    /**
     * Inverse of [encode]. Detects whether the stored value is:
     *  - null/empty → emptyList
     *  - JSON array `[...]` → parsed list of paths
     *  - a `file://` or `content://` URI → single-element list
     *  - anything else → assumed to be legacy base64; returned as a single element
     *    so the caller can still call `.toBitmap()` on it.
     */
    fun decode(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        val trimmed = stored.trimStart()
        return if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
            } catch (e: Exception) {
                // Malformed — treat as legacy base64 in a single slot.
                listOf(stored)
            }
        } else {
            listOf(stored)
        }
    }

    /**
     * True when the entry is a path we can read from disk (`file://` or
     * `content://`); false when it's a base64 blob from a legacy message.
     */
    fun isPath(entry: String): Boolean =
        entry.startsWith("file://") || entry.startsWith("content://")

    /**
     * EXIF-aware bitmap loader. Reads the JPEG's `Orientation` tag (set by the
     * camera) and rotates the decoded bitmap to match the way the user
     * actually held the device. Without this, landscape photos from the Pixel
     * camera render rotated 90° in the chat bubbles because
     * `BitmapFactory.decodeFile` returns the raw sensor-orientation pixel
     * buffer — independent of the EXIF metadata.
     *
     * The byte-copy in [persistFromUri] preserves the EXIF segment intact, so
     * the orientation tag is still present in `filesDir/chat_images/`. We do
     * NOT rotate the file on disk — only the in-memory bitmap used for
     * display. The Gemini side sees the original bytes and reads EXIF itself.
     *
     * Returns null if the file can't be read or the decode fails. Falls back
     * to the plain decoded bitmap (no rotation) when the EXIF segment is
     * missing or unreadable.
     */
    fun decodeBitmapWithOrientation(
        absolutePath: String,
        maxDimension: Int = 1024
    ): Bitmap? {
        // Pass 1: read bounds without allocating.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Pass 2: downsample to ≤ maxDimension px on the longer side. Chat
        // bubbles never render bigger than 1024, and a full-resolution 12-MP
        // photo in ARGB_8888 costs ~48 MB per bitmap — which was OOM-killing
        // the app on the S25 (heap capped at ~268 MB). 1024 @ ARGB_8888 ≈ 4 MB.
        // The upload path passes a larger bound so the model still gets full
        // tile detail without the app ever holding the raw 12-MP buffer.
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / sample > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(absolutePath, opts) ?: return null
        val orientation = try {
            androidx.exifinterface.media.ExifInterface(absolutePath)
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
        } catch (e: Exception) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.postScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bmp // ORIENTATION_NORMAL or UNDEFINED — no transform.
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                .also { rotated -> if (rotated !== bmp) bmp.recycle() }
        } catch (e: Exception) {
            bmp
        }
    }
}
