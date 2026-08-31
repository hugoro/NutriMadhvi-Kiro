package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BmrSnapshotDao {
    @Insert
    suspend fun insert(snapshot: BmrSnapshotEntity): Long

    /** Snapshot in force on [epochDay]: the most recent one whose effective date is ≤ that day.
     *  Returns null when the table is empty (fresh install without a manual BMR and before
     *  the first SH sync) — callers must tolerate this and contribute 0 to "burned" totals. */
    @Query("""
        SELECT * FROM bmr_snapshots
        WHERE effectiveDateEpochDay <= :epochDay
        ORDER BY effectiveDateEpochDay DESC, id DESC
        LIMIT 1
    """)
    suspend fun findEffectiveAt(epochDay: Long): BmrSnapshotEntity?

    /** All snapshots ordered chronologically. Used by BmrResolver to walk a day-range
     *  in O(N+M). Same `id ASC` secondary order as TargetSnapshotDao so same-day inserts
     *  surface in write order, with the freshest winning via the DESC,DESC reads. */
    @Query("SELECT * FROM bmr_snapshots ORDER BY effectiveDateEpochDay ASC, id ASC")
    fun observeAll(): Flow<List<BmrSnapshotEntity>>

    @Query("SELECT * FROM bmr_snapshots ORDER BY effectiveDateEpochDay ASC, id ASC")
    suspend fun listAll(): List<BmrSnapshotEntity>

    /** Latest snapshot regardless of date. Used to detect duplicate writes (same valueKcal
     *  as the last row → skip the insert) and to feed the dashboard's "current TMB" footer. */
    @Query("""
        SELECT * FROM bmr_snapshots
        ORDER BY effectiveDateEpochDay DESC, id DESC
        LIMIT 1
    """)
    suspend fun latest(): BmrSnapshotEntity?
}
