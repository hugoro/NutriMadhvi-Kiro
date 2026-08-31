package com.example.data.chat

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Local-first intent classifier for chat messages — inspired by the
 * `IntentRouter` in the macOS ITOpsEA app
 * (`/Users/i548178/GitHub/ITOpsEA/Sources/IntentRouter.swift`).
 *
 * Idea: instead of letting the LLM decide whether to emit `[QUERY_DATA]`
 * (which leaks raw protocol when the model omits a closing tag, invents
 * field names, or misformats the JSON — see Hugo's screenshots from
 * 2026-06-17), classify the user's wording locally with a tiny regex
 * registry and pre-fetch the data BEFORE calling Gemini. The LLM then
 * only synthesises the pre-fetched facts, never asks for more.
 *
 * This file does ZERO LLM work — it is just regex + heuristics.
 */
sealed class ChatIntent {
    /** The user is asking about a specific window of their nutrition or
     *  activity history. The orchestrator will pre-fetch [window] from the
     *  Room DAOs in parallel and inject the results into the system prompt. */
    data class DataQuery(
        val type: DataType,
        val window: DateWindow,
        val originalText: String
    ) : ChatIntent()

    /** Anything else — open-ended question, theoretical chat, recipe ask,
     *  or just "olá". The orchestrator falls back to the canonical snapshot
     *  (today + 7d + month-so-far + 30d). The LLM may still emit a
     *  `[QUERY_DATA]` block as a fallback if it really needs other data —
     *  the salvage parser covers malformed variants. */
    data class Reasoning(val originalText: String) : ChatIntent()
}

enum class DataType { MEALS, ACTIVITY, BOTH }

/** A closed inclusive date range plus a human-readable label that the
 *  orchestrator forwards into the PRE-FETCHED block so the LLM knows what
 *  the user actually asked. */
data class DateWindow(
    val from: LocalDate,
    val to: LocalDate,
    val label: String
) {
    /** Total days in the window, both bounds inclusive. */
    val days: Int get() = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
}

object IntentRouter {

    /** Hard cap on any data query, mirroring the existing 90-day cap in
     *  `GeminiRepository.runQueryAndFormat`. Anything wider gets clamped to
     *  90 days ending at `today` and a hint label. */
    private const val MAX_WINDOW_DAYS = 90

    /** Public entry point. Returns [ChatIntent.DataQuery] when [text] looks
     *  like a "show me my data" ask, otherwise [ChatIntent.Reasoning]. */
    fun classify(text: String, today: LocalDate): ChatIntent {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ChatIntent.Reasoning(trimmed)

        val window = detectWindow(trimmed, today)
        val type = detectDataType(trimmed)

        return if (window != null) {
            ChatIntent.DataQuery(
                type = type ?: DataType.BOTH,
                window = clamp(window, today),
                originalText = trimmed
            )
        } else if (type != null) {
            // Mentioned a domain but no time window — still leave it to the
            // canonical snapshot. (Most common case: "como vou hoje?", which
            // is already in `today + 7d`.)
            ChatIntent.Reasoning(trimmed)
        } else {
            ChatIntent.Reasoning(trimmed)
        }
    }

    // -------------------------------------------------------------------
    // Window detection
    // -------------------------------------------------------------------

    /** Try every pattern in order of specificity. First match wins. */
    private fun detectWindow(text: String, today: LocalDate): DateWindow? {
        // Explicit ISO range: "entre 2026-06-01 e 2026-06-16"
        ISO_RANGE.find(text)?.let { m ->
            val from = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
            val to = runCatching { LocalDate.parse(m.groupValues[2]) }.getOrNull()
            if (from != null && to != null && !from.isAfter(to)) {
                return DateWindow(from, to, "${m.groupValues[1]} → ${m.groupValues[2]}")
            }
        }

        // "since 2026-06-01" / "desde 2026-06-01"
        ISO_SINCE.find(text)?.let { m ->
            val from = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
            if (from != null && !from.isAfter(today)) {
                return DateWindow(from, today, "desde ${m.groupValues[1]}")
            }
        }

        // "últimos N dias" / "last N days"
        LAST_N_DAYS.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let null
            if (n in 1..365) {
                val from = today.minusDays((n - 1).toLong())
                return DateWindow(from, today, "últimos $n dias")
            }
        }

