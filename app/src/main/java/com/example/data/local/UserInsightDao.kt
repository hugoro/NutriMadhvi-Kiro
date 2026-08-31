package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserInsightDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(insight: UserInsight): Long

    @Update
    suspend fun update(insight: UserInsight)

    /** All confirmed, non-expired insights, freshest first. Used by the
     *  ChatContextBuilder data-card. */
    @Query(
        "SELECT * FROM user_insights " +
            "WHERE expiredAtMs IS NULL AND confirmedAtMs IS NOT NULL " +
            "ORDER BY confidence DESC, createdAtMs DESC " +
            "LIMIT :limit"
    )
    suspend fun topConfirmed(limit: Int = 8): List<UserInsight>

    /** Pending (unconfirmed) insights — surfaced in the "My Facts" management
     *  screen as a queue for Hugo to triage. */
    @Query(
        "SELECT * FROM user_insights " +
            "WHERE expiredAtMs IS NULL AND confirmedAtMs IS NULL " +
            "ORDER BY createdAtMs DESC"
    )
    fun pendingFlow(): Flow<List<UserInsight>>

    @Query(
        "SELECT * FROM user_insights " +
            "WHERE expiredAtMs IS NULL " +
            "ORDER BY confirmedAtMs IS NULL DESC, createdAtMs DESC"
    )
    fun allActiveFlow(): Flow<List<UserInsight>>

    @Query("UPDATE user_insights SET confirmedAtMs = :ts WHERE id = :id")
    suspend fun markConfirmed(id: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE user_insights SET expiredAtMs = :ts WHERE id = :id")
    suspend fun softDelete(id: Long, ts: Long = System.currentTimeMillis())

    /** Used by the analyzer to avoid re-emitting the same finding every run.
     *  Match is by exact text (case-insensitive) within the same category. */
    @Query(
        "SELECT * FROM user_insights " +
            "WHERE LOWER(text) = LOWER(:text) AND category = :category " +
            "AND expiredAtMs IS NULL " +
            "LIMIT 1"
    )
    suspend fun findByText(text: String, category: String): UserInsight?

    /** Phase E: most recent createdAtMs for any insight produced by the
     *  aggregate analyzer. Drives a 24-hour cooldown so kicking the chat
     *  ten times a day doesn't trigger ten redundant scans. Returns null
     *  on a fresh install / before the analyzer has ever run. */
    @Query(
        "SELECT MAX(createdAtMs) FROM user_insights WHERE source = 'aggregate_analyzer'"
    )
    suspend fun lastAggregateRunMs(): Long?

    // ── v23 (2026-06-29) Frente Memory C2.a — knowledge-base extensions ──

    /** All confirmed, non-expired insights. The recall ranker in
     *  [com.example.data.chat.InsightRecaller] does its own scoring on
     *  this set in Kotlin — full-scan is microseconds for <500 rows and
     *  lets us combine signals (keyword overlap, recency, mentions,
     *  confidence) that aren't expressible in a single SQL ORDER BY. */
    @Query(
        "SELECT * FROM user_insights " +
            "WHERE expiredAtMs IS NULL AND confirmedAtMs IS NOT NULL " +
            "ORDER BY id"
    )
    suspend fun allConfirmedActive(): List<UserInsight>

    /** Touched at the end of every chat turn for every insight the
     *  recall ranker injected into the prompt. Feeds the recency bias
     *  on the next turn. */
    @Query("UPDATE user_insights SET lastReferencedAtMs = :ts WHERE id = :id")
    suspend fun markReferenced(id: Long, ts: Long = System.currentTimeMillis())

    /** Bumped when an incoming user message has ≥1 keyword in common
     *  with this insight's [UserInsight.keywords]. Drives the
     *  `ln(1 + mentionCount)` term in [InsightRecaller.score]. */
    @Query("UPDATE user_insights SET mentionCount = mentionCount + 1 WHERE id = :id")
    suspend fun incrementMentionCount(id: Long)

    /** One-shot backfill target: rows whose [UserInsight.keywords] is
     *  still NULL after the v22→v23 migration. The repository worker
     *  pulls these, tokenises [UserInsight.text], and writes back via
     *  [updateKeywordsAndSummary]. Capped to avoid loading the whole
     *  table at once on a future install with thousands of rows. */
    @Query("SELECT * FROM user_insights WHERE keywords IS NULL LIMIT :limit")
    suspend fun missingKeywords(limit: Int = 200): List<UserInsight>

    /** Companion to [missingKeywords]: rows where the column is present
     *  but stores an empty string. Symptom of an early extractor build
     *  that called the insert helper without populating keywords. Treated
     *  by the backfill worker exactly like NULL — re-tokenise text and
     *  persist. */
    @Query("SELECT * FROM user_insights WHERE keywords = '' LIMIT :limit")
    suspend fun emptyKeywords(limit: Int = 200): List<UserInsight>

    @Query("UPDATE user_insights SET keywords = :keywords, summary = :summary WHERE id = :id")
    suspend fun updateKeywordsAndSummary(id: Long, keywords: String, summary: String?)

    // ── v23 Memory polish (2026-06-29) ──

    /** Inverse of [softDelete]: clears `expiredAtMs` so the row rejoins
     *  the active pool. Used by [com.example.ui.MemoryDialog]'s
     *  "Restore" affordance on the expired-collapsible section. */
    @Query("UPDATE user_insights SET expiredAtMs = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    /** ALL insights including soft-deleted ones — needed by the Memory
     *  screen's "Forgotten" collapsible. Confirmed + pending + expired.
     *  ORDER places freshly-touched ones first so the user sees activity
     *  at the top. */
    @Query(
        "SELECT * FROM user_insights " +
            "ORDER BY COALESCE(lastReferencedAtMs, createdAtMs) DESC, id DESC"
    )
    fun allIncludingExpiredFlow(): kotlinx.coroutines.flow.Flow<List<UserInsight>>
}
