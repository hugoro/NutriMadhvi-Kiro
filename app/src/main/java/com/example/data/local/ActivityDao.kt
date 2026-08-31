package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY timestamp DESC")
    fun getAllActivitiesFlow(): Flow<List<ActivityEntry>>

    /** Window query used by the chat orchestrator's local-first DataFetcher
     *  (see `data/chat/DataFetcher.kt`). `endMs` is exclusive. */
    @Query("SELECT * FROM activities WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getActivitiesBetween(startMs: Long, endMs: Long): List<ActivityEntry>

    /** Count for the snapshot-cache invalidation key. */
    @Query("SELECT COUNT(*) FROM activities WHERE timestamp >= :startMs")
    suspend fun countSince(startMs: Long): Int

    @Insert
    suspend fun insertActivity(activity: ActivityEntry): Long

    @Update
    suspend fun updateActivity(activity: ActivityEntry)

    @Delete
    suspend fun deleteActivity(activity: ActivityEntry)

    @Query("SELECT * FROM activities WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getSession(sessionId: String): List<ActivityEntry>

    @Query("DELETE FROM activities WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Transaction
    suspend fun replaceSession(sessionId: String, exercises: List<ActivityEntry>) {
        deleteSession(sessionId)
        exercises.forEach { insertActivity(it) }
    }

    @Query("SELECT EXISTS(SELECT 1 FROM activities WHERE shRecordId = :shRecordId)")
    suspend fun existsByShRecordId(shRecordId: String): Boolean

    @Query("SELECT * FROM activities WHERE shRecordId = :shRecordId LIMIT 1")
    suspend fun findByShRecordId(shRecordId: String): ActivityEntry?

    /** Wipe legacy entries imported from Samsung Health. The app no longer
     *  duplicates SH workouts into the activities table; this clears anything
     *  that pre-dates that policy. Safe to call repeatedly. */
    @Query("DELETE FROM activities WHERE shRecordId IS NOT NULL")
    suspend fun deleteAllShImported(): Int

    /** Wipe every manual activity row. Used by the "Eliminar dados da aplicação"
     *  destructive button on the Reports tab; pairs with [FoodDao.clearAllFoods].
     *  Samsung Health rows are no longer mirrored here, so this never destroys
     *  data sourced from SH. */
    @Query("DELETE FROM activities")
    suspend fun clearAllActivities()
}
