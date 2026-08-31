package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase E: persistent facts the chat assistant has learned about the user.
 *
 * Use cases:
 *  - "Hugo é intolerante a lactose" — preference/allergy.
 *  - "Treina para uma maratona em outubro 2026" — goal.
 *  - "Nutricionista Dra. X recomendou subir proteína para 130g" — professional.
 *  - "Em 80% dos dias acima do target kcal, o utilizador comeu pizza" —
 *    aggregate_pattern (produced by a future AggregateInsightAnalyzer).
 *
 * Append-only by design: editing the past would silently rewrite reports
 * already shared with the nutritionist. Soft-delete via [expiredAtMs] when
 * a fact stops being true.
 *
 * Reads pick the row whose [expiredAtMs] is null AND whose category matches.
 * The data card injected into the chat system instruction surfaces a small
 * top-N slice (most recent + highest confidence).
 */
@Entity(
    tableName = "user_insights",
    indices = [
        Index(value = ["category"]),
        Index(value = ["expiredAtMs"])
    ]
)
data class UserInsight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The fact in natural language, in the user's primary language. The
     *  assistant injects this verbatim into future system prompts so it
     *  speaks the user's language back to them. */
    val text: String,

    /** One of:
     *  - `"preference"` — likes/dislikes.
     *  - `"allergy"` — must-avoid for medical reasons.
     *  - `"goal"` — what the user is trying to achieve.
     *  - `"professional"` — a recommendation from nutritionist/PT.
     *  - `"aggregate_pattern"` — derived statistical regularity.
     *  - `"other"` — fallback.
     *
     * Stored as String to avoid TypeConverter churn; the UI maps to friendly
     * labels via i18n. */
    val category: String,

    /** 0.0–1.0. ≥0.85 + source=user_stated → auto-confirmed at insertion;
     *  otherwise [confirmedAtMs] stays null until Hugo presses Confirm on the
     *  inline card. */
    val confidence: Double,

    /** Where the insight came from. One of:
     *  - `"user_stated"` — the user typed it directly ("sou intolerante...").
     *  - `"llm_inferred"` — Gemini guessed it from context.
     *  - `"aggregate_analyzer"` — a background job derived it from history. */
    val source: String,

    /** ChatSession.id that produced this insight (when applicable). Null for
     *  aggregates and other system-derived insights. */
    val sourceSessionId: Long? = null,

    /** Wall-clock ms when this row was inserted. */
    val createdAtMs: Long = System.currentTimeMillis(),

    /** Set when the user explicitly confirmed (or when source=user_stated +
     *  confidence ≥0.85 auto-confirms). Pending insights have null here and
     *  do NOT show up in the chat data card. */
    val confirmedAtMs: Long? = null,

    /** Soft-delete: when set, the insight stops being injected into prompts.
     *  Keeps an audit trail of what was once true. */
    val expiredAtMs: Long? = null,

    /** Optional comma-separated list of related UserInsight ids for graph
     *  navigation. Populated by the AggregateInsightAnalyzer when a cluster
     *  of facts coalesces (e.g. all "high-sugar day" patterns). */
    val relatedInsightIds: String? = null,

    // ───── v23 (2026-06-29) Frente Memory C2.a — knowledge-base columns ─────
    // All four are nullable / defaulted so pre-existing rows survive the
    // migration unchanged. Backfill is lazy via a one-shot worker on first
    // launch; until then the InsightRecaller's reflection accessors degrade
    // gracefully (see InsightRecaller.kt).

    /** CSV of lower-case keyword tokens extracted from [text]. Used by
     *  [com.example.data.chat.InsightRecaller] to score relevance against
     *  the user's current question without resorting to a vector DB. NULL
     *  for legacy rows until the backfill worker fills them in.
     *
     *  Example: text="É intolerante a lactose." → keywords="intolerante,lactose". */
    val keywords: String? = null,

    /** Wall-clock ms when this insight was last cited in a chat turn. Drives
     *  the recency bonus in [com.example.data.chat.InsightRecaller.score].
     *  NULL means "never referenced after creation" → falls back to
     *  [createdAtMs] for recency calculations. */
    val lastReferencedAtMs: Long? = null,

    /** How many times the user has brought up the topic of this insight
     *  (counted by keyword overlap in incoming user turns). Starts at 1 on
     *  insert; legacy rows that pre-date this column also keep 1 via the
     *  default below. Used in [InsightRecaller.score] as `ln(1+mentionCount)`
     *  so a fact mentioned 50× doesn't crowd out everything else. */
    val mentionCount: Int = 1,

    /** Short (~50 char) one-line restatement of [text] used when several
     *  insights are injected into the same prompt to keep the block
     *  compact. NULL = use [text] as-is. Populated by the same backfill
     *  worker as [keywords]. */
    val summary: String? = null,
)
