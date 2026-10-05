package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    /** 2026-10-05 — one-shot prompt refresh: clear (or re-point) the
     *  server-side cachedContent reference for a session whose stored
     *  systemInstruction is about to be rewritten, so the next turn
     *  re-creates the cache with the new prompt instead of keeping the
     *  stale baked-in one. */
    @Query("UPDATE chat_sessions SET cachedContentName = :cacheName WHERE id = :id")
    suspend fun updateCachedContentName(id: Long, cacheName: String?)

    @Delete
    suspend fun deleteSession(session: ChatSession)

    @Query("SELECT * FROM chat_sessions ORDER BY lastMessageAt DESC, createdAt DESC")
    fun getAllSessionsFlow(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): ChatSession?

    /** v18→v19 (proactive notification engine): resolve the active session for
     *  a given proactive tag (e.g. `meals:2026-06-22` or
     *  `exercise:2026-W25`). Returns the most recently touched match so a
     *  fresh nudge lands on the conversation the user is actively reading.
     *  Returns null when no session with that tag exists yet — caller
     *  (ProactiveNudgeWorker) then inserts a new session with the tag set. */
    @Query("SELECT * FROM chat_sessions WHERE proactiveTag = :tag ORDER BY lastMessageAt DESC LIMIT 1")
    suspend fun findSessionByProactiveTag(tag: String): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Delete
    suspend fun deleteMessage(message: ChatMessage)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSessionFlow(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    /** Phase 4 (chat-redesign-2026-06-12): fetch every message at-or-after a
     *  given wall-clock timestamp. Used to enumerate the rows that
     *  [com.example.ui.ChatViewModel.confirmEditAndResubmit] is about to drop,
     *  so the VM can prune in-memory caches keyed by message id before the
     *  rows disappear. */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND timestamp >= :ts ORDER BY timestamp ASC")
    suspend fun getMessagesFromTimestamp(sessionId: Long, ts: Long): List<ChatMessage>

    /** Atomically delete the edited message AND every later message in the
     *  same session. Used by edit-and-resubmit (#4) and by the "Delete this
     *  message and all later ones" action of the long-press menu. */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND timestamp >= :ts")
    suspend fun deleteMessagesFromTimestamp(sessionId: Long, ts: Long)

    /** Update a message's text in place. Currently unused (the edit flow
     *  re-creates the message via the normal sendMessage path so the Gemini
     *  history stays consistent), but provided for future use cases that
     *  want to fix typos without a full re-send. */
    @Query("UPDATE chat_messages SET text = :newText, editedTimestamp = :editedTs WHERE id = :id")
    suspend fun updateMessageText(id: Long, newText: String, editedTs: Long)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)

    /** All non-null `imageBase64` values across every chat message,
     *  projected out as bare strings. Despite the legacy column name, the
     *  contents are stored as either a single `file://` path, a JSON array
     *  of paths, or — for pre-v15 rows — a raw base64 blob. The orphan-
     *  image cleanup worker calls
     *  [com.example.ui.ChatImageStore.decode] on each row to extract any
     *  on-disk references; base64 entries decode to `["<base64...>"]` and
     *  fail the `isPath` check, so they contribute no references and
     *  cannot accidentally protect a real file from cleanup.
     *
     *  Reads only the one column to keep the worker cheap on long chats. */
    @Query("SELECT imageBase64 FROM chat_messages WHERE imageBase64 IS NOT NULL")
    suspend fun allImagePayloads(): List<String>

    /**
     * 2026-08-31 — background chat reconciliation (mirrors the meal-analysis
     * salvage philosophy, which chat lacked). Finds sessions whose most
     * recent message is from the user with NO assistant reply at all —
     * i.e. a send that failed before a durable error row could be
     * persisted (pre-fix crashes, app kills mid-request, etc). [cutoffTs]
     * guards against flagging a session with a send still genuinely
     * in-flight when the reconciliation pass runs on app start.
     */
    @Query(
        """
        SELECT cm.sessionId FROM chat_messages cm
        WHERE cm.timestamp = (
            SELECT MAX(timestamp) FROM chat_messages WHERE sessionId = cm.sessionId
        )
        AND cm.sender = 'user'
        AND cm.timestamp < :cutoffTs
        """
    )
    suspend fun getDanglingUserOnlySessionIds(cutoffTs: Long): List<Long>

    /** Phase A refinement: bump the session's lastMessageAt so it floats to
     *  the top of the resume list. Called from the repo on every user-side
     *  send. Cheap (single-row UPDATE on a primary key). */
    @Query("UPDATE chat_sessions SET lastMessageAt = :ts WHERE id = :sessionId")
    suspend fun touchSession(sessionId: Long, ts: Long = System.currentTimeMillis())

    /**
     * 2026-06-15 cross-conversation search (WhatsApp-style).
     *
     * Joins `chat_messages_fts MATCH :query` (FTS4 full-text match) with
     * `chat_messages` to return the actual message rows, then with
     * `chat_sessions` to surface the conversation title alongside each hit.
     *
     * The `MATCH` operator accepts FTS4 query syntax — a bare word matches
     * that word, `word*` does a prefix match (callers append the `*`),
     * and quoted phrases like `"olive oil"` do phrase matching. We sort
     * by `chat_messages.timestamp DESC` so the most recent hits surface
     * first, matching WhatsApp's "newest first" ordering.
     *
     * Returns flat rows the UI can render directly without joining
     * downstream — keeps the search list lightweight. The `LIMIT 200`
     * caps memory / scroll depth on huge histories; if it ever bites we
     * can replace with paging.
     */
    @Query("""
        SELECT chat_messages.id          AS messageId,
               chat_messages.sessionId   AS sessionId,
               chat_messages.sender      AS sender,
               chat_messages.text        AS text,
               chat_messages.timestamp   AS timestamp,
               chat_sessions.title       AS sessionTitle
        FROM chat_messages_fts
        JOIN chat_messages ON chat_messages.id = chat_messages_fts.docid
        JOIN chat_sessions ON chat_sessions.id = chat_messages.sessionId
        WHERE chat_messages_fts MATCH :query
        ORDER BY chat_messages.timestamp DESC
        LIMIT 200
    """)
    suspend fun searchMessages(query: String): List<MessageSearchHit>
}

/**
 * Flat row returned by [ChatDao.searchMessages]. Carries enough info to
 * render a search-result row (sender, snippet, time) and to navigate
 * (sessionId + messageId) without hitting the DB again.
 */
data class MessageSearchHit(
    val messageId: Long,
    val sessionId: Long,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val sessionTitle: String
)
