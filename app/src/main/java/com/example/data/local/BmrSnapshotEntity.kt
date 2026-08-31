package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A versioned snapshot of the user's Basal Metabolic Rate (TMB / BMR) in kcal/day.
 *
 * BMR is not static: it shifts as body composition assessments are repeated, weight
 * changes, age advances. Reports must NOT recompute past days against today's BMR —
 * a deficit yesterday should stay a deficit even if the resting burn has gone up
 * since. Each new value (whether read from Samsung Health BodyComposition or saved
 * manually from the profile dialog) inserts a new row stamped with the day it
 * starts to apply; older rows stay untouched.
 *
 * **Append-only.** No update, no delete from runtime code. The same dedup rule the
 * targets table uses applies here: same-day re-saves collapse via `id DESC`
 * tiebreaker in the DAO query, but they don't overwrite history.
 *
 * **Backwards-fill rule (divergent from [TargetSnapshot]).** For days strictly
 * before the earliest snapshot, [com.example.data.bmr.BmrResolver] extends the
 * earliest snapshot backwards instead of contributing zero. Rationale: TMB is a
 * resting cost — even just breathing burns calories — so historical days without
 * a snapshot should carry _some_ BMR rather than appearing to have been "free
 * burns". This is the central reason this entity exists in the first place: prior
 * to versioning, weekly/monthly views sometimes showed surplus when the day was
 * actually deficit because TMB was missing from the burn total.
 *
 * Pre-existing installs are seeded by the v9→v10 migration with one row at epoch
 * day 0 carrying whatever was in `user_profile_pref.profile_manual_bmr` at upgrade
 * time, **only if** the user had set a manual value. Installs without a manual
 * BMR start with an empty table and simply don't contribute BMR until the first
 * SH sync or manual save populates it.
 */
@Entity(
    tableName = "bmr_snapshots",
    indices = [Index(value = ["effectiveDateEpochDay"])]
)
data class BmrSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local-date epoch day (`LocalDate.toEpochDay()`) from which this snapshot
     *  applies, inclusive. The next row's epoch day, minus one, is the implicit
     *  end. The latest row's end is "indefinite". */
    val effectiveDateEpochDay: Long,
    /** Resting energy expenditure in kcal/day. Always > 0 — `<= 0` is rejected at
     *  insertion time in the ViewModel. */
    val valueKcal: Int,
    /** Where this snapshot came from. Stored as String to keep the schema simple
     *  (no TypeConverter). Use the constants in [Companion] for writes; reads
     *  should accept either constant value. */
    val source: String,
    /** For SAMSUNG_HEALTH: original BodyComposition measurement start time.
     *  For MANUAL: when the user pressed save in the profile dialog.
     *  Null tolerated for legacy / migration-seeded rows. Diagnostic only —
     *  does NOT affect which day this snapshot covers. */
    val sourceTimestampMs: Long?,
    /** Wall-clock ms when this row was written. Diagnostic only — the effective
     *  date above is what reports key off. */
    val createdAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_SH = "SAMSUNG_HEALTH"
        const val SOURCE_MANUAL = "MANUAL"
    }
}
