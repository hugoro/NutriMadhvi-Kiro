package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetSnapshotDao {
    @Insert
    suspend fun insert(snapshot: TargetSnapshot): Long

    /** Snapshot in force on [epochDay]: the most recent one whose effective date is ≤ that day.
     *  Returns null only on a fresh DB before MIGRATION_8_9 has run, which shouldn't happen
     *  in normal flow — callers can defensively fall back to the in-memory UserProfile. */
    @Query("""
        SELECT * FROM target_snapshots
        WHERE effectiveDateEpochDay <= :epochDay
        ORDER BY effectiveDateEpochDay DESC, id DESC
        LIMIT 1
    """)
    suspend fun findEffectiveAt(epochDay: Long): TargetSnapshot?

    /** All snapshots ordered chronologically. Used by TargetResolver to walk a day-range
     *  in O(N+M), and by the history screen to render the timeline. */
    @Query("SELECT * FROM target_snapshots ORDER BY effectiveDateEpochDay ASC, id ASC")
    fun observeAll(): Flow<List<TargetSnapshot>>

    @Query("SELECT * FROM target_snapshots ORDER BY effectiveDateEpochDay ASC, id ASC")
    suspend fun listAll(): List<TargetSnapshot>

    /** Latest snapshot regardless of date. Used as the "current" view exposed to the
     *  dashboard's UserProfile-equivalent fields and to the live LLM system prompt. */
    @Query("""
        SELECT * FROM target_snapshots
        ORDER BY effectiveDateEpochDay DESC, id DESC
        LIMIT 1
    """)
    suspend fun latest(): TargetSnapshot?
}
