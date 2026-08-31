package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "food_entries")
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null, // custom image path URI if picked
    val analysisSummary: String? = null,
    val fullSummary: String = "",
    val lastAiAnalyzedSummary: String? = null,
    val mealCategory: MealCategory = MealCategory.SNACK,
    /** IDs of FoodHabit rows that were injected into the prompt for this entry.
     *  Drives the "Aplicado: ..." badge in the UI. Stored as a comma-separated
     *  string by [Converters.fromLongList]. */
    val appliedHabitIds: List<Long>? = null,
    /** Optional grouping handle. When the user logs multiple items in a single
     *  capture session (e.g. lunch photo + dessert photo picked together), every
     *  resulting FoodEntry shares the same `groupId` so the UI can render them
     *  side by side. Singleton sessions and entries created before v11 stay
     *  `null` and are treated as standalone. Generated as
     *  `"meal_${epochMs}_${UUID8}"` only when the session has 2+ items. */
    val groupId: String? = null,
    /** JSON-encoded list of `IngredientDto` returned by the LLM at analysis or
     *  re-analysis time (name + weightGrams + per-portion macros). Powers the
     *  per-macro ingredient hints under each bar in the edit/save meal screen
     *  (e.g. "(10g manteiga; 20g queijo)" under Fats). NULL for legacy entries
     *  (pre-v17) and for entries the model returned without a breakdown.
     *  Encoder lives in `GeminiRepository.encodeIngredients`; decoder in
     *  `com.example.ui.components.parseIngredients`. */
    val ingredientsJson: String? = null,
    /** Grams of "free sugars" per WHO definition inside this meal's total carbs.
     *  Free sugars = mono/disaccharides added by manufacturer/cook + sugars
     *  naturally present in honey, syrups, and fruit juices. Frutose from
     *  intact whole fruit does NOT count. Populated by the LLM at analysis
     *  time (see `GeminiRepository.buildFoodAnalysisPromptCore`).
     *
     *  NULL for legacy entries (pre-v25) and for analysis paths that haven't
     *  been updated. UI falls back to the legacy single-color carbs bar when
     *  the aggregate is zero across the day. Callers that clamp against
     *  `carbs` must use `freeSugarsG.coerceAtMost(carbs)` — see the manual-
     *  edit path where the user may lower `carbs` below the stored value. */
    val freeSugarsG: Double? = null,
    /** Grams of saturated fat inside this meal's total `fat`. Saturated fat =
     *  the fraction of `fat` from fatty acids with no double bonds — dominant in
     *  butter, cheese, cream, lard, fatty meat, bacon, and tropical oils
     *  (coconut/palm). WHO/AHA guideline: keep daily saturated intake below 10%
     *  of daily kcal (~<20 g on a 1800 kcal target).
     *
     *  Invariant: `0 ≤ saturatedFatG ≤ fat`. Every write path clamps with
     *  `.coerceAtMost(fat).coerceAtLeast(0.0)` — see ChatViewModel initial/streaming/
     *  chat-proposal/manual-edit sites and MealAnalysisOrchestrator merge site.
     *
     *  NULL for legacy entries (pre-v26) and for analysis paths that haven't
     *  been updated. UI falls back to the single-color yellow fat bar when
     *  `hasAnySatFatData` is false across the reporting window. */
    val saturatedFatG: Double? = null,
    /** JSON-encoded list of [FoodEditLogEntry]. Records every significant write
     *  to this row: initial insert ("added"), AI re-analysis ("auto"), and
     *  manual save ("manual"). Most-recent first. NULL for entries created
     *  before v30 (no backfill). */
    val editLogJson: String? = null
)

