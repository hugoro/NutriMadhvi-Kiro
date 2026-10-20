package com.example.data.local

import org.json.JSONObject

/**
 * Immutable snapshot of a [FoodEntry]'s nutritional state BEFORE a leftovers
 * adjustment, plus the human-readable leftovers block that was detected.
 *
 * Persisted as JSON in [FoodEntry.leftoverOriginalJson] (schema v38) so the app
 * can:
 *  1. Revert the meal EXACTLY to its pre-adjustment values when the user deletes
 *     the leftovers ("eliminar sobras" X button) — text parsing of a composed
 *     summary could not recover the precise macros.
 *  2. Render the persistent leftovers card from a structured source rather than
 *     parsing `fullSummary` (which now holds only the consumed description).
 *
 * [leftoverBlockText] is the exact multi-line block built by
 * `MealAnalysisOrchestrator.buildLeftoverBlock` — "Total sobras: N kcal" header
 * followed by one "• name (~Xg)" line per leftover ingredient.
 */
data class LeftoverOriginalSnapshot(
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fullSummary: String,
    val lastAiAnalyzedSummary: String?,
    val ingredientsJson: String?,
    val freeSugarsG: Double?,
    val saturatedFatG: Double?,
    val analysisModel: String?,
    val leftoverBlockText: String
)

/** Serialises this snapshot to a compact JSON object string. */
fun LeftoverOriginalSnapshot.encode(): String {
    val obj = JSONObject()
    obj.put("cal", calories)
    obj.put("p", protein)
    obj.put("c", carbs)
    obj.put("f", fat)
    obj.put("fs", fullSummary)
    lastAiAnalyzedSummary?.let { obj.put("las", it) }
    ingredientsJson?.let { obj.put("ing", it) }
    freeSugarsG?.let { obj.put("free", it) }
    saturatedFatG?.let { obj.put("sat", it) }
    analysisModel?.let { obj.put("model", it) }
    obj.put("lbt", leftoverBlockText)
    return obj.toString()
}

/**
 * Parses a [LeftoverOriginalSnapshot] from JSON. Returns null when the string is
 * blank or malformed (salvage-over-crash — a bad snapshot must never break the
 * edit screen; the meal simply won't offer a revert).
 */
fun decodeLeftoverSnapshot(json: String?): LeftoverOriginalSnapshot? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = JSONObject(json)
        LeftoverOriginalSnapshot(
            calories = obj.getInt("cal"),
            protein = obj.getDouble("p"),
            carbs = obj.getDouble("c"),
            fat = obj.getDouble("f"),
            fullSummary = obj.optString("fs", ""),
            lastAiAnalyzedSummary = obj.optString("las").takeIf { it.isNotBlank() },
            ingredientsJson = obj.optString("ing").takeIf { it.isNotBlank() },
            freeSugarsG = if (obj.has("free")) obj.getDouble("free") else null,
            saturatedFatG = if (obj.has("sat")) obj.getDouble("sat") else null,
            analysisModel = obj.optString("model").takeIf { it.isNotBlank() },
            leftoverBlockText = obj.optString("lbt", "")
        )
    }.getOrNull()
}

/**
 * Captures the current nutritional state of this entry into a snapshot.
 * [leftoverBlockText] is supplied by the caller (the orchestrator builds it from
 * the leftovers analysis result).
 */
fun FoodEntry.toLeftoverSnapshot(leftoverBlockText: String): LeftoverOriginalSnapshot =
    LeftoverOriginalSnapshot(
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fullSummary = fullSummary,
        lastAiAnalyzedSummary = lastAiAnalyzedSummary,
        ingredientsJson = ingredientsJson,
        freeSugarsG = freeSugarsG,
        saturatedFatG = saturatedFatG,
        analysisModel = analysisModel,
        leftoverBlockText = leftoverBlockText
    )

/**
 * Returns a copy of this entry with every nutritional field restored to the
 * snapshot's values and [FoodEntry.leftoverOriginalJson] cleared — i.e. the meal
 * as it was before leftovers were ever registered. The caller is responsible for
 * persisting (updateFood) and refreshing session state.
 */
fun FoodEntry.restoreFromSnapshot(s: LeftoverOriginalSnapshot): FoodEntry =
    copy(
        calories = s.calories,
        protein = s.protein,
        carbs = s.carbs,
        fat = s.fat,
        fullSummary = s.fullSummary,
        lastAiAnalyzedSummary = s.lastAiAnalyzedSummary,
        ingredientsJson = s.ingredientsJson,
        freeSugarsG = s.freeSugarsG,
        saturatedFatG = s.saturatedFatG,
        analysisModel = s.analysisModel,
        leftoverOriginalJson = null
    )
