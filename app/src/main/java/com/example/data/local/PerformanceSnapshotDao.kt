package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PerformanceSnapshotDao {
    @Insert
    suspend fun insert(snapshot: PerformanceSnapshotEntity): Long

    /** Most recent snapshot, or null if table is empty. */
    @Query("SELECT * FROM performance_snapshots ORDER BY capturedAtMs DESC, id DESC LIMIT 1")
    suspend fun latest(): PerformanceSnapshotEntity?

    /** Live flow of the most recent snapshot — drives the Performance tab header card. */
    @Query("SELECT * FROM performance_snapshots ORDER BY capturedAtMs DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<PerformanceSnapshotEntity?>

    /** Last N snapshots, newest first. Used by evolution charts and chat context. */
    @Query("SELECT * FROM performance_snapshots ORDER BY capturedAtMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PerformanceSnapshotEntity>

    /** Live list for evolution charts — emits whenever a new assessment is saved. */
    @Query("SELECT * FROM performance_snapshots ORDER BY capturedAtMs ASC")
    fun observeAll(): Flow<List<PerformanceSnapshotEntity>>

    /** Snapshots in a time window [startMs, endMs), ascending. */
    @Query("""
        SELECT * FROM performance_snapshots
        WHERE capturedAtMs >= :startMs AND capturedAtMs < :endMs
        ORDER BY capturedAtMs ASC, id ASC
    """)
    suspend fun getBetween(startMs: Long, endMs: Long): List<PerformanceSnapshotEntity>

    @Query("SELECT * FROM performance_snapshots WHERE id=:id")
    suspend fun getById(id: Long): PerformanceSnapshotEntity?

    /** True if an assessment was already captured within 60 s of [timestampMs].
     *  Used to prevent double-saves when the user quickly confirms/retakes. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM performance_snapshots
            WHERE capturedAtMs BETWEEN :timestampMs - 60000 AND :timestampMs + 60000
            LIMIT 1
        )
    """)
    suspend fun existsNear(timestampMs: Long): Boolean

    /** Updates the LLM coach report on an existing snapshot (async post-save). */
    @Query("UPDATE performance_snapshots SET coachReport=:report, reportGeneratedAtMs=:ts WHERE id=:id")
    suspend fun updateCoachReport(id: Long, report: String, ts: Long)
}
