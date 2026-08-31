package com.example.ui

import com.example.data.bmr.BmrResolver
import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.BmrSnapshotEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-day activity bucket used by the Reports tab's "Evolução" chart on the
 * Activity card.
 *
 * INVARIANT (please don't break): `totalKcal = manualKcal + shWorkoutKcal +
 * shAmbientKcal + bmrKcal`, and across all days in the report range
 * `sum(totalKcal)` MUST equal the `burnedCalories` scalar computed at
 * `ChatScreen.kt:5407-5410`. The two values appear next to each other (totals
 * row at top of card vs. summed bar heights below) — if they drift the user
 * sees a contradiction.
 *
 * The math here intentionally mirrors [buildReportActivityRows] in
 * [ReportActivityRows.kt]: manuals + SH workouts + per-day ambient residual,
 * with SH's 10-kcal ambient threshold removed (every kcal of resting/ambient
 * burn is rolled into the bar, otherwise the daily total would dip below
 * `burnedCalories`). BMR is added as a separate band so the chart can shade it
 * differently from "active" burn.
 *
 * Activity-type breakdown is keyed by the UI-side [ActivityType] enum (the one
 * declared in `ChatScreen.kt:4428` with `Color` fields), not the data-layer
 * `data.local.ActivityType`. This matches what the rest of the report UI
 * (`ActivitySummaryTableFromRows`, `ActivityPieChartFromRows`) already does.
 */
data class DailyActivityBucket(
    val date: LocalDate,
    val manualKcal: Int,
    val shWorkoutKcal: Int,
    val shAmbientKcal: Int,
    val bmrKcal: Int,
    val totalKcal: Int,
    val durationMinutes: Int,
    /** Manual + SH workout kcal grouped by activity type. Ambient & BMR are
     *  kept OUT of this map so the chart can paint them as separate muted
     *  bands. */
    val byType: Map<ActivityType, Int>
)

/**
 * Build one [DailyActivityBucket] per day in [range].
 *
 * @param manuals Already-overlap-filtered manuals (`reportNonOverlappingManuals`
 *                in `ChatScreen.kt:5371`). Manuals overlapping SH workouts have
 *                already been dropped so we don't double-count.
 * @param shWorkouts Samsung Health workouts, no further filtering required —
 *                   we re-bucket by start-time LocalDate.
 * @param shDaily Per-day Samsung Health activity summary. The caller (Reports
 *                tab) already filters this to the report range.
 * @param bmrByDay Per-day BMR snapshot resolution (from
 *                 [BmrResolver.resolveBmrByDay]). Days missing from the map
 *                 contribute 0 BMR — but the resolver's backwards-fill rule
 *                 already covers days before the earliest snapshot.
 * @param range Inclusive `start..endInclusive`. Empty → empty list.
 * @param shAvailable When false, SH workouts and ambient kcal are zeroed.
 *                    Manuals + BMR still flow through (matches the dashboard
 *                    behaviour for users without SH).
 * @param zoneId Time-zone for bucketing epoch-millis timestamps to LocalDate.
 */
fun buildDailyActivityBuckets(
    manuals: List<ActivityEntry>,
    shWorkouts: List<ShWorkout>,
    shDaily: Map<LocalDate, ShDailyActivity>,
    bmrByDay: Map<LocalDate, BmrSnapshotEntity>,
    range: ClosedRange<LocalDate>,
    shAvailable: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<DailyActivityBucket> {
    if (range.start.isAfter(range.endInclusive)) return emptyList()

    // Bucket manuals by LocalDate (start of activity).
    val manualsByDate: Map<LocalDate, List<ActivityEntry>> = manuals
        .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }

    // Bucket SH workouts by LocalDate (workout start).
    val workoutsByDate: Map<LocalDate, List<ShWorkout>> =
        if (shAvailable) shWorkouts.groupBy { it.startTime.atZone(zoneId).toLocalDate() }
        else emptyMap()

    val out = ArrayList<DailyActivityBucket>()
    var day = range.start
    val end = range.endInclusive
    while (!day.isAfter(end)) {
        val dayManuals = manualsByDate[day].orEmpty()
        val dayWorkouts = workoutsByDate[day].orEmpty()

        val manualKcal = dayManuals.sumOf { it.caloriesBurned }
        val shWorkoutKcal = dayWorkouts.sumOf { (it.activeKcal ?: 0) }

        val manualMin = dayManuals.sumOf { it.durationMinutes }
        val shWorkoutMin = dayWorkouts.sumOf {
            Duration.between(it.startTime, it.endTime).toMinutes().toInt().coerceAtLeast(0)
        }

        // shAmbientKcal: pre-2026-06-26 was computed from
        // `ActivitySummary.activeCalories − sum(workouts that day)`, but that
        // aggregate is unreliable on the Pixel (counts workout time twice).
        // The dashboard's "Calories Burned" total now folds casual movement
        // in via `shActiveCal = workouts.sum + steps × 0.015`. The per-day
        // chart band kept here is zeroed so it doesn't double-count; if we
        // ever want a "casual movement" band again, derive it from steps the
        // same way `shActiveCal` does. Detalhes em memória
        // `sh-active-calories-aggregate-vs-tile`.
        val shAmbientKcal = 0
        val shAmbientMin = 0

        val bmrKcal = bmrByDay[day]?.valueKcal ?: 0

        val totalKcal = manualKcal + shWorkoutKcal + shAmbientKcal + bmrKcal
        val durationMinutes = manualMin + shWorkoutMin + shAmbientMin

        // Group manuals + workouts by UI ActivityType. Ambient stays out of this
        // map (it's rendered as its own band in the chart).
        val byType = HashMap<ActivityType, Int>()
        dayManuals.forEach { m ->
            val uiType = getActivityType(m.name)
            byType.merge(uiType, m.caloriesBurned, Int::plus)
        }
        dayWorkouts.forEach { w ->
            val nameForType = w.title?.takeIf { it.isNotBlank() } ?: w.type.displayKey
            val uiType = getActivityType(nameForType)
            byType.merge(uiType, w.activeKcal ?: 0, Int::plus)
        }

        out += DailyActivityBucket(
            date = day,
            manualKcal = manualKcal,
            shWorkoutKcal = shWorkoutKcal,
            shAmbientKcal = shAmbientKcal,
            bmrKcal = bmrKcal,
            totalKcal = totalKcal,
            durationMinutes = durationMinutes,
            byType = byType
        )
        day = day.plusDays(1)
    }
    return out
}
