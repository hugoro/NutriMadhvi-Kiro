package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodHabitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: FoodHabit): Long

    @Update
    suspend fun updateHabit(habit: FoodHabit)

    @Delete
    suspend fun deleteHabit(habit: FoodHabit)

    @Query("SELECT * FROM food_habits WHERE isActive = 1 ORDER BY lastAppliedAt DESC, updatedAt DESC")
    fun getActiveHabitsFlow(): Flow<List<FoodHabit>>

    @Query("SELECT * FROM food_habits ORDER BY isActive DESC, lastAppliedAt DESC, updatedAt DESC")
    fun getAllHabitsFlow(): Flow<List<FoodHabit>>

    @Query("SELECT * FROM food_habits WHERE isActive = 1")
    suspend fun getActiveHabitsSnapshot(): List<FoodHabit>

    @Query("SELECT * FROM food_habits WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FoodHabit?

    @Query("UPDATE food_habits SET lastAppliedAt = :timestamp, timesApplied = timesApplied + 1 WHERE id = :id")
    suspend fun markApplied(id: Long, timestamp: Long)

    @Query("UPDATE food_habits SET isActive = :active, updatedAt = :timestamp WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean, timestamp: Long = System.currentTimeMillis())
}
