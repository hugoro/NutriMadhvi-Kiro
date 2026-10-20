package com.example.data.performance

import kotlin.math.abs

/**
 * Semantic safety validator for LLM tactics (v40).
 *
 * Shape validation alone is NOT safety: an LLM can emit perfectly well-formed
 * JSON that still advises reducing a muscle-dominant zone, a 40% calorie
 * deficit, or protein far above the conservative range. This validator
 * re-checks the LLM block against
 *   1. the persisted, safety-gated priorities (zone + direction + tissue),
 *   2. the anatomical tissue classification (never REDUCE a muscle-dominant
 *      zone, never GROW a fat-dominant zone, never invent direction without
 *      composition data), and
 *   3. explicit conservative numeric bounds (protein, energy balance).
 *
 * ANY violation rejects the WHOLE tactics block — the caller stores the
 * deterministic fallback list and surfaces a visible error for retry.
 *
 * Pure JVM: no Android types, no I/O, no org.json (the caller parses the
 * JSON into [TacticCandidate]s) — unit-testable.
 */
object TacticSafetyValidator {

    /** One LLM tactic after JSON parsing, before semantic validation. */
    data class TacticCandidate(
        val category: String,
        val text: String,
        val target: String?,
        val windowDays: Int,
        /** Zone key for category=="zone" tactics (e.g. "waist"). */
        val zone: String?,
    )

    /** Validation result: [safeJson] is the cleaned JSON ready to persist
     *  (null when the block must be rejected), [reasons] explains why. */
    data class Verdict(val safeJson: String?, val reasons: List<String>)

    val ALLOWED_CATEGORIES =
        setOf("protein", "strength", "steps", "sleep", "calories", "zone")

    // ── Explicit conservative bounds (auditable, never learned) ─────────────
    const val PROTEIN_G_PER_KG_MIN = 1.6f
    const val PROTEIN_G_PER_KG_MAX = 2.2f
    const val CALORIE_DEFICIT_PCT_MIN = 10f
    const val CALORIE_DEFICIT_PCT_MAX = 20f
    const val CALORIE_SURPLUS_PCT_MIN = 5f
    const val CALORIE_SURPLUS_PCT_MAX = 10f
    const val WINDOW_DAYS_MIN = 7
    const val WINDOW_DAYS_MAX = 120
    const val TEXT_MAX_LEN = 200
    const val TARGET_MAX_LEN = 80
    const val TACTICS_MIN = 1
    const val TACTICS_MAX = 6

