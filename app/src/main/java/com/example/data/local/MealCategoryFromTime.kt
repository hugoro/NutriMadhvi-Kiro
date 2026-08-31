package com.example.data.local

import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic meal-category classifier based on the local hour-of-day.
 *
 * Why this exists: the LLM's `mealCategory` field was effectively random —
 * a photo of a sandwich at 16:30 was equally likely to come back as LUNCH,
 * SNACK or DINNER depending on training noise. The user's actual mental
 * model is "what time of day did I eat this", so we override the LLM's
 * call with a clock lookup. The single exception is DESSERT, which is a
 * content judgement (the LLM can see "tiramisu" / "pudim") and is honored
 * regardless of hour.
 *
 * Buckets agreed with Hugo on 2026-06-18:
 *   06:00–10:00  BREAKFAST
 *   10:00–12:00  SNACK   (mid-morning)
 *   12:00–15:00  LUNCH
 *   15:00–18:00  SNACK   (afternoon)
 *   18:00–22:00  DINNER
 *   22:00–06:00  SNACK   (late-night)
 *
 * Boundaries are inclusive on the lower edge, exclusive on the upper edge
 * (`hour in 6 until 10` style), so 10:00 sharp is mid-morning SNACK, not
 * BREAKFAST. The local default zone is used because every call site has a
 * `timestamp` that came from `System.currentTimeMillis()` on this device —
 * no UTC boundary surprises for users on the move.
 */
object MealCategoryFromTime {

    /**
     * Resolve the category Hugo would naturally give a meal logged at
     * [timestampMs]. If [llmCategory] is [MealCategory.DESSERT], it wins —
     * sobremesa às 11h da manhã ainda é sobremesa.
     */
    fun classify(
        timestampMs: Long,
        llmCategory: MealCategory? = null
    ): MealCategory {
        if (llmCategory == MealCategory.DESSERT) return MealCategory.DESSERT
        val hour = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .hour
        return when (hour) {
            in 6 until 10 -> MealCategory.BREAKFAST
            in 10 until 12 -> MealCategory.SNACK     // morning snack
            in 12 until 15 -> MealCategory.LUNCH
            in 15 until 18 -> MealCategory.SNACK     // afternoon snack
            in 18 until 22 -> MealCategory.DINNER
            else -> MealCategory.SNACK               // 22–06 late-night snack
        }
    }
}
