package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User's free-text performance goal, e.g. "run 10 km without stopping".
 * The coach reads this and recommends silhouette-ratio targets via [coachTargetJson].
 *
 * [coachTargetJson] is filled async after insert — JSON object with optional keys:
 *   { "shoulder": 0.37, "waist": 0.25, "hip": 0.30, "thigh": 0.20,
 *     "chest": 0.22, "belly": 0.21, "lateralThigh": 0.19 }
 *
 * Append-only; only the latest row is used for display.
 * Added v37.
 */
@Entity(tableName = "performance_goals")
data class PerformanceGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val goalText: String,
    val coachTargetJson: String? = null,
    val targetsGeneratedAtMs: Long? = null,
    /** v41: the normalization convention the FRONTAL-side targets were
     *  derived under (e.g. "image_height" or "pose_person_height"). NULL =
     *  derived before conventions were tracked = UNKNOWN → the targets are
     *  never compared against a new capture until re-derived (fail-closed). */
    val targetsConventionFrontal: String? = null,
    /** v41: same metadata for the LATERAL-side targets. */
    val targetsConventionLateral: String? = null,
)
