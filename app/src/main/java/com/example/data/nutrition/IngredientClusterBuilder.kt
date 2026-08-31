package com.example.data.nutrition

import com.example.data.local.FoodDao
import com.example.data.local.FoodHabitDao
import com.example.ui.components.parseIngredients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * One aggregated ingredient cluster built from the user's own validated
 * history. Every past meal's `ingredientsJson` carries per-portion macros the
 * user implicitly validated by saving. This aggregates all occurrences of the
 * same ingredient into a single per-100g reference.
 *
 * Uses the MEDIAN of per-100g values (not the mean) so one badly weight-
 * estimated past entry does not skew the cluster.
 */
data class IngredientCluster(
    val normalizedName: String,
    val displayName: String,
    val per100gCalories: Double,
    val per100gProtein: Double,
    val per100gCarbs: Double,
    val per100gFat: Double,
    val per100gSatFat: Double,
    val per100gFreeSugars: Double,
    /** How many past occurrences fed this cluster — a confidence proxy. */
    val occurrences: Int,
    /** "history" | "habit" | "user-verified" */
    val source: String,
)

/**
 * Builds [IngredientCluster]s from the user's meal history (`food_entries`)
 * and memorized habits. This is the ground-truth source for macro enrichment:
 * the user has eaten these before and the values were validated by being saved.
 *
 * Cached in memory; rebuilt when the food count changes (cheap staleness check).
 */
class IngredientClusterBuilder(
    private val foodDao: FoodDao,
    private val habitDao: FoodHabitDao,
) {
    @Volatile private var cachedClusters: List<IngredientCluster>? = null
    @Volatile private var cachedFoodCount: Int = -1

    /** Returns clusters, rebuilding only when the food-entry count changed.
     *  Staleness is checked with a cheap COUNT (not getAllFoods) so a warm
     *  cache costs one COUNT query instead of loading + parsing the whole
     *  table on every analysis. */
    suspend fun getClusters(): List<IngredientCluster> = withContext(Dispatchers.IO) {
        val count = foodDao.countSince(0L)
        val cached = cachedClusters
        if (cached != null && count == cachedFoodCount) return@withContext cached

        val foods = foodDao.getAllFoods()
        val built = build(foods)
        cachedClusters = built
        cachedFoodCount = foods.size
        built
    }

    /** Force-invalidate the cache (e.g. after a manual macro edit). */
    fun invalidate() {
        cachedClusters = null
        cachedFoodCount = -1
    }

    private suspend fun build(
        foods: List<com.example.data.local.FoodEntry>
    ): List<IngredientCluster> {
        // Accumulate per-100g samples keyed by normalized ingredient name.
        data class Sample(
            val displayName: String,
            val kcal: Double?, val protein: Double?, val carbs: Double?,
            val fat: Double?, val satFat: Double?, val freeSugars: Double?,
        )
        val samplesByKey = HashMap<String, MutableList<Sample>>()

        for (food in foods) {
            val ingredients = parseIngredients(food.ingredientsJson)
            for (ing in ingredients) {
                val w = ing.weightGrams
                if (w <= 0) continue
                val key = normalize(ing.name)
                if (key.isEmpty()) continue
                val r = 100.0 / w
                val kcal100 = ing.calories?.toDouble()?.let { it * r }
                val protein100 = ing.protein?.let { it * r }
                val carbs100 = ing.carbs?.let { it * r }
                val fat100 = ing.fat?.let { it * r }

                // Reject implausible per-100g samples BEFORE they enter the
                // median. A past entry with a mis-estimated weight (e.g. a
                // 210g coffee logged as 10g) produces a per-100g value that
                // would otherwise poison the cluster. This is the fix for the
                // "café = 340 kcal/100g, 24g protein" corruption.
                if (kcal100 != null) {
                    val gate = MacroPlausibilityGate.checkPer100g(
                        kcal = kcal100,
                        protein = protein100 ?: 0.0,
                        carbs = carbs100 ?: 0.0,
                        fat = fat100 ?: 0.0,
                    )
                    if (gate is MacroPlausibilityGate.Result.Suspicious) continue
                }

                samplesByKey.getOrPut(key) { mutableListOf() }.add(
                    Sample(
                        displayName = ing.name,
                        kcal      = kcal100,
                        protein   = protein100,
                        carbs     = carbs100,
                        fat       = fat100,
                        satFat    = ing.saturatedFatG?.let { it * r },
                        freeSugars = ing.freeSugarsG?.let { it * r },
                    )
                )
            }
        }

        return samplesByKey.mapNotNull { (key, samples) ->
            val kcal = median(samples.mapNotNull { it.kcal }) ?: return@mapNotNull null
            IngredientCluster(
                normalizedName    = key,
                // Most frequent display name wins; ties broken by most recent (last).
                displayName       = samples.groupingBy { it.displayName }.eachCount()
                    .maxByOrNull { it.value }?.key ?: samples.last().displayName,
                per100gCalories   = kcal,
                per100gProtein    = median(samples.mapNotNull { it.protein }) ?: 0.0,
                per100gCarbs      = median(samples.mapNotNull { it.carbs }) ?: 0.0,
                per100gFat        = median(samples.mapNotNull { it.fat }) ?: 0.0,
                per100gSatFat     = median(samples.mapNotNull { it.satFat }) ?: 0.0,
                per100gFreeSugars = median(samples.mapNotNull { it.freeSugars }) ?: 0.0,
                occurrences       = samples.size,
                source            = "history",
            )
        }
    }

    companion object {
        fun normalize(text: String): String {
            val nfd = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            val sb = StringBuilder()
            for (ch in nfd) if (ch.isLetterOrDigit() || ch == ' ') sb.append(ch)
            return sb.toString().trim().replace(Regex("\\s+"), " ")
        }

        /** Median of a list; null if empty. */
        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid]
            else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
