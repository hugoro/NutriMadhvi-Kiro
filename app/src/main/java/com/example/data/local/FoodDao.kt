package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFood(entry: FoodEntry): Long

    @Update
    suspend fun updateFood(entry: FoodEntry)

    @Delete
    suspend fun deleteFood(entry: FoodEntry)

    @Query("SELECT * FROM food_entries ORDER BY timestamp DESC")
    fun getAllFoodsFlow(): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entries ORDER BY timestamp DESC")
    suspend fun getAllFoods(): List<FoodEntry>

    /** Single-row fetch by id. Used by the background nutrition-enrichment job
     *  to re-read the saved row before updating it, so it never clobbers a user
     *  edit that landed between the analysis Done and the enrich finishing
     *  (mirrors `ChatDao.getSessionById`). Null if the row was deleted. */
    @Query("SELECT * FROM food_entries WHERE id = :id LIMIT 1")
    suspend fun getFoodById(id: Long): FoodEntry?

    /** Window query used by the chat orchestrator's local-first DataFetcher
     *  (see `data/chat/DataFetcher.kt`). Push-down filtering avoids loading
     *  the full table just to drop 95% of rows in memory — a relief on power
     *  users with months of logs. `endMs` is exclusive (callers pass
     *  `endDate.plusDays(1).atStartOfDay`). */
    @Query("SELECT * FROM food_entries WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getFoodsBetween(startMs: Long, endMs: Long): List<FoodEntry>

    /** Lightweight count for the snapshot-cache invalidation key. Callers
     *  pass `epochDay 0` to count the whole table. */
    @Query("SELECT COUNT(*) FROM food_entries WHERE timestamp >= :startMs")
    suspend fun countSince(startMs: Long): Int

    /** All non-null `imagePath` values across the whole table, projected
     *  out as bare strings (no row materialisation). Consumed by
     *  [com.example.data.export.OrphanImageCleanupWorker] to build the
     *  "referenced" set when sweeping `filesDir/food_images/`. Cheaper than
     *  reading every [FoodEntry] just to pull one nullable column. */
    @Query("SELECT imagePath FROM food_entries WHERE imagePath IS NOT NULL")
    suspend fun allImagePaths(): List<String>

    /** Entries whose AI analysis never completed — placeholder name set by the
     *  onFailure path in ChatScreen, image present, no summary written yet.
     *  Used by the resume-retry mechanism in ChatViewModel. */
    @Query("SELECT * FROM food_entries WHERE (name LIKE 'Captured Meal%' OR name LIKE 'Uploaded Meal%') AND imagePath IS NOT NULL AND (fullSummary IS NULL OR fullSummary = '') ORDER BY timestamp DESC")
    suspend fun getPendingAnalysisEntries(): List<FoodEntry>

    @Query("DELETE FROM food_entries")
    suspend fun clearAllFoods()
}
