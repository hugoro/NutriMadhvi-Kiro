package com.example.data.performance

import kotlin.math.cos
import kotlin.math.sin

/**
 * v43.7 (2026-10) — anatomical "where to feel it" highlight geometry.
 *
 * For each (gender × body-view × muscle-region) this returns a small set of
 * closed polygons in NORMALIZED silhouette-PNG coordinates [0,1] (the same
 * convention SilhouetteGoalCard.kt uses: the figure fills the PNG, so [0,1]
 * maps straight onto the rendered image). The UI overlays these polygons on
 * the local silhouette to show the user WHERE the muscle is — because 90% of
 * users cannot name it.
 *
 * These are EDUCATIONAL, approximate regions (a trainer's "feel this area"
 * highlight), NOT medical anatomy. Deliberately generous and simple so the
 * highlight reads at the small card size.
 *
 * v43.11 (2026-10-22): FULL RECALIBRATION. The v43.7 constants were measured
 * on an earlier silhouette drawing; the assets were since redrawn with
 * different proportions, and the highlights landed on the wrong body parts
 * (the "quadríceps" glow sat on the chest — user QA). The body span
 * (head-top..feet) now comes from each actual PNG, and every muscle fraction
 * was re-measured on the real silhouettes (row-profile landmark analysis:
 * shoulders ≈ f 0.15, chest ≈ f 0.18–0.30, crotch ≈ f 0.48, knee ≈ f 0.75 on
 * the male frontal; thigh centres measured per row). Verified by rendering
 * the polygons onto the PNGs and checking the amber clusters per body
 * fraction (see scratch geo_render.py output).
 *
 * Pure JVM — no Android types, unit-testable. The geometry is a closed set:
 * the UI renders a figure only for a combination that has geometry here,
 * otherwise it fails closed to the text-only "Deves sentir" line.
 */
object MuscleMapGeometry {

    /** A closed polygon of normalized [x,y] points (order defines the outline). */
    data class Poly(val pts: List<Pair<Float, Float>>)

    /** A muscle region may be drawn as more than one lobe (e.g. two quads). */
    data class Highlight(val polys: List<Poly>)

    /**
     * The figure's vertical span on each silhouette PNG (head-top..feet,
     * measured on the actual assets). Landmarks below are fractions of that
     * span (0 = head top, 1 = feet), so the highlight tracks the body, not
     * the empty PNG margins.
     */
    private data class Span(val top: Float, val bot: Float)

    private fun span(
        isMale: Boolean,
        view: TrainingDemoCatalog.MuscleView,
    ): Span = when {
        // v43.13: measured (alpha>0, head-top..feet) on the realistic
        // demo_muscle_* Gemini renders (768x1344, 9:16).
        // v43.20 (2026-10-23): the MALE set was regenerated (A-pose matching
        // the female standard, dark brown hair, white briefs) — the male
        // spans were re-measured on the new assets (all three male views now
        // span the same body rows 34..1309). Female unchanged.
        isMale && view == TrainingDemoCatalog.MuscleView.FRONTAL -> Span(0.025f, 0.974f)
        // v43.24: the female frontal is a FULL-BODY single render (head to
        // sole — Gemini inpainted feet on the approved figure base, no
        // composite seam; body rows 34..1308, foot ratio 9.3% ankle→sole).
        // The v43.23 preservation composite (rows 52..1291) and the v43.21
        // D regeneration and the v43.22 20px sole stub were all rejected.
        !isMale && view == TrainingDemoCatalog.MuscleView.FRONTAL -> Span(0.0253f, 0.9732f)
        isMale && view == TrainingDemoCatalog.MuscleView.LATERAL -> Span(0.025f, 0.974f)
        !isMale && view == TrainingDemoCatalog.MuscleView.LATERAL -> Span(0.101f, 0.957f)
        isMale -> Span(0.025f, 0.974f) // male posterior
        else -> Span(0.114f, 0.931f) // female posterior
    }

    /**
     * A vertical rounded "muscle lobe": a stadium/capsule shape centred on
     * [cx], spanning body-fractions [topF]..[botF], with half-width [hw]
     * (PNG-normalized). [capY] is the rounded-cap radius in Y units —
     * defaults to a full semicircle (hw) but can be flattened so adjacent
     * lobes (lats above glutes) keep a clear gap between their cores.
     * Sampled into a smooth closed polygon.
     */
    private fun lobe(
        yf: (Float) -> Float,
        cx: Float,
        topF: Float,
        botF: Float,
        hw: Float,
        capY: Float = hw,
    ): Poly {
        val pts = ArrayList<Pair<Float, Float>>()
        val n = 16
        // Down the right side, rounded bottom, up the left side, rounded top.
        for (i in 0..n) {
            val t = i.toFloat() / n
            val yy = yf(topF) + t * (yf(botF) - yf(topF))
            pts.add(cx + hw to yy)
        }
        // Rounded bottom (ellipse hw × capY) — sweep right→left.
        val cy = yf(botF)
        for (i in 1 until n) {
            val a = Math.toRadians((i.toFloat() / n) * 180.0)
            pts.add(cx + hw * cos(a).toFloat() to cy + capY * sin(a).toFloat())
        }
        for (i in 0..n) {
            val t = 1f - i.toFloat() / n
            val yy = yf(topF) + t * (yf(botF) - yf(topF))
            pts.add(cx - hw to yy)
        }
        // Rounded top (ellipse hw × capY) — sweep left→right.
        for (i in 1 until n) {
            val a = Math.toRadians((i.toFloat() / n) * 180.0)
            pts.add(cx - hw * cos(a).toFloat() to yf(topF) - capY * sin(a).toFloat())
        }
        return Poly(pts)
    }

