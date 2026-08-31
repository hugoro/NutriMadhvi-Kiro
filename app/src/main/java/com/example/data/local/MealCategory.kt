package com.example.data.local

enum class MealCategory(val displayKey: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    SNACK("Snack"),
    DINNER("Dinner"),
    DESSERT("Dessert");

    companion object {
        fun parse(raw: String?): MealCategory {
            if (raw == null) return SNACK
            val trimmed = raw.trim().lowercase()
            return when {
                // DESSERT first to avoid being shadowed by other rules.
                // Keep the keyword set narrow ("dessert"/"sobremesa"/"મીઠાઈ") to avoid
                // false positives like "batata doce" landing as dessert.
                trimmed.contains("dessert") || trimmed.contains("sobremesa") || trimmed.contains("મીઠાઈ") -> DESSERT
                trimmed.contains("breakfast") || trimmed.contains("pequeno") || trimmed.contains("pax") || trimmed.contains("manh") || trimmed.contains("નાસ્તો") -> BREAKFAST
                trimmed.contains("lunch") || trimmed.contains("almoço") || trimmed.contains("bhojan") || trimmed.contains("બપોર") -> LUNCH
                trimmed.contains("dinner") || trimmed.contains("jantar") || trimmed.contains("sanj") || trimmed.contains("સાંજ") -> DINNER
                trimmed.contains("snack") || trimmed.contains("lanche") || trimmed.contains("gusto") || trimmed.contains("નાસ્તો") -> SNACK
                else -> {
                    entries.find { it.name.lowercase() == trimmed } ?: SNACK
                }
            }
        }
    }
}
