package com.example.ui.components

import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.ActivityType
import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * Which of the four Dashboard bars a breakdown popup is describing.
 * CARBS and CALORIES render two tables each (primary + secondary);
 * PROTEIN and FAT render a single table (primary; secondary is empty).
 */
enum class MacroKind { CARBS, PROTEIN, FAT, CALORIES }

/**
 * One row of a contribution table. `amount` is grams for macro tables and
 * kcal for the calories tables; the popup formatter picks the right suffix
 * from [MacroKind]. `foodEntryId` is set on meal rows so the row can be
 * clicked to open Edit Meal Log; activity rows and macro fallbacks leave
 * it null. `durationMinutes` is only populated for activity rows.
 */
data class MacroContributionRow(
    val percent: Double,
    val amount: Double,
    val ingredientName: String,
    val mealName: String,
    val mealCategory: MealCategory?,
    val mealTimestamp: Long,
    val foodEntryId: Long?,
    val durationMinutes: Int?
)

/**
 * Result of a breakdown computation. `primary` is the main table
 * (Meals / Good sources / the single P|F table). `secondary` is the second
 * table for CARBS (Free sugar sources) and CALORIES (Activity); empty for
 * PROTEIN and FAT.
 */
data class MacroBreakdown(
    val primary: List<MacroContributionRow>,
    val secondary: List<MacroContributionRow>
)

/**
 * Placeholder used when a row can't attribute grams to a named ingredient
 * (missing/incomplete `ingredientsJson`, or a meal-level fallback). The
 * popup renders this literally so users see the row is a whole-meal blob
 * rather than a specific ingredient.
 */
const val MACRO_ROW_MISSING = "—"

/* ─────────────────────────────── PROTEIN ─────────────────────────────── */

/**
 * Compute rows for the PROTEIN table. Every food contributes either one row
 * per ingredient (when ingredient data is complete) or a single "—" fallback
 * row that carries the whole food-level value. Per-ingredient values are
 * rescaled so the food's ingredient rows sum to the food's protein total
 * exactly — that guarantees the whole table sums to the number on the bar
 * even when the LLM's per-ingredient values don't add up.
 *
 * FAT is handled separately in [computeFatRows] so it can split saturated /
 * unsaturated when the data is available.
 */
fun computeMacroRows(kind: MacroKind, foods: List<FoodEntry>): MacroBreakdown {
    require(kind == MacroKind.PROTEIN) {
        "computeMacroRows is only for PROTEIN; got $kind (FAT lives in computeFatRows)"
    }
    val total = foods.sumOf { foodMacroValue(it, kind) }
    val rows = mutableListOf<MacroContributionRow>()
    for (food in foods) {
        val foodValue = foodMacroValue(food, kind)
        if (foodValue <= 0.0) continue
        val ings = parseIngredients(food.ingredientsJson)
        val hasAll = ings.isNotEmpty() && ings.all { it.protein != null }
        if (!hasAll) {
            rows += fallbackRow(food, foodValue)
            continue
        }
        val ingsSum = ings.sumOf { it.protein ?: 0.0 }
        if (ingsSum <= 0.0) {
            rows += fallbackRow(food, foodValue)
            continue
        }
        val scale = foodValue / ingsSum
        for (ing in ings) {
            val raw = ing.protein ?: 0.0
            val g = raw * scale
            if (g <= 0.0) continue
            rows += MacroContributionRow(
                percent = 0.0,
                amount = g,
                ingredientName = ing.name,
                mealName = food.name,
                mealCategory = food.mealCategory,
                mealTimestamp = food.timestamp,
                foodEntryId = food.id,
                durationMinutes = null
            )
        }
    }
    return MacroBreakdown(
        primary = withPercentAndSort(rows, total),
        secondary = emptyList()
    )
}

private fun foodMacroValue(food: FoodEntry, kind: MacroKind): Double = when (kind) {
    MacroKind.PROTEIN -> food.protein
    MacroKind.FAT -> food.fat
    MacroKind.CARBS -> food.carbs
    MacroKind.CALORIES -> food.calories.toDouble()
}

