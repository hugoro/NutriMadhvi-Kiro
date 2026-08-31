package com.example.data.local

import androidx.room.TypeConverter

class Converters {
    // Two pairs of converters, distinguished by nullability — Room picks the
    // right one based on the column's declared type. The non-null pair is used
    // for FoodEntry.mealCategory (defaults to SNACK to be defensive against bad
    // legacy data); the nullable pair is used for FoodHabit.mealCategoryHint
    // and must round-trip null faithfully — otherwise an "Any category" habit
    // is silently rewritten to Snack on every save.
    @TypeConverter
    fun fromMealCategory(value: MealCategory): String {
        return value.name
    }

    @TypeConverter
    fun toMealCategory(value: String): MealCategory {
        return try {
            MealCategory.valueOf(value)
        } catch (e: Exception) {
            MealCategory.SNACK
        }
    }

    @TypeConverter
    fun fromMealCategoryNullable(value: MealCategory?): String? {
        return value?.name
    }

    @TypeConverter
    fun toMealCategoryNullable(value: String?): MealCategory? {
        if (value == null) return null
        return try {
            MealCategory.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromActivityType(value: ActivityType?): String {
        return value?.name ?: ActivityType.OTHER.name
    }

    @TypeConverter
    fun toActivityType(value: String?): ActivityType {
        return try {
            if (value != null) ActivityType.valueOf(value) else ActivityType.OTHER
        } catch (e: Exception) {
            ActivityType.OTHER
        }
    }

    /** List<Long> stored as comma-separated string. Null and empty list both map to null. */
    @TypeConverter
    fun fromLongList(value: List<Long>?): String? {
        if (value.isNullOrEmpty()) return null
        return value.joinToString(",")
    }

    @TypeConverter
    fun toLongList(value: String?): List<Long>? {
        if (value.isNullOrBlank()) return null
        return value.split(",").mapNotNull { it.trim().toLongOrNull() }
    }
}

