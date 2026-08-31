package com.example.ui

import com.example.data.local.FoodEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-day macro bucket used by the Reports tab's "Evolução" chart (stacked bars).
 *
 * One entry per day in the report range — INCLUDING days with no logs (`kcal == 0`,
 * `mealCount == 0`). The chart paints an empty-day pill on those columns so the
 * user sees the day exists rather than disappearing it. This is the entire reason
 * we don't just `groupBy` the food list: a `groupBy` would drop empty days.
 *
 * Same `Instant.ofEpochMilli(...).atZone(zoneId).toLocalDate()` bucketing the
 * dashboard's weekly chart uses, so day boundaries don't diverge between the
 * Dashboard and Reports tabs.
 */
data class DailyMacroBucket(
    val date: LocalDate,
    val kcal: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val mealCount: Int
)

/**
 * Walk [range] day-by-day and bucket [foods] (already filtered to the same range
 * by the caller) into one [DailyMacroBucket] per day.
 *
 * @param foods Already-filtered food entries for the period. Items outside [range]
 *              are silently dropped — defensive, in case the caller passes a wider
 *              list.
 * @param range Inclusive `start..endInclusive`. Empty range returns empty list.
 * @param zoneId Time-zone used to bucket epoch-millis timestamps to LocalDate.
 *               Default `ZoneId.systemDefault()` matches `ChatScreen.kt` filtering.
 */
fun buildDailyMacroBuckets(
    foods: List<FoodEntry>,
    range: ClosedRange<LocalDate>,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<DailyMacroBucket> {
    if (range.start.isAfter(range.endInclusive)) return emptyList()

    val byDate: Map<LocalDate, List<FoodEntry>> = foods
        .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }

    val out = ArrayList<DailyMacroBucket>()
    var day = range.start
    val end = range.endInclusive
    while (!day.isAfter(end)) {
        val bucket = byDate[day]
        if (bucket.isNullOrEmpty()) {
            out += DailyMacroBucket(date = day, kcal = 0, protein = 0.0, carbs = 0.0, fat = 0.0, mealCount = 0)
        } else {
            out += DailyMacroBucket(
                date = day,
                kcal = bucket.sumOf { it.calories },
                protein = bucket.sumOf { it.protein },
                carbs = bucket.sumOf { it.carbs },
                fat = bucket.sumOf { it.fat },
                mealCount = bucket.size
            )
        }
        day = day.plusDays(1)
    }
    return out
}
