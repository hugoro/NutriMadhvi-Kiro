package com.example.data.performance

import com.example.ui.tr
import com.example.data.performance.TrainingDemoCatalog.feelByView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v43.7 (2026-10) — personal-trainer execution protocol on the local
 * curated demo cards.
 *
 * The card must teach the user, in order:
 *   1. INTENT — what the exercise is for,
 *   2. FEEL — which muscles the user must feel to know the exercise is
 *      working, + the mirror self-check rule (stop → mirror → resume only
 *      when the sensation reproduces),
 *   3. (execution = existing cue),
 *   4. AFTER — what to do immediately after the set and what NOT to do
 *      (e.g. sitting down immediately → blood pools in the legs →
 *      dizziness/fainting).
 *
 * 90% of users cannot name where a muscle is, so each demo also declares
 * the muscles to FEEL with the body view where the UI draws the anatomical
 * highlight (frontal/lateral silhouette). This test pins the catalog
 * model + the localized protocol copy (PT/EN/GU) + the banned-terms rule.
 *
 * Pure JVM — no Android runtime.
 */
class TrainingDemoFeelProtocolTest {

    private val langs = listOf("pt", "en", "gu")
    private val protocolFields = listOf("intent", "feel", "after_do", "after_dont")

    // ── Catalog model: muscles to feel ──────────────────────────────────────

    @Test
    fun every_demo_declares_muscles_to_feel() {
        for (d in TrainingDemoCatalog.ALL) {
            assertTrue("demo ${d.id} has no muscles to feel", d.feel.isNotEmpty())
        }
    }

    @Test
    fun feel_entries_only_use_closed_view_set_and_known_regions() {
        val views = TrainingDemoCatalog.MuscleView.values().toSet()
        val regions = TrainingDemoCatalog.MuscleRegion.values().toSet()
        for (d in TrainingDemoCatalog.ALL) {
            for (f in d.feel) {
                assertTrue("unknown view in ${d.id}: ${f.view}", f.view in views)
                assertTrue("unknown region in ${d.id}: ${f.region}", f.region in regions)
            }
            // The UI renders at most one figure per view — the grouping must
            // therefore produce at most the three body views (v43.13:
            // FRONTAL + LATERAL + POSTERIOR).
            val grouped = d.feelByView()
            assertTrue("too many views for ${d.id}: $grouped", grouped.size <= 3)
        }
    }

    @Test
    fun squat_feels_quads_frontal_and_glutes_posterior() {
        val squat = TrainingDemoCatalog.byId("squat_bodyweight")!!
        val grouped = squat.feelByView()
        assertTrue("quads must read frontal: $grouped",
            (grouped[TrainingDemoCatalog.MuscleView.FRONTAL] ?: emptyList())
                .contains(TrainingDemoCatalog.MuscleRegion.QUADRICEPS))
        // v43.13: glutes from their canonical view — the back.
        assertTrue("glutes must read posterior: $grouped",
            (grouped[TrainingDemoCatalog.MuscleView.POSTERIOR] ?: emptyList())
                .contains(TrainingDemoCatalog.MuscleRegion.GLUTES))
    }

    // ── Localized protocol copy (PT / EN / GU) ──────────────────────────────

    @Test
    fun protocol_copy_exists_for_all_fields_and_languages() {
        for (d in TrainingDemoCatalog.ALL) {
            for (f in protocolFields) {
                val key = "demo_${d.id}_$f"
                for (lang in langs) {
                    val v = tr(key, lang)
                    assertNotEquals("missing i18n: $key ($lang)", key, v)
                    assertTrue("blank i18n: $key ($lang)", v.isNotBlank())
                }
                assertNotEquals("PT copy not distinct from EN: $key",
                    tr(key, "en"), tr(key, "pt"))
                assertTrue("GU copy lacks Gujarati script: $key",
                    tr(key, "gu").any { it in '\u0A80'..'\u0AFF' })
            }
        }
    }

