package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SilhouetteZoneMath] — the deterministic core of the
 * pose-normalised silhouette measurements (v39).
 *
 * The key product property under test: **scale invariance** — the same body
 * captured at twice the distance (all pixel coordinates × 2, frame × 2)
 * yields the same zone ratios, because zones and normalizer scale together.
 */
class SilhouetteZoneMathTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A "person" as a symmetric rectangle: rows [top..bottom], width px. */
    private fun rectRowSpan(imgW: Int, imgH: Int, top: Int, bottom: Int, width: Int): IntArray =
        IntArray(imgH) { row -> if (row in top..bottom) width else 0 }

    private fun pts(vararg lm: Triple<SilhouetteZoneMath.LandmarkKey, Float, Float>): Map<SilhouetteZoneMath.LandmarkKey, SilhouetteZoneMath.LandmarkPt> =
        lm.associate { (t, x, y) -> t to SilhouetteZoneMath.LandmarkPt(x, y, 0.9f) }

    /** A full-body pose: person from [top] (nose) to [bottom] (ankles),
     *  shoulders at 25% of the span, hips at 65%, knees at 80%, width [w]. */
    private fun fullPose(
        top: Float,
        bottom: Float,
        centerX: Float,
        w: Float,
        halfShoulder: Float,
    ): Map<SilhouetteZoneMath.LandmarkKey, SilhouetteZoneMath.LandmarkPt> {
        val span = bottom - top
        val shoulderY = top + 0.25f * span
        val hipY = top + 0.65f * span
        val kneeY = top + 0.80f * span
        return pts(
            Triple(SilhouetteZoneMath.LandmarkKey.NOSE, centerX, top),
            Triple(SilhouetteZoneMath.LandmarkKey.LEFT_SHOULDER, centerX - halfShoulder, shoulderY),
            Triple(SilhouetteZoneMath.LandmarkKey.RIGHT_SHOULDER, centerX + halfShoulder, shoulderY),
            Triple(SilhouetteZoneMath.LandmarkKey.LEFT_HIP, centerX - halfShoulder, hipY),
            Triple(SilhouetteZoneMath.LandmarkKey.RIGHT_HIP, centerX + halfShoulder, hipY),
            Triple(SilhouetteZoneMath.LandmarkKey.LEFT_KNEE, centerX - halfShoulder, kneeY),
            Triple(SilhouetteZoneMath.LandmarkKey.RIGHT_KNEE, centerX + halfShoulder, kneeY),
            Triple(SilhouetteZoneMath.LandmarkKey.LEFT_ANKLE, centerX - halfShoulder, bottom),
            Triple(SilhouetteZoneMath.LandmarkKey.RIGHT_ANKLE, centerX + halfShoulder, bottom),
        )
    }

    // ── Legacy plan ──────────────────────────────────────────────────────────

    @Test
    fun `legacy plan keeps the pre-v39 fixed fractions`() {
        // imgH = 480 — the real capture height. Expected rows are the exact
        // float32 arithmetic of (frac * 480).toInt(), same as the old analyzer.
        val plan = SilhouetteZoneMath.legacyPlan(isFrontal = true, imgH = 480)
        assertEquals(96, plan.bands.getValue(SilhouetteZoneMath.SHOULDER).startRow)
        assertEquals(153, plan.bands.getValue(SilhouetteZoneMath.SHOULDER).endRow)
        assertEquals(182, plan.bands.getValue(SilhouetteZoneMath.WAIST).startRow)
        assertEquals(249, plan.bands.getValue(SilhouetteZoneMath.WAIST).endRow)
        assertEquals(249, plan.bands.getValue(SilhouetteZoneMath.HIP).startRow)
        assertEquals(312, plan.bands.getValue(SilhouetteZoneMath.HIP).endRow)
        assertEquals(312, plan.bands.getValue(SilhouetteZoneMath.THIGH).startRow)
        assertEquals(374, plan.bands.getValue(SilhouetteZoneMath.THIGH).endRow)
        assertEquals(480, plan.normalizer)
        assertEquals(SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT, plan.normalization)

        val lateral = SilhouetteZoneMath.legacyPlan(isFrontal = false, imgH = 480)
        assertEquals(144, lateral.bands.getValue(SilhouetteZoneMath.CHEST).startRow)
        assertEquals(240, lateral.bands.getValue(SilhouetteZoneMath.CHEST).endRow)
        assertEquals(240, lateral.bands.getValue(SilhouetteZoneMath.BELLY).startRow)
        assertEquals(312, lateral.bands.getValue(SilhouetteZoneMath.BELLY).endRow)
        assertEquals(312, lateral.bands.getValue(SilhouetteZoneMath.LATERAL_THIGH).startRow)
        assertEquals(374, lateral.bands.getValue(SilhouetteZoneMath.LATERAL_THIGH).endRow)
    }

    @Test
    fun `plan without landmarks falls back to legacy`() {
        val a = SilhouetteZoneMath.plan(true, 1000, null)
        assertEquals(SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT, a.normalization)
        val b = SilhouetteZoneMath.plan(true, 1000, emptyMap())
        assertEquals(SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT, b.normalization)
    }

    // ── Pose plan ────────────────────────────────────────────────────────────

    @Test
    fun `full-body pose anchors zones on landmarks and normalises by person height`() {
        val lm = fullPose(top = 60f, bottom = 420f, centerX = 320f, w = 100f, halfShoulder = 50f)
        val plan = SilhouetteZoneMath.plan(true, 480, lm)

        assertEquals(SilhouetteZoneMath.NORMALIZATION_POSE_PERSON_HEIGHT, plan.normalization)
        assertEquals(360, plan.normalizer) // nose→ankle span

        // Shoulder band is centered on the shoulder landmark (y=150).
        val sh = plan.bands.getValue(SilhouetteZoneMath.SHOULDER)
        assertTrue("shoulder band must contain the shoulder row", sh.startRow <= 150 && sh.endRow >= 150)
        // Waist band sits between shoulder and hip (150 < waist < 294).
        val waist = plan.bands.getValue(SilhouetteZoneMath.WAIST)
        assertTrue(waist.startRow > 150 && waist.endRow < 294)
        // Hip band is centered on the hip landmark (y=294).
        val hip = plan.bands.getValue(SilhouetteZoneMath.HIP)
        assertTrue(hip.startRow <= 294 && hip.endRow >= 294)
    }

    @Test
    fun `scale invariance - same body at twice the distance gives the same ratios`() {
        // Body A: 480 px frame, person rows 60..420, width 100 px.
        val lmA = fullPose(top = 60f, bottom = 420f, centerX = 320f, w = 100f, halfShoulder = 50f)
        val planA = SilhouetteZoneMath.plan(true, 480, lmA)
        val spanA = rectRowSpan(640, 480, 60, 420, 100)

        // Body B: identical body at 2× distance — 960 px frame, rows 120..840,
        // width 200 px, every landmark coordinate × 2.
        val lmB = lmA.mapValues { (_, p) ->
            SilhouetteZoneMath.LandmarkPt(p.x * 2f, p.y * 2f, p.inFrameLikelihood)
        }
        val planB = SilhouetteZoneMath.plan(true, 960, lmB)
        val spanB = rectRowSpan(1280, 960, 120, 840, 200)

        val zones = listOf(
            SilhouetteZoneMath.SHOULDER,
            SilhouetteZoneMath.WAIST,
            SilhouetteZoneMath.HIP,
            SilhouetteZoneMath.THIGH,
        )
        for (z in zones) {
            val rA = SilhouetteZoneMath.zoneRatio(spanA, planA.bands.getValue(z), planA.normalizer)
            val rB = SilhouetteZoneMath.zoneRatio(spanB, planB.bands.getValue(z), planB.normalizer)
            assertNotNull("zone $z missing in near capture", rA)
            assertNotNull("zone $z missing in far capture", rB)
            assertEquals(
                "zone $z must be scale-invariant (got $rA vs $rB)",
                rA!!, rB!!, 0.005f
            )
        }
    }

    @Test
    fun `partial or low-confidence pose falls back to legacy`() {
        val full = fullPose(60f, 420f, 320f, 100f, 50f)

        // Missing right ankle.
        val missingAnkle = full.filterKeys { it != SilhouetteZoneMath.LandmarkKey.RIGHT_ANKLE }
        assertEquals(
            SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT,
            SilhouetteZoneMath.plan(true, 480, missingAnkle).normalization
        )

        // One ankle below the confidence threshold.
        val lowConf = full.toMutableMap().apply {
            this[SilhouetteZoneMath.LandmarkKey.LEFT_ANKLE] =
                SilhouetteZoneMath.LandmarkPt(270f, 420f, 0.2f)
        }
        assertEquals(
            SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT,
            SilhouetteZoneMath.plan(true, 480, lowConf).normalization
        )

        // Person fills < 30% of the frame (cropped close-up) → not full-body.
        val tiny = fullPose(430f, 470f, 320f, 100f, 50f)
        assertEquals(
            SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT,
            SilhouetteZoneMath.plan(true, 480, tiny).normalization
        )

        // Degenerate: shoulders at/below hips.
        val degenerate = full.toMutableMap().apply {
            this[SilhouetteZoneMath.LandmarkKey.LEFT_SHOULDER] =
                SilhouetteZoneMath.LandmarkPt(270f, 400f, 0.9f)
            this[SilhouetteZoneMath.LandmarkKey.RIGHT_SHOULDER] =
                SilhouetteZoneMath.LandmarkPt(370f, 400f, 0.9f)
        }
        assertEquals(
            SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT,
            SilhouetteZoneMath.plan(true, 480, degenerate).normalization
        )
    }

    // ── zoneRatio ────────────────────────────────────────────────────────────

    @Test
    fun `zone ratio is mean span over normalizer`() {
        val span = IntArray(100) { if (it in 10..19) 50 else 0 }
        val r = SilhouetteZoneMath.zoneRatio(span, SilhouetteZoneMath.ZoneBand(10, 19), 200)
        assertEquals(0.25f, r!!, 0.0001f) // 50 / 200
    }

    @Test
    fun `zone ratio is null below coverage threshold`() {
        // Only 2 of 10 rows carry foreground → 20% < 30% threshold.
        val span = IntArray(100) { if (it == 10 || it == 11) 50 else 0 }
        assertNull(SilhouetteZoneMath.zoneRatio(span, SilhouetteZoneMath.ZoneBand(10, 19), 200))
    }

    @Test
    fun `zone ratio out-of-range band is clamped, not an exception`() {
        val span = IntArray(50) { 20 }
        val r = SilhouetteZoneMath.zoneRatio(span, SilhouetteZoneMath.ZoneBand(-10, 30), 100)
        assertEquals(0.2f, r!!, 0.0001f)
    }

    @Test
    fun `zone ratio with zero or negative normalizer is null`() {
        val span = IntArray(10) { 20 }
        assertNull(SilhouetteZoneMath.zoneRatio(span, SilhouetteZoneMath.ZoneBand(0, 9), 0))
        assertNull(SilhouetteZoneMath.zoneRatio(IntArray(0), SilhouetteZoneMath.ZoneBand(0, 9), 100))
    }

    // ── Convention comparison (v40) ────────────────────────────────────────

    @Test
    fun `null marker means legacy image_height convention`() {
        assertEquals(SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT, SilhouetteZoneMath.conventionOf(null))
        assertEquals(SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT, SilhouetteZoneMath.conventionOf(""))
        assertEquals(SilhouetteZoneMath.NORMALIZATION_IMAGE_HEIGHT, SilhouetteZoneMath.conventionOf("image_height"))
        assertEquals(SilhouetteZoneMath.NORMALIZATION_POSE_PERSON_HEIGHT, SilhouetteZoneMath.conventionOf("pose_person_height"))
    }

    @Test
    fun `same convention is required to compare metrics`() {
        assertTrue(SilhouetteZoneMath.sameConvention(null, null))
        assertTrue(SilhouetteZoneMath.sameConvention(null, "image_height"))
        assertTrue(SilhouetteZoneMath.sameConvention("image_height", "image_height"))
        assertTrue(SilhouetteZoneMath.sameConvention("pose_person_height", "pose_person_height"))
        // Legacy vs pose are DIFFERENT units even for the same zone.
        assertTrue(!SilhouetteZoneMath.sameConvention(null, "pose_person_height"))
        assertTrue(!SilhouetteZoneMath.sameConvention("image_height", "pose_person_height"))
        assertTrue(!SilhouetteZoneMath.sameConvention("pose_person_height", null))
    }
}
