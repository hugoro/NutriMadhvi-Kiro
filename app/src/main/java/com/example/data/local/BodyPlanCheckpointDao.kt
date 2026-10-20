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

    /** User-requested data deletion (privacy). Callers also delete the
     *  referenced performance_photos files outside Room. */
    @Query("DELETE FROM body_plan_checkpoints WHERE id=:id")
    suspend fun deleteById(id: Long)
}