    @Test
    fun section_labels_resolve_in_all_languages() {
        for (key in listOf("demo_intent_label", "demo_feel_label", "demo_after_label")) {
            for (lang in langs) {
                val v = tr(key, lang)
                assertNotEquals("missing i18n: $key ($lang)", key, v)
                assertTrue("blank i18n: $key ($lang)", v.isNotBlank())
            }
        }
    }

    // v43.8: the figure view labels + tap-to-zoom affordance copy must
    // resolve in every language (no raw keys on the card or in the zoom).
    @Test
    fun zoom_affordance_and_view_labels_resolve_in_all_languages() {
        for (key in listOf("demo_feel_zoom_hint", "demo_feel_zoom_close",
                           "view_frontal", "view_lateral", "view_posterior")) {
            for (lang in langs) {
                val v = tr(key, lang)
                assertNotEquals("missing i18n: $key ($lang)", key, v)
                assertTrue("blank i18n: $key ($lang)", v.isNotBlank())
            }
        }
    }

    @Test
    fun mirror_self_check_rule_in_every_language_and_in_stationary_feel_copy() {
        // The mirror rule is the protocol constant — it must resolve in every
        // language (the card's fail-closed fallback line uses it)…
        val mirror = mapOf("pt" to "espelho", "en" to "mirror", "gu" to "મોજીર")
        for ((lang, word) in mirror) {
            val fb = tr("demo_feel_mirror_fallback", lang).lowercase()
            assertTrue("$lang mirror fallback must carry the mirror rule: $fb",
                fb.contains(word))
            assertTrue("$lang mirror fallback must say stop: $fb",
                fb.contains(stopMarkers[lang]!![0]))
        }
        // …and the stationary demos (where a mirror is usable) carry it in
        // their own feel copy. The cardio demo uses the talk test instead —
        // a mirror cannot check a walking form, so no mirror word there.
        val stationary = setOf("squat_bodyweight", "hip_flexor_stretch")
        for (d in TrainingDemoCatalog.ALL) {
            if (d.id !in stationary) continue
            for ((lang, word) in mirror) {
                val feel = tr("demo_${d.id}_feel", lang).lowercase()
                assertTrue("$lang feel copy must carry the mirror rule: $feel",
                    feel.contains(word))
            }
        }
    }

    /** Stop/ease-off markers per language — a feel copy must carry ONE. */
    private val stopMarkers = mapOf(
        "pt" to listOf("para", "abrandar"),
        "en" to listOf("stop", "slow down"),
        "gu" to listOf("થોભો", "ધીમું કરો"),
    )

    @Test
    fun feel_copy_contains_a_stop_instruction_in_every_language() {
        for (d in TrainingDemoCatalog.ALL) {
            for (lang in langs) {
                val feel = tr("demo_${d.id}_feel", lang).lowercase()
                assertTrue("$lang feel copy must say stop: $feel",
                    stopMarkers[lang]!!.any { feel.contains(it) })
            }
        }
    }

    @Test
    fun after_dont_carries_a_safety_rule_in_every_language() {
        // The universal safety guarantee: the after-don't must protect
        // against fainting (blood pooling) or breath-holding — whichever
        // hazard is the real one for that exercise.
        val safety = mapOf(
            "pt" to listOf("tontura", "desmaio", "respira"),
            "en" to listOf("dizziness", "fainting", "breath"),
            "gu" to listOf("ઝડપકાઈ", "શ્વસન"),
        )
        for (d in TrainingDemoCatalog.ALL) {
            for (lang in langs) {
                val dont = tr("demo_${d.id}_after_dont", lang).lowercase()
                assertTrue("$lang after_dont must carry a safety rule: $dont",
                    safety[lang]!!.any { dont.contains(it) })
            }
        }
    }

    @Test
    fun after_do_and_after_dont_carry_numbers_with_units_in_pt() {
        // The PT methodology requires a concrete "what to do immediately"
        // (with a time) — pin at least one number per after_do (PT).
        for (d in TrainingDemoCatalog.ALL) {
            val doIt = tr("demo_${d.id}_after_do", "pt")
            assertTrue("PT after_do must carry a time (minutes): $doIt",
                Regex("[0-9]+\\s*(?:–-)?\\s*[0-9]*\\s*min").containsMatchIn(doIt) ||
                    doIt.contains("minuto"))
        }
    }

