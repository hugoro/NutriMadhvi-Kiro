package com.example.ui

/**
 * Pure geometry for silhouette zone extraction — NO ML Kit, no Android
 * types, no I/O: unit-testable on the plain JVM. The ML Kit coupling lives
 * in com.example.ui.SilhouettePoseBridge (Android side), which maps
 * PoseLandmark indices to [LandmarkKey] — the single point where the
 * landmark numbering of the ML Kit SDK is referenced, so this file can
 * never drift or accidentally import the SDK.
 *
 * Two conventions:
 *  - **legacy** (pre-v39): zone bands at fixed fractions of the frame height;
 *    ratios normalised by the image height. Works only when the person
 *    fills the frame consistently.
 *  - **pose** (v39+): zone bands anchored on shoulder/hip/knee landmarks,
 *    ratios normalised by the person's on-screen height (nose→ankle span).
 *    Distance-to-camera cancels: both zone width and person height scale
 *    together, so the ratios are scale-invariant (the framing-invariant
 *    version of the same physical quantity "zone width / body height" the
 *    UI has always shown).
 *
 * Falls back to the legacy plan whenever the pose is not full-body, so a
 * failed landmark detection can never degrade a capture below the old
 * behavior.
 *
 * Mixed-convention rule (v40): a snapshot's metrics are comparable to
 * another snapshot's ONLY when both used the same convention on that side
 * (see [conventionOf] / [sameConvention]) — a legacy value and a
 * pose-normalised value of the "same" zone are different units.
 */
object SilhouetteZoneMath {

    /** "image_height" — legacy fixed-fraction zones, image-height normalizer. */
    const val NORMALIZATION_IMAGE_HEIGHT = "image_height"
    /** "pose_person_height" — pose-anchored zones, person-height normalizer. */
    const val NORMALIZATION_POSE_PERSON_HEIGHT = "pose_person_height"

    // Zone keys (match the entity column families).
    const val SHOULDER = "shoulder"
    const val WAIST = "waist"
    const val HIP = "hip"
    const val THIGH = "thigh"
    const val CHEST = "chest"
    const val BELLY = "belly"
    const val LATERAL_THIGH = "lateralThigh"

    private const val MIN_PERSON_SPAN_FRAC = 0.3f
    private const val MAX_PERSON_SPAN_FRAC = 1.0f

    /** The landmarks the zone math needs, as an app-owned enum (pure JVM).
     *  The ML Kit → [LandmarkKey] mapping is done once in
     *  SilhouettePoseBridge, so the SDK's int numbering never leaks here. */
    enum class LandmarkKey {
        NOSE,
        LEFT_SHOULDER, RIGHT_SHOULDER,
        LEFT_HIP, RIGHT_HIP,
        LEFT_KNEE, RIGHT_KNEE,
        LEFT_ANKLE, RIGHT_ANKLE,
    }

    /** One detected landmark: pixel coordinates + in-frame likelihood.
     *  Android-free mirror of ML Kit's PoseLandmark so the math is
     *  unit-testable on the JVM. */
    data class LandmarkPt(val x: Float, val y: Float, val inFrameLikelihood: Float)

    /** Inclusive image-row range over which the foreground span is averaged. */
    data class ZoneBand(val startRow: Int, val endRow: Int)

    data class ZonePlan(
        val bands: Map<String, ZoneBand>,
        val normalizer: Int,
        val normalization: String,
    )

    /**
     * Build the zone plan for an image of [imgH] rows. When [landmarks]
     * contains a full-body pose (nose + both shoulders + both hips + both
     * ankles, each with in-frame likelihood >= [minConfidence]) the pose
     * convention is used; otherwise the legacy fixed-fraction plan is
     * returned (byte-identical bands to the pre-v39 analyzer).
     */
    fun plan(
        isFrontal: Boolean,
        imgH: Int,
        landmarks: Map<LandmarkKey, LandmarkPt>?,
        minConfidence: Float = 0.4f,
    ): ZonePlan {
        val anchors = fullBodyAnchors(landmarks, minConfidence, imgH)
        return if (anchors != null) posePlan(isFrontal, imgH, anchors)
        else legacyPlan(isFrontal, imgH)
    }

    /** Legacy: fixed fractions of the image height (pre-v39 behavior). */
    fun legacyPlan(isFrontal: Boolean, imgH: Int): ZonePlan = ZonePlan(
        bands = if (isFrontal) mapOf(
            SHOULDER to bandFrac(0.20f, 0.32f, imgH),
            WAIST    to bandFrac(0.38f, 0.52f, imgH),
            HIP      to bandFrac(0.52f, 0.65f, imgH),
            THIGH    to bandFrac(0.65f, 0.78f, imgH),
        ) else mapOf(
            CHEST          to bandFrac(0.30f, 0.50f, imgH),
            BELLY          to bandFrac(0.50f, 0.65f, imgH),
            LATERAL_THIGH  to bandFrac(0.65f, 0.78f, imgH),
        ),
        normalizer = imgH,
        normalization = NORMALIZATION_IMAGE_HEIGHT,
    )

    // ── Convention comparison (v40) ─────────────────────────────────────────

    /** The effective convention of a persisted normalization marker.
     *  Null markers (pre-v39/v40 rows) are the legacy convention — they
     *  were always produced by the fixed-fraction plan. */
    fun conventionOf(marker: String?): String =
        marker?.takeIf { it.isNotBlank() } ?: NORMALIZATION_IMAGE_HEIGHT

