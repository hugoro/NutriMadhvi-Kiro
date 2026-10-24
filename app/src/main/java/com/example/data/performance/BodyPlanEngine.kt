package com.example.data.performance

import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.PerformanceSnapshotEntity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic body-plan engine — the local, LLM-free core of the
 * Performance tab's plan system (v39).
 *
 * Design principles:
 *  - **Pure JVM**: no Android types, no I/O, no coroutines. Unit-testable,
 *    runs identically offline, and the Plan view must work with zero LLM
 *    involvement (LLM only enriches: tactics text + checkpoint report).
 *  - **Safety gate (mirrors the existing PerformanceCoachBuilder rule)**:
 *    a zone can only be directed **REDUCE** when it is fat-dominant
 *    (waist/belly). Muscle zones (shoulder/chest/hip/thigh) can only
 *    GROW or HOLD. This is enforced here, not in the UI or the prompt.
 *  - **Conservative without data**: with no body-composition (scale) data
 *    the tissue of a zone is UNKNOWN and every direction is HOLD — the
 *    app never invents a "reduce X" recommendation it cannot ground.
 *
 * Medical context: silhouette zone ratios are group-correlation estimates
 * (Farina et al. 2022, R²≈0.91–0.95 vs DEXA body-fat distribution). The
 * outputs are planning guidance, not a diagnosis — the UI must keep the
 * "not a medical device" disclaimer.
 */
object BodyPlanEngine {

    enum class BodyPhase { BULK, CUT, RECOMP, MAINTAIN }
    enum class Tissue { FAT_DOMINANT, MUSCLE_DOMINANT, UNKNOWN }
    enum class Direction { REDUCE, GROW, HOLD }

    /** One priority zone with its planned direction. */
    data class ZonePriority(
        val zone: String,
        val direction: Direction,
        /** (current − target) × 100, in percentage points. > 0 = above target. */
        val gapPp: Float,
        val tissue: Tissue,
    )

    /** Deterministic tactic template; text is resolved through I18n keys
     *  at persistence time so the engine stays locale-free. */
    data class FallbackTactic(
        val category: String,
        val i18nKey: String,
        val i18nArgs: Map<String, String> = emptyMap(),
        val windowDays: Int = 28,
        /** v43.6 (2026-10): deterministic visual example for this tactic.
         *  Set only when a catalog demo is genuinely compatible (checked
         *  through the SAME fail-closed
         *  [TrainingDemoCatalog.incompatibilities] gate as the validator);
         *  `null` = no card, nothing invented. */
        val demoId: String? = null,
    )

    /** Numeric deltas between two checkpoints (UI localises the labels). */
    data class Deltas(
        val zoneDeltasPp: Map<String, Float> = emptyMap(),
        val weightDeltaKg: Float? = null,
        val fatDeltaPp: Float? = null,
        val muscleDeltaKg: Float? = null,
    )

    // ── Zone keys (single source of truth for the 7 silhouette zones) ──────

    const val SHOULDER = "shoulder"
    const val WAIST = "waist"
    const val HIP = "hip"
    const val THIGH = "thigh"
    const val CHEST = "chest"
    const val BELLY = "belly"
    const val LATERAL_THIGH = "lateralThigh"

    /** The 7 measurable zones — validation boundary for any zone key. */
    val ZONES = setOf(SHOULDER, WAIST, HIP, THIGH, CHEST, BELLY, LATERAL_THIGH)

    // ── Normalization conventions (v41) ─────────────────────────────────────
    //
    // A silhouette ratio is only comparable to its target (or its previous
    // value) when BOTH sides use the same normalization convention. v40
    // stored the convention per capture (frontalNormalization /
    // lateralNormalization markers; NULL = the legacy image-height
    // convention). v41 additionally stores, with each goal's targets, the
    // convention they were derived under (per side) — and the engine only
    // emits gaps/deltas when the two sides agree. Unknown (NULL) target
    // convention = UNKNOWN by definition = never compared (fail-closed).

    const val CONV_IMAGE_HEIGHT = "image_height"
    const val CONV_POSE_PERSON_HEIGHT = "pose_person_height"

