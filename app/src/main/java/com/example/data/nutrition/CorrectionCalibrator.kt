package com.example.data.nutrition

import com.example.data.local.MealCorrectionDao
import com.example.data.local.MealCorrectionEntity
import com.example.ui.components.parseIngredients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A directional calibration derived from the user's correction history, ready
 * to inject into a meal-analysis prompt.
 *
 * Runtime-only, never persisted — it is recomputed (cheaply, cached) from the
 * `meal_corrections` table. Empty when there is not yet enough signal, so
 * appending [toPromptBlock] on an empty calibration is a no-op and the analysis
 * prompt stays byte-identical to today's.
 */
data class CorrectionCalibration(
    /** Signed median % error per macro, e.g. calories +0.24 means the AI runs
     *  ~24% HIGH (user consistently lowers it). Only present once enough
     *  corrections exist for that macro. Range roughly [-1.0, +large]. */
    val calorieBiasPct: Double? = null,
    val proteinBiasPct: Double? = null,
    val carbsBiasPct: Double? = null,
    val fatBiasPct: Double? = null,
    /** Recurrent ingredient substitutions the user made ≥ [MIN_SUBSTITUTION_COUNT]
     *  times: AI said `from`, user replaced with `to`. */
    val substitutions: List<Substitution> = emptyList(),
    /** How many correction rows fed this calibration (for the prompt's honesty). */
    val sampleSize: Int = 0,
) {
    data class Substitution(val from: String, val to: String, val count: Int)

    val isEmpty: Boolean
        get() = calorieBiasPct == null && proteinBiasPct == null &&
            carbsBiasPct == null && fatBiasPct == null && substitutions.isEmpty()

    /**
     * Render as a prompt block appended to the analysis prompt core. Returns
     * "" when [isEmpty] so the caller's string concatenation is a no-op.
     *
     * Deliberately framed as "calibration, not override": the model still reads
     * the photo, but nudges its estimate in the measured direction. Numbers are
     * measured from the user's own saves — nothing here is invented.
     */
    fun toPromptBlock(): String {
        if (isEmpty) return ""
        val lines = buildList {
            calorieBiasPct?.let { add(biasLine("calorie", it)) }
            proteinBiasPct?.let { add(biasLine("protein", it)) }
            carbsBiasPct?.let { add(biasLine("carbohydrate", it)) }
            fatBiasPct?.let { add(biasLine("fat", it)) }
            substitutions.forEach { s ->
                add(
                    "- This user's \"${s.from}\" is usually actually \"${s.to}\" " +
                        "(you mislabelled it ${s.count}× before). Prefer \"${s.to}\" unless the photo clearly shows otherwise."
                )
            }
        }
        return buildString {
            append("\n\n[USER CORRECTION HISTORY — apply as calibration, not as override; based on ")
            append(sampleSize)
            append(" past corrections by this user]\n")
            append(lines.joinToString("\n"))
            append("\nThese are measured from the user's own validated corrections. Bias your estimate in the stated direction, but still trust clear visual evidence in THIS photo.")
        }
    }

    private fun biasLine(macro: String, biasPct: Double): String {
        val pct = abs(biasPct * 100).let { "%.0f".format(it) }
        val dir = if (biasPct > 0) "HIGH — bias it DOWN" else "LOW — bias it UP"
        return "- Your $macro estimates for this user tend to run ~$pct% $dir accordingly."
    }
}

/**
 * Aggregates the append-only `meal_corrections` table into a
 * [CorrectionCalibration]. Same discipline as [IngredientClusterBuilder]:
 * in-memory cache with a cheap COUNT staleness check, median-based (robust to a
 * single outlier), pure derivation.
 *
 * ## Why median + confidence weighting, not mean
 * One badly-corrected meal (user fat-fingered a number) must not swing the
 * calibration. The median of the signed per-correction % errors is robust to
 * that. Corrections the AI made at HIGH self-confidence weigh more: a
 * confident-and-wrong estimate is the strong bias signal, whereas the model
 * already flagged its low-confidence guesses — correcting those tells us less
 * about a systematic bias. Weighting is applied by repeating high-confidence
 * samples in the list the median is taken over (integer weights).
 *
 * ## N-threshold gate
 * A macro bias only surfaces once [MIN_MACRO_SAMPLES] corrections carry a delta
 * for that macro — below that, one or two corrections are noise, not a trend.
 */
