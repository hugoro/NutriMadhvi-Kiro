package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 2026-07-06 (Frente I) — pre-computed Q&A entries the app expects the
 * user to ask.
 *
 * Populated by [com.example.data.notifications.PredictiveQaWorker] in
 * background (WorkManager) using [com.example.data.chat.PredictionEngine]
 * to enumerate likely questions and Gemini's fast tier to synthesise
 * the answer. Consumed by [com.example.data.chat.ChatOrchestrator.handle]
 * on every send: if the user's message hashes to a fresh cache row
 * whose `dataFingerprint` matches the current state, the orchestrator
 * emits the cached answer instantly and kicks the deep tier in
 * background to enrich the reply (see Frente H).
 *
 * Never treated as truth: cache is a fast-path bridge. A staleness
 * check on `dataFingerprint` invalidates any answer that would
 * contradict the user's current logs.
 */
@Entity(
    tableName = "predicted_qa",
    indices = [
        Index(value = ["queryHash"], unique = true),
        Index(value = ["expiresAtMs"]),
    ]
)
data class PredictedQaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Canonicalised hash of (intent, window, canonical text). Two
     *  different phrasings that classify to the same intent+window
     *  should collide here so the cache hit rate is decent. */
    @ColumnInfo(name = "queryHash") val queryHash: String,

    /** Short internal tag ("today_meals", "week_review", "protein_progress").
     *  Not shown to the user — useful for logs and per-tag budget
     *  accounting. */
    @ColumnInfo(name = "tag") val tag: String,

    /** Human-readable canonical form of the question the engine
     *  predicted. Logged when a hit fires so we can see what the user
     *  actually asked vs what the engine expected. */
    @ColumnInfo(name = "canonicalText") val canonicalText: String,

    /** The pre-computed answer text ready to render. Cleaned of any
     *  protocol tags before persist (parser runs once during synth). */
    @ColumnInfo(name = "answerText") val answerText: String,

    /** Fingerprint of the app state at synth time. Format:
     *   "$foodCount|$activityCount|$latestTargetTs|$latestBmrTs|$today"
     *  Any change invalidates the row — the cache never serves an
     *  answer that would contradict fresher data. */
    @ColumnInfo(name = "dataFingerprint") val dataFingerprint: String,

    /** Language code the answer was synthesised in ("pt-PT", "en-US",
     *  "gu-IN"). A hit requires exact match — a Portuguese cached
     *  answer must never be served to a user who switched to English. */
    @ColumnInfo(name = "language") val language: String,

    @ColumnInfo(name = "createdAtMs") val createdAtMs: Long = System.currentTimeMillis(),

    /** Absolute epoch-ms after which the row must not be served.
     *  Worker prunes expired rows on the next run. */
    @ColumnInfo(name = "expiresAtMs") val expiresAtMs: Long,
)