    /** Zones measured on the frontal photo. */
    val FRONTAL_ZONES = setOf(SHOULDER, WAIST, HIP, THIGH)

    /** Zones measured on the lateral photo. */
    val LATERAL_ZONES = setOf(CHEST, BELLY, LATERAL_THIGH)

    /** Effective convention of a persisted snapshot marker. NULL (pre-v39
     *  rows) is the legacy image-height convention — those rows were only
     *  ever produced by the fixed-fraction plan. */
    fun snapshotConvention(marker: String?): String =
        marker?.takeIf { it.isNotBlank() } ?: CONV_IMAGE_HEIGHT

    /** True when [zone]'s target (if any) was derived under the SAME
     *  convention as [snapshot]'s measurement of that zone. Target
     *  convention NULL = unknown → not comparable (fail-closed). */
    fun zoneTargetCompatible(
        zone: String,
        snapshot: PerformanceSnapshotEntity,
        targetsFrontalConv: String?,
        targetsLateralConv: String?,
    ): Boolean {
        val frontal = zone in FRONTAL_ZONES
        val snapConv = if (frontal) snapshotConvention(snapshot.frontalNormalization)
        else snapshotConvention(snapshot.lateralNormalization)
        val targetConv = if (frontal) targetsFrontalConv else targetsLateralConv
        return !targetConv.isNullOrBlank() && targetConv == snapConv
    }

    /** True when EVERY zone that has a target in [targets] is convention-
     *  compatible with [snapshot] — i.e. the whole target set is safe to
     *  use. Any incompatibility (or unknown target convention) means the
     *  targets must be re-derived before priorities may cite them. */
    fun allTargetsCompatible(
        snapshot: PerformanceSnapshotEntity,
        targets: Map<String, Float>,
        targetsFrontalConv: String?,
        targetsLateralConv: String?,
    ): Boolean {
        if (targets.isEmpty()) return true
        if (targetsFrontalConv.isNullOrBlank() || targetsLateralConv.isNullOrBlank()) return false
        return targets.keys.all { zoneTargetCompatible(it, snapshot, targetsFrontalConv, targetsLateralConv) }
    }

    // ── Tunables ────────────────────────────────────────────────────────────

    /** |Δfat| (pp) over the window that counts as a real fat trend. */
    const val FAT_TREND_PP = 0.5f
    /** |Δmuscle| (kg) over the window that counts as a real muscle trend. */
    const val MUSCLE_TREND_KG = 0.3f
    /** Priority noise floor: gaps smaller than this are framing noise. */
    const val MIN_GAP_PP = 0.25f
    /** Fallback protein target (g per kg bodyweight) — standard
     *  muscle-maintenance/recomposition range. */
    const val PROTEIN_G_PER_KG = 1.8f

    // ── Phase ──────────────────────────────────────────────────────────────

    /**
     * Deterministic phase classification from the composition trend
     * (oldest→newest reading) with the stated goal as tie-breaker.
     *
     *  - Δfat > +0.5pp and Δmuscle < 0        → CUT   (fat creeping, muscle leaking)
     *  - Δmuscle > +0.3kg and Δfat > +0.5pp   → RECOMP (mass up, fat up too)
     *  - Δmuscle > +0.3kg and Δfat ≤ +0.5pp   → BULK  (clean mass gain)
     *  - otherwise: goal keywords; if none, MAINTAIN (when data exists)
     *
     * Returns null when there is no usable composition history (weight-only
     * rows carry no fat/muscle signal; the caller then shows "no phase").
     */
    fun classifyPhase(
        history: List<BodyCompositionSnapshotEntity>,
        goalText: String?,
    ): BodyPhase? {
        val fat = history.mapNotNull { it.bodyFatPct }
        val muscle = history.mapNotNull { it.muscleMassKg }
        if (fat.size < 2 && muscle.size < 2) return null

        val deltaFatPp = if (fat.size >= 2) fat.last() - fat.first() else 0f
        val deltaMuscleKg = if (muscle.size >= 2) muscle.last() - muscle.first() else 0f

        val trend = when {
            deltaFatPp > FAT_TREND_PP && deltaMuscleKg < 0f -> BodyPhase.CUT
            deltaMuscleKg > MUSCLE_TREND_KG && deltaFatPp > FAT_TREND_PP -> BodyPhase.RECOMP
            deltaMuscleKg > MUSCLE_TREND_KG -> BodyPhase.BULK
            else -> null
        }
        if (trend != null) return trend
        return goalIntent(goalText) ?: BodyPhase.MAINTAIN
    }

