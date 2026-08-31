package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-taught dietary pattern. The Gemini repository injects active habits
 * into food-analysis prompts so estimates reflect what the user actually eats
 * (e.g. "iogurte grego natural Lidl 10% MG", "panquecas caseiras farinha integral T80")
 * instead of generic averages.
 *
 * The descriptor is free-form text passed verbatim to the model. Macros are
 * intentionally not stored here — keeping the contract simple and flexible
 * lets the user add or omit nutritional detail in plain language.
 */
@Entity(tableName = "food_habits")
data class FoodHabit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Trigger words/phrases (case-insensitive), separated by ';'. Used to match against
     *  the prompt hint and any descriptive text from the captured meal. */
    val trigger: String,
    /** Free-form description sent to the AI when this habit is matched. */
    val descriptor: String,
    /** If set, the habit only applies to this meal category. */
    val mealCategoryHint: MealCategory? = null,
    /** User notes, never sent to the AI. */
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    /** Updated each time this habit is applied to an analysis. */
    val lastAppliedAt: Long? = null,
    /** Incremented each time this habit is applied. */
    val timesApplied: Int = 0
)
