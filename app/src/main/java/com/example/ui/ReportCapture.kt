package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import java.io.FileOutputStream

/**
 * PNG sink for the on-screen ReportTab capture.
 *
 * The Reports tab wraps its data subtree in a `Modifier.drawWithContent { graphicsLayer.record { drawContent() }; drawLayer(graphicsLayer) }`
 * block. When the user taps "Share Image", the click handler awaits
 * `graphicsLayer.toImageBitmap()` from a coroutine and hands the resulting
 * [ImageBitmap] to [writePng]. We persist it under `cacheDir/shares/` (already
 * exposed by FileProvider — see `res/xml/file_paths.xml`) so the existing
 * `Intent.ACTION_SEND` flow can hand a `content://` URI to the share sheet
 * without any extra permission setup.
 *
 * Kept deliberately tiny and side-effect-only: no UI, no recomposition concerns
 * — just the plumbing between Compose's GraphicsLayer and the share intent.
 */
object ReportCapture {
    fun writePng(context: Context, bitmap: ImageBitmap): File {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val out = File(dir, "NutriMadhvi_Report_${System.currentTimeMillis()}.png")
        FileOutputStream(out).use { fos ->
            bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        return out
    }

    /**
     * HTML sink for the "Partilhar report" (detailed) share. Same
     * `cacheDir/shares/` directory as [writePng] (FileProvider already exposes
     * it), so the existing `Intent.ACTION_SEND` flow shares the `.html` with
     * mime `text/html` without extra setup. The string is fully self-contained
     * (inline CSS + base64 chart images) — see [com.example.data.export.ReportHtmlBuilder].
     */
    fun writeHtml(context: Context, html: String): File {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val out = File(dir, "NutriMadhvi_Report_${System.currentTimeMillis()}.html")
        out.writeText(html, Charsets.UTF_8)
        return out
    }
}