    /** Explicit goal intent — used as tie-breaker when the trend is flat. */
    fun goalIntent(goalText: String?): BodyPhase? {
        if (goalText.isNullOrBlank()) return null
        val t = goalText.lowercase()
        if (t.contains("recomp")) return BodyPhase.RECOMP
        val cutPhrases = listOf("perder peso", "emagrecer", "secar", "cortar massa", "lose weight", "fat loss", "cut fat")
        if (cutPhrases.any { t.contains(it) }) return BodyPhase.CUT
        val bulkPhrases = listOf("ganhar massa", "hipertrofia", "bulking", "bulk up", "gain mass", "ganhar músculo")
        if (bulkPhrases.any { t.contains(it) }) return BodyPhase.BULK
        return null
    }

    // ── Tissue + priorities ─────────────────────────────────────────────────

    /**
     * Anatomical tissue class of a silhouette zone. Without composition
     * data the app refuses to ground a direction → UNKNOWN (all HOLD).
     */
    fun classifyTissue(zone: String, hasCompositionData: Boolean): Tissue {
        if (!hasCompositionData) return Tissue.UNKNOWN
        return when (zone) {
            WAIST, BELLY -> Tissue.FAT_DOMINANT
            SHOULDER, CHEST, HIP, THIGH, LATERAL_THIGH -> Tissue.MUSCLE_DOMINANT
            else -> Tissue.UNKNOWN
        }
    }

    /**
     * Top-[max] priority zones by |gap to coach target|, safety-gated:
     * REDUCE is only ever assigned to fat-dominant zones above target;
     * GROW only to muscle-dominant zones below target; everything else
     * is HOLD (including every zone when there is no composition data).
     * Zones without a target or without a measured ratio are skipped.
     * v41: a ratio and its target are only the same unit when both sides
     * use the same normalization convention — incompatible or
     * unknown-convention targets are OMITTED (no misleading gap); the
     * caller re-derives compatible targets and repairs the checkpoint.
     */
    fun rankPriorities(
        snapshot: PerformanceSnapshotEntity,
        targets: Map<String, Float>,
        hasCompositionData: Boolean,
        targetsFrontalConv: String? = null,
        targetsLateralConv: String? = null,
        max: Int = 3,
    ): List<ZonePriority> {
        val out = mutableListOf<ZonePriority>()
        for ((zone, ratio) in zonesOf(snapshot)) {
            val target = targets[zone] ?: continue
            if (ratio == null || target <= 0f) continue
            // v41: a ratio and its target are only the same unit when both
            // sides use the same normalization convention. Incompatible or
            // unknown-convention targets are OMITTED (no misleading gap) —
            // the caller re-derives compatible targets and repairs the
            // checkpoint.
            if (!zoneTargetCompatible(zone, snapshot, targetsFrontalConv, targetsLateralConv)) continue
            val gapPp = (ratio - target) * 100f
            if (abs(gapPp) < MIN_GAP_PP) continue
            val tissue = classifyTissue(zone, hasCompositionData)
            val direction = when {
                gapPp > 0f && tissue == Tissue.FAT_DOMINANT -> Direction.REDUCE
                gapPp < 0f && tissue == Tissue.MUSCLE_DOMINANT -> Direction.GROW
                else -> Direction.HOLD
            }
            out += ZonePriority(zone, direction, gapPp, tissue)
        }
        return out.sortedByDescending { abs(it.gapPp) }.take(max)
    }

    /** The 7 measurable zones of a snapshot, keyed. */
    fun zonesOf(snapshot: PerformanceSnapshotEntity): Map<String, Float?> = mapOf(
        SHOULDER to snapshot.frontalShoulderRatio,
        WAIST    to snapshot.frontalWaistRatio,
        HIP      to snapshot.frontalHipRatio,
        THIGH    to snapshot.frontalThighRatio,
        CHEST    to snapshot.lateralChestRatio,
        BELLY    to snapshot.lateralBellyRatio,
        LATERAL_THIGH to snapshot.lateralThighRatio,
    )

