package com.example.data.nutrition

/**
 * Normalised per-100g nutrition data returned by any source in the lookup cascade.
 */
data class NutritionData(
    val displayName: String,
    val per100gCalories: Double,
    val per100gProtein: Double,
    val per100gCarbs: Double,
    val per100gFat: Double,
    val per100gSatFat: Double,
    val per100gFreeSugars: Double,
    /** e.g. "insa", "usda:12345", "off:737628064502", "user", "gemini" */
    val source: String,
    /** "verified" | "api" | "llm" */
    val confidence: String,
)
