package com.example.data.notifications

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 2026-06-26 redesign: proactive notifications no longer write a
 * [com.example.data.local.ChatSession] + [com.example.data.local.ChatMessage]
 * row at delivery time. Instead, [ProactiveNudgeWorker] packs everything it
 * would have written into this payload, drops the (compressed) bitmap onto
 * disk, and stuffs the rest into the notification's tap intent. The session
 * is only materialized when the user actually taps — see
 * [com.example.MainActivity.handleNotificationIntent].
 *
 * **Why a JSON string in the Intent?** Intent extras have a soft cap around
 * 1 MB; a Parcelable + Bitmap would exceed it for BigPicture renders. JSON
 * with a file pointer keeps the intent payload to ~1 kB while letting the
 * bitmap live in `filesDir/proactive_nudges/`.
 *
 * **Why not just SharedPreferences/Room?** Persisting outside the intent
 * would couple "notification still in shade" to "row still exists" — if the
 * user clears the app cache or the worker GC trims stale entries, taps go
 * dead. The intent is the canonical source of truth for as long as the
 * notification is alive; once tapped (or dismissed) it ceases to exist
 * along with its file.
 */
data class PendingProactiveNudge(
    /** Tag shared across notifications of the same logical thread:
     *  - `meals:<iso-date>`  → 1 session/day shared across breakfast/lunch/dinner
     *  - `exercise:<iso-week>` → 1 session/ISO-week
     *  - null → MacrosAtRisk / DailySummary (always spawn fresh) */
    val proactiveTag: String?,
    /** Localized title used both for the OS notification AND the
     *  [com.example.data.local.ChatSession.title] when the session is
     *  materialized. Pre-resolved at delivery time so re-materialization
     *  in a different language is impossible (and not needed). */
    val sessionTitle: String,
    /** Localized system instruction baked from the user profile at
     *  delivery time. Same rationale as [sessionTitle]. */
    val systemInstruction: String,
    /** The exact message that would have been written to
     *  [com.example.data.local.ChatMessage.text] — visual header (ASCII
     *  bars) + LLM prose joined with "\n\n". Persisted verbatim. */
    val chatBubbleText: String,
    /** Absolute path to the rendered progress-bar bitmap on
     *  `filesDir/proactive_nudges/`. Null when ActivitySpike rendering
     *  failed silently and the notification used BigTextStyle. The
     *  message text already contains the ASCII header so the chat bubble
     *  doesn't need the bitmap — it's only needed for the OS notification
     *  body, which is created in-memory and never re-read after delivery.
     *  Kept here so a follow-up "re-open notification" flow could re-use
     *  it if we ever add one. */
    val bitmapFilePath: String?,
    /** ms-since-epoch when the notification was delivered. Used as the
     *  message timestamp on materialization so the chat bubble lands at
     *  the right point in the day instead of "now (when you tapped)". */
    val deliveredAtMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put(KEY_PROACTIVE_TAG, proactiveTag ?: JSONObject.NULL)
        put(KEY_SESSION_TITLE, sessionTitle)
        put(KEY_SYSTEM_INSTRUCTION, systemInstruction)
        put(KEY_CHAT_BUBBLE_TEXT, chatBubbleText)
        put(KEY_BITMAP_FILE_PATH, bitmapFilePath ?: JSONObject.NULL)
        put(KEY_DELIVERED_AT_MS, deliveredAtMs)
    }.toString()

    companion object {
        const val EXTRA_PENDING_NUDGE_JSON = "pendingNudgeJson"

        private const val KEY_PROACTIVE_TAG = "tag"
        private const val KEY_SESSION_TITLE = "title"
        private const val KEY_SYSTEM_INSTRUCTION = "sys"
        private const val KEY_CHAT_BUBBLE_TEXT = "msg"
        private const val KEY_BITMAP_FILE_PATH = "img"
        private const val KEY_DELIVERED_AT_MS = "ts"

        fun fromJson(json: String): PendingProactiveNudge? = runCatching {
            val obj = JSONObject(json)
            PendingProactiveNudge(
                proactiveTag = obj.optString(KEY_PROACTIVE_TAG).takeIf {
                    !obj.isNull(KEY_PROACTIVE_TAG) && it.isNotEmpty()
                },
                sessionTitle = obj.getString(KEY_SESSION_TITLE),
                systemInstruction = obj.getString(KEY_SYSTEM_INSTRUCTION),
                chatBubbleText = obj.getString(KEY_CHAT_BUBBLE_TEXT),
                bitmapFilePath = obj.optString(KEY_BITMAP_FILE_PATH).takeIf {
                    !obj.isNull(KEY_BITMAP_FILE_PATH) && it.isNotEmpty()
                },
                deliveredAtMs = obj.getLong(KEY_DELIVERED_AT_MS),
            )
        }.getOrNull()

        /**
         * Persist [bitmap] under `filesDir/proactive_nudges/<uuid>.png` and
         * return the absolute path. Caller is responsible for choosing when
         * (if ever) to delete the file — currently we let it leak since each
         * file is tiny (~50 kB) and proactive notifications are at most a
         * few per day.
         */
        fun persistBitmap(ctx: Context, bitmap: Bitmap): String? = runCatching {
            val dir = File(ctx.filesDir, "proactive_nudges").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }
}
