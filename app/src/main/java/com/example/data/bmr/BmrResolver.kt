package com.example.data.bmr

import com.example.data.local.BmrSnapshotEntity
import java.time.LocalDate

/**
 * Pure helpers that turn the append-only list of [BmrSnapshotEntity]s into a
 * "what was the resting burn on day D" lookup, plus an aggregator that sums
 * day-by-day across a date range.
 *
 * Same shape and complexity (O(N+M)) as
 * [com.example.data.targets.TargetResolver.resolveTargetsByDay]; the algorithm
 * is intentionally a parallel template. There is **one deliberate divergence**
 * from the target resolver, which a future reader must not unify:
 *
 *   For days strictly before the earliest snapshot, this resolver extends the
 *   earliest snapshot backwards. The targets resolver omits those days (target
 *   for a day before any prescription is genuinely unknown). For BMR, "no row
 *   yet" doesn't mean the user wasn't burning calories at rest — they were —
 *   so we use the closest known value rather than dropping the day. This is the
 *   whole reason the entity exists: weekly/monthly views sometimes showed
 *   surplus where the day was actually deficit because TMB was missing.
 *
 * Callers pass the list as-is; no DB access happens here. The expectation from
 * [com.example.data.local.BmrSnapshotDao] is that the list is sorted by
 * [BmrSnapshotEntity.effectiveDateEpochDay] ascending.
 *
 * TODO: [NutritionXlsxExporter] currently takes a single live BmrSnapshot and
 * applies it to every day in the export range, which is the same defect this
 * resolver fixes for the dashboard/reports. When time allows, switch the
 * exporter to take `List<BmrSnapshotEntity>` and use [resolveBmrByDay] for
 * per-day TOTAL rows.
 */
object BmrResolver {

    /** Aggregated BMR summed across each day of a range. */
    data class AggregatedBmr(val kcal: Long, val days: Int) {
        companion object {
            val EMPTY = AggregatedBmr(0L, 0)
        }
    }

    /**
     * Map every day in [range] to the snapshot in force that day. Returns an
     * empty map if [range] is empty or [snapshots] is empty.
     *
     * @param snapshots Sorted ascending by effectiveDateEpochDay (DAO contract).
     */
    fun resolveBmrByDay(
        snapshots: List<BmrSnapshotEntity>,
        range: ClosedRange<LocalDate>
    ): Map<LocalDate, BmrSnapshotEntity> {
        if (snapshots.isEmpty() || range.start.isAfter(range.endInclusive)) return emptyMap()

        val out = LinkedHashMap<LocalDate, BmrSnapshotEntity>()
        val first = snapshots.first()
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
            while (idx + 1 < snapshots.size &&
                snapshots[idx + 1].effectiveDateEpochDay <= epoch
            ) {
                idx++
            }
            // Backwards-fill rule (see KDoc on the object): days before the earliest
            // snapshot fall back to that earliest snapshot. This is the documented
            // intentional divergence from TargetResolver.
            out[day] = if (idx >= 0) snapshots[idx] else first
            day = day.plusDays(1)
        }
        return out
    }

    /** Sum of per-day BMR for [range]. */
    fun aggregateOverRange(
        snapshots: List<BmrSnapshotEntity>,
        range: ClosedRange<LocalDate>
    ): AggregatedBmr {
        val byDay = resolveBmrByDay(snapshots, range)
        if (byDay.isEmpty()) return AggregatedBmr.EMPTY

        var kcal = 0L
        for (snap in byDay.values) {
            kcal += snap.valueKcal
        }
        return AggregatedBmr(kcal = kcal, days = byDay.size)
    }
}
