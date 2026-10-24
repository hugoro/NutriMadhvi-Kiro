package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyPlanCheckpointDao {

    @Insert
    suspend fun insert(checkpoint: BodyPlanCheckpointEntity): Long

    @Query("SELECT * FROM body_plan_checkpoints ORDER BY checkpointAtMs DESC, id DESC LIMIT 1")
    suspend fun latest(): BodyPlanCheckpointEntity?

    /** Live list for the Progresso view — newest first. */
    @Query("SELECT * FROM body_plan_checkpoints ORDER BY checkpointAtMs DESC, id DESC")
    fun observeAll(): Flow<List<BodyPlanCheckpointEntity>>

    /** Last N checkpoints, newest first. Used for LLM comparison context. */
    @Query("SELECT * FROM body_plan_checkpoints ORDER BY checkpointAtMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BodyPlanCheckpointEntity>

    @Query("UPDATE body_plan_checkpoints SET tacticsJson=:json, tacticSource=:source WHERE id=:id")
    suspend fun updateTactics(id: Long, json: String, source: String)

    @Query("UPDATE body_plan_checkpoints SET coachReport=:report, reportGeneratedAtMs=:ts WHERE id=:id")
    suspend fun updateReport(id: Long, report: String, ts: Long)

    /** v41 repair path: overwrite ONLY the deterministic core (phase,
     *  priorities, delta, anchored goal) after a convention-compatible
     *  target regeneration. LLM enrichment fields (tacticsJson,
     *  tacticSource, coachReport) are untouched — a successful LLM pass is
     *  never lost by a repair. */
    @Query(
        "UPDATE body_plan_checkpoints SET phase=:phase, prioritiesJson=:prioritiesJson, " +
            "deltaSummary=:deltaSummary, goalText=:goalText, goalTargetsJson=:goalTargetsJson WHERE id=:id"
    )
    suspend fun updateDeterministic(
        id: Long,
        phase: String?,
        prioritiesJson: String?,
        deltaSummary: String?,
        goalText: String?,
        goalTargetsJson: String?,
    )

    @Query("SELECT * FROM body_plan_checkpoints WHERE id=:id")
    suspend fun getById(id: Long): BodyPlanCheckpointEntity?

    @Query("SELECT * FROM body_plan_checkpoints WHERE snapshotId=:snapshotId LIMIT 1")
    suspend fun getBySnapshotId(snapshotId: Long): BodyPlanCheckpointEntity?

    /** Newest checkpoint strictly before [checkpointAtMs] — used for the
     *  comparison context of re-enrichment (no row-count lookup limit). */
    @Query(
        "SELECT * FROM body_plan_checkpoints " +
            "WHERE checkpointAtMs < :checkpointAtMs " +
            "ORDER BY checkpointAtMs DESC, id DESC LIMIT 1"
    )
    suspend fun latestBefore(checkpointAtMs: Long): BodyPlanCheckpointEntity?

    /** Idempotent, transactional one-checkpoint-per-snapshot insert (v40):
     *  if a checkpoint already exists for [BodyPlanCheckpointEntity.snapshotId]
     *  its id is returned instead of inserting a duplicate. Safe under
     *  concurrent calls — the unique index on snapshotId is the final guard:
     *  if two callers race past the existence check, the loser's insert
     *  violates the index and the method re-reads the winner's id, so both
     *  callers observe the same row and one row per snapshot always holds. */
    @androidx.room.Transaction
    suspend fun insertIfAbsentBySnapshotId(checkpoint: BodyPlanCheckpointEntity): Long {
        if (checkpoint.snapshotId != null) {
            val existing = getBySnapshotId(checkpoint.snapshotId!!)
            if (existing != null) return existing.id
            return try {
                insert(checkpoint)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Lost the race — a concurrent insert won; return the winner.
                getBySnapshotId(checkpoint.snapshotId!!)?.id ?: throw e
            }
        }
        return insert(checkpoint)
    }

    /** User-requested data deletion (privacy). Callers also delete the
     *  referenced performance_photos files outside Room. */
    @Query("DELETE FROM body_plan_checkpoints WHERE id=:id")
    suspend fun deleteById(id: Long)
}