    /** True when two persisted markers denote the SAME unit of measurement
     *  — i.e. the metrics behind them can be compared in one trend chart.
     *  A legacy value and a pose-normalised value are NOT comparable even
     *  for the same anatomical zone. */
    fun sameConvention(a: String?, b: String?): Boolean =
        conventionOf(a) == conventionOf(b)

    // ── Pose-anchored path ────────────────────────────────────────────────────

    private data class Anchors(
        val shoulderY: Int,
        val hipY: Int,
        val kneeMidY: Int?,
        val personHeightPx: Int,
    )

    /** Returns null unless every required landmark is confidently in frame
     *  and the nose→ankle span is physically plausible for a full-body shot. */
    private fun fullBodyAnchors(
        landmarks: Map<LandmarkKey, LandmarkPt>?,
        minConfidence: Float,
        imgH: Int,
    ): Anchors? {
        if (landmarks == null || imgH <= 0) return null
        fun y(t: LandmarkKey): Float? {
            val lm = landmarks[t] ?: return null
            if (lm.inFrameLikelihood < minConfidence) return null
            return lm.y
        }
        val nose = y(LandmarkKey.NOSE) ?: return null
        val ls = y(LandmarkKey.LEFT_SHOULDER) ?: return null
        val rs = y(LandmarkKey.RIGHT_SHOULDER) ?: return null
        val lh = y(LandmarkKey.LEFT_HIP) ?: return null
        val rh = y(LandmarkKey.RIGHT_HIP) ?: return null
        val la = y(LandmarkKey.LEFT_ANKLE) ?: return null
        val ra = y(LandmarkKey.RIGHT_ANKLE) ?: return null

        val ankleMid = (la + ra) / 2f
        val spanPx = ankleMid - nose
        val spanFrac = spanPx / imgH
        if (spanFrac < MIN_PERSON_SPAN_FRAC || spanFrac > MAX_PERSON_SPAN_FRAC) return null

        val shoulderY = (ls + rs) / 2f
        val hipY = (lh + rh) / 2f
        if (hipY - shoulderY < 0.1f * spanPx) return null // degenerate (shoulders at/below hips)

        val kneeL = y(LandmarkKey.LEFT_KNEE)
        val kneeR = y(LandmarkKey.RIGHT_KNEE)
        val kneeMid = if (kneeL != null && kneeR != null) (kneeL + kneeR) / 2f else null

        return Anchors(
            shoulderY = shoulderY.toInt().coerceIn(0, imgH - 1),
            hipY = hipY.toInt().coerceIn(0, imgH - 1),
            kneeMidY = kneeMid?.toInt()?.coerceIn(0, imgH - 1),
            personHeightPx = spanPx.toInt().coerceAtLeast(1),
        )
    }

    private fun posePlan(isFrontal: Boolean, imgH: Int, a: Anchors): ZonePlan {
        val torso = a.hipY - a.shoulderY
        fun row(fracFromShoulder: Float): Int = (a.shoulderY + fracFromShoulder * torso).toInt()

        val thighTop = (a.hipY + 0.15f * torso).toInt()
        val thighBot = a.kneeMidY?.let { (it - 0.08f * torso).toInt() } ?: row(0.55f)

        val frontalBands = mapOf(
            SHOULDER to bandRow((a.shoulderY - 0.06f * torso).toInt(), (a.shoulderY + 0.08f * torso).toInt(), imgH),
            WAIST    to bandRow(row(0.52f), row(0.68f), imgH),
            HIP      to bandRow((a.hipY - 0.10f * torso).toInt(), (a.hipY + 0.12f * torso).toInt(), imgH),
            THIGH    to bandRow(thighTop, thighBot, imgH),
        )
        val lateralBands = mapOf(
            CHEST to bandRow(row(0.12f), row(0.42f), imgH),
            BELLY to bandRow(row(0.48f), (a.hipY - 0.06f * torso).toInt(), imgH),
            LATERAL_THIGH to bandRow(thighTop, thighBot, imgH),
        )
        return ZonePlan(
            bands = if (isFrontal) frontalBands else lateralBands,
            normalizer = a.personHeightPx,
            normalization = NORMALIZATION_POSE_PERSON_HEIGHT,
        )
    }

    private fun bandFrac(start: Float, end: Float, imgH: Int): ZoneBand =
        ZoneBand((start * imgH).toInt().coerceIn(0, imgH - 1), (end * imgH).toInt().coerceIn(0, imgH - 1))

    private fun bandRow(start: Int, end: Int, imgH: Int): ZoneBand =
        ZoneBand(start.coerceIn(0, imgH - 1), end.coerceIn(0, imgH - 1))

    // ── Ratio extraction (shared with the ML Kit segmentation path) ──────────

    /**
     * Mean foreground span over [band], normalised by [normalizer].
     * Returns null when fewer than [minCoverage] of the band's rows carry
     * foreground (person not visible in that zone). [rowSpan] holds the
     * leftmost–rightmost foreground width per image row.
     */
    fun zoneRatio(
        rowSpan: IntArray,
        band: ZoneBand,
        normalizer: Int,
        minCoverage: Float = 0.30f,
    ): Float? {
        if (normalizer <= 0 || rowSpan.isEmpty()) return null
        val r0 = band.startRow.coerceIn(0, rowSpan.size - 1)
        val r1 = band.endRow.coerceIn(0, rowSpan.size - 1)
        if (r1 < r0) return null
        val rows = r1 - r0 + 1
        var covered = 0
        var sum = 0L
        for (row in r0..r1) {
            val s = rowSpan[row]
            if (s > 0) covered++
            sum += s
        }
        if (covered.toFloat() / rows < minCoverage) return null
        return (sum.toFloat() / rows) / normalizer
    }
}
