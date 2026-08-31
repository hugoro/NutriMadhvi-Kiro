package com.example.data.nutrition

/**
 * Rejects implausible per-100g nutrition values before they replace an
 * LLM estimate. This is the quality gate that would have caught the bad
 * USDA matches (coffee = 411 kcal/100g, egg = 368 kcal/100g).
 *
 * Two independent checks:
 *  1. Energy density in a sane range for food (0–900 kcal/100g; pure fat/oil
 *     tops out at ~900, so anything above is a data error).
 *  2. Atwater consistency: kcal ≈ 4·protein + 4·carbs + 9·fat (±tolerance).
 *     A value that fails this is internally inconsistent — the kcal doesn't
 *     match the macro breakdown, which is the signature of a wrong DB match.
 */
object MacroPlausibilityGate {

    private const val MAX_KCAL_PER_100G = 900.0
    private const val ATWATER_TOLERANCE = 0.30  // ±30% — loose enough for fibre/alcohol/rounding

    sealed interface Result {
        data object Valid : Result
        data class Suspicious(val reason: String) : Result
    }

    /**
     * Validate per-100g values. [kcal] is required; macros default to 0 when
     * the source omitted them.
     */
    fun checkPer100g(
        kcal: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
    ): Result {
        if (kcal < 0 || kcal > MAX_KCAL_PER_100G) {
            return Result.Suspicious("kcal/100g out of range: $kcal")
        }
        if (protein < 0 || carbs < 0 || fat < 0) {
            return Result.Suspicious("negative macro")
        }
        // Atwater check only when we have a macro breakdown to check against.
        val atwater = 4 * protein + 4 * carbs + 9 * fat
        if (atwater > 5.0 && kcal > 5.0) {
            val ratio = kcal / atwater
            if (ratio < (1 - ATWATER_TOLERANCE) || ratio > (1 + ATWATER_TOLERANCE)) {
                return Result.Suspicious(
                    "Atwater mismatch: kcal=$kcal vs 4P+4C+9F=${"%.0f".format(atwater)} (ratio ${"%.2f".format(ratio)})"
                )
            }
        }
        return Result.Valid
    }

    /**
     * Validate that a meal's totals roughly equal the sum of its ingredient
     * portions (±[tolerance]). Used after enrichment recalculation.
     * Returns Valid when there are no ingredients to compare against.
     */
    fun checkMealTotalsAgainstIngredients(
        mealKcal: Int,
        ingredientKcalSum: Int,
        tolerance: Double = 0.15,
    ): Result {
        if (ingredientKcalSum <= 0) return Result.Valid
        val drift = kotlin.math.abs(mealKcal - ingredientKcalSum).toDouble() /
            maxOf(ingredientKcalSum, 1)
        return if (drift <= tolerance) Result.Valid
        else Result.Suspicious("meal kcal=$mealKcal drifts ${"%.0f".format(drift * 100)}% from ingredient sum=$ingredientKcalSum")
    }
}
