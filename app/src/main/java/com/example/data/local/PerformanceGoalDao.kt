package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PerformanceGoalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: PerformanceGoalEntity): Long

    @Query("SELECT * FROM performance_goals ORDER BY createdAtMs DESC LIMIT 1")
    fun observeLatest(): Flow<PerformanceGoalEntity?>

    @Query("SELECT * FROM performance_goals ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun latest(): PerformanceGoalEntity?

    @Query("SELECT * FROM performance_goals ORDER BY createdAtMs DESC")
    suspend fun listAll(): List<PerformanceGoalEntity>

    @Query("UPDATE performance_goals SET coachTargetJson = :json, targetsGeneratedAtMs = :ts WHERE id = :id")
    suspend fun updateCoachTargets(id: Long, json: String, ts: Long)
}