    // ── Banned terms (same rule as the base card copy) ──────────────────────

    @Test
    fun protocol_copy_never_promises_spot_fat_loss_and_never_mentions_camera() {
        val banned = listOf(
            "perder gordura local", "queimar gordura local", "gordura localizada",
            "reduzir gordura local", "eliminar gordura", "spot reduction",
            "melt", "melting", "burn fat", "fat loss",
            "câmera", "camera", "webcam", "fotocâmera",
        )
        for (d in TrainingDemoCatalog.ALL) {
            for (f in protocolFields) {
                for (lang in langs) {
                    val v = tr("demo_${d.id}_$f", lang).lowercase()
                    for (b in banned.distinct()) {
                        assertFalse("$b found in ${d.id}/$f/$lang → '$v'", v.contains(b))
                    }
                }
            }
        }
    }

    // ── Muscle labels (the anatomical-figure captions) ──────────────────────

    @Test
    fun muscle_labels_resolve_in_all_languages() {
        for (r in TrainingDemoCatalog.MuscleRegion.values()) {
            val key = "muscle_${r.name.lowercase()}"
            for (lang in langs) {
                val v = tr(key, lang)
                assertNotEquals("missing i18n: $key ($lang)", key, v)
                assertTrue("blank i18n: $key ($lang)", v.isNotBlank())
            }
            assertNotEquals("PT not distinct from EN: $key",
                tr(key, "en"), tr(key, "pt"))
            assertTrue("GU lacks Gujarati script: $key",
                tr(key, "gu").any { it in '\u0A80'..'\u0AFF' })
        }
    }

    // ── Deterministic strength fallback template (no longer vague) ─────────

    @Test
    fun strength_fallback_template_is_concrete_in_every_language() {
        // The old template was "2–3 sessões/semana com progressão gradual da
        // carga" — no exercise, no sets×reps, no session length. Pin the new
        // concreteness so it cannot regress to vague copy.
        val pt = tr("tactic_strength_progressive", "pt")
        assertTrue("PT template must name session length: $pt", pt.contains("30–45 min"))
        assertTrue("PT template must carry sets×reps: $pt", pt.contains("3×8–12"))
        assertTrue("PT template must carry exercises: $pt",
            pt.contains("agachamento") && pt.contains("prancha"))
        assertTrue("PT template must carry a numbered progression rule: $pt",
            pt.contains("+1") && pt.contains("2 semanas"))

        val en = tr("tactic_strength_progressive", "en")
        assertTrue("EN template must name session length: $en", en.contains("30–45 min"))
        assertTrue("EN template must carry sets×reps: $en", en.contains("3×8–12"))
        assertTrue("EN template must carry a numbered progression rule: $en",
            en.contains("+1") && en.contains("2 weeks"))

        val gu = tr("tactic_strength_progressive", "gu")
        assertTrue("GU template must name session length: $gu", gu.contains("30–45"))
        assertTrue("GU template must carry sets×reps: $gu", gu.contains("3×8–12"))
        assertTrue("GU template must be real Gujarati: $gu",
            gu.any { it in '\u0A80'..'\u0AFF' })
    }

    @Test
    fun prompt_requires_concrete_strength_tactics() {
        val p = BodyPlanPromptBuilder.buildTacticsPrompt(
            phase = null,
            priorities = emptyList(),
            goalText = null,
            latestComp = null,
            name = "X",
            lang = "pt",
        )
        val low = p.lowercase()
        assertTrue("prompt must require sets×reps in strength tactics",
            low.contains("séries × repetições"))
        assertTrue("prompt must require a numbered progression rule",
            low.contains("progressão em números"))
        assertTrue("prompt must name the anti-vagueness rule",
            low.contains("nunca só"))
    }

    // ── v43.7: anatomical highlight geometry (muscle-map figures) ───────────

