package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Append-only DAO over [MealCorrectionEntity]. Same shape as
 * [BmrSnapshotDao]: `@Insert` + read queries only, no per-row update/delete.
 *
 * [trimTo] keeps the table bounded — the calibrator only needs a recent window
 * of corrections to derive a stable bias, and an unbounded table would slow the
 * full-scan aggregation. Call it right after [insert].
 */
@Dao
interface MealCorrectionDao {
    @Insert
    suspend fun insert(correction: MealCorrectionEntity): Long

    /** All corrections, newest first. The calibrator reads the whole (small)
     *  table and derives medians in Kotlin — no SQL aggregation needed. */
    @Query("SELECT * FROM meal_corrections ORDER BY createdAtMs DESC")
    suspend fun listAll(): List<MealCorrectionEntity>

    /** Cheap staleness key for the calibrator's in-memory cache (mirrors
     *  FoodDao.countSince). */
    @Query("SELECT COUNT(*) FROM meal_corrections")
    suspend fun count(): Int

    /** Delete every row except the newest [keep] by createdAtMs. Keeps the
     *  table bounded (see class doc). No-op when the table already holds
     *  ≤ [keep] rows. */
    @Query("""
        DELETE FROM meal_corrections
        WHERE id NOT IN (
            SELECT id FROM meal_corrections ORDER BY createdAtMs DESC LIMIT :keep
        )
    """)
    suspend fun trimTo(keep: Int)
}
