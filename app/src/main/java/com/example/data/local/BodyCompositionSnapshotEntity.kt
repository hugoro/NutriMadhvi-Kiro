package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A versioned snapshot of the user's body composition. Source of truth is
 * Samsung Health (e.g. a Medisana scale syncing via VitaDock+ → S-Health);
 * this table is a local cache that grows whenever the app sees a new
 * measurement so the chat assistant can answer "qual era o teu peso há 2
 * meses?" without re-querying S-Health and so the UI has a "last known"
 * fallback when S-Health is offline / disconnected / permission revoked.
 *
 * **Append-only.** Each new measurement that differs from the most recent
 * row in [sourceTimestampMs] (or any value field) inserts a new row.
 * Dedup happens at insert time in the ViewModel — same `sourceTimestampMs`
 * means "we already have this point", drop.
 *
 * **Field nullability.** Different scales populate different fields. The
 * Medisana via VitaDock+ tested 2026-06-26 writes WEIGHT, HEIGHT,
 * BODY_FAT, MUSCLE_MASS, TOTAL_BODY_WATER, BMI and BMR but NOT
 * FAT_FREE_MASS / SKELETAL_MUSCLE / BODY_FAT_MASS — those return null.
 * We deliberately mirror that nullability instead of synthesising values
 * here. The UI / chat layer can derive massa magra trivially as
 * `weight × (1 − bodyFat/100)` when both are present.
 *
 * BMR is NOT stored here — it has its own [BmrSnapshotEntity] table with a
 * different effective-date contract (TMB applies to a date range; weight is
 * a point reading). Keeping them separate avoids cross-coupling those two
 * concerns when only one of the fields changes.
 *
 * Added 2026-06-26 alongside the dashboard redesign + Medisana validation
 * step. See memory dashboard-redesign-2026-06-26 (or its successor) for
 * the full thread.
 */
@Entity(
    tableName = "body_composition_snapshots",
    indices = [Index(value = ["sourceTimestampMs"])]
)
data class BodyCompositionSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Start time of the original S-Health BodyComposition entry, ms
     *  since epoch. This is what we dedup on — the same measurement
     *  re-read on a later sync must NOT insert a duplicate row. Null
     *  tolerated for future manual-entry path (no such path today). */
    val sourceTimestampMs: Long?,
    /** Wall-clock ms when this row was written by the app. Diagnostic
     *  only — UI / chat key off [sourceTimestampMs]. */
    val capturedAtMs: Long = System.currentTimeMillis(),
    /** Weight in kg. Required: a snapshot with no weight is useless — we
     *  drop those at insert time in the ViewModel. */
    val weightKg: Float,
    /** Body fat percentage (0..100). Null when the scale didn't report it. */
    val bodyFatPct: Float? = null,
    /** Muscle mass in **kg**. The scale reports muscle as a percentage;
     *  SamsungHealthRepository converts it to kg (weight × %/100) at read
     *  time, so this column holds real kilograms. Null when not reported. */
    val muscleMassKg: Float? = null,
    /** Total body water in **kg**. The scale reports water as a percentage;
     *  converted to kg (weight × %/100) at read time. Null when not reported. */
    val totalBodyWaterKg: Float? = null,
    /** Body mass index. Null when the scale didn't compute it. */
    val bmiValue: Float? = null,
    /** Height in cm — usually static but the scale can re-report it on
     *  every weigh-in; keeping it here means we don't need a separate
     *  profile.height field for chat queries. */
    val heightCm: Float? = null
)
