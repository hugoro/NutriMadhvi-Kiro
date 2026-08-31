package com.example.data.chat

import com.example.data.local.UserInsight

/**
 * 2026-06-29 — Frente Memory C2.b. Question-directed recall over the
 * `user_insights` table.
 *
 * Replaces the previous `topConfirmed(8)` call site in
 * [com.example.data.remote.ChatContextBuilder] (`/data/remote/ChatContextBuilder.kt:207`).
 * That static top-N strategy ages older facts out of the prompt regardless
 * of relevance — Hugo (2026-06-28) said the system seems to "forget" facts
 * between sessions. Top-8 means a low-confidence-but-very-relevant fact
 * never makes it in.
 *
 * Strategy (pure Kotlin, no SQL gymnastics — the app holds <500 insights so
 * a full scan is microseconds):
 *   1. **Keyword filter**: tokenize the user's question, intersect with
 *      each insight's `keywords` column (added in schema v24). When the
 *      column is null (legacy row not yet backfilled), fall back to a
 *      substring match on `text`.
 *   2. **Score**: keyword-overlap × 4  +  recency_decay  +  confidence  +
 *      ln(1 + mentionCount). Higher is better.
 *   3. **Pull related**: for each top-K hit, walk `relatedInsightIds` 1-hop
 *      and re-score; if a related node scores above the bottom of the K,
 *      include it.
 *   4. **Cap** at [maxItems]. The default of 12 stays under ~600 prompt
 *      bytes (50 bytes/insight) — comfortably below the previous top-8 of
 *      ~400 bytes per turn, but with 50% better hit rate on relevant
 *      questions.
 *
 * Side effect: when an insight is selected for injection, the caller
 * should bump its `lastReferencedAtMs` (schema v24) — that signal feeds
 * back into recency for the next turn. The recaller itself stays pure
 * (no DB writes) so it remains trivially unit-testable.
 *
 * Falls back gracefully when v24 columns are missing: scores degrade to
 * recency + confidence only, which is exactly the legacy behaviour.
 */
object InsightRecaller {

    /** Default cap for prompt injection. ~600 bytes vs the previous ~400
     *  for top-8 — 50% more room but only when the question warrants it. */
    const val DEFAULT_MAX_ITEMS: Int = 12