    /**
     * Validates a parsed LLM tactics block.
     *
     * @param candidates parsed tactics (null = block missing/malformed)
     * @param priorities the persisted safety-gated priorities of the
     *        checkpoint (the plan the tactics must agree with)
     * @param weightKg latest anchored body weight (grounds g/day protein)
     * @param hasCompositionData whether the checkpoint has scale data
     *        (grounds tissue classification for zones outside the top-3)
     */
    fun validate(
        candidates: List<TacticCandidate>?,
        priorities: List<BodyPlanEngine.ZonePriority>,
        weightKg: Float?,
        hasCompositionData: Boolean,
    ): Verdict {
        if (candidates == null) {
            return Verdict(null, listOf("tactics block missing or malformed"))
        }
        if (candidates.size !in TACTICS_MIN..TACTICS_MAX) {
            return Verdict(
                null,
                listOf("tactic count ${candidates.size} outside ${TACTICS_MIN}..${TACTICS_MAX}"),
            )
        }
        val prioritiesByZone = priorities.associateBy { it.zone }
        val reasons = mutableListOf<String>()
        for ((idx, t) in candidates.withIndex()) {
            val category = t.category.lowercase().trim()
            if (category !in ALLOWED_CATEGORIES) {
                reasons += "tactic#$idx unknown category \"$category\""
                continue
            }
            val text = t.text.trim()
            if (text.isEmpty() || text.length > TEXT_MAX_LEN) {
                reasons += "tactic#$idx text missing or over ${TEXT_MAX_LEN} chars"
                continue
            }
            if (t.target != null && t.target.trim().length > TARGET_MAX_LEN) {
                reasons += "tactic#$idx target over ${TARGET_MAX_LEN} chars"
                continue
            }
            if (t.windowDays !in WINDOW_DAYS_MIN..WINDOW_DAYS_MAX) {
                reasons += "tactic#$idx windowDays ${t.windowDays} outside ${WINDOW_DAYS_MIN}..${WINDOW_DAYS_MAX}"
                continue
            }
            when (category) {
                "zone" -> validateZoneTactic(t, prioritiesByZone, hasCompositionData)
                    ?.let { reasons += "tactic#$idx zone: $it" }
                "protein" -> validateProtein(t, weightKg)
                    ?.let { reasons += "tactic#$idx protein: $it" }
                "calories" -> validateCalories(t)
                    ?.let { reasons += "tactic#$idx calories: $it" }
                // strength / steps / sleep: qualitative guidance — no numeric
                // safety surface beyond the bounds above.
            }
        }
        if (reasons.isNotEmpty()) return Verdict(null, reasons)

        // Cleaned JSON, built purely (quotes escaped) — ready to persist.
        val sb = StringBuilder("[")
        candidates.forEachIndexed { i, t ->
            if (i > 0) sb.append(",")
            sb.append("{\"category\":\"").append(t.category.lowercase().trim())
            sb.append("\",\"text\":\"").append(escape(t.text.trim()))
            t.target?.takeIf { it.isNotBlank() }?.let {
                sb.append("\",\"target\":\"").append(escape(it.trim().take(TARGET_MAX_LEN)))
            }
            sb.append("\",\"windowDays\":").append(t.windowDays)
            t.zone?.takeIf { it.isNotBlank() }?.let {
                sb.append(",\"zone\":\"").append(it.trim()).append("\"")
            }
            sb.append("}")
        }
        return Verdict(sb.append("]").toString(), emptyList())
    }

    // ── Per-category checks (return a reason string, or null = safe) ────────

    private fun validateZoneTactic(
        t: TacticCandidate,
        prioritiesByZone: Map<String, BodyPlanEngine.ZonePriority>,
        hasCompositionData: Boolean,
    ): String? {
        val zone = t.zone?.lowercase()?.trim()
        if (zone == null) return "zone tactic missing the zone key"
        if (zone !in BodyPlanEngine.ZONES) return "unknown zone \"$zone\""
        val prio = prioritiesByZone[zone]
        val tissue = prio?.tissue
            ?: BodyPlanEngine.classifyTissue(zone, hasCompositionData)
        val dir = detectDirection(t.text + " " + (t.target ?: ""))

        // Hard rule: never REDUCE a muscle-dominant zone, never GROW a
        // fat-dominant one — independent of what the plan says.
        if (dir == BodyPlanEngine.Direction.REDUCE && tissue == BodyPlanEngine.Tissue.MUSCLE_DOMINANT) {
            return "REDUCE advised for muscle-dominant zone \"$zone\""
        }
        if (dir == BodyPlanEngine.Direction.GROW && tissue == BodyPlanEngine.Tissue.FAT_DOMINANT) {
            return "GROW advised for fat-dominant zone \"$zone\""
        }
        // No composition data → tissue unknown → no zone direction can be
        // grounded → reject directional advice entirely.
        if (tissue == BodyPlanEngine.Tissue.UNKNOWN) {
            return if (dir == null) "zone \"$zone\" not grounded (no composition data)"
            else "directional advice for zone \"$zone\" without composition data"
        }
        // Must not contradict the persisted safety-gated priority.
        if (prio != null) {
            if (dir == null && prio.direction != BodyPlanEngine.Direction.HOLD) {
                return "zone tactic for \"$zone\" does not express the persisted ${prio.direction} direction"
            }
            if (dir != null && dir != prio.direction) {
                return "direction $dir conflicts with persisted priority ${prio.direction} for \"$zone\""
            }
        } else if (dir == null) {
            // Zone outside the top-3 with no detectable direction: nothing
            // actionable to verify → reject (the deterministic fallback
            // carries the grounded zone tactics).
            return "zone tactic for \"$zone\" carries no verifiable direction"
        }
        return null
    }

