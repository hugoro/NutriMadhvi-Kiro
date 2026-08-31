package com.example.data.nutrition

import android.content.Context
import com.example.data.local.IngredientNutritionDao
import com.example.data.local.IngredientNutritionEntity
import com.example.data.local.MealSimilarity
import com.example.data.remote.IngredientDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cascade lookup service for per-ingredient nutrition data.
 *
 * Ground-truth-first priority order:
 *  1. Local clusters — ingredients the user has eaten & validated before
 *     ([IngredientClusterBuilder] over food history + habits). Highest trust.
 *  2. Room cache ([ingredient_nutrition_cache]) — previously resolved lookups.
 *  3. INSA PT embedded SQLite — Portuguese/Mediterranean reference data.
 *  4. LLM fallback — keep the Gemini estimate.
 *
 * Every candidate value passes through [MacroPlausibilityGate] before it is
 * allowed to replace the LLM estimate. A value that fails the gate is
 * discarded (the LLM estimate is kept), which is what prevents bad matches
 * from degrading good estimates.
 *
 * External text-search DBs (USDA / Open Food Facts) were removed after they
 * returned implausible matches ("coffee" = 411 kcal/100g).
 */
class NutritionLookupService(
    context: Context,
    private val dao: IngredientNutritionDao,
    private val clusterBuilder: IngredientClusterBuilder,
) {
    private val insa = InsaLocalSource(context)

    /** Similarity threshold for matching an ingredient name to a cluster. */
    private val clusterMatchThreshold = 0.85

    suspend fun enrichIngredients(
        ingredients: List<IngredientDto>,
    ): List<IngredientDto> = withContext(Dispatchers.IO) {
        val clusters = runCatching { clusterBuilder.getClusters() }.getOrDefault(emptyList())
        ingredients.map { ingredient ->
            runCatching { enrich(ingredient, clusters) }
                .getOrDefault(ingredient.copy(sourceTag = "gemini"))
        }
    }

    private suspend fun enrich(
        ingredient: IngredientDto,
        clusters: List<IngredientCluster>,
    ): IngredientDto {
        val displayName = ingredient.name ?: return ingredient.copy(sourceTag = "gemini")
        val weightG = ingredient.weightGrams.takeIf { it > 0 }
            ?: return ingredient.copy(sourceTag = "gemini")
        val normalizedPt = IngredientClusterBuilder.normalize(displayName)
        val englishName = ingredient.englishName?.takeIf { it.isNotBlank() }
        val normalizedEn = englishName?.let { IngredientClusterBuilder.normalize(it) }

        // 1. Local cluster (user's own validated history)
        val cluster = bestCluster(normalizedPt, clusters)
            ?: normalizedEn?.let { bestCluster(it, clusters) }
        if (cluster != null && cluster.occurrences >= 1) {
            val data = cluster.toNutritionData()
            if (isPlausible(data)) {
                return ingredient.applyPer100g(data, weightG)
            }
        }

        // 2. Room cache
        val cached = dao.findByNormalizedName(normalizedPt)
            ?: normalizedEn?.let { dao.findByNormalizedName(it) }
        if (cached != null && cached.confidence != "llm") {
            val data = cached.toNutritionData()
            if (isPlausible(data)) {
                dao.incrementUsed(cached.normalizedName, System.currentTimeMillis())
                return ingredient.applyPer100g(data, weightG)
            }
        }

        // 3. INSA embedded (Portuguese reference)
        val insaResult = insa.lookup(normalizedPt)
        if (insaResult != null && isPlausible(insaResult)) {
            saveToCache(normalizedPt, displayName, insaResult)
            return ingredient.applyPer100g(insaResult, weightG)
        }

        // 4. LLM fallback — keep original estimate
        return ingredient.copy(sourceTag = "gemini")
    }

    /** Best cluster whose name similarity meets the threshold, preferring
     *  higher occurrence counts on ties. */
    private fun bestCluster(key: String, clusters: List<IngredientCluster>): IngredientCluster? {
        var best: IngredientCluster? = null
        var bestScore = 0.0
        for (c in clusters) {
            val score = MealSimilarity.similarity(key, c.normalizedName)
            if (score >= clusterMatchThreshold &&
                (score > bestScore || (score == bestScore && c.occurrences > (best?.occurrences ?: 0)))
            ) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun isPlausible(data: NutritionData): Boolean =
        MacroPlausibilityGate.checkPer100g(
            kcal = data.per100gCalories,
            protein = data.per100gProtein,
            carbs = data.per100gCarbs,
            fat = data.per100gFat,
        ) is MacroPlausibilityGate.Result.Valid

    private suspend fun saveToCache(key: String, displayName: String, data: NutritionData) {
        val now = System.currentTimeMillis()
        dao.upsert(
            IngredientNutritionEntity(
                normalizedName    = key,
                displayName       = displayName,
                per100gCalories   = data.per100gCalories,
                per100gProtein    = data.per100gProtein,
                per100gCarbs      = data.per100gCarbs,
                per100gFat        = data.per100gFat,
                per100gSatFat     = data.per100gSatFat,
                per100gFreeSugars = data.per100gFreeSugars,
                source            = data.source,
                confidence        = data.confidence,
                createdAtMs       = now,
                updatedAtMs       = now,
            )
        )
    }

    private fun IngredientNutritionEntity.toNutritionData() = NutritionData(
        displayName = displayName,
        per100gCalories = per100gCalories, per100gProtein = per100gProtein,
        per100gCarbs = per100gCarbs, per100gFat = per100gFat,
        per100gSatFat = per100gSatFat, per100gFreeSugars = per100gFreeSugars,
        source = source, confidence = confidence,
    )

    private fun IngredientCluster.toNutritionData() = NutritionData(
        displayName = displayName,
        per100gCalories = per100gCalories, per100gProtein = per100gProtein,
        per100gCarbs = per100gCarbs, per100gFat = per100gFat,
        per100gSatFat = per100gSatFat, per100gFreeSugars = per100gFreeSugars,
        source = source, confidence = "verified",
    )
}

/** Scale per-100g values by portion weight and return enriched IngredientDto. */
private fun IngredientDto.applyPer100g(data: NutritionData, weightG: Double): IngredientDto {
    val r = weightG / 100.0
    return copy(
        calories      = (data.per100gCalories  * r).toInt(),
        protein       = data.per100gProtein    * r,
        carbs         = data.per100gCarbs      * r,
        fat           = data.per100gFat        * r,
        saturatedFatG = data.per100gSatFat     * r,
        freeSugarsG   = data.per100gFreeSugars * r,
        sourceTag     = data.source,
    )
}
