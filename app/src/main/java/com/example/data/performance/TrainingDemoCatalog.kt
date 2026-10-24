package com.example.data.performance

/**
 * v43 (2026-10) — LOCAL curated catalog of training demonstrations.
 *
 * The LLM never invents exercises, URLs, or loads: it may only reference
 * one of these stable [id]s via the optional `demoId` tactic field, and
 * [TacticSafetyValidator] rejects anything that is not in
 * [ALLOWED_DEMO_IDS] (fail closed). Every card is:
 *   - illustrated by a LOCAL asset ([illustrationName] — the UI maps the
 *     name to R.drawable; no remote images, no camera),
 *   - localized copy through `tr("demo_<id>_name|prescription|cue|progression")`,
 *   - demonstrated by a previously curated https video on an allowlisted
 *     host, opened externally (ACTION_VIEW) — that is all, at this stage.
 *
 * Pure JVM: no Android types, unit-testable.
 */
object TrainingDemoCatalog {

    enum class DemoKind { STRENGTH, ACTIVITY, RECOVERY }

    /** v43.7 (2026-10): muscles the user should FEEL during the exercise —
     *  the personal-trainer protocol step "indicar que músculos o utilizador
     *  deverá sentir para saber se está a ter o impacto desejável". 90% of
     *  users cannot name where a muscle is, so each [MuscleFeel] also fixes
     *  the BODY VIEW (frontal/lateral) where the UI draws the anatomical
     *  highlight over the local silhouette. Closed set — the geometry file
     *  must cover every (gender × view × region) combination, guarded by
     *  tests; an uncovered combination fails closed to text-only rendering. */
    enum class MuscleRegion { QUADRICEPS, GLUTES, HIP_FLEXORS, CALVES, HAMSTRINGS,
        // v43.9 (2026-10): upper-body regions — the "full-body strength
        // circuit" demo shows where the torso work lands (chest reads
        // frontal, back reads posterior), so "força" users can point at the
        // muscle just like the leg demos do.
        PECTORALS, LATS }

    // v43.13 (2026-10-22): POSTERIOR added — the user wants back muscles
    // (lats, hamstrings, glutes) shown from their CANONICAL view: the back,
    // not the side. Lateral stays for muscles that read best in profile
    // (calves). Order = render order in the card Row.
    enum class MuscleView { FRONTAL, LATERAL, POSTERIOR }

    data class MuscleFeel(
        val region: MuscleRegion,
        val view: MuscleView,
    )

    data class TrainingDemo(
        val id: String,
        val kind: DemoKind,
        /** Resource NAME of the local illustration (UI resolves to R.drawable). */
        val illustrationName: String,
        /** Previously curated demonstration video (https, allowlisted host). */
        val videoUrl: String,
        /** v43.1: tactic categories this demo is compatible with. The
         *  validator rejects a tactic whose category is not in this set —
         *  a walking demo must not ride on a strength tactic. */
        val compatibleCategories: Set<String>,
        /** v43.1: body regions (PT/EN keywords, matched in tactic text+target)
         *  that CONFLICT with this demo. A lower-body demo on an upper-body
         *  strength tactic is misleading guidance → rejected (fail closed). */
        val conflictKeywords: List<String> = emptyList(),
        /** v43.1: zone keys that conflict with this demo (zone tactics). */
        val conflictZones: Set<String> = emptySet(),
        /** v43.7: muscles to feel, each with the view where it is highlighted.
         *  At most one frontal + one lateral cluster (the UI renders at most
         *  two mini-figures per card). Empty = text-only "Deves sentir". */
        val feel: List<MuscleFeel> = emptyList(),
    )

    /** v43.7: feel entries grouped per view — the UI renders one figure per
     *  view with all its regions highlighted. Pure JVM. */
    fun TrainingDemo.feelByView(): Map<MuscleView, List<MuscleRegion>> =
        feel.groupBy({ it.view }, { it.region })

    // v43.1: upper-body region keywords (PT/EN) — a demo targeting the lower
    // body must not be attached to a tactic focused on any of these.
    private val UPPER_BODY_KEYWORDS = listOf(
        "membros superiores", "membro superior", "superiores",
        "ombros", "ombro", "peito", "bra\u00e7os", "bra\u00e7o",
        "shoulders", "shoulder", "chest", "arms", "arm ", "upper body",
    )

