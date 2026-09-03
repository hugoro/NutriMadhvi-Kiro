package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import kotlin.math.abs

/**
 * Runtime confidence dimensions used for ensemble routing.
 *
 * The existing [FoodAnalysisResult.confidence] is a coarse `low|medium|high`
 * self-rating and stays untouched for backwards compatibility. These finer
 * dimensions are derived at runtime and never persisted.
 *
 * All values are in `0.0..1.0`.
 */
data class ConfidenceProfile(
    /** Confidence that the visual food identity is correct. */
    val identification: Double,
    /** Confidence in the estimated quantity/weight/volume. */
    val portion: Double,
    /** Confidence that a *specific* dish name is justified rather than a
     *  safer generic label. Low means "the model may be over-naming". */
    val specificity: Double,
    /** Whether the response appears to cover the visible components rather
     *  than only the dominant item. */
    val completeness: Double,
    /** Whether names, portions, totals and ingredient structure cohere. */
    val consistency: Double,
) {
    companion object {
        /** Neutral profile used when a provider gives us nothing to score. */
        val UNKNOWN = ConfidenceProfile(0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Derives a [ConfidenceProfile] from a parsed result.
 *
 * Pure and deterministic so routing decisions are unit-testable without any
 * live API call. Raw LLM self-confidence is only ever *one* input — per the
 * design rules it is a routing signal, never treated as ground truth.
 */
object ConfidenceScorer {

    /** Regional/branded dish names carry identity risk when the model has no
     *  strong visual justification: they assert a preparation, not just a
     *  food. We do not blacklist them — we lower [ConfidenceProfile.specificity]
     *  so the router prefers a second opinion. */
    private val SPECIFIC_DISH_MARKERS = listOf(
        "makhani", "masala", "biryani", "tikka", "korma", "vindaloo",
        "bolonhesa", "bolognese", "carbonara", "parmigiana", "cacciatore",
        "bacalhau à", "bacalhau a ", "francesinha", "feijoada", "cozido à",
        "naan", "chai", "paneer", "dosa", "samosa", "pad thai", "ramen",
        "teriyaki", "katsu", "gyoza", "paella", "risotto", "goulash",
    )

    /** Words that signal the model hedged on identity. */
    private val HEDGE_MARKERS = listOf(
        "possibly", "possivelmente", "provavelmente", "probably",
        "maybe", "talvez", "unclear", "unknown", "desconhecido",
        "similar to", "semelhante a", "parece", "appears to be", "?",
    )

    fun score(result: FoodAnalysisResult): ConfidenceProfile {
        val self = selfRating(result.confidence)
        val name = result.name?.trim().orEmpty()
        val ingredients = result.ingredients.orEmpty()

        if (name.isBlank() && ingredients.isEmpty()) return ConfidenceProfile.UNKNOWN

        val identification = identificationScore(name, self)
        val portion = portionScore(result, self)
        val specificity = specificityScore(name, self)
        val completeness = completenessScore(result)
        val consistency = consistencyScore(result)

        return ConfidenceProfile(
            identification = identification,
            portion = portion,
            specificity = specificity,
            completeness = completeness,
            consistency = consistency,
        )
    }

    /** Maps the coarse LLM self-rating onto a base score. */
    private fun selfRating(confidence: String?): Double = when (confidence?.trim()?.lowercase()) {
        "high", "alta", "alto" -> 0.90
        "medium", "média", "media", "médio", "medio" -> 0.70
        "low", "baixa", "baixo" -> 0.45
        else -> 0.70 // unrated → treat as medium, never as high
    }

    private fun identificationScore(name: String, self: Double): Double {
        if (name.isBlank()) return 0.0
        val lower = name.lowercase()
        var score = self
        if (HEDGE_MARKERS.any { lower.contains(it) }) score -= 0.25
        // A bare one-word label like "food"/"meal" is not an identification.
        if (lower in setOf("food", "comida", "meal", "refeição", "refeicao", "prato", "dish")) {
            score -= 0.35
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun portionScore(result: FoodAnalysisResult, self: Double): Double {
        val ingredients = result.ingredients.orEmpty()
        if (ingredients.isEmpty()) {
            // No per-ingredient weights at all: portion rests entirely on an
            // unverifiable meal-level guess.
            return (self - 0.25).coerceIn(0.0, 1.0)
        }
        var score = self
        // Non-positive or absurd weights mean the portion model broke down.
        if (ingredients.any { it.weightGrams <= 0.0 }) score -= 0.35
        if (ingredients.any { it.weightGrams > 3000.0 }) score -= 0.25
        // Suspiciously round numbers everywhere suggest a template, not a read.
        val allRound = ingredients.size >= 2 && ingredients.all { it.weightGrams % 50.0 == 0.0 }
        if (allRound) score -= 0.10
        return score.coerceIn(0.0, 1.0)
    }

    private fun specificityScore(name: String, self: Double): Double {
        if (name.isBlank()) return 0.0
        val lower = name.lowercase()
        var score = self
        if (SPECIFIC_DISH_MARKERS.any { lower.contains(it) }) score -= 0.30
        // Long multi-word regional names are the classic over-specification.
        if (name.split(Regex("\\s+")).size >= 4) score -= 0.10
        return score.coerceIn(0.0, 1.0)
    }

    private fun completenessScore(result: FoodAnalysisResult): Double {
        val ingredients = result.ingredients.orEmpty()
        return when {
            ingredients.isEmpty() -> 0.35
            ingredients.size == 1 -> 0.65
            else -> 0.90
        }
    }

    /**
     * Internal coherence: do the ingredient macros roughly add up to the meal
     * totals? The prompt tolerates ≤5% drift because the LLM rounds per row,
     * so we only penalise clear structural inconsistency.
     */
    private fun consistencyScore(result: FoodAnalysisResult): Double {
        val ingredients = result.ingredients.orEmpty()
        if (ingredients.isEmpty()) return 0.60
        var score = 0.95

        val mealCalories = result.calories
        val sumCalories = ingredients.mapNotNull { it.calories }.takeIf { it.isNotEmpty() }?.sum()
        if (mealCalories != null && mealCalories > 0 && sumCalories != null && sumCalories > 0) {
            val drift = abs(mealCalories - sumCalories).toDouble() / mealCalories.toDouble()
            score -= when {
                drift <= 0.05 -> 0.0
                drift <= 0.20 -> 0.10
                drift <= 0.50 -> 0.25
                else -> 0.45
            }
        }

        // Saturated fat can never exceed total fat, per-ingredient.
        val violatesFat = ingredients.any { ing ->
            val fat = ing.fat
            val sat = ing.saturatedFatG
            fat != null && sat != null && sat > fat + 0.01
        }
        if (violatesFat) score -= 0.20

        // Nameless ingredient rows mean the structure is unreliable.
        if (ingredients.any { it.name.isBlank() }) score -= 0.15

        return score.coerceIn(0.0, 1.0)
    }
}
