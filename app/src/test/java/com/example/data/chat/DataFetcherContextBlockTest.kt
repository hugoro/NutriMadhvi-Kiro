package com.example.data.chat

import com.example.data.health.ShWorkout
import com.example.data.local.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Locks the shape of the "## Samsung Health workouts (individual sessions)"
 * block emitted into the LLM system prompt. Two invariants:
 *
 *  1. Each per-session row carries the SPECIFIC Samsung modality
 *     (ShWorkout.sourceLabel — "Pilates", "Weight machine", "Track running")
 *     followed by its coarse bucket (cardio / strength / mobility / other).
 *     A regression here surfaces as the LLM writing "Yoga" when the user
 *     did Pilates.
 *
 *  2. The "By bucket:" summary line groups sessions by [ActivityType.bucket],
 *     independent of sourceLabel — so weekly volume totals don't change
 *     when new fine-grained labels are added.
 */
class DataFetcherContextBlockTest {

    private val zone = ZoneId.of("Europe/Lisbon")

    /** Convenience: 30min at 08:00 UTC on the given date. */
    private fun workout(
        date: LocalDate,
        type: ActivityType,
        sourceLabel: String,
        kcal: Int = 100,
        title: String? = null
    ): ShWorkout {
        val start = date.atTime(8, 0).atZone(zone).toInstant()
        val end = start.plusSeconds(30 * 60)
        return ShWorkout(
            recordId = "$date-$sourceLabel",
            type = type,
            sourceLabel = sourceLabel,
            startTime = start,
            endTime = end,
            title = title,
            notes = null,
            activeKcal = kcal
        )
    }

    private fun renderWith(workouts: List<ShWorkout>): String {
        val d = LocalDate.of(2026, 7, 6)
        val data = PrefetchedData(
            window = DateWindow(from = d.minusDays(6), to = d, label = "last 7 days"),
            type = DataType.ACTIVITY,
            meals = emptyList(),
            activities = emptyList(),
            shSteps = emptyMap(),
            shDaily = emptyMap(),
            shWorkouts = workouts
        )
        return data.toContextBlock("en")
    }

    @Test fun `Pilates row shows sourceLabel not YOGA`() {
        val out = renderWith(
            listOf(workout(LocalDate.of(2026, 7, 6), ActivityType.YOGA, "Pilates", kcal = 207))
        )
        // The specific modality reaches the LLM…
        assertTrue("row must contain 'Pilates'", out.contains("Pilates"))
        // …and the coarse bucket still appears as a dot-separated token.
        assertTrue("row must carry the mobility bucket", out.contains("mobility"))
        // Regression guard: the pre-refactor raw-uppercase token no longer
        // appears inline with the row (the "By bucket:" line uses lower-case,
        // so the only place "YOGA" could come from is the old format).
        val row = out.lines().first { it.startsWith("- 2026-") }
        assertFalse("row must not print raw 'YOGA' next to Pilates: '$row'", row.contains("YOGA"))
    }

    @Test fun `three distinct modalities render with their own sourceLabel`() {
        val d = LocalDate.of(2026, 7, 6)
        val out = renderWith(
            listOf(
                workout(d, ActivityType.YOGA, "Pilates"),
                workout(d.minusDays(1), ActivityType.STRENGTH, "Weight machine"),
                workout(d.minusDays(2), ActivityType.RUNNING, "Track running")
            )
        )
        assertTrue(out.contains("Pilates"))
        assertTrue(out.contains("Weight machine"))
        assertTrue(out.contains("Track running"))
    }

    @Test fun `By bucket summary aggregates by bucket regardless of sourceLabel`() {
        val d = LocalDate.of(2026, 7, 6)
        val out = renderWith(
            listOf(
                // 2 mobility sessions with different fine labels
                workout(d, ActivityType.YOGA, "Pilates", kcal = 200),
                workout(d.minusDays(1), ActivityType.YOGA, "Yoga", kcal = 150),
                // 1 strength session
                workout(d.minusDays(2), ActivityType.STRENGTH, "Weight machine", kcal = 250)
            )
        )
        // The bucket summary line reflects the [ActivityType.bucket] mapping —
        // 2 mobility + 1 strength — independent of the specific labels.
        val bucketLine = out.lines().first { it.startsWith("By bucket:") }
        assertTrue("bucket line must show mobility 2 sess: '$bucketLine'", bucketLine.contains("mobility: 2 sess"))
        assertTrue("bucket line must show strength 1 sess: '$bucketLine'", bucketLine.contains("strength: 1 sess"))
    }

    @Test fun `custom title still surfaces after the row prefix`() {
        val out = renderWith(
            listOf(workout(LocalDate.of(2026, 7, 6), ActivityType.YOGA, "Pilates", title = "Morning ballet-inspired"))
        )
        assertTrue("user title should still appear at end of row", out.contains("Morning ballet-inspired"))
    }

    @Test fun `empty sourceLabel falls back to bucket displayKey`() {
        // ShWorkout may carry sourceLabel="" for the SDK UNDEFINED / null case.
        // The row must remain parseable — we fall back to the bucket displayKey
        // so the LLM still sees a coherent token in the modality slot.
        val out = renderWith(
            listOf(workout(LocalDate.of(2026, 7, 6), ActivityType.OTHER, sourceLabel = ""))
        )
        val row = out.lines().first { it.startsWith("- 2026-") }
        assertTrue(
            "row must contain the bucket displayKey when sourceLabel is empty: '$row'",
            row.contains("Other / General")
        )
    }
}