    /**
     * Strict pure-JVM parser for the persisted priorities JSON
     * ([prioritiesToJson]'s inverse) — re-reads the deterministic core on
     * retry so re-enrichment is checkpoint-anchored (v40). Hand-rolled (not
     * org.json) so the engine stays pure-JVM testable. Fail-soft: entries
     * with an unknown zone, malformed direction/tissue, or a non-finite /
     * implausible gapPp are DROPPED, never thrown. gapPp is clamped to
     * ±30pp at this boundary.
     */
    fun parsePrioritiesJson(json: String?): List<ZonePriority> {
        if (json.isNullOrBlank()) return emptyList()
        val s = json.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return emptyList()
        val out = mutableListOf<ZonePriority>()
        var i = 1
        val end = s.length - 1
        while (i < end) {
            while (i < end && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= end) break
            if (s[i] != '{') break
            val fields = LinkedHashMap<String, String>()
            i++
            var broken = false
            while (i < end) {
                while (i < end && (s[i].isWhitespace() || s[i] == ',')) i++
                if (i >= end) break
                if (s[i] == '}') { i++; break }
                if (s[i] != '"') { broken = true; break }
                val keyStart = i + 1
                val keyEnd = s.indexOf('"', keyStart)
                if (keyEnd < 0) { broken = true; break }
                val key = s.substring(keyStart, keyEnd)
                i = keyEnd + 1
                while (i < end && s[i].isWhitespace()) i++
                if (i >= end || s[i] != ':') { broken = true; break }
                i++
                while (i < end && s[i].isWhitespace()) i++
                if (i < end && s[i] == '"') {
                    val vEnd = s.indexOf('"', i + 1)
                    if (vEnd < 0) { broken = true; break }
                    fields[key] = s.substring(i + 1, vEnd)
                    i = vEnd + 1
                } else {
                    val numStart = i
                    while (i < end && (s[i].isDigit() || s[i] == '.' || s[i] == '-' || s[i] == 'e' || s[i] == 'E' || s[i] == '+')) i++
                    if (i == numStart) { broken = true; break }
                    fields[key] = s.substring(numStart, i)
                }
            }
            if (broken) break
            val zone = fields["zone"]
            val direction = fields["direction"]?.let {
                runCatching { Direction.valueOf(it) }.getOrNull()
            }
            val gapPp = fields["gapPp"]?.toFloatOrNull()
            val tissue = fields["tissue"]?.let {
                runCatching { Tissue.valueOf(it) }.getOrNull()
            } ?: Tissue.UNKNOWN
            if (zone != null && zone in ZONES && direction != null && gapPp != null &&
                gapPp.isFinite() && abs(gapPp) <= 30f
            ) {
                out += ZonePriority(zone, direction, gapPp.coerceIn(-30f, 30f), tissue)
            }
        }
        return out
    }

    // ── Deterministic tactics (LLM fallback) ────────────────────────────────

