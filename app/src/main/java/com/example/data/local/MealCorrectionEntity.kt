package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured "gap" between what the meal-analysis AI estimated and what the
 * user actually saved, recorded at save time.
 *
 * ## Why this table exists
 * The AI's interpretation of a meal photo is *systematically biased* in
 * consistent directions for a given user (e.g. it reads thin toast as
 * sourdough, assumes cooking fat on eggs eaten without oil, over-estimates
 * starch portions). The user corrects the same characteristics over and over.
 * Correcting the same thing twice is already one time too many.
 *
 * Every correction is a signal — *AI estimated X → user saved Y, at some
 * initial self-confidence*. Before this table that signal was thrown away:
 * [FoodEditLogEntry] records only THAT an edit happened, never the delta, and
 * the original AI macros live only in an in-memory ViewModel field that the
 * save overwrites. This table persists the delta so [com.example.data.nutrition.CorrectionCalibrator]
 * can aggregate it into a directional calibration the analysis prompt applies
 * on future photos.
 *
 * ## Append-only
 * Same discipline as [BmrSnapshotEntity] / [BodyCompositionSnapshotEntity]:
 * `@Insert`-only DAO, no update/delete of individual rows. The calibrator reads
 * the whole (small) table and derives medians. The DAO trims the oldest rows
 * past a cap so the table cannot grow without bound.
 *
 * ## Nullability
 * Every value field is nullable. A correction may change only the name, only a
 * macro, only the ingredient list, or any subset — we store whatever the AI
 * emitted and whatever the user saved, and the calibrator ignores dimensions
 * that are absent. `aiIngredientsJson`/`userIngredientsJson` hold the raw
 * `IngredientDto` JSON so the calibrator can parse ingredient substitutions
 * (e.g. "pão de massa mãe" → "tosta") without a schema change here.
 */
@Entity(
    tableName = "meal_corrections",
    indices = [Index(value = ["createdAtMs"])]
)
data class MealCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock ms when the correction was saved. Drives ordering + trim. */
    val createdAtMs: Long = System.currentTimeMillis(),
    /** Meal-category bucket (Breakfast/Lunch/…) for future per-category
     *  calibration. Null when unknown. */
    val mealCategory: String? = null,
    /** Which LLM produced the estimate that was corrected (e.g.
     *  `gemini-3.6-flash`). Null for entries with no recorded model. */
    val model: String? = null,
    /** The AI's meal-level self-rating at estimate time (`low`|`medium`|`high`).
     *  Confident-and-wrong corrections weigh more in the calibrator than
     *  low-confidence ones — the model already knew it was guessing there. */
    val initialConfidence: String? = null,

    // --- dish name gap ---
    val aiName: String? = null,
    val userName: String? = null,

    // --- meal-level macro gap ---
    val aiCalories: Int? = null,
    val userCalories: Int? = null,
    val aiProtein: Double? = null,
    val userProtein: Double? = null,
    val aiCarbs: Double? = null,
    val userCarbs: Double? = null,
    val aiFat: Double? = null,
    val userFat: Double? = null,

    // --- ingredient breakdown gap (raw IngredientDto JSON) ---
    val aiIngredientsJson: String? = null,
    val userIngredientsJson: String? = null,
)
