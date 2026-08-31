package com.example.data.insights

import com.example.data.local.FoodEntry
import com.example.data.local.UserInsight
import com.example.data.local.UserInsightDao
import com.example.data.local.TargetSnapshot
import com.example.data.targets.TargetResolver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase E (the missing piece — Hugo asked for this explicitly when he
 * reformulated the knowledge-graph option): a pure analyzer that walks the
 * food/activity history, computes a small set of aggregate patterns, and
 * emits each finding as a pending [UserInsight] (with `source =
 * "aggregate_analyzer"` and `confirmedAtMs = null`) so the user gets a card
 * to confirm in the chat.
 *
 * Why "pending" + card instead of auto-confirm? Aggregate findings are
 * statistical guesses, not facts the user said. The `[INSIGHT_PROPOSAL]`
 * card flow (Phase E) is the same UX as for `llm_inferred` insights, so we
 * piggy-back on it rather than inventing a new approval surface.
 *
 * What we look for, in this initial cut:
 *  1. **Foods over-represented on high-kcal days.** Days are split into
 *     "above target kcal" vs "at-or-below". For each food name, we compute
 *     its frequency on each side and emit an insight when the imbalance is
 *     large enough (≥3× more frequent on high days, with N≥3 above-target
 *     days observed in the window).
 *  2. **Foods over-represented on low-kcal days.** Same as #1 but inverted —
 *     surfaces the staples Hugo eats on lighter days.
 *  3. **Days where macro mix is heavily off-target.** A blunt counter of
 *     how many days have protein < 70% of target (potentially under-eating
 *     protein) and emits a single aggregate insight summarising the trend
 *     when the count is dominant.
 *
 * Idempotency: every emit calls [GeminiRepositoryFacade.insertUserInsight]
 * (which de-dupes by exact text + category), so re-running on the same data
 * yields no new rows. The de-dup is purely textual — if the analyzer phrases
 * the same finding differently across runs (e.g. tweaks the percentage), a
 * new row appears. We keep the phrasing strictly templated below to avoid
 * that drift.
 *
 * This module is pure (no Android, no Room directly — operates over already-
 * read lists), so it lives in `data/insights/` and is unit-testable. The
 * trigger (post-sync, scheduled job, manual button) is in the ViewModel.
 */
object AggregateInsightAnalyzer {

    /** Minimum number of days observed in each bucket before a finding is
     *  considered statistically meaningful. Below this, the aggregate is
     *  almost certainly noise and we suppress it. */
    private const val MIN_DAYS_PER_BUCKET = 3

    /** Frequency ratio threshold: a food has to be at least this many times
     *  more common on the "extreme" side than on the "control" side before we
     *  surface it. 3× is loose enough to catch real patterns in a few weeks
     *  of data without flagging coincidences. */
    private const val IMBALANCE_RATIO = 3.0

    /** Minimum window length, in days. Shorter windows aren't rich enough to
     *  produce useful aggregates and would mostly emit one-off observations. */
    private const val MIN_WINDOW_DAYS = 14

    /**
     * Result for one analyzer pass. Tests assert against this; the side-effect
     * of inserting into the DAO is opt-in via [persist].
     */
    data class Findings(
        val foodsOnHighKcalDays: List<FoodPattern>,
        val foodsOnLowKcalDays: List<FoodPattern>,
        val proteinUnderShoot: ProteinUnderShoot?
    ) {
        fun isEmpty(): Boolean =
            foodsOnHighKcalDays.isEmpty() && foodsOnLowKcalDays.isEmpty() && proteinUnderShoot == null

        fun toInsightTexts(): List<Pair<String, String>> = buildList {
            foodsOnHighKcalDays.forEach { p ->
                add("Em dias acima do target kcal, ${p.foodName} aparece em ${p.percentOnExtreme.toInt()}% das refeições registadas." to "aggregate_pattern")
            }
            foodsOnLowKcalDays.forEach { p ->
                add("Em dias abaixo do target kcal, ${p.foodName} aparece em ${p.percentOnExtreme.toInt()}% das refeições registadas." to "aggregate_pattern")
            }
            proteinUnderShoot?.let { u ->
                add("Em ${u.daysUnderShoot}/${u.totalDays} dias analisados, a proteína ficou abaixo de 70% do target." to "aggregate_pattern")
            }
        }
    }

    data class FoodPattern(
        val foodName: String,
        val occurrencesOnExtreme: Int,
        val occurrencesOnControl: Int,
        val percentOnExtreme: Double,
        val ratio: Double
    )

    data class ProteinUnderShoot(
        val daysUnderShoot: Int,
        val totalDays: Int
    )