    /**
     * Safe, conservative tactic list used whenever the LLM is unavailable
     * or its output fails validation. Categories are stable strings; the
     * user-facing text is resolved from I18n keys at persistence time.
     */
    fun defaultTactics(
        phase: BodyPhase?,
        priorities: List<ZonePriority>,
        weightKg: Float?,
    ): List<FallbackTactic> {
        val proteinG = weightKg?.let { (it * PROTEIN_G_PER_KG).roundToInt() }
        val tactics = mutableListOf<FallbackTactic>()
        tactics += FallbackTactic(
            "protein", "tactic_protein_target",
            mapOf("grams" to (proteinG?.toString() ?: "")), 28,
        )
        // v43.9: the generic strength template is FULL-BODY (no region) →
        // it now pairs with the region-neutral full-body circuit demo.
        // demoIdForTactic still fails closed through the catalog gate.
        tactics += FallbackTactic(
            "strength", "tactic_strength_progressive",
            demoId = demoIdForTactic("strength", "", null, null),
        )
        // v43.6: deterministic visual examples — the SAME fail-closed
        // catalog gate (demoIdForTactic) that the validator uses.
        tactics += FallbackTactic(
            "steps", "tactic_daily_steps",
            demoId = demoIdForTactic("steps", "daily steps", null, null),
        )
        tactics += FallbackTactic(
            "sleep", "tactic_sleep",
            demoId = demoIdForTactic("sleep", "sleep", null, null),
        )

        // One actionable zone tactic for the top priority (if any).
        val top = priorities.firstOrNull { it.direction != Direction.HOLD }
        if (top != null) {
            tactics += FallbackTactic(
                "zone",
                if (top.direction == Direction.REDUCE) "tactic_zone_reduce" else "tactic_zone_grow",
                mapOf("zone" to top.zone),
            )
        }
        // Phase-specific energy guidance.
        when (phase) {
            BodyPhase.CUT    -> tactics += FallbackTactic("calories", "tactic_cut_deficit")
            BodyPhase.BULK   -> tactics += FallbackTactic("calories", "tactic_bulk_surplus")
            BodyPhase.RECOMP -> tactics += FallbackTactic("calories", "tactic_recomp_balance")
            else -> {}
        }
        return tactics
    }

    // ── v43.6: deterministic demoId + backfill (visual examples) ────────────

    /** Lower-body region keywords (PT/EN) — the POSITIVE match that allows
     *  a lower-body demo on a strength tactic. A generic strength tactic
     *  (no region) gets NO demo: nothing is invented. */
    private val LOWER_BODY_KEYWORDS = listOf(
        "membros inferiores", "membro inferior", "inferiores",
        "pernas", "perna", "coxas", "coxa", "quadril",
        "glúteos", "gluteos", "joelhos", "joelho",
        "legs", "thigh", "knee", "glute", "lower body",
    )

    /**
     * v43.6 (2026-10): deterministic visual example for a tactic.
     *
     *  The candidate is chosen by a positive per-category match, then
     *  passed through the SAME fail-closed catalog gate
     *  ([TrainingDemoCatalog.incompatibilities]) as
     *  [TacticSafetyValidator.validateDemoCompatibility]: any mismatch
     *  (e.g. upper-body keywords or zone on a squat demo) → `null`.
     *  Categories without a catalog demo (protein, calories, zone) always
     *  return `null`. Pure JVM.
     *
     *  v43.9: strength mapping — an explicit LOWER-body focus keeps the
     *  squat demo (more specific); anything else (generic "força",
     *  upper-body focus, no region) gets the region-neutral
     *  `full_body_circuit`, which highlights both halves of the body.
     */
    fun demoIdForTactic(
        category: String,
        text: String,
        target: String?,
        zone: String?,
    ): String? {
        val cat = category.lowercase().trim()
        val demo = when (cat) {
            "steps" -> TrainingDemoCatalog.byId("brisk_walk")
            "sleep" -> TrainingDemoCatalog.byId("hip_flexor_stretch")
            "strength" ->
                if (mentionsLowerBody(text, target, zone))
                    TrainingDemoCatalog.byId("squat_bodyweight")
                else TrainingDemoCatalog.byId("full_body_circuit")
            else -> null
        } ?: return null
        val compatible = with(TrainingDemoCatalog) {
            demo.incompatibilities(cat, text, target, zone).isEmpty()
        }
        return if (compatible) demo.id else null
    }

    private fun mentionsLowerBody(text: String, target: String?, zone: String?): Boolean {
        val z = zone?.lowercase()?.trim()
        if (z == THIGH || z == LATERAL_THIGH || z == HIP) return true
        val combined = "$text $target".lowercase()
        return LOWER_BODY_KEYWORDS.any { combined.contains(it) }
    }