private fun fallbackRow(food: FoodEntry, amount: Double): MacroContributionRow =
    MacroContributionRow(
        percent = 0.0,
        amount = amount,
        ingredientName = MACRO_ROW_MISSING,
        mealName = food.name,
        mealCategory = food.mealCategory,
        mealTimestamp = food.timestamp,
        // Carry the source meal id so tapping the row opens Edit Meal, matching
        // the CALORIES table. Every macro row maps to a concrete food.
        foodEntryId = food.id,
        durationMinutes = null
    )

/* ──────────────────────────────── FAT ───────────────────────────────── */

/**
 * Compute rows for the FAT popup. When any food carries a saturatedFatG
 * estimate, this returns two tables: `primary` (unsaturated fat sources)
 * and `secondary` (saturated fat sources), both ingredient-level and
 * rescaled per food so the sums match `food.fat - satFat` and `satFat`
 * respectively. When no food carries the field, `primary` holds one
 * ingredient-level entry per row (like the old computeMacroRows(FAT)
 * path) and `secondary` is empty — the dialog reads that as "single table
 * mode" and renders like Protein.
 *
 * The rescale is critical: LLMs emit per-ingredient saturatedFatG values
 * that don't sum exactly to the meal-level total. We trust the meal-level
 * total (authoritative for the bar) and scale ingredient contributions to
 * match. Same pattern as [computeCarbsRows]'s "Good sources" leg.
 */
fun computeFatRows(foods: List<FoodEntry>): MacroBreakdown {
    val totalSat = foods.sumOf { (it.saturatedFatG ?: 0.0).coerceAtMost(it.fat) }
    val totalUnsat = foods.sumOf { food ->
        val sat = (food.saturatedFatG ?: 0.0).coerceAtMost(food.fat)
        (food.fat - sat).coerceAtLeast(0.0)
    }

    val unsatRows = mutableListOf<MacroContributionRow>()
    val satRows = mutableListOf<MacroContributionRow>()

    for (food in foods) {
        if (food.fat <= 0.0) continue
        val sat = (food.saturatedFatG ?: 0.0).coerceAtMost(food.fat)
        val unsat = (food.fat - sat).coerceAtLeast(0.0)
        val ings = parseIngredients(food.ingredientsJson)

        // Secondary — saturated fat sources. Ingredient-level when data is
        // complete (every ingredient carries a non-null saturatedFatG whose
        // sum is positive); meal-level "—" fallback otherwise.
        if (sat > 0.0) {
            val ingsSatSum = ings.sumOf { it.saturatedFatG ?: 0.0 }
            val hasAllSat = ings.isNotEmpty()
                && ings.all { it.saturatedFatG != null }
                && ingsSatSum > 0.0
            if (hasAllSat) {
                val scale = sat / ingsSatSum
                for (ing in ings) {
                    val g = (ing.saturatedFatG ?: 0.0) * scale
                    if (g <= 0.0) continue
                    satRows += MacroContributionRow(
                        percent = 0.0,
                        amount = g,
                        ingredientName = ing.name,
                        mealName = food.name,
                        mealCategory = food.mealCategory,
                        mealTimestamp = food.timestamp,
                        foodEntryId = food.id,
                        durationMinutes = null
                    )
                }
            } else {
                satRows += fallbackRow(food, sat)
            }
        }

        // Primary — unsaturated fat sources. Prorate by
        // `ingredient.fat - ingredient.saturatedFatG` when both are known;
        // otherwise by `ingredient.fat`; otherwise fallback meal-level "—".
        if (unsat <= 0.0) continue
        val hasAllFat = ings.isNotEmpty() && ings.all { it.fat != null }
        if (!hasAllFat) {
            unsatRows += fallbackRow(food, unsat)
            continue
        }
        val ingsUnsatSum = ings.sumOf { ing ->
            val ingFat = ing.fat ?: 0.0
            val ingSat = ing.saturatedFatG ?: 0.0
            (ingFat - ingSat).coerceAtLeast(0.0)
        }
        // If every ingredient's per-row unsaturated share collapses to 0
        // (e.g. LLM claimed 100% saturated on every ingredient but the
        // meal-level says otherwise), fall back to prorating by raw fat
        // so the whole unsaturated slice still lands on real ingredients.
        val (scale, source) = if (ingsUnsatSum > 0.0) {
            unsat / ingsUnsatSum to "unsat"
        } else {
            val ingsFatSum = ings.sumOf { it.fat ?: 0.0 }
            if (ingsFatSum > 0.0) unsat / ingsFatSum to "fat"
            else 0.0 to "none"
        }
        if (source == "none") {
            unsatRows += fallbackRow(food, unsat)
            continue
        }
        for (ing in ings) {
            val basis = when (source) {
                "unsat" -> ((ing.fat ?: 0.0) - (ing.saturatedFatG ?: 0.0)).coerceAtLeast(0.0)
                else    -> (ing.fat ?: 0.0)
            }
            val g = basis * scale
            if (g <= 0.0) continue
            unsatRows += MacroContributionRow(
                percent = 0.0,
                amount = g,
                ingredientName = ing.name,
                mealName = food.name,
                mealCategory = food.mealCategory,
                mealTimestamp = food.timestamp,
                foodEntryId = food.id,
                durationMinutes = null
            )
        }
    }

    return MacroBreakdown(
        primary = withPercentAndSort(unsatRows, totalUnsat),
        secondary = withPercentAndSort(satRows, totalSat)
    )
}

