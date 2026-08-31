package com.example.data.targets

import com.example.data.local.TargetSnapshot
import java.time.LocalDate

/**
 * Pure helpers that turn the append-only list of [TargetSnapshot]s into a "what was
 * the prescribed target on day D" lookup, plus an aggregator that sums day-by-day
 * across a date range.
 *
 * Both walk the snapshot list once, in O(N + M) where N is the number of snapshots
 * and M the number of days in the range. Callers pass the list as-is; no DB access
 * happens here. The expectation from [com.example.data.local.TargetSnapshotDao] is
 * that the list is sorted by [TargetSnapshot.effectiveDateEpochDay] ascending.
 */
object TargetResolver {

    /** Aggregated targets summed across each day of a range. Replaces the old
     *  `userProfile.target* × daysMultiplier` shorthand on the dashboard, the
     *  reports tab, and the exporters. Days with no covering snapshot contribute
     *  zero (defensive — should not happen once MIGRATION_8_9 has run). */
    data class AggregatedTargets(
        val kcal: Long,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val steps: Long,
        val activeMinutes: Long,
        val activityCalories: Long,
        val days: Int
    ) {
        companion object {
            val EMPTY = AggregatedTargets(0, 0.0, 0.0, 0.0, 0, 0, 0, 0)
        }
    }

    /**
     * Map every day in [range] to the snapshot in force that day. Returns an empty
     * map if [range] is empty or [snapshots] is empty.
     *
     * @param snapshots Sorted ascending by effectiveDateEpochDay (DAO contract).
     */
    fun resolveTargetsByDay(
        snapshots: List<TargetSnapshot>,
        range: ClosedRange<LocalDate>
    ): Map<LocalDate, TargetSnapshot> {
        if (snapshots.isEmpty() || range.start.isAfter(range.endInclusive)) return emptyMap()

        val out = LinkedHashMap<LocalDate, TargetSnapshot>()
        var idx = -1
        // Advance idx to the snapshot that covers range.start: the largest one whose
        // effective day ≤ start. After this, idx ∈ [-1, snapshots.lastIndex].
        val startEpoch = range.start.toEpochDay()
        for (i in snapshots.indices) {
            if (snapshots[i].effectiveDateEpochDay <= startEpoch) idx = i else break
        }

        var day = range.start
        val end = range.endInclusive
        while (!day.isAfter(end)) {
            val epoch = day.toEpochDay()
            // Step forward through snapshots whose effective day starts on or before
            // this day. Each snapshot wins for every day from its effective day until
            // the next one's.
            while (idx + 1 < snapshots.size &&
                snapshots[idx + 1].effectiveDateEpochDay <= epoch
            ) {
                idx++
            }
            if (idx >= 0) out[day] = snapshots[idx]
            day = day.plusDays(1)
        }
        return out
    }

    /** Sum of per-day targets for [range], used wherever the UI needs a single
     *  "period target" number that respects mid-range transitions. */
    fun aggregateOverRange(
        snapshots: List<TargetSnapshot>,
        range: ClosedRange<LocalDate>
    ): AggregatedTargets {
        val byDay = resolveTargetsByDay(snapshots, range)
        if (byDay.isEmpty()) return AggregatedTargets.EMPTY

        var kcal = 0L
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        var steps = 0L
        var activeMinutes = 0L
        var activityCalories = 0L
        for (snap in byDay.values) {
            kcal += snap.targetKcal
            protein += snap.targetProtein
            carbs += snap.targetCarbs
            fat += snap.targetFat
            steps += snap.targetSteps
            activeMinutes += snap.targetActiveMinutes
            activityCalories += snap.targetActivityCalories
        }
        return AggregatedTargets(
            kcal = kcal,
            protein = protein,
            carbs = carbs,
            fat = fat,
            steps = steps,
            activeMinutes = activeMinutes,
            activityCalories = activityCalories,
            days = byDay.size
        )
    }
}