        // "esta semana" / "this week" → Mon → today
        if (THIS_WEEK.containsMatchIn(text)) {
            val daysSinceMonday = (today.dayOfWeek.value - 1).toLong()
            return DateWindow(today.minusDays(daysSinceMonday), today, "esta semana")
        }

        // "ontem" / "yesterday"
        if (YESTERDAY.containsMatchIn(text)) {
            val y = today.minusDays(1)
            return DateWindow(y, y, "ontem")
        }

        // "início do mês" / "este mês" / "o mês todo" / "this month"
        if (THIS_MONTH.containsMatchIn(text)) {
            val first = today.withDayOfMonth(1)
            val ym = YearMonth.from(today)
            val monthName = ym.month.getDisplayName(TextStyle.FULL, Locale("pt", "PT"))
            return DateWindow(first, today, "desde início de $monthName")
        }

        // "este ano" / "this year" → Jan 1 → today (capped to 90d below).
        if (THIS_YEAR.containsMatchIn(text)) {
            val first = today.withDayOfYear(1)
            return DateWindow(first, today, "este ano")
        }

        return null
    }

    private fun clamp(w: DateWindow, today: LocalDate): DateWindow {
        if (w.days <= MAX_WINDOW_DAYS) return w
        // Keep the upper bound (typically `today`) and slide the lower bound
        // back to MAX_WINDOW_DAYS-1 days before it. Tag the label.
        val newFrom = w.to.minusDays((MAX_WINDOW_DAYS - 1).toLong())
        return DateWindow(newFrom, w.to, "${w.label} (limitado a $MAX_WINDOW_DAYS dias)")
    }

    // -------------------------------------------------------------------
    // Data-type inference (which DAOs to hit)
    // -------------------------------------------------------------------

    /** Returns null if no domain keyword present; orchestrator defaults to
     *  BOTH for safety. */
    fun detectDataType(text: String): DataType? {
        val lower = text.lowercase()
        val mealHits = MEAL_KEYWORDS.count { lower.contains(it) }
        val actHits = ACTIVITY_KEYWORDS.count { lower.contains(it) }
        return when {
            mealHits > 0 && actHits > 0 -> DataType.BOTH
            mealHits > 0 -> DataType.MEALS
            actHits > 0 -> DataType.ACTIVITY
            else -> null
        }
    }

    /**
     * 2026-06-28 — Frente C: does the user's wording suggest they'd benefit
     * from an inline chart? Triggers the parallel `[CHART_PROPOSAL]` call
     * in [ChatOrchestrator.handle]. Conservative on purpose — false
     * positives waste a Gemini call and may run into the 20 RPM free-tier
     * limit. The chart endpoint silently swallows 429s so a missed positive
     * just means "no chart this turn".
     *
     * Returns true when the text contains explicit visualisation cues
     * ("gráfico", "evolução", "tendência", "mostra-me") OR an aggregate
     * question over a time window ("esta semana", "últimos 7 dias"). Single-
     * point questions ("quantos kcal hoje?") DO NOT trigger — they'd just
     * waste a call to render one bar.
     */
    fun wantsChart(text: String): Boolean {
        val lower = text.lowercase()
        // Explicit visual cues — strong signal.
        if (CHART_KEYWORDS.any { lower.contains(it) }) return true
        // Aggregate-over-window phrasing without an explicit chart keyword:
        // a multi-day window IS the marker. We reuse the same regexes
        // [classify] uses to detect windows. A single-day window (yesterday,
        // a single ISO date) is NOT enough — must be ≥2 days.
        val today = LocalDate.now()
        val window = detectWindow(text, today) ?: return false
        return window.days >= 2
    }

    /** Words that strongly imply a chart is wanted. Multilingual; kept
     *  conservative to avoid false positives on casual phrasing. */
    private val CHART_KEYWORDS = listOf(
        // pt
        "gráfico", "grafico", "evolução", "evolucao",
        "tendência", "tendencia", "mostra-me", "mostra me",
        "visualiza", "visualizar", "histograma", "barras",
        // en
        "chart", "graph", "trend", "show me", "visualize", "visualise",
        "histogram", "bars", "plot"
    )

    // -------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------

    // Explicit ISO ranges & since-dates
    private val ISO_RANGE = Regex(
        """\b(\d{4}-\d{2}-\d{2})\s*(?:e|to|→|–|-)\s*(\d{4}-\d{2}-\d{2})\b""",
        RegexOption.IGNORE_CASE
    )
    private val ISO_SINCE = Regex(
        """\b(?:desde|since|from|a partir de)\s+(\d{4}-\d{2}-\d{2})\b""",
        RegexOption.IGNORE_CASE
    )

    // Relative windows
    private val LAST_N_DAYS = Regex(
        """(?:últim[oa]s?|ultim[oa]s?|last|past)\s+(\d{1,3})\s*(?:dias?|days?)""",
        RegexOption.IGNORE_CASE
    )
    private val THIS_WEEK = Regex(
        """\b(?:esta semana|this week|na semana|nesta semana|deste semana)\b""",
        RegexOption.IGNORE_CASE
    )
    private val YESTERDAY = Regex(
        """\b(?:ontem|yesterday)\b""",
        RegexOption.IGNORE_CASE
    )
    // Catches: "este mês", "deste mês", "no mês", "o mês todo", "início do mês",
    // "início deste mês", "desde início do mês", "this month", "since start of month".
    private val THIS_MONTH = Regex(
        """\b(?:(?:início|comeco|começo)(?:\s+d[oe])?\s+m[êe]s|este m[êe]s|deste m[êe]s|no m[êe]s|o m[êe]s todo|m[êe]s atual|m[êe]s corrente|this month|since\s+(?:start|beginning)\s+of\s+(?:the\s+)?month)\b""",
        RegexOption.IGNORE_CASE
    )
    private val THIS_YEAR = Regex(
        """\b(?:este ano|deste ano|no ano|ano atual|ano corrente|this year)\b""",
        RegexOption.IGNORE_CASE
    )

    // Keyword sets — kept short & specific so we don't false-positive on
    // generic verbs like "comer" used hypothetically. Multilingual: pt-PT,
    // pt-BR, en. (gu intentionally limited — tests cover the common cases.)
    private val MEAL_KEYWORDS = listOf(
        // pt
        "calori", "kcal", "comi ", "comida", "refeição", "refeicao",
        "jantar", "jantei", "almoço", "almoco", "almocei",
        "pequeno-almoço", "pequeno almoço", "pequeno-almoco",
        "lanche", "lanchei", "ementa", "dieta", "macro", "macros",
        "proteína", "proteina", "hidratos", "carbo", "gordura",
        "nutrição", "nutricao", "alimentação", "alimentacao",
        // en
        "calorie", " ate ", " eat ", "meal", "breakfast", "lunch", "dinner",
        "snack", "protein", "carb", " fat ", "diet", "nutrition", "food"
    )
    private val ACTIVITY_KEYWORDS = listOf(
        // pt
        "treino", "treinei", "corri ", "corrida", "caminhei", "caminhada",
        "atividade", "actividade", "exercício", "exercicio",
        "passos", " passo ", " km ", "minuto", "minutos", "movimento",
        // en
        "workout", "trained", " ran ", "walked", "activity", "steps",
        "exercise", "training"
    )
}
