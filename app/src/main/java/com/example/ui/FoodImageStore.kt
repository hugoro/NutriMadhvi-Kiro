package com.example.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Phase 5 (2026-06-13): lossless persistence for the Activity tab's food
 * images. Mirrors [ChatImageStore] but writes to `filesDir/food_images/`
 * (the directory that has always backed `FoodEntry.imagePath`).
 *
 * Why this exists: the legacy pipeline went
 *   content://  → ImageDecoder.decodeBitmap (decode JPEG → ARGB_8888)
 *               → bitmap.compress(JPEG, 90)  ← re-encode (filesDir copy)
 *               → bitmap.compress(JPEG, 70)  ← re-encode (Gemini upload)
 * On a nutrition label or a packaging description, two cascaded JPEG
 * re-encodes destroy the fine text detail Hugo depends on. Worse, the
 * camera path used `TakePicturePreview` which only returns a thumbnail
 * Bitmap (~1024 px max) — so resolution was already gone before the
 * re-encodes even started.
 *
 * This store gives three byte-level paths:
 *  - [persistFromUri] — copy a content://gallery URI verbatim into food_images/
 *  - [loadImageBytesAndMime] — read a file:// path back as (base64, mime) for
 *    Gemini upload, again with no decode/re-encode
 *  - [mirrorToPublicCamera] — byte-copy a captured photo into DCIM/Camera/ so
 *    it shows up in the device gallery (was previously a JPEG re-encode too)
 */
object FoodImageStore {

    private const val DIR_NAME = "food_images"

    /**
     * Lossless persistence: copies the source URI's raw bytes (typically a
     * JPEG/HEIC from the gallery picker) into `filesDir/food_images/<uuid>.<ext>`
     * WITHOUT decoding/re-encoding. Returns the resulting `file://` URI as a
     * string, or null on failure.
     */
    fun persistFromUri(context: Context, sourceUri: Uri): String? {
        return try {
            val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
            val mime = context.contentResolver.getType(sourceUri)
            val ext = extensionFor(mime)
            val file = File(dir, "img_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
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
     * Read the bytes of a previously-persisted image at [fileUriString] and
     * encode them as base64 for Gemini upload. The mime type is sniffed from
     * the URI (via ContentResolver) with a fallback to extension parsing —
     * this is how HEIC stays HEIC, PNG stays PNG, etc., when sending to the
     * API. Returns null if the URI is unreadable.
     *
     * NOTE: the base64 string is newline-free (Base64.NO_WRAP) — the Gemini
     * REST inlineData payload does not tolerate embedded newlines on some
     * code paths.
     */
    /** Above this size the image is re-encoded before upload. See
     *  [loadImageBytesAndMime]. */
    private const val UPLOAD_MAX_BYTES = 3_000_000

    /** Longest edge kept when re-encoding, preserving fine packaging text. */
    private const val UPLOAD_MAX_DIMENSION = 2048

    fun loadImageBytesAndMime(context: Context, fileUriString: String): Pair<String, String>? {
        return try {
            val uri = Uri.parse(fileUriString)
            val resolver = context.contentResolver
            val sniffedMime = resolver.getType(uri)
            val mime = sniffedMime ?: extensionToMime(fileUriString)
            val bytes = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "openInputStream returned null for $fileUriString" }
                input.readBytes()
            }
            // A 12-MP camera original is ~9 MB, which base64 inflates to ~12 MB
            // on the wire — the dominant cause of failed/timed-out analyses on
            // mobile data. A 2048px transport copy retains fine packaging text
            // while keeping the request bounded. The persisted original is
            // never modified; only the upload copy shrinks.
            val (uploadBytes, uploadMime) = if (bytes.size > UPLOAD_MAX_BYTES) {
                downscaleForUpload(bytes) ?: (bytes to mime)
            } else {
                bytes to mime
            }
            val b64 = Base64.encodeToString(uploadBytes, Base64.NO_WRAP)
            b64 to uploadMime
        } catch (e: Exception) {
            android.util.Log.w("FoodImageStore", "loadImageBytesAndMime failed for $fileUriString", e)
            null
        }
    }

    /**
     * Re-encode [source] as a bounded JPEG. Returns null when the bytes cannot
     * be decoded, so the caller falls back to uploading the original rather
     * than dropping the image.
     */
    private fun downscaleForUpload(source: ByteArray): Pair<ByteArray, String>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longSide / sample > UPLOAD_MAX_DIMENSION) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(source, 0, source.size, opts)
            if (bmp == null) {
                null
            } else {
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bmp.recycle()
                out.toByteArray() to "image/jpeg"
            }
        }
    } catch (t: Throwable) {
        // Includes OutOfMemoryError — uploading the original beats losing it.
        android.util.Log.w("FoodImageStore", "downscaleForUpload failed: ${t.javaClass.simpleName}")
        null
    }

    /**
     * Byte-copy a captured photo (already on disk inside `food_images/`) into
     * the public DCIM/Camera/ folder so it appears in the device's Gallery
     * app alongside other camera shots. Used only for in-app camera captures
     * — gallery picks already live in public storage.
     *
     * Android 10+ uses MediaStore with `RELATIVE_PATH=DCIM/Camera`; older
     * versions write to `Environment.DIRECTORY_DCIM` directly. The previous
     * implementation re-encoded the JPEG; this one preserves the original
     * bytes exactly.
     */
    fun mirrorToPublicCamera(context: Context, sourceFileUriString: String): Boolean {
        return try {
            val sourceUri = Uri.parse(sourceFileUriString)
            val resolver = context.contentResolver
            val sniffedMime = resolver.getType(sourceUri) ?: extensionToMime(sourceFileUriString)
            val displayName = "NutriMadhvi_${System.currentTimeMillis()}.${extensionFor(sniffedMime)}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, sniffedMime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/NutriMadhvi")
                }
                val destUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openInputStream(sourceUri)?.use { input ->
                    resolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                    } ?: return false
                } ?: return false
                // Suppress media scanner so the folder doesn't appear in Gallery/Google Photos.
                ensureNoMedia()
                true
            } else {
                @Suppress("DEPRECATION")
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val nutriDir = File(dcim, "NutriMadhvi").apply { if (!exists()) mkdirs() }
                val destFile = File(nutriDir, displayName)
                resolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return false
                ensureNoMedia()
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Drop a .nomedia sentinel into DCIM/NutriMadhvi/ so the media scanner
     *  (and therefore Gallery and Google Photos) ignores the folder.
     *  Tries both the DCIM public dir and the primary external storage root
     *  because Samsung resolves these differently on some builds. */
    private fun ensureNoMedia() {
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

    /** Map a mime type to the file extension we'll write under. Defaults to
     *  `.jpg` for any unknown type (rare, but possible with some Samsung/Pixel
     *  pickers that don't tag a mime). */
    private fun extensionFor(mime: String?): String = when {
        mime == null -> "jpg"
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("heic") || mime.contains("heif") -> "heic"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        else -> "jpg"
    }

    /** Inverse helper: when the ContentResolver can't tell us a mime
     *  (e.g. a stale `file://` URI), fall back to inferring from extension. */
    private fun extensionToMime(uriOrPath: String): String {
        val lower = uriOrPath.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".heic") || lower.endsWith(".heif") -> "image/heic"
            else -> "image/jpeg"
        }
    }
}
