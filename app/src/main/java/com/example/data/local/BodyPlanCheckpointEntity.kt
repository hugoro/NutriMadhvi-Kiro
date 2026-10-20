package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A timestamped body-plan checkpoint: the deterministic assessment of the user's
 * body-composition phase + priority zones + coaching tactics, anchored to a
 * [PerformanceSnapshotEntity] and optionally a body-composition (scale) row.
 *
 * Splitting the deterministic part (phase, priorities, deltas) from the LLM part
 * (tactics, report) is deliberate: the deterministic fields are computed locally
 * at save time and are ALWAYS present, so the Plan view works offline. The LLM
 * fields fill in async afterwards; when they are null the UI shows a retry
 * instead of a spinner.
 *
 * Append-only. One checkpoint per confirmed silhouette assessment (dedup happens
 * upstream — one [PerformanceSnapshotEntity] per capture, 60 s dedup window).
 *
 * Added v39.
 */
@Entity(
    tableName = "body_plan_checkpoints",
    indices = [
        Index(value = ["checkpointAtMs"]),
        // v40 — one checkpoint per snapshot (SQLite treats NULLs as distinct,
        // so checkpoints without a snapshot stay allowed).
        Index(value = ["snapshotId"], unique = true),
    ]
)
data class BodyPlanCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock ms when the checkpoint was created. */
    val checkpointAtMs: Long = System.currentTimeMillis(),
    /** performance_snapshots.id this checkpoint was computed from. UNIQUE (v40). */
    val snapshotId: Long? = null,
    /** body_composition_snapshots.id of the latest scale reading at checkpoint time. */
    val bodyCompRowId: Long? = null,
    /** "BULK" | "CUT" | "RECOMP" | "MAINTAIN" — null when no composition data exists. */
    val phase: String? = null,
    /**
     * Top-3 priority zones, JSON array:
     * [{"zone":"waist","direction":"REDUCE","gapPp":1.8,"tissue":"FAT_DOMINANT"}, ...]
     * Computed locally (BodyPlanEngine.rankPriorities); safety-gated.
     */
    val prioritiesJson: String? = null,
    /**
     * Coaching tactics, JSON array:
     * [{"category":"nutrition","text":"...","target":"...","windowDays":28}, ...]
     * LLM-generated (routine cheap models via ModelRoles) or the
     * deterministic fallback list. LLM tactics pass a semantic safety
     * validator (TacticSafetyValidator) before persistence.
     */
    val tacticsJson: String? = null,
    /** "llm:<model>" (which model of the routine chain produced it) or
     *  "deterministic_fallback" — which source produced [tacticsJson]. */
    val tacticSource: String? = null,
    /** Deterministic delta summary vs the previous checkpoint (e.g. "cintura −0.3pp, peso +0.4kg"). */
    val deltaSummary: String? = null,
    /** LLM checkpoint report (compares this checkpoint with the previous one). */
    val coachReport: String? = null,
    val reportGeneratedAtMs: Long? = null,
    /** v40 — goal text anchored at checkpoint time (retry re-enrichment must
     *  not use today's possibly-changed goal). */
    val goalText: String? = null,
    /** v40 — goal coach-target JSON anchored at checkpoint time. */
    val goalTargetsJson: String? = null,
)
