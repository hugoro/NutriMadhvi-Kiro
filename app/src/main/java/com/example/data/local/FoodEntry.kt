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
    /** JSON-encoded edit log introduced by the main branch in schema v30.
     *
     *  This branch does not read or write it, but the column MUST stay declared:
     *  devices that ran a main-branch build already have it inside
     *  `food_entries`, and Room rejects an entity whose column set doesn't match
     *  the physical table. Dropping it here would both crash those installs on
     *  open and destroy the history on a future merge back into main. */
    val editLogJson: String? = null,
    /**
     * Which LLM produced this entry's analysis (e.g. `gemini-3.1-pro-preview`,
     * `qwen/qwen3.6-27b`). Persisted (not just kept in the in-memory
     * [com.example.data.remote.FoodAnalysisResult.analysisModel]) so the model
     * badge in the edit-meal screen survives an app restart — before this
     * column, re-opening a saved entry always showed the hardcoded "Pro" badge
     * or nothing, because the analysis result that named the real model only
     * ever lived in a ViewModel StateFlow.
     *
     * NULL for legacy entries (pre-v29) and for entries saved through paths
     * that don't stamp it; the UI falls back to a generic "Pro" label in that
     * case rather than showing a blank badge.
     */
    val analysisModel: String? = null,
    /** JSON snapshot of this meal's macros + summary + ingredients BEFORE a
     *  leftovers adjustment, so "eliminar sobras" can revert the meal exactly to
     *  its pre-adjustment state. Also carries the human-readable leftovers block
     *  text so the persisted card can render without parsing `fullSummary`.
     *  NULL = this meal was never adjusted via leftovers. Added in schema v38.
     *  Encoder/decoder in [com.example.data.local.LeftoverSnapshot]. */
    val leftoverOriginalJson: String? = null
)