    /**
     * Compute aggregate findings over [foods] given the target snapshots.
     *
     * @param foods All FoodEntry rows in scope (caller can pre-filter to a
     *   window). Empty input yields [Findings] with everything null/empty.
     * @param targetSnapshots Sorted-ascending list (DAO contract).
     * @param windowEnd Last day to include (inclusive). Defaults to today.
     * @param windowDays How far back to look from [windowEnd]. Caller decides
     *   the cadence — the canonical use is "last 90 days" so the window stays
     *   relevant as the user logs more data.
     */
    fun analyze(
        foods: List<FoodEntry>,
        targetSnapshots: List<TargetSnapshot>,
        windowEnd: LocalDate = LocalDate.now(),
        windowDays: Int = 90,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Findings {
        if (foods.isEmpty() || targetSnapshots.isEmpty() || windowDays < MIN_WINDOW_DAYS) {
            return Findings(emptyList(), emptyList(), null)
        }
        val windowStart = windowEnd.minusDays((windowDays - 1).toLong())

        // Bucket foods by date.
        val foodsByDay: Map<LocalDate, List<FoodEntry>> = foods
            .filter {
                val d = Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate()
                !d.isBefore(windowStart) && !d.isAfter(windowEnd)
            }
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }

        if (foodsByDay.size < MIN_WINDOW_DAYS) {
            // Not enough days with data — give up early so we don't fire
            // misleading aggregates on a half-empty week.
            return Findings(emptyList(), emptyList(), null)
        }

        val targetsByDay = TargetResolver.resolveTargetsByDay(
            targetSnapshots,
            windowStart..windowEnd
        )

        // Classify each day as above / at-or-below the target kcal in force.
        val highDays = mutableListOf<LocalDate>()
        val lowDays = mutableListOf<LocalDate>()
        val proteinTrack = mutableListOf<Pair<LocalDate, Boolean>>() // (day, underShoot)

        for ((day, dayFoods) in foodsByDay) {
            val tgt = targetsByDay[day] ?: continue
            val totalKcal = dayFoods.sumOf { it.calories }
            val totalProtein = dayFoods.sumOf { it.protein }
            if (totalKcal > tgt.targetKcal) highDays += day
            else lowDays += day
            proteinTrack += day to (totalProtein < tgt.targetProtein * 0.7)
        }

        val highPatterns = if (highDays.size >= MIN_DAYS_PER_BUCKET && lowDays.size >= MIN_DAYS_PER_BUCKET) {
            findFoodPatterns(highDays, lowDays, foodsByDay)
        } else emptyList()
        val lowPatterns = if (highDays.size >= MIN_DAYS_PER_BUCKET && lowDays.size >= MIN_DAYS_PER_BUCKET) {
            findFoodPatterns(lowDays, highDays, foodsByDay)
        } else emptyList()

        val proteinFinding = run {
            val total = proteinTrack.size
            val under = proteinTrack.count { it.second }
            if (total >= MIN_WINDOW_DAYS && under >= total / 2) {
                ProteinUnderShoot(under, total)
            } else null
        }

        return Findings(highPatterns, lowPatterns, proteinFinding)
    }

    /**
     * For each food name, count its occurrences on [extremeDays] vs
     * [controlDays], then keep names that pass the [IMBALANCE_RATIO] gate.
     * Smoothing (+1 to controls) keeps brand-new foods on the extreme side
     * from blowing up to "infinity ratio".
     */
    private fun findFoodPatterns(
        extremeDays: List<LocalDate>,
        controlDays: List<LocalDate>,
        foodsByDay: Map<LocalDate, List<FoodEntry>>
    ): List<FoodPattern> {
        val extremeNames = extremeDays.flatMap { foodsByDay[it].orEmpty().map { f -> normaliseName(f.name) } }
        val controlNames = controlDays.flatMap { foodsByDay[it].orEmpty().map { f -> normaliseName(f.name) } }
        val extremeCounts = extremeNames.groupingBy { it }.eachCount()
        val controlCounts = controlNames.groupingBy { it }.eachCount()

        return extremeCounts
            .mapNotNull { (name, hits) ->
                if (hits < 2) return@mapNotNull null
                val ctrl = controlCounts[name] ?: 0
                val ratio = hits.toDouble() / (ctrl + 1).toDouble()
                if (ratio < IMBALANCE_RATIO) return@mapNotNull null
                FoodPattern(
                    foodName = name,
                    occurrencesOnExtreme = hits,
                    occurrencesOnControl = ctrl,
                    percentOnExtreme = 100.0 * hits / extremeNames.size,
                    ratio = ratio
                )
            }
            .sortedByDescending { it.ratio }
            .take(3) // cap so a single run doesn't flood the chat with cards
    }

    /** Lowercase + strip the trailing parens common in our food names so
     *  "Pizza (Margherita)" and "pizza margherita" collapse to one bucket. */
    private fun normaliseName(name: String): String =
        name.trim().lowercase()
            .replace(Regex("\\s*\\(.*?\\)\\s*$"), "")
            .replace(Regex("\\s+"), " ")

    /**
     * Persist findings as pending UserInsights. Re-runs are idempotent
     * because [UserInsightDao.findByText] guards each insert. Returns the
     * number of NEW insights actually inserted (i.e. excluding duplicates).
     */
    suspend fun persist(
        findings: Findings,
        dao: UserInsightDao
    ): Int {
        var inserted = 0
        for ((text, category) in findings.toInsightTexts()) {
            val existing = dao.findByText(text, category)
            if (existing != null) continue
            dao.insert(
                UserInsight(
                    text = text,
                    category = category,
                    confidence = 0.6,
                    source = "aggregate_analyzer",
                    sourceSessionId = null,
                    confirmedAtMs = null // user must approve via chat card
                )
            )
            inserted += 1
        }
        return inserted
    }
}
