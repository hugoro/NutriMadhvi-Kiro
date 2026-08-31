package com.example.data.notifications

import com.example.data.health.ShDailyActivity
import com.example.data.local.FoodDao
import com.example.data.local.MealCategory
import com.example.data.local.MealCategoryFromTime
import com.example.data.local.TargetSnapshotDao
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What the proactive engine might want to fire **right now**.
 *
 * Each variant carries:
 *  - [dedupKey] — opaque string fed to [NotificationDedupStore] so we never
 *    send the same nudge twice in a window where the rule would still match.
 *  - [sessionTag] — value to stamp on `ChatSession.proactiveTag` so multiple
 *    nudges of the same flavour land in the same chat. `null` means "always
 *    create a brand-new session".
 *
 * Key conventions are documented at the top of
 * [NotificationDedupStore]; keep both in sync if you tweak them.
 */
sealed class Trigger(val dedupKey: String, val sessionTag: String?) {

    /** No log yet for the meal whose hour-of-day window is open right now. */
    class MealMissing(
        val category: MealCategory,
        val isoDate: LocalDate,
    ) : Trigger(
        dedupKey = "meal_missing:${category.name}:$isoDate",
        sessionTag = "meals:$isoDate",
    )

    /** Samsung Health rate-of-burn this hour is ≥ 2× yesterday's hourly average. */
    class ActivitySpike(
        val deltaKcal: Int,
        val isoDate: LocalDate,
        val hour: Int,
        val isoWeekTag: String,
    ) : Trigger(
        dedupKey = "activity_spike:$isoDate-$hour",
        sessionTag = isoWeekTag,
    )

    /** Late in the day and both kcal AND protein are under-consumed vs target. */
    class MacrosAtRisk(
        val kcalPct: Int,
        val proteinPct: Int,
        val isoDate: LocalDate,
    ) : Trigger(
        dedupKey = "macros_at_risk:$isoDate",
        sessionTag = null,
    )

    /** End-of-day recap window (20–22 h). */
    class DailySummary(
        val isoDate: LocalDate,
    ) : Trigger(
        dedupKey = "daily_summary:$isoDate",
        sessionTag = null,
    )
}

/**
 * Pure-Kotlin trigger logic for the proactive notification worker.
 *
 * No `Context`, no `Log`, no `NotificationManager` — only DAO calls and
 * data passed in by the caller. That keeps it unit-testable with mocked
 * DAOs and lets the worker (Bloco G) own all Android plumbing.
 *
 * [evaluate] tries each rule in fixed priority order and returns the first
 * matching [Trigger], or `null` if nothing fires. Each rule is skipped
 * silently when its per-toggle pref is `false` or when [dedup] reports the
 * key as already-sent.
 *
 * Priority (highest → lowest):
 *   1. [Trigger.MealMissing]
 *   2. [Trigger.ActivitySpike]
 *   3. [Trigger.MacrosAtRisk]
 *   4. [Trigger.DailySummary]
 */
object TriggerEvaluator {

    /** Hour-of-day window we evaluate the missing-meal rule in.
     *  10:00 covers breakfast, 15:00 covers lunch, 22:00 covers dinner. */
    private val MEAL_CHECK_HOURS = setOf(10, 15, 22)

