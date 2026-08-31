package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A versioned snapshot of the prescribed daily targets (calories + macros + activity).
 *
 * The targets the user types into Profile change over time — losing weight today,
 * maintenance next month — and reports must NOT re-interpret last month's days
 * against today's numbers. Each save inserts a new row with the date it starts to
 * apply; older rows stay untouched. Reads pick the row whose [effectiveDateEpochDay]
 * is the largest one ≤ the day being asked about (see TargetSnapshotDao).
 *
 * **Append-only.** No update, no delete from runtime code. Editing the past would
 * silently rewrite reports already shared with the nutritionist.
 *
 * Pre-existing installs are seeded by MIGRATION_8_9 with one row at epoch day 0
 * carrying whatever targets were in `user_profile_pref` at upgrade time, so any
 * historical FoodEntry/ActivityEntry is covered.
 */
@Entity(
    tableName = "target_snapshots",
    indices = [Index(value = ["effectiveDateEpochDay"])]
)
data class TargetSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local-date epoch day (`LocalDate.toEpochDay()`) from which this snapshot
     *  applies, inclusive. The next row's epoch day, minus one, is the implicit
     *  end. The latest row's end is "indefinite". */
    val effectiveDateEpochDay: Long,
    val targetKcal: Int,
    val targetProtein: Double,
    val targetCarbs: Double,
    val targetFat: Double,
    val targetSteps: Int,
    val targetActiveMinutes: Int,
    val targetActivityCalories: Int,
    /** Optional free-text rationale ("switched to maintenance", "bulk phase").
     *  Surfaced in the Target History screen; never sent to the LLM. */
    val note: String? = null,
    /** Wall-clock ms when this row was written. Diagnostic only — the effective
     *  date above is what reports key off. */
    val createdAtMs: Long = System.currentTimeMillis()
)
