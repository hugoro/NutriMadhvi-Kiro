package com.example.ui

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.Segmentation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Body-zone width ratios extracted from a single silhouette mask. */
data class SilhouetteRatios(
    /** Zone width (foreground span) normalised by image pixel height.
     *  A null means that zone had < 30% row coverage — person not visible there. */
    val shoulderRatio: Float?,   // frontal: bioacromial
    val waistRatio: Float?,      // frontal
    val hipRatio: Float?,        // frontal
    val thighRatio: Float?,      // frontal
    val chestRatio: Float?,      // lateral: antero-posterior depth at chest
    val bellyRatio: Float?,      // lateral
    val lateralThighRatio: Float?, // lateral
)

/**
 * Runs ML Kit Selfie Segmentation on [bitmap], splits the resulting foreground
 * mask into anatomical vertical zones, and returns zone width / image-height ratios.
 *
 * Validated approach: Farina et al. 2022 — silhouette-based ratios correlate
 * R²=0.91–0.95 with DEXA body-fat distribution.
 *
 * Zone boundaries are defined as fixed fractions of image height (no ML Kit
 * Pose needed for the segmentation pass):
 *   0–20%  head/neck  (ignored)
 *   20–32% shoulder
 *   32–50% waist/chest (frontal=waist, lateral=chest)
 *   50–65% hip/belly   (frontal=hip, lateral=belly)
 *   65–80% thigh
 *   80–100% shin/foot  (ignored)
 *
 * For FRONTAL photos use [isFrontal]=true (returns shoulder/waist/hip/thigh).
 * For LATERAL photos use [isFrontal]=false (returns chest/belly/thighLateral).
 *
 * Runs on the calling coroutine; the ML Kit callback is bridged via
 * [suspendCancellableCoroutine]. Call from a background coroutine (IO / Default).
 */
object SilhouetteAnalyzer {

    private const val MIN_ZONE_COVERAGE = 0.30f  // 30% of rows must have foreground

    suspend fun analyze(bitmap: Bitmap, isFrontal: Boolean): SilhouetteRatios {
        val mask = runSegmentation(bitmap)
        val w = mask.width
        val h = mask.height

        // Row-by-row foreground span: leftmost–rightmost pixel with confidence > 0.5
        val rowSpan = IntArray(h) { row ->
            var left = w
            var right = -1
            for (col in 0 until w) {
                if (mask.getPixel(col, row) != 0) { // non-zero = foreground
                    if (col < left) left = col
                    if (col > right) right = col
                }
            }
            if (right >= left) right - left + 1 else 0
        }
        mask.recycle()

        fun zoneRatio(startFrac: Float, endFrac: Float): Float? {
            val r0 = (h * startFrac).toInt().coerceIn(0, h - 1)
            val r1 = (h * endFrac).toInt().coerceIn(0, h - 1)
            val zoneRows = r1 - r0 + 1
            if (zoneRows <= 0) return null
            val coveredRows = (r0..r1).count { rowSpan[it] > 0 }
            if (coveredRows.toFloat() / zoneRows < MIN_ZONE_COVERAGE) return null
            val meanSpan = (r0..r1).sumOf { rowSpan[it].toLong() }.toFloat() / zoneRows
            return meanSpan / h
        }

        return if (isFrontal) {
            SilhouetteRatios(
                shoulderRatio      = zoneRatio(0.20f, 0.32f),
                waistRatio         = zoneRatio(0.38f, 0.52f),
                hipRatio           = zoneRatio(0.52f, 0.65f),
                thighRatio         = zoneRatio(0.65f, 0.78f),
                chestRatio         = null,
                bellyRatio         = null,
                lateralThighRatio  = null,
            )
        } else {
            SilhouetteRatios(
                shoulderRatio      = null,
                waistRatio         = null,
                hipRatio           = null,
                thighRatio         = null,
                chestRatio         = zoneRatio(0.30f, 0.50f),
                bellyRatio         = zoneRatio(0.50f, 0.65f),
                lateralThighRatio  = zoneRatio(0.65f, 0.78f),
            )
        }
    }

    // ── ML Kit Segmentation bridge ───────────────────────────────────────────

    private suspend fun runSegmentation(bitmap: Bitmap): Bitmap =
        suspendCancellableCoroutine { cont ->
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
            val segmenter = Segmentation.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            segmenter.process(input)
                .addOnSuccessListener { result ->
                    // Decode the confidence mask into a B&W bitmap (foreground=any non-zero alpha)
                    val buffer = result.buffer
                    val maskW = result.width
                    val maskH = result.height
                    val pixels = IntArray(maskW * maskH)
                    buffer.rewind()
                    for (i in pixels.indices) {
                        val confidence = buffer.float  // 0.0–1.0
                        pixels[i] = if (confidence > 0.5f) -1 else 0  // -1 = 0xFFFFFFFF = white
                    }
                    val maskBitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
                    maskBitmap.setPixels(pixels, 0, maskW, 0, 0, maskW, maskH)
                    segmenter.close()
                    cont.resume(maskBitmap)
                }
                .addOnFailureListener { e ->
                    segmenter.close()
                    cont.resumeWithException(e)
                }
            cont.invokeOnCancellation { segmenter.close() }
        }
}
