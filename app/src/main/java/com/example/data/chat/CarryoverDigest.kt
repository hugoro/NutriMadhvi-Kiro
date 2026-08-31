package com.example.data.chat

import android.content.Context

/**
 * 2026-06-29 — Frente Memory C3. Cross-session "carry-over digest".
 *
 * The rolling-window of v183 ([ChatOrchestrator.handle] intra-session
 * trimming) keeps each session's history compact, but every NEW session
 * starts blind to what the user discussed in the previous one. This
 * forces them to re-introduce context ("eu já te tinha dito que…") and
 * is the second face of the same "system doesn't remember" complaint
 * the user raised on 2026-06-28.
 *
 * Solution: maintain a single short text (~200-400 chars) that summarises
 * "what has been happening in the user's recent conversations". Updated
 * incrementally on every assistant turn — one factual sentence is
 * appended; once total length crosses [MAX_CHARS], the OLDEST 60% is
 * re-summarised by Flash into a single sentence and prepended. Net
 * length stays bounded.
 *
 * Storage: SharedPreferences `chat_memory/carryover_digest_v1`. Pure
 * String, no schema. Per-install (= per-user given the new "one device,
 * one user" architecture). Survives session deletion, app updates, and
 * cold starts — but is just metadata, never read by anyone except the
 * prompt builder. Soft-resettable via [reset] from a Settings screen
 * (not wired yet).
 *
 * Injected into the prompt by [ChatOrchestrator.handle] as a short
 * "## Cross-session digest" block before "Known facts about <user>".
 * The model treats it as background continuity, not as authoritative
 * data — the canonical snapshot and UserInsights still drive the live
 * numbers.
 *
 * NOT a vector DB. NOT semantic retrieval. Just a rolling string the
 * model reads on every turn. Cost: ~100 tokens / turn extra. Worth it
 * because it kills the "re-introduce yourself every session" UX bug.
 */
object CarryoverDigest {

    private const val PREFS = "chat_memory"
    private const val KEY = "carryover_digest_v1"
    private const val LAST_COMPRESSED_AT_MS = "carryover_compressed_at_ms_v1"

    /** Hard cap on the persisted digest length. Past this, compression
     *  kicks in. Tuned so the prompt injection stays ≤ ~100 tokens. */
    internal const val MAX_CHARS = 400

    /** When compressing, drop the OLDEST [COMPRESS_FRACTION] of the
     *  text and replace it with a 1-sentence summary. Anything ≤ this
     *  fraction stays verbatim. 0.6 = compress 60%, keep most-recent
     *  40% verbatim. */
    internal const val COMPRESS_FRACTION = 0.6

    /** Currently persisted digest. Empty string when unset. Reading is
     *  synchronous + lock-free — SharedPreferences in MODE_PRIVATE is
     *  the canonical Android pattern for this size of data. */
    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""

    /**
     * Append a single factual sentence describing what just happened in
     * this turn. Called by [ChatOrchestrator.handle] after the assistant
     * reply has been parsed + persisted, in [backgroundScope] so the
     * user never waits on this.
     *
     * The [sentence] caller-supplied is expected to be telegram-style:
     * "Hugo logou jantar de pizza" / "Madhvi mencionou cansaço pós-treino"
     * / "Almoço hoje: 540 kcal, baixo em proteína". The orchestrator
     * synthesises this from the user message + the LLM reply at turn-
     * close time using a tiny Flash call (in [maybeAppend]).
     *
     * When the accumulated digest crosses [MAX_CHARS], invokes
     * [compress] to halve it.
     */
    suspend fun appendAndMaybeCompress(
        context: Context,
        sentence: String,
        compressFn: suspend (oldestChunk: String) -> String?,
    ) {
        if (sentence.isBlank()) return
        val trimmed = sentence.trim().trimEnd('.', '!', '?') + "."
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY, "") ?: ""
        val appended = if (current.isBlank()) trimmed else "$current $trimmed"

        if (appended.length <= MAX_CHARS) {
            prefs.edit().putString(KEY, appended).apply()
            android.util.Log.d(
                "CHAT_CARRYOVER",
                "append: len=${appended.length}/${MAX_CHARS}"
            )
            return
        }

        // Past the cap — compress.
        val cutIdx = (appended.length * COMPRESS_FRACTION).toInt()
        // Cut at a sentence boundary if possible.
        val safeCut = appended.indexOf('.', cutIdx).let { dot ->
            if (dot in cutIdx..(cutIdx + 60)) dot + 1 else cutIdx
        }
        val oldestChunk = appended.substring(0, safeCut).trim()
        val mostRecentChunk = appended.substring(safeCut).trim()
        val summarised = try {
            compressFn(oldestChunk)
        } catch (t: Throwable) {
            android.util.Log.w("CHAT_CARRYOVER", "compress threw: ${t.message?.take(120)}")
            null
        }
        val nextDigest = if (summarised.isNullOrBlank()) {
            // Fallback: drop the oldest chunk entirely. Better lose old
            // context than blow the cap.
            android.util.Log.w("CHAT_CARRYOVER", "compress null — dropping oldest ${oldestChunk.length}chars")
            mostRecentChunk
        } else {
            val merged = (summarised.trim().trimEnd('.') + ". $mostRecentChunk").trim()
            // Defensive: if the summariser returned something HUGE,
            // truncate to half of MAX_CHARS so we never paint ourselves
            // into a corner.
            if (merged.length > MAX_CHARS) {
                android.util.Log.w("CHAT_CARRYOVER", "compress produced ${merged.length}chars > cap, truncating")
                merged.take(MAX_CHARS - 1)
            } else {
                merged
            }
        }
        prefs.edit()
            .putString(KEY, nextDigest)
            .putLong(LAST_COMPRESSED_AT_MS, System.currentTimeMillis())
            .apply()
        android.util.Log.d(
            "CHAT_CARRYOVER",
            "compressed: len=${appended.length} → ${nextDigest.length}"
        )
    }

    /**
     * Markdown block to inject into the system prompt. Empty string
     * when the digest is blank (no need to clutter the prompt with an
     * empty section). Caller (ChatOrchestrator) appends to
     * `dynamicSystemText` BEFORE the kbBlock so the model reads context
     * in order: recent cross-session continuity → factual recall hits.
     */
    fun toPromptBlock(context: Context): String {
        val d = load(context)
        if (d.isBlank()) return ""
        return "\n\n## Cross-session digest (recent context across past chats)\n$d\n"
    }

    /** Manual reset, e.g. from a future Settings screen "Clear memory"
     *  button. Not currently wired. Soft-resets only — doesn't touch
     *  UserInsights, FoodHabits, or anything else. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .remove(LAST_COMPRESSED_AT_MS)
            .apply()
        android.util.Log.i("CHAT_CARRYOVER", "reset")
    }
}
