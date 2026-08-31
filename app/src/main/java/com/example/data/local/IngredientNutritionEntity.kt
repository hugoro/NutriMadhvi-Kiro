package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of per-100g nutrition data for individual ingredients.
 *
 * Built up organically: first time an ingredient is seen, looked up from
 * INSA PT (embedded asset) → USDA FoodData Central → Open Food Facts,
 * then stored here. Subsequent analyses read from cache — zero network calls
 * for known ingredients.
 *
 * [source] encodes the origin:
 *   "insa"          — INSA Tabela da Composição de Alimentos (Portuguese DB, embedded)
 *   "usda:12345"    — USDA FoodData Central, FDC ID
 *   "off:737628..."  — Open Food Facts, barcode/ID
 *   "user"          — user manually corrected the value in the app
 *   "gemini"        — LLM estimate (kept to avoid re-querying APIs for unknown ingredients)
 *
 * [confidence]:
 *   "verified" — INSA or user-corrected (trusted reference data)
 *   "api"      — USDA or OFF lookup (reliable but not officially PT-verified)
 *   "llm"      — Gemini estimate (fallback only; never preferred over api/verified)
 */
@Entity(
    tableName = "ingredient_nutrition_cache",
    indices = [Index("normalizedName", unique = true)]
)
data class IngredientNutritionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Lookup key: lowercase, accent-stripped, trimmed (e.g. "azeite virgem extra") */
    val normalizedName: String,
    /** Display name as originally provided by the LLM or user */
    val displayName: String,
    val per100gCalories: Double,
    val per100gProtein: Double,
    val per100gCarbs: Double,
    val per100gFat: Double,
    val per100gSatFat: Double,
    val per100gFreeSugars: Double,
    val source: String,
    val confidence: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val timesUsed: Int = 0,
)
