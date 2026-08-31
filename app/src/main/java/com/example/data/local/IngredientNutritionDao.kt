package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IngredientNutritionDao {

    /** Insert or replace. Called after a successful external lookup. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: IngredientNutritionEntity)

    /** Exact normalised-name lookup — O(log n) via the unique index. */
    @Query("SELECT * FROM ingredient_nutrition_cache WHERE normalizedName = :key LIMIT 1")
    suspend fun findByNormalizedName(key: String): IngredientNutritionEntity?

    /** Increment usage counter after each successful cache hit. */
    @Query("UPDATE ingredient_nutrition_cache SET timesUsed = timesUsed + 1, updatedAtMs = :nowMs WHERE normalizedName = :key")
    suspend fun incrementUsed(key: String, nowMs: Long)

    /** Mark as user-verified (highest confidence). Called when user edits macros. */
    @Query("""
        UPDATE ingredient_nutrition_cache
        SET source = 'user', confidence = 'verified',
            per100gCalories = :kcal, per100gProtein = :protein,
            per100gCarbs = :carbs, per100gFat = :fat,
            per100gSatFat = :satFat, per100gFreeSugars = :freeSugars,
            updatedAtMs = :nowMs
        WHERE normalizedName = :key
    """)
    suspend fun markUserVerified(
        key: String,
        kcal: Double, protein: Double, carbs: Double,
        fat: Double, satFat: Double, freeSugars: Double,
        nowMs: Long,
    )

    @Query("SELECT COUNT(*) FROM ingredient_nutrition_cache")
    suspend fun count(): Int

    @Query("SELECT * FROM ingredient_nutrition_cache ORDER BY timesUsed DESC")
    suspend fun getAll(): List<IngredientNutritionEntity>
}