    @Test
    fun every_catalog_feel_combination_has_geometry_for_both_genders() {
        val missing = MuscleMapGeometry.missingCoverage()
        assertEquals("catalog feel pairs without geometry (fail open): $missing",
            emptySet<Pair<TrainingDemoCatalog.MuscleView, TrainingDemoCatalog.MuscleRegion>>(),
            missing)
        for (isMale in listOf(true, false)) {
            for (d in TrainingDemoCatalog.ALL) {
                for (f in d.feel) {
                    val h = MuscleMapGeometry.highlight(isMale, f.view, f.region)
                    assertTrue("no geometry for ${d.id} ${f.region}/${f.view} (male=$isMale)",
                        h != null && h.polys.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun geometry_polys_are_well_formed_and_inside_the_png() {
        for (isMale in listOf(true, false)) {
            for (view in TrainingDemoCatalog.MuscleView.values()) {
                for (region in TrainingDemoCatalog.MuscleRegion.values()) {
                    val h = MuscleMapGeometry.highlight(isMale, view, region) ?: continue
                    for (poly in h.polys) {
                        assertTrue("degenerate polygon: $region $view", poly.pts.size >= 8)
                        for ((x, y) in poly.pts) {
                            assertTrue("point outside PNG: $region $view ($x,$y)",
                                x in 0f..1f && y in 0f..1f)
                            // body span guard: never in the empty PNG margins
                            assertTrue("point outside body span: $region $view ($x,$y)",
                                y in 0.05f..0.95f)
                        }
                        // a real loop: non-zero bounding box (no degenerate sliver)
                        val xs = poly.pts.map { it.first }
                        val ys = poly.pts.map { it.second }
                        val w = xs.max() - xs.min()
                        val ht = ys.max() - ys.min()
                        assertTrue("degenerate polygon: $region $view (w=$w h=$ht)",
                            w > 0.005f && ht > 0.005f)
                    }
                }
            }
        }
    }

    @Test
    fun regions_not_drawn_in_a_view_fail_closed_to_null() {
        // quads are a frontal read; glutes a posterior read (v43.13) —
        // cross-view asks must be null so the UI never renders a figure in
        // the wrong view.
        assertTrue(MuscleMapGeometry.highlight(true, TrainingDemoCatalog.MuscleView.FRONTAL,
            TrainingDemoCatalog.MuscleRegion.GLUTES) == null)
        assertTrue(MuscleMapGeometry.highlight(true, TrainingDemoCatalog.MuscleView.LATERAL,
            TrainingDemoCatalog.MuscleRegion.QUADRICEPS) == null)
        assertTrue(MuscleMapGeometry.highlight(false, TrainingDemoCatalog.MuscleView.LATERAL,
            TrainingDemoCatalog.MuscleRegion.HIP_FLEXORS) == null)
        // v43.13: back view — no front-of-body muscles there either.
        assertTrue(MuscleMapGeometry.highlight(true, TrainingDemoCatalog.MuscleView.POSTERIOR,
            TrainingDemoCatalog.MuscleRegion.QUADRICEPS) == null)
        assertTrue(MuscleMapGeometry.highlight(false, TrainingDemoCatalog.MuscleView.POSTERIOR,
            TrainingDemoCatalog.MuscleRegion.PECTORALS) == null)
        assertTrue(MuscleMapGeometry.highlight(false, TrainingDemoCatalog.MuscleView.POSTERIOR,
            TrainingDemoCatalog.MuscleRegion.HIP_FLEXORS) == null)
        // …and front muscles are not drawn in the back view.
        assertTrue(MuscleMapGeometry.highlight(true, TrainingDemoCatalog.MuscleView.FRONTAL,
            TrainingDemoCatalog.MuscleRegion.LATS) == null)
        assertTrue(MuscleMapGeometry.highlight(true, TrainingDemoCatalog.MuscleView.FRONTAL,
            TrainingDemoCatalog.MuscleRegion.HAMSTRINGS) == null)
    }

    @Test
    fun v43_12_highlights_sit_on_the_right_body_part() {
        // Regression guard, v43.12 STANDARD-ANATOMY pass (user QA 2026-10-22:
        // "asneira na interpretação… não precisas de inventar que os glúteos
        // ficam no peito ou costas"). v43.17 (2026-10-23, user QA 2nd round:
        // pecs rising above the clavicles onto the neck/trapezius, quads
        // covering the patella and spilling onto the TFL/hip flexors): the
        // v43.11 constants were measured on the earlier slim renders; on the
        // current realistic A-pose demo_muscle_* assets the proportions differ
        // (male: clavicle f 0.20, chest 0.22–0.42, crotch 0.575, patella
        // 0.83–0.87; female v43.24 full-body render: clavicle f 0.27,
        // crotch f 0.51, patella f 0.73).
        // The bands below are measured on those assets (row-profile landmark
        // analysis) and are GENDER-AWARE — male and female figures no longer
        // share the same vertical bands.
        fun bodyFrac(isMale: Boolean, view: TrainingDemoCatalog.MuscleView,
                     poly: MuscleMapGeometry.Poly): Pair<Float, Float> {
            val (top, bot) = when (view) {
                TrainingDemoCatalog.MuscleView.FRONTAL ->
                    // v43.24: the female frontal is the full-body single
                    // render (head to sole, Gemini inpainted feet); body
                    // rows 34..1308.
                    if (isMale) 0.001f to 0.986f else 0.0253f to 0.9732f
                TrainingDemoCatalog.MuscleView.LATERAL ->
                    if (isMale) 0.001f to 0.969f else 0.101f to 0.957f
                TrainingDemoCatalog.MuscleView.POSTERIOR ->
                    if (isMale) 0.002f to 0.946f else 0.114f to 0.931f
            }
            val ys = poly.pts.map { it.second }.map { (it - top) / (bot - top) }
            return ys.min() to ys.max()
        }
        fun xMid(poly: MuscleMapGeometry.Poly): Float {
            val xs = poly.pts.map { it.first }
            return (xs.min() + xs.max()) / 2f
        }
        fun band(label: String, got: Float, lo: Float, hi: Float) {
            assertTrue("$label out of band: $got (want $lo..$hi)", got >= lo && got <= hi)
        }
        for (isMale in listOf(true, false)) {
            val front = TrainingDemoCatalog.MuscleView.FRONTAL
            // Quads on the THIGH — below the crotch, ending above the
            // patella (v43.17: the old band ended at f 0.78, ON the knee).
            for (poly in MuscleMapGeometry.highlight(isMale, front,
                TrainingDemoCatalog.MuscleRegion.QUADRICEPS)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, front, poly)
                val mid = (fMin + fMax) / 2f
                if (isMale) {
                    assertTrue("quads (male) not on the thigh: f ${fMin}..$fMax",
                        fMin >= 0.58f && fMax <= 0.85f && mid >= 0.60f)
                } else {
                    // v43.24: on the full-body render the crotch sits at
                    // f0.51 and the knee at f0.73 (longer legs than the
                    // v43.23 composite, crotch f0.69 / knee f0.87).
                    assertTrue("quads (female) not on the thigh: f ${fMin}..$fMax",
                        fMin >= 0.50f && fMax <= 0.78f && mid >= 0.58f)
                }
                if (isMale) band("quads male x on the drawn legs", xMid(poly), 0.38f, 0.62f)
                else band("quads female x on the drawn legs", xMid(poly), 0.30f, 0.72f)
            }
            // Pecs on the CHEST — below the clavicle (v43.17: the old top
            // rode onto the neck/upper trapezius), above the waist.
            for (poly in MuscleMapGeometry.highlight(isMale, front,
                TrainingDemoCatalog.MuscleRegion.PECTORALS)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, front, poly)
                if (isMale) {
                    assertTrue("pecs (male) not on the chest: f ${fMin}..$fMax",
                        fMin >= 0.18f && fMax <= 0.44f)
                } else {
                    assertTrue("pecs (female) not on the chest: f ${fMin}..$fMax",
                        fMin >= 0.22f && fMax <= 0.44f)
                }
            }
            // Lateral view.
            val lat = TrainingDemoCatalog.MuscleView.LATERAL
            // Glutes in the BUTTOCK band — below the waist line. v43.17: on
            // the A-pose renders the buttock mass sits at f 0.58–0.75 (the
            // v43.11 lobe sat on the iliac crest / hip line).
            for (poly in MuscleMapGeometry.highlight(isMale, lat,
                TrainingDemoCatalog.MuscleRegion.GLUTES)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, lat, poly)
                assertTrue("glutes ($isMale) not on the buttock: f ${fMin}..$fMax",
                    fMin >= 0.50f && fMax <= 0.78f)
                band("glutes ($isMale) on the rear side", xMid(poly), 0.10f, 0.48f)
            }
            // Lats in the MID-LOWER BACK — the regression the user flagged:
            // the old lobe (f 0.14–0.26) was on the NECK/upper trapezius.
            for (poly in MuscleMapGeometry.highlight(isMale, lat,
                TrainingDemoCatalog.MuscleRegion.LATS)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, lat, poly)
                assertTrue("lats ($isMale) not mid-lower back: f ${fMin}..$fMax",
                    fMin >= 0.22f && fMax <= 0.50f)
                if (isMale) band("lats male x on the back line", xMid(poly), 0.32f, 0.48f)
            }
            // Hamstrings: back of the thigh, on the rear half of the
            // drawn leg (render faces right → back = low x).
            for (poly in MuscleMapGeometry.highlight(isMale, lat,
                TrainingDemoCatalog.MuscleRegion.HAMSTRINGS)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, lat, poly)
                assertTrue("hamstrings ($isMale) not on the back thigh: f ${fMin}..$fMax",
                    fMin >= 0.50f && fMax <= 0.78f)
                band("hamstrings ($isMale) on the rear leg", xMid(poly), 0.30f, 0.56f)
            }
            // Calves: below the knee, behind the lower leg.
            for (poly in MuscleMapGeometry.highlight(isMale, lat,
                TrainingDemoCatalog.MuscleRegion.CALVES)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, lat, poly)
                assertTrue("calves ($isMale) not below the knee: f ${fMin}..$fMax",
                    fMin >= 0.72f && fMax <= 0.95f)
            }
            // v43.13: posterior (back) view — back muscles from the back.
            val post = TrainingDemoCatalog.MuscleView.POSTERIOR
            // Lats: mid-lower back, flanking the spine — never the neck,
            // never the waist.
            for (poly in MuscleMapGeometry.highlight(isMale, post,
                TrainingDemoCatalog.MuscleRegion.LATS)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, post, poly)
                assertTrue("lats post ($isMale) not mid-lower back: f ${fMin}..$fMax",
                    fMin >= 0.22f && fMax <= 0.52f)
            }
            // Glutes: buttock band, below the waist line. v43.17: the
            // gluteal fold on the A-pose renders sits at f 0.58–0.62, so
            // the v43.11 band (f 0.42–0.65) read as "glutes on the hip".
            for (poly in MuscleMapGeometry.highlight(isMale, post,
                TrainingDemoCatalog.MuscleRegion.GLUTES)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, post, poly)
                assertTrue("glutes post ($isMale) not on the buttock: f ${fMin}..$fMax",
                    fMin >= 0.52f && fMax <= 0.85f)
            }
            // Hamstrings: back of the thigh below the glutes, ending
            // above the knee (v43.17: the v43.11 band ran crotch→knee).
            for (poly in MuscleMapGeometry.highlight(isMale, post,
                TrainingDemoCatalog.MuscleRegion.HAMSTRINGS)!!.polys) {
                val (fMin, fMax) = bodyFrac(isMale, post, poly)
                assertTrue("hamstrings post ($isMale) not on the back thigh: f ${fMin}..$fMax",
                    fMin >= 0.74f && fMax <= 0.95f)
            }
        }
    }
}