/* ─────────────────────────────── CARBS ──────────────────────────────── */

/**
 * Compute rows for the CARBS popup: two tables.
 * `primary` (Good sources) is ingredient-level, prorated so per-food sums
 * equal `food.carbs - effectiveFreeSugars(food)`.
 * `secondary` (Free sugar sources) is meal-level: one row per food with a
 * non-zero [effectiveFreeSugars] — the false-positive guard drops meals whose
 * ingredients name no plausible sugar source (e.g. milk-in-coffee, roast meat),
 * folding that carb back into the good leg. The row's ingredient column carries
 * the best-guess culprit ([topFreeSugarSourceHint]); when none matches it falls
 * back to the meal name (never a bare "—").
 */
fun computeCarbsRows(foods: List<FoodEntry>): MacroBreakdown {
    val totalFree = foods.sumOf { effectiveFreeSugars(it) }
    val totalGood = foods.sumOf { food ->
        (food.carbs - effectiveFreeSugars(food)).coerceAtLeast(0.0)
    }

    val badRows = mutableListOf<MacroContributionRow>()
    val goodRows = mutableListOf<MacroContributionRow>()

    for (food in foods) {
        val ings = parseIngredients(food.ingredientsJson)
        val fs = effectiveFreeSugars(food)

        // ── Free-sugar (secondary) leg ──────────────────────────────────
        // Ingredient-level when every ingredient carries a non-null
        // freeSugarsG whose sum is positive: prorate each ing.freeSugarsG,
        // rescaled to the authoritative meal-level total `fs` (mirrors the
        // computeFatRows satRows leg). Otherwise keep the single meal-level
        // row labelled by the name heuristic (legacy blobs; never a bare "—").
        if (fs > 0.0) {
            val ingsFreeSum = ings.sumOf { it.freeSugarsG ?: 0.0 }
            val hasAllFree = ings.isNotEmpty()
                && ings.all { it.freeSugarsG != null }
                && ingsFreeSum > 0.0
            if (hasAllFree) {
                val scale = fs / ingsFreeSum
                for (ing in ings) {
                    val g = (ing.freeSugarsG ?: 0.0) * scale
                    if (g <= 0.0) continue
                    badRows += MacroContributionRow(
                        percent = 0.0,
                        amount = g,
                        ingredientName = ing.name,
                        mealName = food.name,
                        mealCategory = food.mealCategory,
                        mealTimestamp = food.timestamp,
                        foodEntryId = food.id,
                        durationMinutes = null
                    )
                }
            } else {
                badRows += MacroContributionRow(
                    percent = 0.0,
                    amount = fs,
                    ingredientName = topFreeSugarSourceHint(ings) ?: food.name,
                    mealName = food.name,
                    mealCategory = food.mealCategory,
                    mealTimestamp = food.timestamp,
                    foodEntryId = food.id,
                    durationMinutes = null
                )
            }
        }

        // ── Good-source (primary) leg ───────────────────────────────────
        // Prorate `goodFood` (= meal carbs − meal free sugars) across
        // ingredients. When per-ingredient free sugars are complete, the
        // basis is each ingredient's NON-free carbs (carbs − its own
        // freeSugarsG) so the sugary ingredient's residual starch lands here
        // and the sugar itself does not (mirrors computeFatRows' unsat basis).
        // Otherwise prorate by raw carbs. Rescale keeps the per-food sum at
        // exactly `goodFood`, so primary+secondary still equals Σ carbs.
        val goodFood = (food.carbs - fs).coerceAtLeast(0.0)
        if (goodFood <= 0.0) continue
        val hasAllFreeForGood = ings.isNotEmpty() && ings.all { it.freeSugarsG != null }
        val goodBasis: (StoredIngredient) -> Double = { ing ->
            val c = ing.carbs ?: 0.0
            if (hasAllFreeForGood) (c - (ing.freeSugarsG ?: 0.0)).coerceAtLeast(0.0) else c
        }
        val ingsGoodSum = ings.sumOf { goodBasis(it) }
        val hasAll = ings.isNotEmpty() && ings.all { it.carbs != null } && ingsGoodSum > 0.0
        if (!hasAll) {
            goodRows += fallbackRow(food, goodFood)
            continue
        }
        val scale = goodFood / ingsGoodSum
        for (ing in ings) {
            val g = goodBasis(ing) * scale
            if (g <= 0.0) continue
            goodRows += MacroContributionRow(
                percent = 0.0,
                amount = g,
                ingredientName = ing.name,
                mealName = food.name,
                mealCategory = food.mealCategory,
                mealTimestamp = food.timestamp,
                foodEntryId = food.id,
                durationMinutes = null
            )
        }
    }

    return MacroBreakdown(
        primary = withPercentAndSort(goodRows, totalGood),
        secondary = withPercentAndSort(badRows, totalFree)
    )
}