    private fun validateProtein(t: TacticCandidate, weightKg: Float?): String? {
        val s = (t.text + " " + (t.target ?: "")).lowercase()
        // Prefer an explicit g/kg figure.
        val gPerKg = PER_KG_REGEX.find(s)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
        if (gPerKg != null) {
            return if (gPerKg in PROTEIN_G_PER_KG_MIN..PROTEIN_G_PER_KG_MAX) null
            else "protein ${gPerKg} g/kg outside ${PROTEIN_G_PER_KG_MIN}..${PROTEIN_G_PER_KG_MAX}"
        }
        // Otherwise a g/day figure — verifiable only against the anchored
        // body weight; without one it is ungrounded → reject (conservative).
        val gPerDay = GRAMS_DAY_REGEX.find(s)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
        if (gPerDay != null) {
            if (weightKg == null || weightKg <= 0f) {
                return "protein ${gPerDay} g/day ungrounded (no body weight)"
            }
            val lo = weightKg * PROTEIN_G_PER_KG_MIN
            val hi = weightKg * PROTEIN_G_PER_KG_MAX
            return if (gPerDay in lo..hi) null
            else "protein ${gPerDay} g/day outside conservative ${lo.toInt()}..${hi.toInt()} g/day"
        }
        return "protein tactic carries no verifiable grams target"
    }

    private fun validateCalories(t: TacticCandidate): String? {
        val s = (t.text + " " + (t.target ?: "")).lowercase()
        val pct = PCT_REGEX.find(s)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
        if (pct == null) return null // "em manutenção" style — no number to violate
        val isDeficit = DEFICIT_WORDS.any { s.contains(it) }
        val isSurplus = SURPLUS_WORDS.any { s.contains(it) }
        return when {
            isDeficit && isSurplus -> "ambiguous energy advice (deficit and surplus together)"
            isDeficit ->
                if (pct in CALORIE_DEFICIT_PCT_MIN..CALORIE_DEFICIT_PCT_MAX) null
                else "deficit ${pct}% outside ${CALORIE_DEFICIT_PCT_MIN}..${CALORIE_DEFICIT_PCT_MAX}%"
            isSurplus ->
                if (pct in CALORIE_SURPLUS_PCT_MIN..CALORIE_SURPLUS_PCT_MAX) null
                else "surplus ${pct}% outside ${CALORIE_SURPLUS_PCT_MIN}..${CALORIE_SURPLUS_PCT_MAX}%"
            else -> "energy advice with ${pct}% but no deficit/surplus context"
        }
    }

    /** Detects the direction a zone tactic expresses, from keywords.
     *  Returns null when no direction is detectable. */
    private fun detectDirection(s: String): BodyPlanEngine.Direction? {
        val t = s.lowercase()
        val reduce = REDUCE_WORDS.any { t.contains(it) }
        val grow = GROW_WORDS.any { t.contains(it) }
        return when {
            reduce && grow -> BodyPlanEngine.Direction.HOLD // ambiguous — treated as no direction
            reduce -> BodyPlanEngine.Direction.REDUCE
            grow -> BodyPlanEngine.Direction.GROW
            else -> null
        }
    }

    private val PER_KG_REGEX = Regex("(\\d+(?:[.,]\\d+)?)\\s*g\\s*/\\s*kg")
    private val GRAMS_DAY_REGEX = Regex("(\\d+(?:[.,]\\d+)?)\\s*g(?:rama(?:s)?)?\\s*(?:/|por)\\s*dia")
    private val PCT_REGEX = Regex("(\\d+(?:[.,]\\d+)?)\\s*%")
    private val REDUCE_WORDS = listOf(
        "reduz", "reduzir", "reducao", "redução", "reduce", "diminuir",
        "défice", "deficit", "déficit",
    )
    private val GROW_WORDS = listOf(
        "aumentar", "aumento", "increase", "grow", "crescer", "progressiv",
        "hipertrofi", "ganho de massa",
    )
    private val DEFICIT_WORDS = listOf("défice", "deficit", "déficit", "cut ")
    private val SURPLUS_WORDS = listOf("superávit", "surplus", "bulk")

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ")
}
