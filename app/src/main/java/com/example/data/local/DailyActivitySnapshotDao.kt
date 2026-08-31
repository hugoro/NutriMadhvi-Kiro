package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyActivitySnapshotDao {
    /** Upsert keyed on dateEpochDay — a manual "force refresh" overwrites the day cleanly. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: DailyActivitySnapshotEntity)

    @Query("SELECT * FROM daily_activity_snapshots WHERE dateEpochDay = :epochDay")
    suspend fun findByDay(epochDay: Long): DailyActivitySnapshotEntity?

    /** All snapshots within an inclusive epoch-day range. Used by the resolver to
     *  aggregate over a report period. */
    @Query("""
        SELECT * FROM daily_activity_snapshots
        WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        ORDER BY dateEpochDay ASC
    """)
    suspend fun listInRange(startEpochDay: Long, endEpochDay: Long): List<DailyActivitySnapshotEntity>

    @Query("SELECT * FROM daily_activity_snapshots ORDER BY dateEpochDay ASC")
    fun observeAll(): Flow<List<DailyActivitySnapshotEntity>>

    /** Used by the worker to detect days that still need a snapshot (back-fill). */
    @Query("""
        SELECT MAX(dateEpochDay) FROM daily_activity_snapshots
    """)
    suspend fun latestDay(): Long?
}
