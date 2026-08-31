package com.example.data.meal

import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto

/**
 * Stateless regex-based extractor that turns the raw SSE buffer (which is
 * almost-JSON, almost-always-incomplete) into a [FoodAnalysisProgress] the
 * UI can render.
 *
 * Why regex and not [com.squareup.moshi.JsonReader]: the lenient JsonReader
 * still throws on objects truncated mid-string ({"name":"Aloo without
 * closing quote), and a try/catch per chunk burns CPU while never producing
 * useful intermediate data. A handful of regexes over the accumulated buffer
 * is simpler, faster, and aligns with the salvage philosophy already used
 * by the chat parser ([[parser-salvage-over-warning]]).
 *
 * The regexes are deliberately pessimistic — they only match values that
 * are clearly terminated (closing quote / digit boundary), so partially-
 * streamed values don't briefly show garbage.
 */
internal object PartialJsonExtractor {

    private val NAME_RX =
        Regex("""\"name\"\s*:\s*\"([^\"\\]{1,120})\"""")
    private val MEAL_CATEGORY_RX =
        Regex("""\"mealCategory\"\s*:\s*\"([A-Za-z]{3,15})\"""")
    private val INGREDIENT_NAME_RX =
        Regex("""\"name\"\s*:\s*\"([^\"\\]{1,80})\"""")
    private val CALORIES_RX =
        Regex("""\"calories\"\s*:\s*(\d{1,5})(?=[\s,}\]])""")
    private val PROTEIN_RX =
        Regex("""\"protein\"\s*:\s*([\d]{1,4}(?:\.\d{1,3})?)(?=[\s,}\]])""")
    private val CARBS_RX =
        Regex("""\"carbs\"\s*:\s*([\d]{1,4}(?:\.\d{1,3})?)(?=[\s,}\]])""")
    private val FAT_RX =
        Regex("""\"fat\"\s*:\s*([\d]{1,4}(?:\.\d{1,3})?)(?=[\s,}\]])""")

    /**
     * Marker that splits the top-level meal object from the ingredients array.
     * The first NAME_RX match before this marker is the meal name; matches
     * after it are ingredient names.
     */
    private val INGREDIENTS_KEY_RX =
        Regex("""\"ingredients\"\s*:\s*\[""")

    /**
     * Returns a new progress snapshot, monotonically merging matches found in
     * [buffer] with whatever was already known in [prev]. Idempotent: feeding
     * the same buffer twice returns an equal value.
     */
    fun extractPartial(buffer: String, prev: FoodAnalysisProgress): FoodAnalysisProgress {
        val ingredientsStart = INGREDIENTS_KEY_RX.find(buffer)?.range?.last ?: -1
        val mealSlice = if (ingredientsStart > 0) buffer.substring(0, ingredientsStart) else buffer
        val ingredientsSlice = if (ingredientsStart > 0) buffer.substring(ingredientsStart) else ""

        // Top-level meal name: first NAME_RX match in the slice BEFORE the ingredients array.
        val newName = prev.name ?: NAME_RX.find(mealSlice)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        val newCategory = prev.mealCategory ?: MEAL_CATEGORY_RX.find(mealSlice)?.groupValues?.get(1)?.lowercase()

        // Top-level macros come from the meal slice; same for calories.
        val newCalories = prev.calories ?: CALORIES_RX.find(mealSlice)?.groupValues?.get(1)?.toIntOrNull()
        val newProtein = prev.protein ?: PROTEIN_RX.find(mealSlice)?.groupValues?.get(1)?.toDoubleOrNull()
        val newCarbs = prev.carbs ?: CARBS_RX.find(mealSlice)?.groupValues?.get(1)?.toDoubleOrNull()
        val newFat = prev.fat ?: FAT_RX.find(mealSlice)?.groupValues?.get(1)?.toDoubleOrNull()

        // Ingredients are name-only for the partial chips strip; full macros
        // come from the final Moshi parse. We keep insertion order and drop
        // dupes (a re-emitted chunk re-includes earlier names).
        val ingredientNames = if (ingredientsSlice.isNotBlank()) {
            INGREDIENT_NAME_RX.findAll(ingredientsSlice)
                .mapNotNull { it.groupValues.getOrNull(1)?.takeIf { v -> v.isNotBlank() } }
                .toList()
        } else emptyList()

        val mergedIngredients = if (ingredientNames.size > prev.ingredientsSoFar.size) {
            // Dedup case-insensitively but preserve original casing.
            val seen = HashSet<String>(ingredientNames.size)
            ingredientNames.filter { seen.add(it.lowercase()) }
        } else prev.ingredientsSoFar

        return prev.copy(
            name = newName,
            mealCategory = newCategory,
            ingredientsSoFar = mergedIngredients,
            calories = newCalories,
            protein = newProtein,
            carbs = newCarbs,
            fat = newFat,
            rawBytesReceived = buffer.length
        )
    }

    /**
     * Last-resort salvage when Moshi rejects the final JSON. Pulls whatever
     * fields the regex can find and packages them as a [FoodAnalysisResult]
     * — macros default to 0 so the UI can still render the edit form, and
     * the user fills the gaps. Returns null if even the meal name couldn't
     * be salvaged (caller falls back to the bare-image draft).
     */
    fun salvageFinal(buffer: String): FoodAnalysisResult? {
        val progress = extractPartial(buffer, FoodAnalysisProgress())
        val name = progress.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Build the IngredientDto list from names only — weights and per-ingredient
        // macros are lost during salvage; default to 0 grams so the JSON is valid
        // and the edit-meal screen renders the chips. The user can edit weights.
        val ingredients = progress.ingredientsSoFar
            .takeIf { it.isNotEmpty() }
            ?.map { IngredientDto(name = it, weightGrams = 0.0) }
        return FoodAnalysisResult(
            name = name,
            calories = progress.calories ?: 0,
            protein = progress.protein ?: 0.0,
            carbs = progress.carbs ?: 0.0,
            fat = progress.fat ?: 0.0,
            mealCategory = progress.mealCategory,
            ingredients = ingredients
        )
    }
}
