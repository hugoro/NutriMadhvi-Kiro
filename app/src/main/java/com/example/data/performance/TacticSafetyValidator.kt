package com.example.data.performance

import com.example.data.performance.TrainingDemoCatalog.incompatibilities

/**
 * Semantic safety validator for LLM tactics (v40, hardened v41).
 *
 * Shape validation alone is NOT safety: an LLM can emit perfectly well-formed
 * JSON that still advises reducing a muscle-dominant zone, a 40% calorie
 * deficit, or protein far above the conservative range. This validator
 * re-checks the LLM block against
 *   1. the persisted, safety-gated priorities (zone + direction + tissue),
 *   2. the anatomical tissue classification (never REDUCE a muscle-dominant
 *      zone, never GROW a fat-dominant zone, never invent direction without
 *      composition data),
 *   3. CONTRADICTORY direction language (reduce+grow in one tactic is
 *      REJECTED — it is never mapped to HOLD), and
 *   4. explicit conservative numeric bounds on EVERY numeric health
 *      prescription: protein g/kg and g/day, calorie deficit/surplus in %
 *      and absolute kcal, sleep hours, daily steps, training frequency,
 *      sets, reps, volume (sets×reps), intensity (RPE, %1RM), distances,
 *      durations. Any number that does not match a recognized, bounded
 *      pattern is an UNRECOGNIZED numeric prescription → reject.
 *
 * v42 (2026-10) granularity: violations are PER-TACTIC. The safe tactics
 * survive and are returned cleaned; each rejected tactic is reported with
 * its reasons so the caller can swap ONLY those categories for the
 * deterministic equivalent. The whole block is rejected only when NOTHING
 * survives (or the block is missing/malformed) — the caller then stores the
 * full deterministic fallback and surfaces a visible error for retry.
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
        /** v43: optional curated training-demo ID. Must be in
         *  [TrainingDemoCatalog.ALLOWED_DEMO_IDS] or the tactic is
         *  rejected — the LLM never invents exercises/URLs/loads. */
        val demoId: String? = null,
    )

    /** One rejected tactic with its reasons (v42 granularity). */
    data class RejectedTactic(
        val index: Int,
        val tactic: TacticCandidate,
        val reasons: List<String>,
    )

    /** Validation result (v42): the safe subset SURVIVES; each rejected
     *  tactic is reported individually so the caller can swap ONLY the
     *  rejected categories for the deterministic equivalent. [blockReason]
     *  is non-null when nothing can be used at all (missing/malformed/count
     *  out of range) — the caller then stores the full deterministic
     *  fallback and surfaces a visible error for retry. */
    data class Verdict(
        val safe: List<TacticCandidate>,
        val rejected: List<RejectedTactic>,
        val blockReason: String?,
    ) {
        /** Cleaned JSON of the safe subset (null when nothing survived). */
        val safeJson: String? get() = if (safe.isEmpty()) null else buildCleanedJson(safe)
        val reasons: List<String> get() = buildList {
            if (blockReason != null) add(blockReason)
            rejected.forEach { addAll(it.reasons) }
        }
        /** Nothing usable at all (missing/malformed/count). */
        val blocked: Boolean get() = blockReason != null
        /** The whole block must be replaced by the deterministic fallback. */
        val fullyRejected: Boolean get() = blockReason != null || safe.isEmpty()
        /** A usable plan exists, but some tactics were swapped out. */
        val adjusted: Boolean get() = blockReason == null && safe.isNotEmpty() && rejected.isNotEmpty()
    }

    val ALLOWED_CATEGORIES =
        setOf("protein", "strength", "steps", "sleep", "calories", "zone")

    // ── Explicit conservative bounds (auditable, never learned) ─────────────
    const val PROTEIN_G_PER_KG_MIN = 1.6f
    const val PROTEIN_G_PER_KG_MAX = 2.2f
    const val CALORIE_DEFICIT_PCT_MIN = 10f
    const val CALORIE_DEFICIT_PCT_MAX = 20f
    const val CALORIE_SURPLUS_PCT_MIN = 5f
    const val CALORIE_SURPLUS_PCT_MAX = 10f
    /** Absolute daily intake floor/ceiling (kcal) — anything below the
     *  floor is a starve-level prescription; above the ceiling is not a
     *  sensible whole-day intake for a training population. */
    const val CALORIE_ABS_INTAKE_MIN = 1200f
    const val CALORIE_ABS_INTAKE_MAX = 4000f
    /** Absolute daily deficit/surplus (kcal/day) with explicit deficit or
     *  surplus semantics — 250..600 kcal/day is the standard moderate band. */
    const val CALORIE_ABS_DELTA_MIN = 250f
    const val CALORIE_ABS_DELTA_MAX = 600f
    const val SLEEP_HOURS_MIN = 6f
    const val SLEEP_HOURS_MAX = 10f
    /** Non-sleep durations expressed in hours (walk, cardio block). */
    const val HOURS_MIN = 0.5f
    const val HOURS_MAX = 4f
    const val MINUTES_MIN = 5f
    const val MINUTES_MAX = 180f
    const val STEPS_DAY_MIN = 4000f
    const val STEPS_DAY_MAX = 15000f
    const val TRAINING_FREQ_WEEK_MIN = 1f
    const val TRAINING_FREQ_WEEK_MAX = 5f
    const val SETS_MIN = 1f
    const val SETS_MAX = 8f
    const val REPS_MIN = 2f
    const val REPS_MAX = 25f
    const val RPE_MIN = 1f
    const val RPE_MAX = 9f
    /** Intensity as % of estimated 1RM — below 40 is unproductive junk
     *  volume, above 90 is maximal/near-maximal loading, not routine. */
    const val PCT1RM_MIN = 40f
    const val PCT1RM_MAX = 90f
    const val DISTANCE_KM_MIN = 0.5f
    const val DISTANCE_KM_MAX = 30f
    const val DURATION_DAYS_MIN = 1f
    const val DURATION_DAYS_MAX = 120f
    const val DURATION_WEEKS_MIN = 1f
    const val DURATION_WEEKS_MAX = 26f
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
            return Verdict(emptyList(), emptyList(), "tactics block missing or malformed")
        }
        if (candidates.size !in TACTICS_MIN..TACTICS_MAX) {
            return Verdict(
                emptyList(),
                emptyList(),
                "tactic count ${candidates.size} outside ${TACTICS_MIN}..${TACTICS_MAX}",
            )
        }
        val prioritiesByZone = priorities.associateBy { it.zone }
        // v42 (2026-10) granularity: violations are per-tactic. Safe tactics
        // survive and are returned cleaned; each rejected tactic carries its
        // own reasons so the caller swaps only the rejected categories for
        // the deterministic equivalent (never the whole block).
        val safe = mutableListOf<TacticCandidate>()
        val rejected = mutableListOf<RejectedTactic>()
        for ((idx, t) in candidates.withIndex()) {
            val category = t.category.lowercase().trim()
            val reasons = mutableListOf<String>()
            if (category !in ALLOWED_CATEGORIES) {
                reasons += "unknown category \"$category\""
            } else {
                val text = t.text.trim()
                if (text.isEmpty() || text.length > TEXT_MAX_LEN) {
                    reasons += "text missing or over ${TEXT_MAX_LEN} chars"
                }
                if (t.target != null && t.target.trim().length > TARGET_MAX_LEN) {
                    reasons += "target over ${TARGET_MAX_LEN} chars"
                }
                if (t.windowDays !in WINDOW_DAYS_MIN..WINDOW_DAYS_MAX) {
                    reasons += "windowDays ${t.windowDays} outside ${WINDOW_DAYS_MIN}..${WINDOW_DAYS_MAX}"
                }
                // v43 (2026-10): demos come ONLY from the local curated
                // catalog — a URL smuggled into the text is an invented
                // reference → reject (the demoId field is the only door).
                val combined = (t.text + " " + (t.target ?: "")).trim()
                if (URL_IN_TEXT_REGEX.containsMatchIn(combined)) {
                    reasons += "URL in tactic text — demos use the curated demoId field only"
                }
                // v43 (2026-10): demoId allowlist (fail closed).
                if (t.demoId != null &&
                    t.demoId !in TrainingDemoCatalog.ALLOWED_DEMO_IDS
                ) {
                    reasons += "unknown demoId \"${t.demoId}\" (not in the curated catalog)"
                }
                // v43.1 (2026-10): the demo must FIT the tactic — wrong
                // category (walk demo on a strength tactic) or wrong region
                // (squat demo on an upper-body strength tactic) is
                // misleading guidance → reject. Fail closed. Reasons come
                // from the catalog's shared incompatibility predicate.
                if (t.demoId != null) {
                    TrainingDemoCatalog.byId(t.demoId)?.let { demo ->
                        demo.incompatibilities(category, t.text, t.target, t.zone)
                            .forEach { r -> reasons += "demoId \"${t.demoId}\": $r" }
                    }
                }
                when (category) {
                    "zone" -> validateZoneTactic(t, prioritiesByZone, hasCompositionData)
                        ?.let { reasons += "zone: $it" }
                    "protein" -> validateProteinPresence(combined)
                        ?.let { reasons += "protein: $it" }
                    // calories / strength / steps / sleep: all numeric
                    // prescriptions are checked by the generic scanner below.
                }
                // EVERY number in the tactic must match a recognized, bounded
                // prescription pattern; anything else is an unverified
                // numeric health claim → reject THIS tactic.
                numericPrescriptionViolations(combined, category, weightKg).forEach {
                    reasons += "numeric: $it"
                }
            }
            if (reasons.isEmpty()) safe += t
            else rejected += RejectedTactic(idx, t, reasons)
        }
        return Verdict(safe, rejected, null)
    }

    /** Serializes tactics into the cleaned, persist-ready JSON shape
     *  (quotes escaped; optional target/zone/demoId omitted when blank;
     *  demoId only for ids already validated against the curated catalog). */
    fun buildCleanedJson(candidates: List<TacticCandidate>): String {
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
            t.demoId?.takeIf { it.isNotBlank() && it in TrainingDemoCatalog.ALLOWED_DEMO_IDS }
                ?.let { sb.append(",\"demoId\":\"").append(it.trim()).append("\"") }
            sb.append("}")
        }
        return sb.append("]").toString()
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
        val detected = detectDirection(t.text + " " + (t.target ?: ""))
        // v41: contradictory reduce+grow language is a REJECTION, never a
        // silent HOLD — "reduce and grow the same zone" is nonsense the
        // deterministic engine would never emit.
        if (detected == DirDetect.CONFLICT) {
            return "contradictory reduce+grow language for zone \"$zone\""
        }
        val dir = when (detected) {
            DirDetect.REDUCE -> BodyPlanEngine.Direction.REDUCE
            DirDetect.GROW -> BodyPlanEngine.Direction.GROW
            DirDetect.CONFLICT -> null // handled above
            DirDetect.NONE -> null
        }

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

    /** Protein tactics must carry at least one verifiable grams figure
     *  (g/kg or g/day); the BOUNDS themselves are enforced by the numeric
     *  scanner with the same reason strings. */
    private fun validateProteinPresence(s: String): String? {
        val t = s.lowercase()
        if (PER_KG_CTX_REGEX.containsMatchIn(t) || GRAMS_DAY_CTX_REGEX.containsMatchIn(t)) return null
        return "protein tactic carries no verifiable grams target"
    }

    // ── Generic numeric prescription scanner (v41) ──────────────────────────
    //
    // Finds every number in the tactic text and requires each one to match a
    // recognized prescription pattern with an explicit conservative bound.
    // This is what makes "500 kcal/day", "60000 steps", "12 hours of
    // sleep", "10x/week" and "40x20 volume" all REJECT, while "15% deficit",
    // "8000 steps", "8h sleep" PASS — and it fails CLOSED on any number the
    // validator does not understand (numeric obfuscation is rejected, not
    // guessed).

    private fun numericPrescriptionViolations(
        combined: String,
        category: String,
        weightKg: Float?,
    ): List<String> {
        val out = mutableListOf<String>()
        val s = combined.lowercase()
        val deficit = DEFICIT_WORDS.any { s.contains(it) }
        val surplus = SURPLUS_WORDS.any { s.contains(it) }
        val sleepContext = category == "sleep" || SLEEP_WORDS.any { s.contains(it) }
        val oneRmContext = "1rm" in s || "1 rm" in s || "1-rm" in s
        var skipUntil = -1
        // v42 (2026-10): interval pre-pass. "7–8 horas", "7-8 steps", "2 a 3
        // sessões" are ONE prescription with two endpoints sharing the unit
        // context that follows the second endpoint. Both endpoints are
        // validated under that same band and the range is CONSUMED so the
        // per-number loop below does not re-scan the halves as (unknown)
        // single numbers. Unrecognized interval context fails closed.
        val consumed = mutableListOf<IntRange>()
        val intervalMatches = (INTERVAL_DASH.findAll(s) + INTERVAL_WORD.findAll(s))
            .sortedBy { it.range.first }
        for (m in intervalMatches) {
            if (consumed.any { m.range.first in it }) continue
            val rawA = m.groupValues[1]
            val rawB = m.groupValues[2]
            val a = parseNumber(rawA)
            val b = parseNumber(rawB)
            if (a == null || b == null) continue
            val ctxAfter = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 1 + 28))
            val reason = classifyInterval(
                a, b, rawA, rawB, ctxAfter.trimStart(),
                deficit, surplus, sleepContext, oneRmContext, weightKg,
            )
            if (reason != null) {
                out += "interval: $reason"
            } else {
                consumed += m.range
            }
        }
        for (m in NUMBER_REGEX.findAll(s)) {
            if (consumed.any { m.range.first in it }) continue
            if (m.range.first < skipUntil) continue
            val raw = m.groupValues[1]
            val value = parseNumber(raw)
            if (value == null) continue
            val ctx = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 1 + 28))
            val pre = s.substring(maxOf(0, m.range.first - 6), m.range.first)
            // The "1" of a "1RM" / "1-rm" / "1 rm" scale label is a unit
            // reference, not a numeric prescription — skip it (the % it
            // modifies is validated by the pct1RM branch).
            if (raw == "1" && Regex("^[-\\s]?rm\\b").containsMatchIn(ctx)) continue
            val reason = classifyNumber(value, raw, ctx, pre, deficit, surplus, sleepContext, oneRmContext, weightKg)
            if (reason != null) {
                out += reason
                continue
            }
            // Volume pair "N x M": the sets half was validated above; also
            // validate the rep half and consume it so it isn't re-scanned.
            val trimmed = ctx.trimStart()
            if (trimmed.startsWith("x") || trimmed.startsWith("×")) {
                val pair = SETS_REPS_NEXT.find(ctx)
                if (pair != null) {
                    val repsRaw = pair.groupValues[1]
                    val reps = parseNumber(repsRaw)
                    if (reps == null || reps !in REPS_MIN..REPS_MAX) {
                        out += "volume ${raw}x${repsRaw} — reps ${repsRaw} outside ${REPS_MIN.toInt()}..${REPS_MAX.toInt()}"
                    }
                    skipUntil = m.range.last + 1 + (pair.range.first + repsRaw.length)
                }
            }
        }
        return out
    }

    /** Decodes a matched number: repeated 3-digit groups are thousands
     *  separators ("1.500.000" / "1,500,000" → 1500000), a single
     *  separator with a non-3-digit tail is a decimal point ("1.8" →
     *  1.8 — stripping it would corrupt g/kg values), a single separator
     *  with a 3-digit tail follows the pt-BR thousands convention
     *  ("1.500" → 1500), and bare digits are plain. */
    private fun parseNumber(raw: String): Float? {
        if (raw.contains('.') && raw.contains(',')) return raw.replace(",", "").toFloatOrNull()
        val isThousands = Regex("^\\d{1,3}([.,]\\d{3})+$").matches(raw)
        return when {
            isThousands -> raw.replace(Regex("[.,]"), "").toFloatOrNull()
            Regex("^\\d+[.,]\\d+$").matches(raw) -> raw.replace(",", ".").toFloatOrNull()
            else -> raw.toFloatOrNull()
        }
    }

    /** Classifies one number by its surrounding context. Returns a reason
     *  string when the prescription is out of bounds / unbounded, null when
     *  it matches a recognized pattern inside its conservative bounds. */
    private fun classifyNumber(
        value: Float,
        raw: String,
        ctx: String,
        pre: String,
        deficit: Boolean,
        surplus: Boolean,
        sleepContext: Boolean,
        oneRmContext: Boolean,
        weightKg: Float?,
    ): String? {
        val c = ctx.trimStart()
        return when {
            // ── protein ─────────────────────────────────────────────────
            c.startsWith("g/kg") || c.startsWith("g/ kg") ->
                if (value in PROTEIN_G_PER_KG_MIN..PROTEIN_G_PER_KG_MAX) null
                else "protein $value g/kg outside ${PROTEIN_G_PER_KG_MIN}..${PROTEIN_G_PER_KG_MAX}"

            c.matches(Regex("g(?:ramas?)?\\s*(?:/|por)\\s*dia\\b")) ->
                if (weightKg == null || weightKg <= 0f) {
                    "protein $value g/day ungrounded (no body weight)"
                } else {
                    val lo = weightKg * PROTEIN_G_PER_KG_MIN
                    val hi = weightKg * PROTEIN_G_PER_KG_MAX
                    // v42 (2026-10): explicit ROUNDING tolerance only — the
                    // LLM rounds the computed grams to a clean number (132→130).
                    // max(2 g, 2% of the computed upper bound). The clinical
                    // g/kg band itself is NOT relaxed.
                    val tol = maxOf(2f, 0.02f * hi)
                    if (value in (lo - tol)..(hi + tol)) null
                    else "protein $value g/day outside conservative ${lo.toInt()}..${hi.toInt()} g/day"
                }

            // ── RPE (unit precedes the number) ──────────────────────────
            pre.trimEnd().endsWith("rpe") ->
                if (value in RPE_MIN..RPE_MAX) null
                else "RPE $value outside ${RPE_MIN.toInt()}..${RPE_MAX.toInt()}"

            // ── percentages: intensity, deficit/surplus, bare ───────────
            c.startsWith("%") -> when {
                oneRmContext ->
                    if (value in PCT1RM_MIN..PCT1RM_MAX) null
                    else "intensity $value% of 1RM outside ${PCT1RM_MIN.toInt()}..${PCT1RM_MAX.toInt()}"
                deficit && surplus -> "ambiguous energy advice (deficit and surplus together)"
                deficit ->
                    if (value in CALORIE_DEFICIT_PCT_MIN..CALORIE_DEFICIT_PCT_MAX) null
                    else "deficit $value% outside ${CALORIE_DEFICIT_PCT_MIN}..${CALORIE_DEFICIT_PCT_MAX}%"
                surplus ->
                    if (value in CALORIE_SURPLUS_PCT_MIN..CALORIE_SURPLUS_PCT_MAX) null
                    else "surplus $value% outside ${CALORIE_SURPLUS_PCT_MIN}..${CALORIE_SURPLUS_PCT_MAX}%"
                else -> "$value% with no deficit/surplus context"
            }

            // ── absolute calories ───────────────────────────────────────
            c.startsWith("kcal") || c.startsWith("calorias") || c.startsWith("caloria") -> {
                val asDelta = (deficit || surplus) && value in CALORIE_ABS_DELTA_MIN..CALORIE_ABS_DELTA_MAX
                val asIntake = value in CALORIE_ABS_INTAKE_MIN..CALORIE_ABS_INTAKE_MAX
                when {
                    asDelta -> null // moderate absolute deficit/surplus
                    asIntake -> null // plausible whole-day intake
                    deficit || surplus ->
                        "absolute ${if (deficit) "deficit" else "surplus"} $value kcal/day outside " +
                            "${CALORIE_ABS_DELTA_MIN.toInt()}..${CALORIE_ABS_DELTA_MAX.toInt()} kcal/day"
                    else -> "absolute $value kcal/day without bounded deficit/surplus or intake semantics " +
                        "(outside ${CALORIE_ABS_INTAKE_MIN.toInt()}..${CALORIE_ABS_INTAKE_MAX.toInt()} kcal/day)"
                }
            }

            // ── sleep / durations in hours ──────────────────────────────
            // "h"/"hora"/"horas" as a standalone token (word boundary), not
            // a full-string match — "12 horas por noite" must be classified.
            c.startsWith("h") && c.matches(Regex("h(?:oras?)?(?:\\s.*)?")) -> {
                val (lo, hi, label) = if (sleepContext)
                    Triple(SLEEP_HOURS_MIN, SLEEP_HOURS_MAX, "sleep")
                else Triple(HOURS_MIN, HOURS_MAX, "duration")
                if (value in lo..hi) null
                else "$label $value h outside ${lo}..${hi}"
            }

            c.startsWith("min") ->
                if (value in MINUTES_MIN..MINUTES_MAX) null
                else "duration $value min outside ${MINUTES_MIN.toInt()}..${MINUTES_MAX.toInt()}"

            // ── steps ───────────────────────────────────────────────────
            c.startsWith("passos") || c.startsWith("steps") || c.startsWith("passo ") ->
                if (value in STEPS_DAY_MIN..STEPS_DAY_MAX) null
                else "steps $value outside ${STEPS_DAY_MIN.toInt()}..${STEPS_DAY_MAX.toInt()}"

            // ── training frequency / volume ─────────────────────────────
            c.startsWith("vezes") || c.startsWith("times") ||
                c.startsWith("sess\u00e3o") || c.startsWith("sess\u00f5es") ||
                c.startsWith("session") || c.startsWith("sessions") ->
                if (value in TRAINING_FREQ_WEEK_MIN..TRAINING_FREQ_WEEK_MAX) null
                else "training frequency $value times/week outside " +
                    "${TRAINING_FREQ_WEEK_MIN.toInt()}..${TRAINING_FREQ_WEEK_MAX.toInt()}"

            c.startsWith("séries") || c.startsWith("series") || c.startsWith("sets") ->
                if (value in SETS_MIN..SETS_MAX) null
                else "sets $value outside ${SETS_MIN.toInt()}..${SETS_MAX.toInt()}"

            c.startsWith("repetições") || c.startsWith("reps") ->
                if (value in REPS_MIN..REPS_MAX) null
                else "reps $value outside ${REPS_MIN.toInt()}..${REPS_MAX.toInt()}"

            c.startsWith("x") || c.startsWith("×") ->
                // sets×reps pair — the sets half is validated here, the rep
                // half by the caller.
                if (value in SETS_MIN..SETS_MAX) null
                else "volume sets $value outside ${SETS_MIN.toInt()}..${SETS_MAX.toInt()}"

            // ── distance ────────────────────────────────────────────────
            c.startsWith("km") ->
                if (value in DISTANCE_KM_MIN..DISTANCE_KM_MAX) null
                else "distance $value km outside ${DISTANCE_KM_MIN}..${DISTANCE_KM_MAX}"

            // ── durations ───────────────────────────────────────────────
            c.startsWith("dias") || c.startsWith("days") ->
                if (value in DURATION_DAYS_MIN..DURATION_DAYS_MAX) null
                else "duration $value days outside ${DURATION_DAYS_MIN.toInt()}..${DURATION_DAYS_MAX.toInt()}"

            c.startsWith("semanas") || c.startsWith("weeks") ->
                if (value in DURATION_WEEKS_MIN..DURATION_WEEKS_MAX) null
                else "duration $value weeks outside ${DURATION_WEEKS_MIN.toInt()}..${DURATION_WEEKS_MAX.toInt()}"

            // ── fail closed ─────────────────────────────────────────────
            else -> "unrecognized numeric prescription \"$raw${c.take(12)}\""
        }
    }

    /** Validates an interval "A sep B" (sep = -/–/—/~ or a/à/até/to).
     *  Both endpoints must fall inside the SAME band derived from the unit
     *  context after the second endpoint. Percent/kcal/RPE intervals are not
     *  recognized prescriptions → fail closed. */
    private fun classifyInterval(
        a: Float,
        b: Float,
        rawA: String,
        rawB: String,
        c: String,
        deficit: Boolean,
        surplus: Boolean,
        sleepContext: Boolean,
        oneRmContext: Boolean,
        weightKg: Float?,
    ): String? {
        return when {
            c.startsWith("g/kg") || c.startsWith("g/ kg") ->
                checkIntervalBand(a, b, rawA, rawB, PROTEIN_G_PER_KG_MIN, PROTEIN_G_PER_KG_MAX, "protein g/kg")
            c.matches(Regex("g(?:ramas?)?\\s*(?:/|por)\\s*dia\\b")) -> {
                if (weightKg == null || weightKg <= 0f) {
                    "protein interval $rawA-$rawB g/day ungrounded (no body weight)"
                } else {
                    val lo = weightKg * PROTEIN_G_PER_KG_MIN
                    val hi = weightKg * PROTEIN_G_PER_KG_MAX
                    val tol = maxOf(2f, 0.02f * hi) // rounding tolerance only
                    checkIntervalBand(a, b, rawA, rawB, lo - tol, hi + tol, "protein g/day")
                }
            }
            c.startsWith("h") && c.matches(Regex("h(?:oras?)?(?:\\s.*)?")) -> {
                val (lo, hi, label) = if (sleepContext)
                    Triple(SLEEP_HOURS_MIN, SLEEP_HOURS_MAX, "sleep h")
                else Triple(HOURS_MIN, HOURS_MAX, "duration h")
                checkIntervalBand(a, b, rawA, rawB, lo, hi, label)
            }
            c.startsWith("min") ->
                checkIntervalBand(a, b, rawA, rawB, MINUTES_MIN, MINUTES_MAX, "duration min")
            c.startsWith("passos") || c.startsWith("steps") || c.startsWith("passo ") ->
                checkIntervalBand(a, b, rawA, rawB, STEPS_DAY_MIN, STEPS_DAY_MAX, "steps")
            c.startsWith("vezes") || c.startsWith("times") ||
            c.startsWith("sess\u00e3o") || c.startsWith("sess\u00f5es") ||
            c.startsWith("session") || c.startsWith("sessions") ->
                checkIntervalBand(a, b, rawA, rawB, TRAINING_FREQ_WEEK_MIN, TRAINING_FREQ_WEEK_MAX, "training frequency/week")
            c.startsWith("s\u00e9ries") || c.startsWith("series") || c.startsWith("sets") ->
                checkIntervalBand(a, b, rawA, rawB, SETS_MIN, SETS_MAX, "sets")
            c.startsWith("repeti\u00e7\u00f5es") || c.startsWith("reps") ->
                checkIntervalBand(a, b, rawA, rawB, REPS_MIN, REPS_MAX, "reps")
            c.startsWith("km") ->
                checkIntervalBand(a, b, rawA, rawB, DISTANCE_KM_MIN, DISTANCE_KM_MAX, "distance km")
            c.startsWith("dias") || c.startsWith("days") ->
                checkIntervalBand(a, b, rawA, rawB, DURATION_DAYS_MIN, DURATION_DAYS_MAX, "days")
            c.startsWith("semanas") || c.startsWith("weeks") ->
                checkIntervalBand(a, b, rawA, rawB, DURATION_WEEKS_MIN, DURATION_WEEKS_MAX, "weeks")
            else -> "unrecognized numeric interval \"$rawA-$rawB${c.take(12)}\""
        }
    }

    private fun checkIntervalBand(
        a: Float,
        b: Float,
        rawA: String,
        rawB: String,
        lo: Float,
        hi: Float,
        label: String,
    ): String? =
        if (a in lo..hi && b in lo..hi) null
        else "$label interval $rawA-$rawB outside ${lo}..${hi}"

    /** Detects the direction a zone tactic expresses, from keywords. */
    private enum class DirDetect { REDUCE, GROW, CONFLICT, NONE }

    private fun detectDirection(s: String): DirDetect {
        val t = s.lowercase()
        val reduce = REDUCE_WORDS.any { t.contains(it) }
        val grow = GROW_WORDS.any { t.contains(it) }
        return when {
            reduce && grow -> DirDetect.CONFLICT // v41: contradiction, not HOLD
            reduce -> DirDetect.REDUCE
            grow -> DirDetect.GROW
            else -> DirDetect.NONE
        }
    }

    // Number: thousands-separated (1.500 / 1,500 / 1.234.567) first, then a
    // plain decimal (1.8 / 8000 / 40). Obfuscated forms ("5oo", "1 5 0 0")
    // match as smaller numbers that then FAIL their bounded checks or land
    // in the unrecognized bucket — the scanner fails closed, never open.
    private val NUMBER_REGEX = Regex("(\\d{1,3}(?:[.,]\\d{3})+|\\d+(?:[.,]\\d+)?)")
    // v43 (2026-10): any URL smuggled into the tactic text is an invented
    // reference — demos exist only via the curated demoId allowlist.
    private val URL_IN_TEXT_REGEX =
        Regex("https?://|www\\.|youtu\\.be/", RegexOption.IGNORE_CASE)
    // v42 (2026-10): ranges — hyphen/en-dash/em-dash/tilde and the PT/EN
    // word forms. Both halves share the unit context after the second half.
    private val INTERVAL_DASH =
        Regex("(\\d+(?:[.,]\\d+)?)\\s*[-\\u2013\\u2014~]\\s*(\\d+(?:[.,]\\d+)?)")
    private val INTERVAL_WORD =
        Regex("(\\d+(?:[.,]\\d+)?)\\s+(?:a|\u00e0|at\u00e9|to)\\s+(\\d+(?:[.,]\\d+)?)")
    private val SETS_REPS_NEXT = Regex("^\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)")
    private val PER_KG_CTX_REGEX = Regex("g\\s*/\\s*kg")
    private val GRAMS_DAY_CTX_REGEX = Regex("g(?:ramas?)?\\s*(?:/|por)\\s*dia")
    private val REDUCE_WORDS = listOf(
        "reduz", "reduzir", "reducao", "redução", "reduce", "reduces", "diminuir",
        "défice", "deficit", "déficit",
    )
    private val GROW_WORDS = listOf(
        "aumentar", "aumenta", "aumento", "increase", "increases", "grow",
        "crescer", "progressiv", "hipertrofi", "ganho de massa",
    )
    private val DEFICIT_WORDS = listOf("défice", "deficit", "déficit", "cut ")
    private val SURPLUS_WORDS = listOf("superávit", "surplus", "bulk")
    private val SLEEP_WORDS = listOf(
        "sono", "dormir", "dorme", "durma", "sleep",
    )

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ")
}
