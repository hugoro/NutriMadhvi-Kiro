package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A timestamped record of joint angles measured via ML Kit Pose Detection.
 * Mirrors the joint set tracked by Telmo's simulador_1.html (PoseNet):
 *   - Shoulders and hips: sagittal + frontal plane angles
 *   - Elbows and knees: sagittal only (single-plane joints)
 *
 * Angles are stored in degrees (0–180°). Null means that joint was not
 * confidently detected at capture time.
 *
 * Append-only. Dedup happens at insert time on [capturedAtMs] — if two
 * assessments are taken within 60 s the second is dropped.
 *
 * Added v35 alongside the Performance tab.
 */
@Entity(
    tableName = "performance_snapshots",
    indices = [Index(value = ["capturedAtMs"])]
)
data class PerformanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock ms when the user confirmed the assessment. Dedup key. */
    val capturedAtMs: Long = System.currentTimeMillis(),
    /** "manual" = user tapped "Medir agora"; "post_workout" = prompted after SH sync */
    val source: String = "manual",
    /** Optional freetext note saved with the assessment. */
    val notes: String? = null,

    // ── Shoulders ────────────────────────────────────────────────────────────
    /** leftHip → leftShoulder → leftElbow */
    val leftShoulderSagittalDeg: Float? = null,
    /** rightShoulder → leftShoulder → leftElbow */
    val leftShoulderFrontalDeg: Float? = null,
    /** rightHip → rightShoulder → rightElbow */
    val rightShoulderSagittalDeg: Float? = null,
    /** leftShoulder → rightShoulder → rightElbow */
    val rightShoulderFrontalDeg: Float? = null,

    // ── Hips ─────────────────────────────────────────────────────────────────
    /** leftShoulder → leftHip → leftKnee */
    val leftHipSagittalDeg: Float? = null,
    /** rightHip → leftHip → leftKnee */
    val leftHipFrontalDeg: Float? = null,
    /** rightShoulder → rightHip → rightKnee */
    val rightHipSagittalDeg: Float? = null,
    /** leftHip → rightHip → rightKnee */
    val rightHipFrontalDeg: Float? = null,

    // ── Elbows (sagittal only) ───────────────────────────────────────────────
    /** leftShoulder → leftElbow → leftWrist */
    val leftElbowSagittalDeg: Float? = null,
    /** rightShoulder → rightElbow → rightWrist */
    val rightElbowSagittalDeg: Float? = null,

    // ── Knees (sagittal only) ────────────────────────────────────────────────
    /** leftHip → leftKnee → leftAnkle */
    val leftKneeSagittalDeg: Float? = null,
    /** rightHip → rightKnee → rightAnkle */
    val rightKneeSagittalDeg: Float? = null,

    // ── Silhouette photo paths (v36) — filesDir-relative ────────────────────
    val frontalImagePath: String? = null,
    val lateralImagePath: String? = null,

    // ── Silhouette width ratios: pixel span of zone / image pixel height ─────
    // Frontal photo zones
    val frontalShoulderRatio: Float? = null,  // bioacromial width
    val frontalWaistRatio: Float? = null,
    val frontalHipRatio: Float? = null,
    val frontalThighRatio: Float? = null,
    // Lateral photo zones
    val lateralChestRatio: Float? = null,
    val lateralBellyRatio: Float? = null,
    val lateralThighRatio: Float? = null,

    // ── LLM coach report (v36) — generated async after save ──────────────────
    val coachReport: String? = null,
    val reportGeneratedAtMs: Long? = null
)