    /**
     * The highlight for a (gender, view, region), or null when that muscle
     * is not drawn in that view (fail closed → text-only rendering).
     */
    fun highlight(
        isMale: Boolean,
        view: TrainingDemoCatalog.MuscleView,
        region: TrainingDemoCatalog.MuscleRegion,
    ): Highlight? {
        val s = span(isMale, view)
        val yf: (Float) -> Float = { f -> s.top + f * (s.bot - s.top) }

        return when (view) {
            TrainingDemoCatalog.MuscleView.FRONTAL -> when (region) {
                // QUADRICEPS: front of each thigh (crotch→knee), STANDARD
                // anatomy. v43.13: the realistic renders draw the legs
                // SEPARATE (A-pose) — centres measured on the keyed ink
                // (male thighs x 0.33–0.46 / 0.52–0.66; female x 0.34–0.47 /
                // 0.51–0.65).
                TrainingDemoCatalog.MuscleRegion.QUADRICEPS -> Highlight(listOf(
                    if (isMale) lobe(yf, 0.405f, 0.645f, 0.795f, 0.050f, capY = 0.025f)
                    // v43.24: full-body single render (body rows 34..1308) —
                    // crotch f0.51, knee f0.73, ankle f0.91; legs measured
                    // at x 0.415–0.425 / 0.574–0.586, widths 0.15→0.09.
                    // Quads run just below the crotch and end above the
                    // knee.
                    else lobe(yf, 0.420f, 0.562f, 0.727f, 0.050f, capY = 0.0234f),
                    if (isMale) lobe(yf, 0.590f, 0.645f, 0.795f, 0.050f, capY = 0.025f)
                    else lobe(yf, 0.580f, 0.562f, 0.727f, 0.050f, capY = 0.0234f),
                ))
                // Hip flexors: a short band across the groin / front of hip.
                // v43.24: gender-aware — the female figure's crotch sits at
                // f0.51 (the male's at f0.58), so the female band sits just
                // above the crotch line (rectus-femoris origin), clear of
                // the quad lobes (which start at f0.562).
                TrainingDemoCatalog.MuscleRegion.HIP_FLEXORS -> Highlight(listOf(
                    if (isMale) lobe(yf, 0.500f, 0.47f, 0.54f, 0.13f, capY = 0.025f)
                    else lobe(yf, 0.500f, 0.445f, 0.505f, 0.122f, capY = 0.0234f),
                ))
                // PECTORALS: two lobes on the chest (below clavicle, above
                // waist). Female wears a top — the glow still marks the
                // chest region beneath it.
                // v43.24: re-centred on the figure axis (x=384): the old
                // lobes sat asymmetrically on the off-centre composite.
                TrainingDemoCatalog.MuscleRegion.PECTORALS -> Highlight(listOf(
                    if (isMale) lobe(yf, 0.425f, 0.25f, 0.39f, 0.055f, capY = 0.02f)
                    else lobe(yf, 0.435f, 0.281f, 0.375f, 0.043f, capY = 0.0187f),
                    if (isMale) lobe(yf, 0.575f, 0.25f, 0.39f, 0.055f, capY = 0.02f)
                    else lobe(yf, 0.565f, 0.281f, 0.375f, 0.043f, capY = 0.0187f),
                ))
                // These are read on the lateral / posterior figures.
                else -> null
            }
            TrainingDemoCatalog.MuscleView.LATERAL -> {
                // The figure faces RIGHT in the lateral render, so the
                // BACK sits LEFT of the axis (x < axis). Only the CALVES
                // are shown lateral in the catalog (v43.13) — the other
                // muscles read in their canonical views.
                when (region) {
                    // Glutes: the buttock mass, rear-side, below the waist
                    // line (iliac crest) — kept for completeness (the
                    // catalog shows glutes posterior).
                    // v43.17: re-measured on the realistic A-pose renders
                    // (buttock mass f 0.60–0.75 of the body span).
                    // v43.20: male cx nudged 0.44→0.46 on the regenerated
                    // male lateral (body edge at the glute rows is x≈0.41;
                    // 0.44 pushed the lobe 13px outside the silhouette).
                    TrainingDemoCatalog.MuscleRegion.GLUTES -> Highlight(listOf(
                        lobe(yf, if (isMale) 0.46f else 0.465f, 0.608f, 0.70f,
                            if (isMale) 0.045f else 0.038f, capY = 0.028f),
                    ))
                    // Calves: the lower-leg bulk behind the knee.
                    TrainingDemoCatalog.MuscleRegion.CALVES -> Highlight(listOf(
                        lobe(yf, if (isMale) 0.44f else 0.44f, 0.805f, 0.905f,
                            if (isMale) 0.045f else 0.028f, capY = 0.025f),
                    ))
                    // Hamstrings: back of the thigh, rear of the femur.
                    TrainingDemoCatalog.MuscleRegion.HAMSTRINGS -> Highlight(listOf(
                        lobe(yf, if (isMale) 0.445f else 0.458f, 0.59f, 0.71f,
                            if (isMale) 0.042f else 0.034f, capY = 0.02f),
                    ))
                    // Lats: MID-LOWER BACK, below the armpit to the waist —
                    // kept for completeness (the catalog shows lats
                    // posterior).
                    TrainingDemoCatalog.MuscleRegion.LATS -> Highlight(listOf(
                        lobe(yf, if (isMale) 0.46f else 0.47f, 0.305f, 0.435f,
                            if (isMale) 0.045f else 0.038f, capY = 0.025f),
                    ))
                    // Frontal / posterior muscles are not drawn lateral.
                    else -> null
                }
            }
            // v43.13: POSTERIOR — the back view. The user's rule: back
            // muscles (lats, hamstrings, glutes) read from the back, where
            // they are CANONICALLY seen. All three are paired left/right of
            // the spine; vertical bands are the canonical 8-head ones
            // (lats = mid-lower back between armpit and waist; glutes =
            // buttocks below the waist line; hamstrings = back of thigh).
            // x centres / half-widths measured on the demo_muscle_*
            // posterior renders (torso core x 0.38–0.62; legs separate at
            // f 0.53–0.56).
            TrainingDemoCatalog.MuscleView.POSTERIOR -> when (region) {
                // Lats: mid-lower back, flanking the spine.
                // v43.17: re-measured on the realistic A-pose renders —
                // the back panel runs from just below the armpit (f 0.28)
                // to the waist line (f 0.48); the v43.11 constants started
                // at the shoulder/trapezius.
                TrainingDemoCatalog.MuscleRegion.LATS -> Highlight(listOf(
                    lobe(yf, if (isMale) 0.445f else 0.445f, 0.30f, 0.46f,
                        if (isMale) 0.045f else 0.042f, capY = 0.02f),
                    lobe(yf, if (isMale) 0.553f else 0.550f, 0.30f, 0.46f,
                        if (isMale) 0.045f else 0.042f, capY = 0.02f),
                ))
                // Glutes: the two buttock masses, below the waist line.
                // v43.17: on the A-pose renders the gluteal fold sits at
                // f 0.58–0.62 and the buttocks end at f 0.78–0.80 (the
                // v43.11 constants sat on the iliac crest / hip line).
                TrainingDemoCatalog.MuscleRegion.GLUTES -> Highlight(listOf(
                    lobe(yf, if (isMale) 0.41f else 0.427f, 0.625f, 0.775f,
                        if (isMale) 0.045f else 0.040f, capY = 0.025f),
                    lobe(yf, if (isMale) 0.59f else 0.566f, 0.625f, 0.775f,
                        if (isMale) 0.045f else 0.040f, capY = 0.025f),
                ))
                // Hamstrings: back of the thigh below the glutes, ending
                // above the knee. v43.17: re-measured (the v43.11 band ran
                // crotch→knee and read as "back of the whole leg").
                TrainingDemoCatalog.MuscleRegion.HAMSTRINGS -> Highlight(listOf(
                    lobe(yf, if (isMale) 0.40f else 0.428f, 0.84f, 0.89f,
                        if (isMale) 0.034f else 0.032f, capY = 0.02f),
                    lobe(yf, if (isMale) 0.595f else 0.566f, 0.84f, 0.89f,
                        if (isMale) 0.034f else 0.032f, capY = 0.02f),
                ))
                // Front-of-body muscles are not drawn in the back view.
                else -> null
            }
        }
    }

    /** True when the UI can render a figure for this combination. */
    fun hasGeometry(
        isMale: Boolean,
        view: TrainingDemoCatalog.MuscleView,
        region: TrainingDemoCatalog.MuscleRegion,
    ): Boolean = highlight(isMale, view, region) != null

    /**
     * Every (view, region) pair the catalog actually uses must have geometry.
     * Returns the missing pairs (empty = full coverage, fail closed).
     */
    fun missingCoverage(): Set<Pair<TrainingDemoCatalog.MuscleView, TrainingDemoCatalog.MuscleRegion>> {
        val used = TrainingDemoCatalog.ALL.flatMap { it.feel }
            .map { it.view to it.region }
            .toSet()
        val missing = mutableSetOf<Pair<TrainingDemoCatalog.MuscleView, TrainingDemoCatalog.MuscleRegion>>()
        for ((view, region) in used) {
            for (isMale in listOf(true, false)) {
                if (!hasGeometry(isMale, view, region)) {
                    missing += view to region
                    break
                }
            }
        }
        return missing
    }
}
