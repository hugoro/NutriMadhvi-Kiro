package com.example.ui

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.health.displayLabel
import com.example.data.local.ActivityEntry
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the merged activity row list rendered in the PNG report and exported to Google Sheets.
 *
 * Mirrors the dashboard's hybrid-activity logic at `ChatScreen.kt` (around line 708-745):
 *  - Manual `ActivityEntry` rows (already pre-filtered to drop ones overlapping any SH workout).
 *  - Samsung Health workout sessions for the period.
 *
 * The casual-movement contribution (steps × 0.015 kcal) is folded into the header total in
 * `ChatScreen.kt` via `shActiveCal`/`reportShActiveCal` and shown as the "Calories Burned"
 * scalar; it is NOT emitted as a per-day row here. Pre-2026-06-26 the function emitted a
 * "Daily activity (ambient)" row computed from `ActivitySummary.activeCalories − workouts.sum`,
 * but that aggregate is unreliable on the Pixel (counts workout time twice) so we no longer
 * read it. Detalhes em memória `sh-active-calories-aggregate-vs-tile`.
 *
 * Output is sorted newest-first to match the existing `ActivityDao.getAllActivitiesFlow` ordering.
 */
@Suppress("UNUSED_PARAMETER")
fun buildReportActivityRows(
    manuals: List<ActivityEntry>,
    shWorkouts: List<ShWorkout>,
    shDaily: Map<LocalDate, ShDailyActivity>,
    shAvailable: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<ReportActivityRow> {
    val rows = mutableListOf<Pair<Long, ReportActivityRow>>() // (sortKey ms, row)

    manuals.forEach { m ->
        rows += m.timestamp to ReportActivityRow(
            name = m.name,
            typeLabel = m.activityType.displayKey,
            durationMinutes = m.durationMinutes,
            caloriesBurned = m.caloriesBurned,
            source = ReportActivityRow.Source.MANUAL,
            sourceLabel = m.sourceLabel
        )
    }

    if (shAvailable) {
        shWorkouts.forEach { w ->
            val durationMin = Duration.between(w.startTime, w.endTime).toMinutes().toInt().coerceAtLeast(0)
            rows += w.startTime.toEpochMilli() to ReportActivityRow(
                // Preserve the specific Samsung Health modality (e.g. "Pilates",
                // "Weight machine") via ShWorkout.displayLabel — this is the
                // priority title > sourceLabel > type.displayKey. Before this
                // change the row showed the coarse bucket displayKey ("Yoga /
                // Stretch") for every SH session, which broke fidelity.
                name = w.displayLabel,
                typeLabel = w.type.displayKey,
                durationMinutes = durationMin,
                caloriesBurned = w.activeKcal ?: 0,
                source = ReportActivityRow.Source.SAMSUNG_HEALTH_WORKOUT,
                // Fine-grained modality for aggregators that need to list
                // modalities inside a bucket (e.g. "Distribuição por tipo").
                // Follows the same title > sourceLabel priority as displayLabel:
                // a user-set customTitle ("Treino Sala") is more meaningful
                // than the SDK's raw "OTHER" bucket-name for the summary card.
                // Falls back to the SDK's sourceLabel (already humanised).
                sourceLabel = w.title?.takeIf { it.isNotBlank() }
                    ?: w.sourceLabel.takeIf { it.isNotBlank() }
            )
        }
    }

    return rows.sortedByDescending { it.first }.map { it.second }
}