/* ────────────────────────────── CALORIES ────────────────────────────── */

/**
 * Compute rows for the CALORIES popup: two tables.
 * `primary` (Meals) — one row per food, `amount = food.calories`. Row is
 * clickable (foodEntryId set) so tapping opens Edit Meal Log.
 * `secondary` (Activity) — the same rows the "Análise" tab shows in
 * `buildReportActivityRows` so both surfaces agree: one row per manual
 * activity plus one row per Samsung Health workout session in the period.
 * The residual "ambient movement" (`shActiveCal − Σ workouts`, i.e.
 * steps × 0.015 + passive movement) is NOT emitted as a row here —
 * matches the Análise convention and avoids the misleading generic
 * "Samsung Health" line.
 *
 * Honesty note: the Activity table sums to
 * `Σ (manual.caloriesBurned) + Σ (workout.activeKcal ?: 0)`, which may be
 * LESS than the amber "Burned" figure by the ambient residual. The Meals
 * bar in the popup header still shows the food total against the goal,
 * unchanged. Table percentages use each table's own denominator so rows
 * within a table always sum to 100%.
 *
 * @param manualActivities pass the same `nonOverlappingManuals` local
 *   DashboardTab used — filtered to drop entries that overlap SH workouts,
 *   so the popup and the bar agree.
 * @param shWorkouts every SH workout the ViewModel is exposing; this
 *   function filters to the given [periodDateRange] using [zoneId].
 */
