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
    /** Zone width (foreground span) normalised by the plan's normalizer
     *  (image height for legacy captures, person height for pose captures).
     *  A null means that zone had < 30% row coverage — person not visible there. */
    val shoulderRatio: Float?,   // frontal: bioacromial
    val waistRatio: Float?,      // frontal
    val hipRatio: Float?,        // frontal
    val thighRatio: Float?,      // frontal
    val chestRatio: Float?,      // lateral: antero-posterior depth at chest
    val bellyRatio: Float?,      // lateral
    val lateralThighRatio: Float?, // lateral
)

/** Result of [SilhouetteAnalyzer.analysis]: the ratios plus which convention
 *  produced them (persisted on `performance_snapshots.silhouetteNormalization`). */
data class SilhouetteAnalysis(
    val ratios: SilhouetteRatios,
    val normalization: String, // SilhouetteZoneMath.NORMALIZATION_*
)

/**
 * Runs ML Kit Selfie Segmentation on [bitmap], splits the resulting foreground
 * mask into anatomical vertical zones, and returns zone width ratios.
 *
 * Validated approach: Farina et al. 2022 — silhouette-based ratios correlate
 * R²=0.91–0.95 with DEXA body-fat distribution.
 *
 * Zone bands (see [SilhouetteZoneMath]):
 *  - **pose path** (v39+): when a full-body pose is detected, zones are
 *    anchored on shoulder/hip/knee landmarks and ratios are normalised by the
 *    person's on-screen height — invariant to camera distance.
 *  - **legacy path**: fixed fractions of the frame height, normalised by
 *    image height (pre-v39 behavior).
 *
 * For FRONTAL photos use [isFrontal]=true (returns shoulder/waist/hip/thigh).
 * For LATERAL photos use [isFrontal]=false (returns chest/belly/thighLateral).
 *
 * Runs on the calling coroutine; the ML Kit callback is bridged via
 * [suspendCancellableCoroutine]. Call from a background coroutine (IO / Default).
 */
object SilhouetteAnalyzer {

    private const val MIN_ZONE_COVERAGE = 0.30f  // 30% of rows must have foreground

    /** Legacy entry point — fixed frame-fraction zones, image-height
     *  normalizer (identical output to the pre-v39 analyzer). */
    suspend fun analyze(bitmap: Bitmap, isFrontal: Boolean): SilhouetteRatios =
        analysis(bitmap, isFrontal, null).ratios

    /** Zone extraction with optional pose landmarks. When [landmarks] is a
     *  full-body pose the pose-normalised convention is used; otherwise the
     *  legacy frame-fraction convention. */
    suspend fun analysis(
        bitmap: Bitmap,
        isFrontal: Boolean,
        landmarks: Map<Int, com.google.mlkit.vision.pose.PoseLandmark>?,
    ): SilhouetteAnalysis {
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

        val plan = SilhouetteZoneMath.plan(isFrontal, h, landmarks?.toLandmarkKeys())
        fun ratio(zone: String): Float? {
            val band = plan.bands[zone] ?: return null
            return SilhouetteZoneMath.zoneRatio(rowSpan, band, plan.normalizer, MIN_ZONE_COVERAGE)
        }

        val ratios = if (isFrontal) {
            SilhouetteRatios(
                shoulderRatio      = ratio(SilhouetteZoneMath.SHOULDER),
                waistRatio         = ratio(SilhouetteZoneMath.WAIST),
                hipRatio           = ratio(SilhouetteZoneMath.HIP),
                thighRatio         = ratio(SilhouetteZoneMath.THIGH),
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
                chestRatio         = ratio(SilhouetteZoneMath.CHEST),
                bellyRatio         = ratio(SilhouetteZoneMath.BELLY),
                lateralThighRatio  = ratio(SilhouetteZoneMath.LATERAL_THIGH),
            )
        }
        return SilhouetteAnalysis(ratios, plan.normalization)
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