    /**
     * v43.6 (2026-10): one-shot, additive, replay-safe backfill of
     * deterministic visual examples onto an OLD tacticsJson (pre-v43.6
     * rows persisted without demoId).
     *
     *  - ADDITIVE: every existing field (and its order) is preserved; a
     *    [demoIdForTactic] result is appended only to tactics WITHOUT a
     *    demoId.
     *  - NEVER OVERRIDES: a persisted demoId is never rewritten, even if
     *    it looks wrong — that is the validator's (generation) and the UI
     *    render-check's (display) business.
     *  - REPLAY-SAFE: returns `null` when nothing changes, so callers can
     *    skip the write; a second pass over the output is a no-op.
     *  - FAIL-SOFT: malformed JSON → `null` (the row is left untouched).
     */
    fun backfillDemoIds(tacticsJson: String?): String? {
        val s = tacticsJson?.trim()
        if (s.isNullOrEmpty() || s.length < 2 || s[0] != '[') return null
        val n = s.length
        var i = 1
        val objs = mutableListOf<String>()
        var changed = false
        while (true) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) return null
            if (s[i] == ']') break
            if (s[i] == ',') { i++; continue }
            if (s[i] != '{') return null
            val parsed = parseFlatObject(s, i) ?: return null
            val fields = parsed.first
            val next = parsed.second
            val cat = fields["category"] ?: return null
            if (!cat.isString) return null
            val hasDemo = fields["demoId"]?.isString == true
            val newDemo = if (hasDemo) null else demoIdForTactic(
                cat.raw,
                fields["text"]?.takeIf { it.isString }?.raw.orEmpty(),
                fields["target"]?.takeIf { it.isString }?.raw,
                fields["zone"]?.takeIf { it.isString }?.raw,
            )
            if (newDemo != null) {
                // fieldsToJson emits the closing `}` — reopen the object,
                // append the demo, and close it again.
                objs += fieldsToJson(fields).dropLast(1) + ",\"demoId\":\"$newDemo\"}"
                changed = true
            } else {
                objs += fieldsToJson(fields)
            }
            i = next
        }
        if (!changed) return null
        return "[" + objs.joinToString(",") + "]"
    }

    /** Flat `{...}` object parser (tactic objects never nest). Returns
     *  fields in ORIGINAL order plus the index just past `}`; `null` when
     *  malformed. String values keep their raw interior (escapes intact)
     *  so re-serialization is byte-stable for untouched fields. */
    private fun parseFlatObject(s: String, i: Int): Pair<LinkedHashMap<String, TacticField>, Int>? {
        val fields = LinkedHashMap<String, TacticField>()
        val n = s.length
        var j = i + 1
        while (true) {
            while (j < n && s[j].isWhitespace()) j++
            if (j >= n) return null
            if (s[j] == '}') return fields to (j + 1)
            if (s[j] != '"') return null
            // key (kept raw; keys in our JSON are plain)
            var k = j + 1
            val keySb = StringBuilder()
            while (k < n) {
                if (s[k] == '\\' && k + 1 < n) { keySb.append(s[k]).append(s[k + 1]); k += 2 }
                else if (s[k] == '"') break
                else { keySb.append(s[k]); k++ }
            }
            if (k >= n) return null
            val key = keySb.toString()
            k++
            while (k < n && s[k].isWhitespace()) k++
            if (k >= n || s[k] != ':') return null
            k++
            while (k < n && s[k].isWhitespace()) k++
            if (k >= n) return null
            if (s[k] == '"') {
                // string value: keep the raw interior (escapes intact)
                var v = k + 1
                val start = v
                while (v < n) {
                    if (s[v] == '\\' && v + 1 < n) v++
                    else if (s[v] == '"') break
                    v++
                }
                if (v >= n) return null
                fields[key] = TacticField(s.substring(start, v), true)
                j = v + 1
            } else {
                // numbers (windowDays) — the only non-string in tactics JSON
                val numStart = k
                while (k < n && s[k] != ',' && s[k] != '}' && !s[k].isWhitespace()) k++
                val tok = s.substring(numStart, k)
                if (tok.isEmpty() || !tok.all { it.isDigit() || it in "+-.eE" }) return null
                fields[key] = TacticField(tok, false)
                j = k
            }
            while (j < n && s[j].isWhitespace()) j++
            if (j >= n) return null
            if (s[j] != ',' && s[j] != '}') return null
            if (s[j] == ',') j++
        }
    }

    private data class TacticField(val raw: String, val isString: Boolean)

    private fun fieldsToJson(fields: LinkedHashMap<String, TacticField>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, f) in fields) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escapeJson(k)).append('"').append(':')
            sb.append(if (f.isString) '"'.plus(f.raw).plus('"') else f.raw)
        }
        sb.append('}')
        return sb.toString()
    }

    /** Escape a PLAIN (unescaped) string for JSON emission. Tactic field
     *  values never contain quotes (the emitters replace `"` with `'`),
     *  but escape defensively anyway. */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ── Deltas between checkpoints ──────────────────────────────────────────

    /** Numeric deltas between the previous and the latest checkpoint.
     *  Null-safe: a missing side yields empty deltas (first checkpoint). */
    fun deltasBetween(
        latest: PerformanceSnapshotEntity?,
        previous: PerformanceSnapshotEntity?,
        latestComp: BodyCompositionSnapshotEntity?,
        previousComp: BodyCompositionSnapshotEntity?,
    ): Deltas {
        val zoneDeltas = LinkedHashMap<String, Float>()
        if (latest != null && previous != null) {
            val cur = zonesOf(latest)
            val prev = zonesOf(previous)
            for (zone in cur.keys) {
                val a = cur[zone] ?: continue
                val b = prev[zone] ?: continue
                // v41: a delta is only meaningful when BOTH captures used the
                // same normalization convention for that zone's photo side —
                // a frontal image-height ratio and a frontal person-height
                // ratio are different units, so the delta is omitted (not
                // computed) rather than reported as a misleading change.
                val frontal = zone in FRONTAL_ZONES
                val latestConv = if (frontal) snapshotConvention(latest.frontalNormalization)
                                 else snapshotConvention(latest.lateralNormalization)
                val prevConv = if (frontal) snapshotConvention(previous.frontalNormalization)
                               else snapshotConvention(previous.lateralNormalization)
                if (latestConv != prevConv) continue
                val d = (a - b) * 100f
                if (abs(d) >= MIN_GAP_PP) zoneDeltas[zone] = d
            }
        }
        return Deltas(
            zoneDeltasPp = zoneDeltas,
            weightDeltaKg = latestComp?.weightKg?.let { l ->
                previousComp?.weightKg?.let { p -> (l - p) }
            },
            fatDeltaPp = latestComp?.bodyFatPct?.let { l ->
                previousComp?.bodyFatPct?.let { p -> (l - p) }
            },
            muscleDeltaKg = latestComp?.muscleMassKg?.let { l ->
                previousComp?.muscleMassKg?.let { p -> (l - p) }
            },
        )
    }

    // ── Coach-targets JSON (app-defined flat object, 7 numeric keys) ────────

    /**
     * Parses the app's own strict coach-targets format
     * `{"shoulder":0.00,"waist":0.00,...}` — a flat object of non-negative
     * numbers — into a zone→target map. Hand-rolled (not org.json) so the
     * engine stays pure-JVM testable. Malformed input → empty map (the
     * caller then has no targets → no priorities; fail-soft by design).
     */
    fun targetsFromJson(json: String?): Map<String, Float> {
        if (json.isNullOrBlank()) return emptyMap()
        val s = json.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return emptyMap()
        val out = LinkedHashMap<String, Float>()
        var i = 1
        val end = s.length - 1
        while (i < end) {
            // skip whitespace / commas
            while (i < end && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= end) break
            if (s[i] != '"') return emptyMap()
            val keyStart = i + 1
            var keyEnd = s.indexOf('"', keyStart)
            if (keyEnd < 0) return emptyMap()
            val key = s.substring(keyStart, keyEnd)
            i = keyEnd + 1
            while (i < end && s[i].isWhitespace()) i++
            if (i >= end || s[i] != ':') return emptyMap()
            i++
            while (i < end && s[i].isWhitespace()) i++
            val numStart = i
            while (i < end && (s[i].isDigit() || s[i] == '.' || s[i] == '-' || s[i] == 'e' || s[i] == 'E' || s[i] == '+')) i++
            if (i == numStart) return emptyMap()
            val value = s.substring(numStart, i).toFloatOrNull() ?: return emptyMap()
            if (value < 0f || value > 1f) return emptyMap() // ratio sanity: 0..1
            out[key] = value
        }
        return out
    }
}
