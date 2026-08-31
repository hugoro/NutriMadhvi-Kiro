package com.example.data.activity

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.DailyActivitySnapshotEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Resolves "active calories burned" per day in a hybrid model:
 *
 *   - **Today (and any day with no snapshot yet)**: prefer Samsung Health's own
 *     daily aggregate `TOTAL_ACTIVE_CALORIES_BURNED` (the value the SH home tile
 *     shows), falling back to `workouts.sum` when the aggregate is missing/zero
 *     and finally to `workouts.sum` when neither is available.
 *   - **Past days with a snapshot**: use [DailyActivitySnapshotEntity.activeKcal]
 *     verbatim. The snapshot is canonical; we never re-derive against fresh SH
 *     data because Samsung Health back-fills/edits/shifts workout records
 *     retroactively on some devices (the Pixel 10 in particular).
 *
 * ## Why prefer the aggregate over a manual formula
 *
 * The previous implementation here used a hand-rolled `workouts.sum + steps × 0.015`
 * formula calibrated on two low-step days (817 and 6.069 passos). On high-step days
 * (12k+ passos) the casual term grew linearly and double-counted steps taken inside
 * workout sessions — observed app 776 vs SH tile 647 on 2026-06-27 (+20%). The SH
 * aggregate is what the user sees in the Samsung Health home/Activity screen, so it
 * is by definition the source of truth for "active calories today".
 *
 * ## Fallback semantics when the aggregate is zero
 *
 * Some devices/days return 0 for the aggregate even when workouts exist (observed in
 * older memory notes on the Pixel 10). When that happens, we use `workouts.sum` as
 * a floor — it is what the user actually exercised, even if SH hasn't rolled it up
 * into the day's "active calories" bin yet. We do NOT re-add a step-based casual
 * term; that's what caused the regression we're fixing.
 *
 * The shape mirrors [com.example.data.bmr.BmrResolver]: pure object, no DB or SDK
 * access from inside. Callers pass the inputs the DAOs already gave them.
 */
object DailyActivityResolver {

    /** Aggregated active calories summed across each day of a range, with per-day
     *  origin (`fromSnapshot=true` means the day was served from the frozen
     *  snapshot table). */
    data class AggregatedActivity(
        val kcal: Long,
        val days: Int,
        val snapshotDays: Int,
        val liveDays: Int
    ) {
        companion object {
            val EMPTY = AggregatedActivity(0L, 0, 0, 0)
        }
    }

    /**
     * Compute live "active kcal" for a single day. Used by the worker when
     * freezing D-1, and by the resolver as the fallback for days without a
     * snapshot.
     *
     * Order of preference:
     *   1. `dailySummary.activeCalories` (the SH aggregate — matches the tile).
     *   2. `workouts.sum` when the aggregate is zero/missing.
     *
     * Active minutes always come from the workouts list (sum of session
     * durations) — that's what the dashboard shows alongside the kcal value.
     */
    fun computeLiveKcalForDay(
        day: LocalDate,
        workouts: List<ShWorkout>,
        dailySummary: ShDailyActivity?,
        stepsForDay: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): LiveActivityForDay {
        var workoutsKcal = 0
        var activeMin = 0
        for (w in workouts) {
            val d = w.startTime.atZone(zoneId).toLocalDate()
            if (d == day) {
                workoutsKcal += w.activeKcal ?: 0
                activeMin += java.time.Duration.between(w.startTime, w.endTime)
                    .toMinutes().toInt().coerceAtLeast(0)
            }
        }
        val aggregateKcal = dailySummary?.activeCalories?.toInt() ?: 0
        val activeKcal = when {
            aggregateKcal > 0 -> aggregateKcal
            else -> workoutsKcal // fallback only when SH hasn't rolled up yet
        }
        return LiveActivityForDay(
            day = day,
            workoutsKcal = workoutsKcal,
            aggregateKcal = aggregateKcal,
            activeKcal = activeKcal,
            steps = stepsForDay,
            activeMinutes = activeMin
        )
    }

    data class LiveActivityForDay(
        val day: LocalDate,
        /** Sum of `ShWorkout.activeKcal` on this day. Diagnostic — actual UI value is [activeKcal]. */
        val workoutsKcal: Int,
        /** `TOTAL_ACTIVE_CALORIES_BURNED` aggregate from SH for this day, or 0 if missing. Diagnostic. */
        val aggregateKcal: Int,
        /** Final value shown to the user. Equal to [aggregateKcal] when > 0, else [workoutsKcal]. */
        val activeKcal: Int,
        val steps: Long,
        val activeMinutes: Int
    )

    /**
     * Sum of per-day active kcal across [range].
     *
     * For days within the range:
     *   - If a snapshot exists for that day → use it verbatim.
     *   - Otherwise → compute live (aggregate-preferred) from the SDK data
     *     that was passed in.
     *
     * @param snapshots All known frozen snapshots. Will be filtered to those in range.
     * @param workouts All workouts visible right now (for the live fallback).
     * @param dailySummaryByDate `readDailyActivitySummary` result — the SH aggregate per day.
     * @param stepsByDay Steps per day from SH. Diagnostic only now (not in the formula).
     * @param range Inclusive date range.
     */
    fun aggregateOverRange(
        snapshots: List<DailyActivitySnapshotEntity>,
        workouts: List<ShWorkout>,
        dailySummaryByDate: Map<LocalDate, ShDailyActivity>,
        stepsByDay: Map<LocalDate, Long>,
        range: ClosedRange<LocalDate>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AggregatedActivity {
        if (range.start.isAfter(range.endInclusive)) return AggregatedActivity.EMPTY

        val byDay = snapshots.associateBy { LocalDate.ofEpochDay(it.dateEpochDay) }
        var kcal = 0L
        var snapshotDays = 0
        var liveDays = 0
        var day = range.start
        val end = range.endInclusive
        while (!day.isAfter(end)) {
            val snap = byDay[day]
            if (snap != null) {
                kcal += snap.activeKcal
                snapshotDays++
            } else {
                val steps = stepsByDay[day] ?: 0L
                val summary = dailySummaryByDate[day]
                val live = computeLiveKcalForDay(day, workouts, summary, steps, zoneId)
                kcal += live.activeKcal
                liveDays++
            }
            day = day.plusDays(1)
        }
        return AggregatedActivity(
            kcal = kcal,
            days = snapshotDays + liveDays,
            snapshotDays = snapshotDays,
            liveDays = liveDays
        )
    }
}
