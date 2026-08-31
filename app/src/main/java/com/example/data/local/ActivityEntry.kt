package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activities")
data class ActivityEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMinutes: Int,
    val caloriesBurned: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "",
    val fullSummary: String = "",
    val lastAiAnalyzedSummary: String? = null,
    val activityType: ActivityType = ActivityType.OTHER,
    val notes: String? = null,
    val shRecordId: String? = null,
    /**
     * Fine-grained modality label, mirroring `ShWorkout.sourceLabel`. Set when
     * a LOG_ACTIVITY_PROPOSAL chat card carries a specific modality (e.g.
     * "Pilates", "Weight machine"), so a manual chat-confirmed entry keeps
     * the same fidelity as a Samsung Health-sourced session. Null on legacy
     * rows and on quick-log paths that only know a bucket — renderers must
     * fall back to `name` / `activityType.displayKey`. See migration
     * `MIGRATION_26_27` and [[samsung-health-exercise-type-mapping]].
     */
    val sourceLabel: String? = null
)