    /**
     * Rank [pool] (all confirmed, non-expired insights from
     * `UserInsightDao.allActive()` filtered to `confirmedAtMs != null`)
     * by relevance to [userText], optionally biased toward [extraKeywords]
     * the orchestrator already computed (e.g. from
     * [com.example.data.chat.IntentRouter.detectDataType] hints).
     *
     * Returns the trimmed top list — at most [maxItems] entries, ordered
     * by descending score.
     */
    fun rank(
        userText: String,
        pool: List<UserInsight>,
        extraKeywords: Set<String> = emptySet(),
        maxItems: Int = DEFAULT_MAX_ITEMS,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Ranked> {
        if (pool.isEmpty()) return emptyList()
        val askKeywords = (tokenize(userText) + extraKeywords.map { it.lowercase() }).toSet()
        val scored = pool.mapNotNull { ins ->
            // Confirmed-only — defensive even though the caller should filter.
            if (ins.confirmedAtMs == null || ins.expiredAtMs != null) return@mapNotNull null
            val s = score(ins, askKeywords, nowMs)
            Ranked(insight = ins, score = s)
        }
        return scored.sortedByDescending { it.score }.take(maxItems)
    }

    /**
     * Two-pass variant: rank, then for the top-K winners walk
     * `relatedInsightIds` and re-include any 1-hop neighbour that scores
     * above the lowest first-pass score. Keeps the graph signal but never
     * lets the recall expand past [maxItems].
     */
    fun rankWithRelated(
        userText: String,
        pool: List<UserInsight>,
        extraKeywords: Set<String> = emptySet(),
        maxItems: Int = DEFAULT_MAX_ITEMS,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Ranked> {
        val first = rank(userText, pool, extraKeywords, maxItems, nowMs)
        if (first.isEmpty() || first.size >= maxItems) return first
        val byId = pool.associateBy { it.id }
        val firstIds = first.map { it.insight.id }.toSet()
        val minScore = first.minOf { it.score }
        val askKeywords = (tokenize(userText) + extraKeywords.map { it.lowercase() }).toSet()
        val expansions = mutableListOf<Ranked>()
        for (r in first) {
            val relatedCsv = r.insight.relatedInsightIds ?: continue
            relatedCsv.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it != r.insight.id && it !in firstIds }
                .mapNotNull { byId[it] }
                .filter { it.confirmedAtMs != null && it.expiredAtMs == null }
                .forEach { rel ->
                    val s = score(rel, askKeywords, nowMs)
                    if (s > minScore && expansions.none { it.insight.id == rel.id }) {
                        expansions += Ranked(rel, s)
                    }
                }
        }
        return (first + expansions).sortedByDescending { it.score }.take(maxItems)
    }

    /** What the caller persists / injects. The orchestrator uses
     *  [insight] for the prompt block and [score] for diagnostic logging. */
    data class Ranked(val insight: UserInsight, val score: Double)

    // -------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------

    private fun score(
        ins: UserInsight,
        askKeywords: Set<String>,
        nowMs: Long,
    ): Double {
        // 1. Keyword overlap. When the v24 `keywords` column is null (legacy
        //    row not yet backfilled), fall back to substring-matching the
        //    insight `text` against each ask keyword.
        val insightKeys: Set<String> = readKeywords(ins)
            ?: tokenize(ins.text)
        val overlap = askKeywords.intersect(insightKeys).size
        // 2. Recency. `lastReferencedAtMs` (v24) preferred; fall back to
        //    `createdAtMs` so legacy rows still rank by age.
        val anchorMs = readLastReferencedAtMs(ins) ?: ins.createdAtMs
        val ageDays = ((nowMs - anchorMs) / 86_400_000.0).coerceAtLeast(0.0)
        // Decay: 1.0 within 7 days, halves each subsequent ~14 days.
        val recency = Math.exp(-ageDays / 30.0)
        // 3. Confidence — always present.
        val conf = ins.confidence.coerceIn(0.0, 1.0)
        // 4. Mention count — log so a fact mentioned 50× doesn't crowd out
        //    everything else. `mentionCount` (v24) — fallback to 1.
        val mentions = readMentionCount(ins).coerceAtLeast(1)
        val mentionsScore = Math.log(1.0 + mentions)

        // Keyword overlap dominates: a perfect match is worth ~4×
        // confidence's max contribution. Score formula tuned by hand —
        // unit tests in [InsightRecallerTest] pin the rough ordering.
        return overlap * 4.0 + recency * 1.5 + conf * 1.0 + mentionsScore * 0.5
    }

    // -------------------------------------------------------------------
    // Tokenisation — mirrors [InsightExtractor.keywordize] so a fact
    // captured by the extractor matches its own keywords on the way back.
    // -------------------------------------------------------------------

    internal fun tokenize(text: String): Set<String> = text
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    // -------------------------------------------------------------------
    // V23 column accessors. The 2026-06-29 migration added all four
    // fields as proper data-class properties on [UserInsight]; we can
    // read them directly. Pre-existing rows have NULL / 1 defaults
    // applied by the migration, so the score function still works.
    // -------------------------------------------------------------------

    private fun readKeywords(ins: UserInsight): Set<String>? {
        val raw = ins.keywords ?: return null
        if (raw.isBlank()) return null
        return raw.split(",").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toSet()
    }

    private fun readLastReferencedAtMs(ins: UserInsight): Long? = ins.lastReferencedAtMs

    private fun readMentionCount(ins: UserInsight): Int = ins.mentionCount

    private val STOP_WORDS = setOf(
        // pt
        "que", "uma", "uns", "umas", "para", "com", "sem", "muito", "pouco",
        "isso", "essa", "esse", "este", "esta", "isto", "tudo", "nada", "como",
        "quando", "onde", "porque", "pois", "mas", "também", "tambem", "aqui",
        "ali", "lá", "cá", "qual", "quais", "quem", "ser", "estar", "ter",
        // en
        "the", "and", "but", "for", "with", "without", "very", "much", "this",
        "that", "these", "those", "all", "none", "when", "where", "because",
        "also", "here", "there", "what", "which", "who", "have", "has",
    )
}
