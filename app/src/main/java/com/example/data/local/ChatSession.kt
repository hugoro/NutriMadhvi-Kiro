package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["proactiveTag"])]
)
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val model: String = "gemini-3.5-flash",
    val systemInstruction: String = "",
    /** Phase G: differentiates personal chat from a session intended to feed a
     *  professional (nutritionist or personal trainer). Drives system-prompt
     *  bias and the "Generate report" affordance. Values: "personal" |
     *  "nutritionist" | "personal_trainer". Stored as String to keep the
     *  schema simple. */
    val sessionType: String = "personal",
    /** Phase A refinement (v13→v14): wall-clock ms of the last user message
     *  in this session. Drives "resume the chat I was actually using" instead
     *  of "resume the most recently created chat". Defaults to [createdAt] for
     *  legacy rows so freshly-migrated installs sort sensibly. Updated by the
     *  repository on every user-side `sendMessage` and on session creation. */
    val lastMessageAt: Long = createdAt,
    /** v17→v18 (plan ADENDO 2 — Fix 9): name of the Gemini explicit
     *  context cache holding this session's stable system-prompt prefix
     *  (HABIT_PROTOCOL + template + nameDirective). Format
     *  `cachedContents/{id}`. Null when no cache has been created yet
     *  (legacy sessions, very fresh sessions where the create call is
     *  in-flight, or sessions whose last cache was deleted/expired).
     *  Populated lazily by [ChatOrchestrator] on the first send of an
     *  uncached session; cleared on 404 to force re-creation. */
    val cachedContentName: String? = null,
    /** v18→v19 (proactive notification engine, 2026-06-22): groups
     *  sessions that share a proactive notification thread so multiple
     *  nudges across the same window land on the *same* chat instead of
     *  spamming new ones. Format:
     *    - meals     → `meals:<iso-date>`     (one session per day,
     *                                          shared across breakfast/
     *                                          lunch/dinner reminders)
     *    - exercise  → `exercise:<year>-W<iso-week>` (one per ISO week)
     *    - other     → null  (macros-at-risk / daily-summary always get
     *                         a fresh session, by design)
     *  Null for every session created before v19 and for every regular
     *  user-initiated chat. Indexed (see [ChatDao.findSessionByProactiveTag]). */
    val proactiveTag: String? = null,
    /** v23→v24 (rolling-window history, 2026-06-29): one-line digest of
     *  the OLDEST turns in this session, summarising what has dropped
     *  out of the LLM payload because the rolling cap was reached. Used
     *  by [com.example.data.chat.ChatOrchestrator] to inject a single
     *  `[PREVIOUS_TURNS_SUMMARY] …` line when the conversation grows
     *  past the cap (currently 30 turns / 32 KB), so the model still has
     *  context for what was discussed earlier without re-sending every
     *  byte. Null means "no rolling cap reached yet" — the whole history
     *  fits inside the cap and goes verbatim. The ChatMessage rows
     *  themselves are NEVER deleted; this column is metadata only.
     *  Populated incrementally by the orchestrator on every turn that
     *  needs to roll. */
    val digestSummary: String? = null
)