fun computeCaloriesRows(
    foods: List<FoodEntry>,
    manualActivities: List<ActivityEntry>,
    shWorkouts: List<ShWorkout>,
    shAvailable: Boolean,
    periodDateRange: ClosedRange<LocalDate>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    activityTypeLabel: (ActivityType) -> String
): MacroBreakdown {
    val totalMealsKcal = foods.sumOf { it.calories }.toDouble()

    val meals = foods
        .filter { it.calories > 0 }
        .map { food ->
            MacroContributionRow(
                percent = 0.0,
                amount = food.calories.toDouble(),
                ingredientName = "",
                mealName = food.name,
                mealCategory = food.mealCategory,
                mealTimestamp = food.timestamp,
                foodEntryId = food.id,
                durationMinutes = null
            )
        }

    // Manual rows + per-workout SH rows, mirroring buildReportActivityRows
    // in ReportActivityRows.kt so the popup and the Análise tab stay in sync.
    val acts = mutableListOf<MacroContributionRow>()
    for (a in manualActivities) {
        if (a.caloriesBurned <= 0) continue
        acts += MacroContributionRow(
            percent = 0.0,
            amount = a.caloriesBurned.toDouble(),
            ingredientName = "",
            mealName = activityTypeLabel(a.activityType),
            mealCategory = null,
            mealTimestamp = a.timestamp,
            foodEntryId = null,
            durationMinutes = a.durationMinutes
        )
    }
    if (shAvailable) {
        for (w in shWorkouts) {
            val day = w.startTime.atZone(zoneId).toLocalDate()
            if (day !in periodDateRange) continue
            val kcal = w.activeKcal ?: 0
            if (kcal <= 0) continue
            val durationMin = Duration.between(w.startTime, w.endTime)
                .toMinutes().toInt().coerceAtLeast(0)
            acts += MacroContributionRow(
                percent = 0.0,
                amount = kcal.toDouble(),
                ingredientName = "",
                // Mirrors ShWorkout.displayLabel priority:
                // title (user's own) > sourceLabel (SDK fine grain) >
                // activityTypeLabel(coarse bucket, translated).
                // Kept out-of-line here so the translated bucket string is
                // computed only when we fall through — activityTypeLabel is
                // a Composable-context callback and cheap enough but leaner
                // to call once.
                mealName = w.title?.takeIf { it.isNotBlank() }
                    ?: w.sourceLabel.takeIf { it.isNotBlank() }
                    ?: activityTypeLabel(w.type),
                mealCategory = null,
                mealTimestamp = w.startTime.toEpochMilli(),
                foodEntryId = null,
                durationMinutes = durationMin
            )
        }
    }
    val totalActivityKcal = acts.sumOf { it.amount }

    return MacroBreakdown(
        primary = withPercentAndSort(meals, totalMealsKcal),
        secondary = withPercentAndSort(acts, totalActivityKcal)
    )
}

/* ─────────────────────────── shared helpers ─────────────────────────── */

/**
 * Effective free sugars for a meal, applying the false-positive guard.
 *
 * `FoodEntry.freeSugarsG` is a single LLM-estimated meal-level number; the model
 * sometimes over-assigns it to plain milk in coffee or unglazed roast meat (see
 * the WHO prompt in GeminiRepository). This helper is the single source of truth
 * that decides how much of that number we actually believe:
 *
 *  - Suppresses to 0 when NO ingredient plausibly carries free sugar
 *    ([topFreeSugarSourceHint] returns null) AND we have an ingredient breakdown
 *    to prove it (`ings.isNotEmpty()`) — the confident false-positive case.
 *  - Trusts the LLM number when there is no ingredient data (legacy/omitted
 *    breakdown) — we can't prove it wrong, so we don't hide it.
 *
 * Always clamped at `carbs`, matching the split-bar clamp used everywhere else.
 * Used by [computeCarbsRows] AND the Dashboard/popup aggregate in ChatScreen so
 * the bar, the dashboard, and the breakdown table never disagree.
 */
fun effectiveFreeSugars(food: FoodEntry): Double {
    val fs = (food.freeSugarsG ?: 0.0).coerceAtMost(food.carbs)
    if (fs <= 0.0) return 0.0
    val ings = parseIngredients(food.ingredientsJson)
    // Per-ingredient evidence wins over the name heuristic: if any ingredient
    // explicitly carries free sugars, the LLM has localized it — trust the
    // meal number and let computeCarbsRows attribute it. The name-based
    // false-positive guard below only applies to legacy blobs that have no
    // per-ingredient sugar to prove the number right.
    if (ings.any { (it.freeSugarsG ?: 0.0) > 0.0 }) return fs
    val hasHint = topFreeSugarSourceHint(ings) != null
    return if (!hasHint && ings.isNotEmpty()) 0.0 else fs
}

/**
 * Fill in percent = amount / denom * 100 for every row, then sort desc
 * by percent; tie-break by timestamp desc, then mealName asc so the
 * order is deterministic across recompositions.
 */
private fun withPercentAndSort(
    rows: List<MacroContributionRow>,
    denom: Double
): List<MacroContributionRow> {
    val safeDenom = max(denom, 0.0000001)
    return rows
        .map { it.copy(percent = it.amount / safeDenom * 100.0) }
        .sortedWith(
            compareByDescending<MacroContributionRow> { it.percent }
                .thenByDescending { it.mealTimestamp }
                .thenBy { it.mealName }
        )
}
