package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Frozen daily snapshot of "active calories" (workouts + casual movement) and the raw
 * inputs that produced it. Built once per day by [com.example.data.health.DailyActivitySnapshotWorker]
 * at ~02:00 for the day that just ended (D-1), and never touched again — so historical
 * reports always show the same number the user saw on the dashboard that day, even if
 * Samsung Health later re-syncs and changes the underlying workout list.
 *
 * Why a snapshot rather than re-deriving from the SDK every time?
 * - The `TOTAL_ACTIVE_CALORIES_BURNED` aggregate is unreliable on some devices (the
 *   Pixel 10 in particular counts workout time twice — observed 988 kcal vs the
 *   Samsung Health home tile 552).
 * - The live formula `workouts.sum + steps × 0.015` matches the tile within ~3% on
 *   the day of measurement, but a re-derivation tomorrow against today's workouts
 *   could shift if SH back-fills an exercise session, deletes one, or shifts the
 *   timestamps (the Pixel has been observed doing all three).
 *
 * Once frozen, the value is the canonical "active calories burned" for that day; the
 * dashboard renders it as-is and the reports tab aggregates over it the same way
 * BMR snapshots are aggregated by [com.example.data.bmr.BmrResolver].
 *
 * **Append-only-ish.** Worker uses `OnConflictStrategy.REPLACE` keyed on
 * [dateEpochDay] so a manual re-snapshot (debug, "force refresh") overwrites the day
 * cleanly. There's only ever one row per day. No history of "the snapshot for
 * 2026-06-26 had two values".
 */
@Entity(tableName = "daily_activity_snapshots")
data class DailyActivitySnapshotEntity(
    /** Local-date epoch day (`LocalDate.toEpochDay()`) the snapshot covers. */
    @PrimaryKey val dateEpochDay: Long,
    /** Frozen active-calories total for this day, in kcal. This is what reports show.
     *  Equal to [workoutsKcal] + (`steps` × 0.015), rounded to int. */
    val activeKcal: Int,
    /** Sum of `ShWorkout.activeKcal` for workouts that started on this day. Kept for
     *  diagnostics (so we can tell "the snapshot was 600, but only 400 came from
     *  workouts" without re-querying SH). */
    val workoutsKcal: Int,
    /** Daily step total from SH (`TOTAL_STEPS` aggregate). Diagnostic. */
    val steps: Long,
    /** Sum of workout durations in minutes. Diagnostic — matches the activeMin
     *  scalar shown on the dashboard for that day. */
    val activeMinutes: Int,
    /** Wall-clock ms when the worker captured this snapshot. Diagnostic only —
     *  reports key off [dateEpochDay], not this. */
    val capturedAtMs: Long = System.currentTimeMillis()
)