    val ALL: List<TrainingDemo> = listOf(
        TrainingDemo(
            id = "squat_bodyweight",
            kind = DemoKind.STRENGTH,
            illustrationName = "demo_squat_bodyweight",
            videoUrl = "https://www.youtube.com/watch?v=CKcDiJnLaLY",
            compatibleCategories = setOf("strength"),
            conflictKeywords = UPPER_BODY_KEYWORDS,
            conflictZones = setOf("shoulder", "chest"),
            // v43.13: quads are frontal (front of thigh); glutes read from
            // their CANONICAL view — the back (posterior).
            feel = listOf(
                MuscleFeel(MuscleRegion.QUADRICEPS, MuscleView.FRONTAL),
                MuscleFeel(MuscleRegion.GLUTES, MuscleView.POSTERIOR),
            ),
        ),
        TrainingDemo(
            id = "brisk_walk",
            kind = DemoKind.ACTIVITY,
            illustrationName = "demo_brisk_walk",
            videoUrl = "https://www.youtube.com/watch?v=2BfbiyIKnK4",
            // Full-body cardio — no regional conflict; steps tactics only.
            compatibleCategories = setOf("steps"),
            // v43.13: walking chain — quads frontal; glutes from the back
            // (posterior); calves read in profile (lateral) — the only
            // muscle shown lateral.
            feel = listOf(
                MuscleFeel(MuscleRegion.QUADRICEPS, MuscleView.FRONTAL),
                MuscleFeel(MuscleRegion.GLUTES, MuscleView.POSTERIOR),
                MuscleFeel(MuscleRegion.CALVES, MuscleView.LATERAL),
            ),
        ),
        TrainingDemo(
            id = "hip_flexor_stretch",
            kind = DemoKind.RECOVERY,
            illustrationName = "demo_hip_flexor_stretch",
            videoUrl = "https://www.youtube.com/watch?v=MqOFNpdTsPE",
            // Hip/legs recovery — pairs with sleep (recovery), strength
            // (recovery work) and steps; never with an upper-body focus.
            compatibleCategories = setOf("sleep", "strength", "steps"),
            conflictKeywords = UPPER_BODY_KEYWORDS,
            conflictZones = setOf("shoulder", "chest"),
            // v43.13: hip flexors read at the front of the hip (frontal);
            // the glute from the back (posterior).
            feel = listOf(
                MuscleFeel(MuscleRegion.HIP_FLEXORS, MuscleView.FRONTAL),
                MuscleFeel(MuscleRegion.GLUTES, MuscleView.POSTERIOR),
            ),
        ),
        // v43.9 (2026-10): the GENERIC "força" tactic finally gets a visual
        // example. A full-body bodyweight circuit (squat + push-up + plank)
        // is deliberately region-neutral — no conflict keywords, no
        // conflict zones — so it fits ANY strength tactic (generic,
        // lower- or upper-body focused) and answers "o que é força / onde
        // é o tronco" with both halves of the body highlighted.
        TrainingDemo(
            id = "full_body_circuit",
            kind = DemoKind.STRENGTH,
            illustrationName = "demo_full_body_circuit",
            videoUrl = "https://www.youtube.com/watch?v=qvhHhDNjtxM",
            compatibleCategories = setOf("strength"),
            // Full-body: no regional conflict by design.
            // v43.13: back muscles from their CANONICAL view — lats and
            // hamstrings are BACK muscles: shown on the posterior figure,
            // not the side.
            feel = listOf(
                MuscleFeel(MuscleRegion.QUADRICEPS, MuscleView.FRONTAL),
                MuscleFeel(MuscleRegion.PECTORALS, MuscleView.FRONTAL),
                MuscleFeel(MuscleRegion.LATS, MuscleView.POSTERIOR),
                MuscleFeel(MuscleRegion.HAMSTRINGS, MuscleView.POSTERIOR),
            ),
        ),
    )

    /** v43.1: detailed incompatibility reasons (empty = compatible).
     *  SINGLE SOURCE OF TRUTH shared by the safety validator (audit
     *  reasons in logcat) and the UI (fail-closed render check — persisted
     *  rows are never re-validated at generation time, so the UI must not
     *  render a demo that does not fit its tactic either). */
    fun TrainingDemo.incompatibilities(
        category: String,
        text: String,
        target: String?,
        zone: String?,
    ): List<String> {
        val out = mutableListOf<String>()
        val cat = category.lowercase().trim()
        if (cat !in compatibleCategories) {
            out += "not compatible with category \"$cat\" (allowed: ${compatibleCategories.joinToString(", ")})"
        }
        val z = zone?.lowercase()?.trim()
        if (z != null && z in conflictZones) {
            out += "conflicts with upper-body zone \"$z\""
        }
        val combined = ((text ?: "") + " " + (target ?: "")).lowercase()
        conflictKeywords.firstOrNull { combined.contains(it) }?.let { kw ->
            out += "conflicts with the tactic focus \"$kw\""
        }
        return out
    }

    /** The only demo IDs the LLM may emit; anything else is rejected. */
    val ALLOWED_DEMO_IDS: Set<String> = ALL.map { it.id }.toSet()

    /** Curated-host allowlist (the validator and the catalog agree). */
    val VIDEO_HOSTS: Set<String> =
        setOf("www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be")

    /** v43.2 (2026-10): runtime guard for the external demo link — the UI
     *  must never launch a URI it has not validated. A URL is safe only if
     *  it parses, uses HTTPS, and its HOST is exactly one of [VIDEO_HOSTS]
     *  (lookalike domains, userinfo tricks, http, other schemes — all
     *  rejected; fail closed). Pure JVM, unit-testable. */
    fun isSafeDemoUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host in VIDEO_HOSTS
    }

    fun byId(id: String?): TrainingDemo? =
        id?.let { i -> ALL.firstOrNull { it.id == i } }
}