class CorrectionCalibrator(
    private val dao: MealCorrectionDao,
) {
    @Volatile private var cached: CorrectionCalibration? = null
    @Volatile private var cachedCount: Int = -1

    /** Returns the calibration, rebuilding only when the correction count
     *  changed (cheap COUNT, mirrors IngredientClusterBuilder.getClusters). */
    suspend fun getCalibration(): CorrectionCalibration = withContext(Dispatchers.IO) {
        val count = dao.count()
        val hit = cached
        if (hit != null && count == cachedCount) return@withContext hit
        val built = build(dao.listAll())
        cached = built
        cachedCount = count
        built
    }

    /** Force-invalidate after a new correction is inserted. */
    fun invalidate() {
        cached = null
        cachedCount = -1
    }

    private fun build(rows: List<MealCorrectionEntity>): CorrectionCalibration {
        if (rows.isEmpty()) return CorrectionCalibration()

        // --- directional macro bias -----------------------------------------
        // Signed % error = (ai - user) / user. Positive → AI over-estimates.
        fun biasFor(
            ai: (MealCorrectionEntity) -> Double?,
            user: (MealCorrectionEntity) -> Double?,
        ): Double? {
            val samples = mutableListOf<Double>()
            for (r in rows) {
                val a = ai(r) ?: continue
                val u = user(r) ?: continue
                if (u <= 0.0) continue // can't compute a ratio against zero
                val pctErr = (a - u) / u
                // Ignore absurd ratios (data glitch); a real bias is bounded.
                if (abs(pctErr) > 3.0) continue
                val weight = confidenceWeight(r.initialConfidence)
                repeat(weight) { samples.add(pctErr) }
            }
            // Gate: require a minimum number of DISTINCT corrections (not weighted
            // copies) before trusting the bias.
            val distinct = rows.count { r ->
                val a = ai(r); val u = user(r)
                a != null && u != null && u > 0.0 && abs((a - u) / u) <= 3.0
            }
            if (distinct < MIN_MACRO_SAMPLES) return null
            val m = median(samples) ?: return null
            // Suppress trivial biases — a ±5% median is noise, not worth a line.
            return if (abs(m) < MIN_MEANINGFUL_BIAS) null else m
        }

        val calorieBias = biasFor({ it.aiCalories?.toDouble() }, { it.userCalories?.toDouble() })
        val proteinBias = biasFor({ it.aiProtein }, { it.userProtein })
        val carbsBias = biasFor({ it.aiCarbs }, { it.userCarbs })
        val fatBias = biasFor({ it.aiFat }, { it.userFat })

        // --- recurrent ingredient substitutions -----------------------------
        // For each correction, diff the AI ingredient set against the user's.
        // A name present in AI but replaced by a different name in user (same
        // rough slot) is a substitution candidate. We count normalized pairs.
        val subCounts = HashMap<Pair<String, String>, Int>()
        val subDisplay = HashMap<Pair<String, String>, Pair<String, String>>()
        for (r in rows) {
            val aiNames = parseIngredients(r.aiIngredientsJson)
                .map { it.name }.filter { it.isNotBlank() }
            val userNames = parseIngredients(r.userIngredientsJson)
                .map { it.name }.filter { it.isNotBlank() }
            if (aiNames.isEmpty() || userNames.isEmpty()) continue
            val aiNorm = aiNames.map { IngredientClusterBuilder.normalize(it) }.toSet()
            val userNormToDisplay = userNames.associateBy { IngredientClusterBuilder.normalize(it) }
            val userNorm = userNormToDisplay.keys
            // AI names the user dropped entirely (present in AI, absent in user).
            val dropped = aiNames.filter { IngredientClusterBuilder.normalize(it) !in userNorm }
            // User names the AI never had (present in user, absent in AI).
            val added = userNames.filter { IngredientClusterBuilder.normalize(it) !in aiNorm }
            // A substitution is a dropped↔added pair when there is exactly one of
            // each — the unambiguous "you said A, it was actually B" case.
            if (dropped.size == 1 && added.size == 1) {
                val fromN = IngredientClusterBuilder.normalize(dropped[0])
                val toN = IngredientClusterBuilder.normalize(added[0])
                if (fromN.isNotEmpty() && toN.isNotEmpty() && fromN != toN) {
                    val key = fromN to toN
                    subCounts[key] = (subCounts[key] ?: 0) + 1
                    subDisplay[key] = dropped[0] to added[0]
                }
            }
        }
        val substitutions = subCounts
            .filter { it.value >= MIN_SUBSTITUTION_COUNT }
            .map { (key, count) ->
                val disp = subDisplay[key] ?: (key.first to key.second)
                CorrectionCalibration.Substitution(from = disp.first, to = disp.second, count = count)
            }
            .sortedByDescending { it.count }
            .take(MAX_SUBSTITUTION_LINES)

        return CorrectionCalibration(
            calorieBiasPct = calorieBias,
            proteinBiasPct = proteinBias,
            carbsBiasPct = carbsBias,
            fatBiasPct = fatBias,
            substitutions = substitutions,
            sampleSize = rows.size,
        )
    }

    companion object {
        /** Distinct corrections carrying a macro delta before its bias surfaces. */
        const val MIN_MACRO_SAMPLES = 5
        /** A |median| below this is treated as no meaningful bias. */
        const val MIN_MEANINGFUL_BIAS = 0.08 // 8%
        /** Times a substitution pair must recur before it's surfaced. */
        const val MIN_SUBSTITUTION_COUNT = 2
        const val MAX_SUBSTITUTION_LINES = 5

        /** Integer weight for the median-over-repeats trick. Confident-and-wrong
         *  is the strong signal; low-confidence corrections still count but less. */
        fun confidenceWeight(confidence: String?): Int = when (confidence?.trim()?.lowercase()) {
            "high", "alta", "alto" -> 3
            "medium", "média", "media", "médio", "medio" -> 2
            "low", "baixa", "baixo" -> 1
            else -> 2 // unrated → treat as medium
        }

        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid]
            else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
