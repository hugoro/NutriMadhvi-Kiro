package com.example.data.chat

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.ActivityType
import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import com.example.data.local.TargetSnapshot
import com.example.data.local.TrainingBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 7 — unit tests for [NutritionTools], the pure deterministic nutrition
 * tool seam. Plain JVM, hand-built rows, fixed UTC zone + fixed dates so the
 * arithmetic is fully determined (no `System.currentTimeMillis()`, no Mockito —
 * not on the classpath). Each test proves one contract the Phase 8 agentic
 * layer will rely on, and the edge cases where a naive implementation diverges
 * from the app's established arithmetic (avg-on-logged-days, budget sign,
 * single-reading no-trend, per-bucket rollup, defensive window-filtering).
 */
class NutritionToolsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 9, 8)

    private fun ms(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun window(from: LocalDate, to: LocalDate, label: String = "test") =
        DateWindow(from = from, to = to, label = label)

    private fun food(
        date: LocalDate,
        kcal: Int,
        p: Double = 0.0,
        c: Double = 0.0,
        f: Double = 0.0,
        hour: Int = 12,
    ) = FoodEntry(
        name = "meal",
        calories = kcal,
        protein = p,
        carbs = c,
        fat = f,
        timestamp = ms(date, hour),
        mealCategory = MealCategory.SNACK,
    )

    private fun target(kcal: Int) = TargetSnapshot(
        effectiveDateEpochDay = 0,
        targetKcal = kcal,
        targetProtein = 100.0,
        targetCarbs = 200.0,
        targetFat = 60.0,
        targetSteps = 8000,
        targetActiveMinutes = 30,
        targetActivityCalories = 400,
    )

    private fun bodyComp(
        date: LocalDate,
        weight: Float,
        fat: Float? = null,
        muscle: Float? = null,
    ) = BodyCompositionSnapshotEntity(
        sourceTimestampMs = ms(date),
        weightKg = weight,
        bodyFatPct = fat,
        muscleMassKg = muscle,
    )

    private fun workout(date: LocalDate, type: ActivityType, minutes: Long, kcal: Int?) = ShWorkout(
        recordId = "r-${date}-${type.name}-$minutes",
        type = type,
        sourceLabel = type.name,
        startTime = date.atTime(9, 0).atZone(zone).toInstant(),
        endTime = date.atTime(9, 0).plusMinutes(minutes).atZone(zone).toInstant(),
        title = null,
        notes = null,
        activeKcal = kcal,
    )

    // === getCalorieSummary =================================================

    @Test
    fun calorieSummary_totalsAndAverages() {
        // 3 days in a 7-day window, 2 with logs (day 8 has two entries).
        val foods = listOf(
            food(today, 500), food(today, 300),        // 800 on today
            food(today.minusDays(2), 600),             // 600 two days ago
        )
        val w = window(today.minusDays(6), today)      // 7 days
        val r = NutritionTools.getCalorieSummary(foods, w, zone = zone)

        assertEquals(1400, r.totalKcal)
        assertEquals(3, r.entryCount)
        assertEquals(2, r.daysWithLogs)
        assertEquals(7, r.windowDays)
        assertEquals(1400 / 7, r.perDayAvg)            // per window day = 200
        assertEquals(1400 / 2, r.avgOnLoggedDays)      // per logged day = 700
        assertNull(r.vsTargetDelta)                    // no target passed
    }

    @Test
    fun calorieSummary_targetDeltaSign() {
        val foods = listOf(food(today, 2500))
        val w = window(today, today)                   // 1 day
        val over = NutritionTools.getCalorieSummary(foods, w, target(2000), zone)
        assertEquals(2000, over.targetKcalPerDay)
        assertEquals(500, over.vsTargetDelta)          // +500 over budget
        assertTrue(over.toContextBlock().contains("over budget"))

        val under = NutritionTools.getCalorieSummary(listOf(food(today, 1500)), w, target(2000), zone)
        assertEquals(-500, under.vsTargetDelta)        // under budget
        assertTrue(under.toContextBlock().contains("under budget"))
    }

    @Test
    fun calorieSummary_emptyWindow() {
        val r = NutritionTools.getCalorieSummary(emptyList(), window(today, today), zone = zone)
        assertEquals(0, r.totalKcal)
        assertEquals(0, r.daysWithLogs)
        assertEquals(0, r.avgOnLoggedDays)             // no divide-by-zero
        assertTrue(r.toContextBlock().contains("No meals logged"))
    }

    @Test
    fun calorieSummary_filtersOutsideWindowDefensively() {
        // Caller hands a wider list than the window; tool must still be correct.
        val foods = listOf(
            food(today, 500),                          // in window
            food(today.minusDays(30), 9999),           // way outside
        )
        val r = NutritionTools.getCalorieSummary(foods, window(today, today), zone = zone)
        assertEquals(500, r.totalKcal)
        assertEquals(1, r.entryCount)
    }

    // === getMacroSummary ===================================================

    @Test
    fun macroSummary_totalsPerDayAndSplit() {
        // Two logged days: pure-protein day + pure-fat day.
        val foods = listOf(
            food(today, 400, p = 100.0),               // 100g protein = 400 macro-kcal
            food(today.minusDays(1), 900, f = 100.0),  // 100g fat = 900 macro-kcal
        )
        val w = window(today.minusDays(6), today)
        val r = NutritionTools.getMacroSummary(foods, w, zone)

        assertEquals(100.0, r.proteinG, 0.001)
        assertEquals(100.0, r.fatG, 0.001)
        assertEquals(0.0, r.carbsG, 0.001)
        assertEquals(2, r.daysWithLogs)
        assertEquals(50.0, r.proteinPerDay, 0.001)     // 100g / 2 logged days
        // Energy split: 400 protein-kcal + 900 fat-kcal = 1300; ~30% / 0% / ~69%.
        assertEquals(30, r.proteinPct)
        assertEquals(0, r.carbsPct)
        assertEquals(69, r.fatPct)
    }

    @Test
    fun macroSummary_emptyIsSafe() {
        val r = NutritionTools.getMacroSummary(emptyList(), window(today, today), zone)
        assertEquals(0.0, r.proteinG, 0.001)
        assertEquals(0, r.proteinPct)
        assertTrue(r.toContextBlock().contains("No meals logged"))
    }

    // === getWeightTrend ====================================================

    @Test
    fun weightTrend_deltaSlopeAndSeries() {
        // 80.0 → 79.0 over 10 days (three readings), newest-first as the DAO returns.
        val history = listOf(
            bodyComp(today, 79.0f, fat = 20.0f),
            bodyComp(today.minusDays(5), 79.5f),
            bodyComp(today.minusDays(10), 80.0f, fat = 22.0f),
        )
        val w = window(today.minusDays(30), today)
        val r = NutritionTools.getWeightTrend(history, w, zone)

        assertEquals(3, r.readingCount)
        assertEquals(80.0f, r.firstWeightKg)
        assertEquals(79.0f, r.lastWeightKg)
        assertEquals(-1.0f, r.deltaKg!!, 0.001f)
        assertEquals(-1.0f / 10f, r.slopeKgPerDay!!, 0.001f)   // over the observed 10-day span
        // Series chronological (newest last) with lean-mass derived where bf present.
        assertEquals(today.minusDays(10), r.series.first().date)
        assertEquals(today, r.series.last().date)
        val lean = r.series.last().leanMassKg!!
        assertEquals(79.0f * (1f - 20f / 100f), lean, 0.001f)  // 63.2
    }

    @Test
    fun weightTrend_singleReadingNoTrend() {
        val r = NutritionTools.getWeightTrend(listOf(bodyComp(today, 80.0f)), window(today, today), zone)
        assertEquals(1, r.readingCount)
        assertNull(r.deltaKg)
        assertNull(r.slopeKgPerDay)
        assertTrue(r.toContextBlock().contains("no trend"))
    }

    @Test
    fun weightTrend_dedupsSameDayLatestWins() {
        // Two readings same day: latest-of-day (later timestamp) must win.
        val history = listOf(
            bodyComp(today, 79.0f).copy(sourceTimestampMs = ms(today, hour = 20)),  // evening
            bodyComp(today, 81.0f).copy(sourceTimestampMs = ms(today, hour = 6)),   // morning
        )
        val r = NutritionTools.getWeightTrend(history, window(today, today), zone)
        assertEquals(1, r.readingCount)                 // one row per day
        assertEquals(79.0f, r.lastWeightKg)             // evening reading won
    }

    @Test
    fun weightTrend_ignoresNullTimestamp() {
        val history = listOf(
            bodyComp(today, 79.0f),
            BodyCompositionSnapshotEntity(sourceTimestampMs = null, weightKg = 200.0f),
        )
        val r = NutritionTools.getWeightTrend(history, window(today.minusDays(6), today), zone)
        assertEquals(1, r.readingCount)                 // null-ts row excluded from timeline
        assertEquals(79.0f, r.lastWeightKg)
    }

    // === getActivitySummary ================================================

    @Test
    fun activitySummary_combinesSourcesAndBuckets() {
        val w = window(today.minusDays(6), today)
        val steps = mapOf(today to 10000L, today.minusDays(1) to 5000L)
        val daily = mapOf(
            today to ShDailyActivity(today, Duration.ofMinutes(40), 500f, 2000f, 3000f),
            today.minusDays(1) to ShDailyActivity(today.minusDays(1), Duration.ofMinutes(20), 250f, 1800f, 1500f),
        )
        val workouts = listOf(
            workout(today, ActivityType.RUNNING, 30, 300),
            workout(today.minusDays(1), ActivityType.STRENGTH, 45, 200),
        )
        val manual = listOf(
            ActivityEntry(name = "walk", durationMinutes = 15, caloriesBurned = 60, timestamp = ms(today)),
        )
        val r = NutritionTools.getActivitySummary(w, manual, steps, daily, workouts, zone)

        assertEquals(15000L, r.totalSteps)
        assertEquals(750, r.shActiveKcal)               // 500 + 250
        assertEquals(60L, r.shActiveMinutes)            // 40 + 20
        assertEquals(4500, r.shDistanceMeters)          // 3000 + 1500
        assertEquals(2, r.shDaysWithData)
        assertEquals(2, r.workoutSessions)
        assertEquals(1, r.manualEntries)
        assertEquals(15L, r.manualMinutes)
        assertEquals(60, r.manualKcal)

        // Bucket rollup: RUNNING→CARDIO, STRENGTH→STRENGTH.
        val cardio = r.buckets.first { it.bucket == TrainingBucket.CARDIO }
        assertEquals(1, cardio.sessions)
        assertEquals(30L, cardio.minutes)
        assertEquals(300, cardio.activeKcal)
        val strength = r.buckets.first { it.bucket == TrainingBucket.STRENGTH }
        assertEquals(45L, strength.minutes)
    }

    @Test
    fun activitySummary_emptyIsSafe() {
        val r = NutritionTools.getActivitySummary(window(today, today), zone = zone)
        assertEquals(0L, r.totalSteps)
        assertEquals(0, r.workoutSessions)
        assertTrue(r.buckets.isEmpty())
        assertTrue(r.toContextBlock().contains("No activity recorded"))
    }

    @Test
    fun activitySummary_filtersWorkoutsOutsideWindow() {
        val w = window(today, today)
        val workouts = listOf(
            workout(today, ActivityType.RUNNING, 30, 300),
            workout(today.minusDays(10), ActivityType.RUNNING, 60, 600),  // outside
        )
        val r = NutritionTools.getActivitySummary(w, shWorkouts = workouts, zone = zone)
        assertEquals(1, r.workoutSessions)
        assertEquals(30L, r.buckets.first { it.bucket == TrainingBucket.CARDIO }.minutes)
    }

    @Test
    fun contextBlocks_nonEmptyAndLabelled() {
        // Sanity: every renderer includes the window label so the LLM can anchor.
        val w = window(today, today, label = "hoje")
        assertTrue(NutritionTools.getCalorieSummary(listOf(food(today, 500)), w, zone = zone).toContextBlock().contains("hoje"))
        assertTrue(NutritionTools.getMacroSummary(listOf(food(today, 500, p = 10.0)), w, zone).toContextBlock().contains("hoje"))
        assertTrue(NutritionTools.getWeightTrend(listOf(bodyComp(today, 80f)), w, zone).toContextBlock().contains("hoje"))
        assertNotNull(NutritionTools.getActivitySummary(w, zone = zone).toContextBlock())
    }
}
