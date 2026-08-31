package com.example.data.chat

import com.example.data.local.UserInsight
import java.time.LocalDate

/**
 * 2026-07-06 (Frente I) — deterministic engine that enumerates
 * questions the user is LIKELY to ask right now.
 *
 * Pure Kotlin, no LLM here — the LLM is only involved AFTER a
 * question is predicted, to synthesise the answer. Sources of signal:
 *
 * 1. **Time-of-day pattern**: mornings get "resumo de ontem",
 *    lunchtime gets "quanto já comi hoje?", evenings get "recap".
 * 2. **Top-N confirmed insights** (`UserInsight.topConfirmed`): if
 *    the user has an active `NUTRITION_GOAL` or `ROUTINE` insight,
 *    the corresponding progress question is likely.
 * 3. **User's recent chat history** (top-K most repeated questions
 *    in `chat_messages` with `sender="user"`, canonicalised via
 *    [IntentRouter.classify]).
 *
 * Returns AT MOST 6 predictions per call — a budget cap on how many
 * LLM syntheses the worker triggers per run. Higher values would
 * burn quota on unlikely questions.
 */
object PredictionEngine {

    /** Hard cap on predictions per run. Keeps quota bounded when the
     *  KB is rich (which it usually is for engaged users). */
    private const val MAX_PREDICTIONS: Int = 6

    data class PredictedQuery(
        val intent: ChatIntent.DataQuery,
        val canonicalText: String,
        val tag: String,
    )

    /**
     * Enumerate likely questions. [nowHour] is 0..23; [userInsights]
     * is the CONFIRMED slice ordered by descending mention count so
     * frequently-mentioned tópicos rank higher.
     */
    fun predictLikelyQuestions(
        today: LocalDate,
        nowHour: Int,
        userInsights: List<UserInsight>,
        recentUserQuestions: List<String>,
    ): List<PredictedQuery> {
        val out = mutableListOf<PredictedQuery>()

        // 1. Time-of-day baseline. Every run gets at least one of
        //    these so the cache stays populated for cold periods.
        val timeOfDay = timeOfDayPrediction(today, nowHour)
        if (timeOfDay != null) out += timeOfDay

        // 2. Top-N insight-derived questions. Iterate in the caller's
        //    order (already ranked by mentionCount/recency).
        for (insight in userInsights) {
            if (out.size >= MAX_PREDICTIONS) break
            val fromInsight = insightPrediction(insight, today) ?: continue
            // De-dup by tag — a repeat category shouldn't double-book
            // the cache when we already have one prediction for it.
            if (out.any { it.tag == fromInsight.tag }) continue
            out += fromInsight
        }

        // 3. Recent user questions (top-K distinct). Canonicalise via
        //    IntentRouter so "how much protein today" and "have I hit
        //    my protein" collapse to the same intent.
        for (q in recentUserQuestions) {
            if (out.size >= MAX_PREDICTIONS) break
            val classified = IntentRouter.classify(q, today)
            val asDataQuery = when (classified) {
                is ChatIntent.DataQuery -> classified
                is ChatIntent.Reasoning -> ChatIntent.DataQuery(
                    type = DataType.BOTH,
                    window = DateWindow(
                        from = today.minusDays(6),
                        to = today,
                        label = "last 7 days",
                    ),
                    originalText = q
                )
            }
            val tag = "user_recent_${q.take(20).lowercase().replace(Regex("[^a-z0-9]"), "_")}"
            if (out.any { it.tag == tag }) continue
            out += PredictedQuery(
                intent = asDataQuery,
                canonicalText = q,
                tag = tag,
            )
        }

        return out.take(MAX_PREDICTIONS)
    }

    private fun timeOfDayPrediction(today: LocalDate, nowHour: Int): PredictedQuery? {
        return when (nowHour) {
            in 5..11 -> PredictedQuery(
                intent = ChatIntent.DataQuery(
                    type = DataType.BOTH,
                    window = DateWindow(
                        from = today.minusDays(1),
                        to = today.minusDays(1),
                        label = "yesterday",
                    ),
                    originalText = "Faz-me o resumo de ontem",
                ),
                canonicalText = "Faz-me o resumo de ontem",
                tag = "morning_yesterday_recap",
            )
            in 12..14 -> PredictedQuery(
                intent = ChatIntent.DataQuery(
                    type = DataType.MEALS,
                    window = DateWindow(
                        from = today,
                        to = today,
                        label = "today",
                    ),
                    originalText = "O que já comi hoje?",
                ),
                canonicalText = "O que já comi hoje?",
                tag = "midday_today_meals",
            )
            in 15..18 -> PredictedQuery(
                intent = ChatIntent.DataQuery(
                    type = DataType.BOTH,
                    window = DateWindow(
                        from = today,
                        to = today,
                        label = "today",
                    ),
                    originalText = "Como estou hoje face aos meus alvos?",
                ),
                canonicalText = "Como estou hoje face aos meus alvos?",
                tag = "afternoon_progress",
            )
            in 19..22 -> PredictedQuery(
                intent = ChatIntent.DataQuery(
                    type = DataType.BOTH,
                    window = DateWindow(
                        from = today,
                        to = today,
                        label = "today",
                    ),
                    originalText = "Recap de hoje",
                ),
                canonicalText = "Recap de hoje",
                tag = "evening_recap",
            )
            else -> null // night / early morning: nothing pre-cached
        }
    }

    private fun insightPrediction(insight: UserInsight, today: LocalDate): PredictedQuery? {
        val cat = insight.category.uppercase()
        return when {
            cat.contains("NUTRITION") || cat.contains("GOAL") -> PredictedQuery(
                intent = ChatIntent.DataQuery(
                    type = DataType.MEALS,
                    window = DateWindow(
                        from = today.minusDays(6),
                        to = today,
                        label = "last 7 days",
                    ),
                    originalText = "Estou a bater a proteína nos últimos 7 dias?",
                ),
                canonicalText = "Estou a bater a proteína nos últimos 7 dias?",
                tag = "insight_nutrition_progress",
            )
            cat.contains("ROUTINE") || cat.contains("TRAINING") -> PredictedQuery(
                intent = ChatIntent.DataQuery(
                    type = DataType.ACTIVITY,
                    window = DateWindow(
                        from = today.minusDays(29),
                        to = today,
                        label = "last 30 days",
                    ),
                    originalText = "Como está a minha consistência no treino este mês?",
                ),
                canonicalText = "Como está a minha consistência no treino este mês?",
                tag = "insight_training_consistency",
            )
            else -> null
        }
    }

    /** Canonical query hash used both to persist and to look up.
     *  Uses only intent+window (not free text) so different phrasings
     *  of the same question collide on the same row. */
    fun canonicalHash(intent: ChatIntent.DataQuery, language: String): String {
        return "${language}|${intent.type}|${intent.window.from}|${intent.window.to}"
    }
}
