package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 2026-07-06 (Frente I) — DAO for the predictive Q&A cache. See
 * [PredictedQaEntity] for the shape and invalidation contract.
 */
@Dao
interface PredictedQaDao {

    /** Insert or replace on `queryHash` collision — the newer synth
     *  wins since it has a fresher fingerprint. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PredictedQaEntity): Long

    /** Fetch a cache row by canonical hash. Callers must additionally
     *  verify `dataFingerprint == current` and `expiresAtMs > now`
     *  before serving — the DAO is a bag, not a smart cache. */
    @Query("SELECT * FROM predicted_qa WHERE queryHash = :queryHash LIMIT 1")
    suspend fun findByHash(queryHash: String): PredictedQaEntity?

    /** Delete rows whose `expiresAtMs` is in the past. Called by the
     *  worker at the start of every run so a growing app doesn't
     *  accumulate stale rows. Returns the number of rows deleted for
     *  logging. */
    @Query("DELETE FROM predicted_qa WHERE expiresAtMs < :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    /** Delete every row (used when the worker detects a global
     *  invalidation event — e.g. profile language changed). */
    @Query("DELETE FROM predicted_qa")
    suspend fun clear(): Int

    /** All rows regardless of expiry — used by the worker to decide
     *  which tags need re-synthesis. */
    @Query("SELECT * FROM predicted_qa ORDER BY createdAtMs DESC")
    suspend fun allRows(): List<PredictedQaEntity>
}