    suspend fun evaluate(
        nowZdt: ZonedDateTime,
        prefs: NotificationPrefs,
        dedup: (String) -> Boolean,
        foodDao: FoodDao,
        targetSnapshotDao: TargetSnapshotDao,
        shStepsByDate: Map<LocalDate, Long>,
        shDailyActivity: Map<LocalDate, ShDailyActivity>,
    ): Trigger? {
        if (!prefs.masterEnabled) return null

        val today = nowZdt.toLocalDate()
        val hour = nowZdt.hour
        val zone = nowZdt.zone

        // 1) MealMissing
        if (prefs.mealMissingEnabled && hour in MEAL_CHECK_HOURS) {
            val (category, windowStart, windowEnd) = when (hour) {
                10 -> Triple(MealCategory.BREAKFAST, 6, 10)
                15 -> Triple(MealCategory.LUNCH, 12, 15)
                else -> Triple(MealCategory.DINNER, 18, 22)
            }
            val candidate = Trigger.MealMissing(category, today)
            if (!dedup(candidate.dedupKey)) {
                val startMs = today.atTime(windowStart, 0).atZone(zone).toInstant().toEpochMilli()
                val endMs = today.atTime(windowEnd, 0).atZone(zone).toInstant().toEpochMilli()
                val foods = foodDao.getFoodsBetween(startMs, endMs)
                val hasMeal = foods.any { entry ->
                    MealCategoryFromTime.classify(entry.timestamp, entry.mealCategory) == category
                }
                if (!hasMeal) return candidate
            }
        }

        // 2) ActivitySpike
        if (prefs.activitySpikeEnabled) {
            val isoWeek = today.get(WeekFields.ISO.weekOfWeekBasedYear())
            val isoWeekYear = today.get(WeekFields.ISO.weekBasedYear())
            val isoWeekTag = String.format(
                Locale.ROOT,
                "exercise:%d-W%02d",
                isoWeekYear,
                isoWeek,
            )
            val candidate = Trigger.ActivitySpike(
                deltaKcal = 0, // filled below if we fire
                isoDate = today,
                hour = hour,
                isoWeekTag = isoWeekTag,
            )
            if (!dedup(candidate.dedupKey)) {
                val todayAct = shDailyActivity[today]
                val yesterdayAct = shDailyActivity[today.minusDays(1)]
                if (todayAct != null && yesterdayAct != null) {
                    val hoursSoFarToday = (hour + 1).coerceAtLeast(1)
                    val todayHourlyRate = todayAct.activeCalories / hoursSoFarToday.toFloat()
                    val yesterdayHourlyRate = yesterdayAct.activeCalories / 24f
                    if (todayHourlyRate >= yesterdayHourlyRate * 2f &&
                        todayAct.activeCalories > 150f
                    ) {
                        val deltaKcal = (todayHourlyRate - yesterdayHourlyRate)
                            .coerceAtLeast(0f)
                            .toInt()
                        return Trigger.ActivitySpike(
                            deltaKcal = deltaKcal,
                            isoDate = today,
                            hour = hour,
                            isoWeekTag = isoWeekTag,
                        )
                    }
                }
            }
        }

        // 3) MacrosAtRisk
        if (prefs.macrosAtRiskEnabled && hour >= 18) {
            val candidate = Trigger.MacrosAtRisk(
                kcalPct = 0,
                proteinPct = 0,
                isoDate = today,
            )
            if (!dedup(candidate.dedupKey)) {
                val snap = targetSnapshotDao.findEffectiveAt(today.toEpochDay())
                if (snap != null && snap.targetKcal > 0 && snap.targetProtein > 0.0) {
                    val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
                    val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val foods = foodDao.getFoodsBetween(startMs, endMs)
                    val kcalConsumed = foods.sumOf { it.calories.toLong() }
                    val proteinConsumed = foods.sumOf { it.protein }
                    val kcalPct = ((kcalConsumed * 100.0) / snap.targetKcal).toInt()
                    val proteinPct = ((proteinConsumed * 100.0) / snap.targetProtein).toInt()
                    if (kcalPct < 60 && proteinPct < 50) {
                        return Trigger.MacrosAtRisk(
                            kcalPct = kcalPct,
                            proteinPct = proteinPct,
                            isoDate = today,
                        )
                    }
                }
            }
        }

        // 4) DailySummary
        if (prefs.dailySummaryEnabled && hour in 20..21) {
            val candidate = Trigger.DailySummary(today)
            if (!dedup(candidate.dedupKey)) return candidate
        }

        // Touch unused params so a future reader sees they are part of the
        // contract even though current rules don't consume them. Bloco E /
        // Bloco G will pass them on to the prompt builder.
        @Suppress("UNUSED_EXPRESSION") shStepsByDate

        return null
    }
}
