package com.example.data.chat

import com.example.data.local.UserInsight

/**
 * 2026-06-29 — Frente Memory C2.d. Pure-Kotlin regex pattern detector that
 * picks up "would-be insights" from the user's chat messages even when the
 * LLM forgets to emit a [INSIGHT_PROPOSAL] block.
 *
 * Hugo's complaint (2026-06-28): the assistant only captures facts the LLM
 * proactively proposes. Things the user says in passing ("odeio coentros",
 * "treino às terças", "sou intolerante a lactose") were never persisted
 * unless the LLM happened to write a proposal block — Gemini Flash misses
 * those ~50% of the time on conversational turns.
 *
 * This extractor runs **after** [com.example.data.remote.ActionProposalParser]
 * has already extracted any LLM-emitted proposals. We only fire when the
 * parser found nothing for the same user text (so a high-confidence
 * LLM-proposed insight always wins). Matches are inserted into
 * `user_insights` with:
 *   - `source = "auto_extracted"`
 *   - `confidence = 0.55` (mid — needs user confirmation)
 *   - `confirmedAtMs = null` (goes into the pending queue / Memory Screen)
 *
 * Hugo's [InsightExtractorTest] in `app/src/test/` covers the regex matrix.
 *
 * NOT an AI / LLM call. NOT a vector retrieval. Pure regex + a stop-word
 * filter. Aims for high precision on common phrasings; misses are fine —
 * the LLM still has its own path via [INSIGHT_PROPOSAL].
 */
object InsightExtractor {

    /** What categories this extractor can produce. Same vocabulary as the
     *  LLM-side path so the Memory Screen displays everything uniformly. */
    private enum class Category(val key: String) {
        PREFERENCE("preference"),
        ALLERGY("allergy"),
        GOAL("goal"),
        ROUTINE("other"), // routines map to "other" — UserInsight's vocab
    }

    /**
     * Scan [userText] for first-person fact patterns. Returns the candidate
     * insights — caller decides whether to persist (the orchestrator checks
     * the LLM-side parser first; if the LLM proposed at least one insight in
     * the same turn, the extractor's findings are suppressed to avoid double
     * cards).
     *
     * Returns an empty list when nothing matched.
     */
    fun extract(userText: String, language: String): List<Candidate> {
        if (userText.isBlank()) return emptyList()
        val lower = userText.lowercase()
        val out = mutableListOf<Candidate>()

        // Allergies / intolerances — strongest signal (medical). High conf.
        ALLERGY_PATTERNS.forEach { rx ->
            rx.findAll(lower).forEach { m ->
                val target = sanitiseTarget(m.groupValues.getOrNull(1)) ?: return@forEach
                out += Candidate(
                    text = formatSentence(language, "allergy", target),
                    category = Category.ALLERGY.key,
                    confidence = 0.70,
                    keywords = keywordize(target),
                )
            }
        }

        // Negative preferences (dislikes) — common phrasings.
        DISLIKE_PATTERNS.forEach { rx ->
            rx.findAll(lower).forEach { m ->
                val target = sanitiseTarget(m.groupValues.getOrNull(1)) ?: return@forEach
                out += Candidate(
                    text = formatSentence(language, "dislike", target),
                    category = Category.PREFERENCE.key,
                    confidence = 0.60,
                    keywords = keywordize(target),
                )
            }
        }

        // Positive preferences (likes).
        LIKE_PATTERNS.forEach { rx ->
            rx.findAll(lower).forEach { m ->
                val target = sanitiseTarget(m.groupValues.getOrNull(1)) ?: return@forEach
                out += Candidate(
                    text = formatSentence(language, "like", target),
                    category = Category.PREFERENCE.key,
                    confidence = 0.55,
                    keywords = keywordize(target),
                )
            }
        }

        // Goals — "objectivo é X", "quero perder X kg".
        GOAL_PATTERNS.forEach { rx ->
            rx.findAll(lower).forEach { m ->
                val phrase = m.groupValues.getOrNull(0)?.trim() ?: return@forEach
                if (phrase.length < 8 || phrase.length > 120) return@forEach
                out += Candidate(
                    text = phrase.replaceFirstChar { it.uppercase() } + ".",
                    category = Category.GOAL.key,
                    confidence = 0.55,
                    keywords = keywordize(phrase),
                )
            }
        }

        // Routines — "treino às terças", "corro de manhã".
        ROUTINE_PATTERNS.forEach { rx ->
            rx.findAll(lower).forEach { m ->
                val phrase = m.groupValues.getOrNull(0)?.trim() ?: return@forEach
                if (phrase.length < 8 || phrase.length > 120) return@forEach
                out += Candidate(
                    text = phrase.replaceFirstChar { it.uppercase() } + ".",
                    category = Category.ROUTINE.key,
                    confidence = 0.50,
                    keywords = keywordize(phrase),
                )
            }
        }

        // Dedup within a single user message (same target shouldn't fire
        // both "dislike" and "allergy") — allergy wins.
        return dedup(out)
    }

    /** Bridge from [Candidate] to a fresh [UserInsight] row ready for
     *  [com.example.data.local.UserInsightDao.insert]. Always pending
     *  (`confirmedAtMs = null`); the user confirms via the inline card or
     *  the Memory Screen. */
    fun toUserInsight(c: Candidate, sessionId: Long?): UserInsight =
        UserInsight(
            text = c.text,
            category = c.category,
            confidence = c.confidence,
            source = "auto_extracted",
            sourceSessionId = sessionId,
            // createdAtMs = default (System.currentTimeMillis())
            confirmedAtMs = null,
        )

    // -------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------

    data class Candidate(
        val text: String,
        val category: String,
        val confidence: Double,
        /** Pre-computed keyword list — written into `UserInsight.keywords`
         *  in v24 of the schema by the caller. Kept here so the extractor
         *  doesn't have to re-tokenise downstream. */
        val keywords: String,
    )

    private fun dedup(list: List<Candidate>): List<Candidate> {
        if (list.size < 2) return list
        // Group by keyword-set overlap. If two candidates share ≥1 keyword,
        // keep the one with the higher confidence.
        val sortedByConf = list.sortedByDescending { it.confidence }
        val kept = mutableListOf<Candidate>()
        for (cand in sortedByConf) {
            val candKeys = cand.keywords.split(",").filter { it.isNotBlank() }.toSet()
            val collision = kept.any { existing ->
                val existKeys = existing.keywords.split(",").filter { it.isNotBlank() }.toSet()
                candKeys.intersect(existKeys).isNotEmpty()
            }
            if (!collision) kept += cand
        }
        return kept
    }

    /** Crop the captured fragment to a single noun phrase the way a human
     *  would phrase it back. Drops trailing connectors and trims length so
     *  we never end up with "coentros e também não como pimenta verde". */
    private fun sanitiseTarget(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim().lowercase()
        // Cut at common connectors so multi-target sentences split cleanly.
        for (cutter in arrayOf(" e ", ", ", " mas ", " porque ", " por ", " que ", ".", "!", "?")) {
            val idx = t.indexOf(cutter)
            if (idx in 1..40) t = t.substring(0, idx)
        }
        t = t.trim().trim { it == '.' || it == ',' || it == ';' || it == ':' }
        if (t.isEmpty() || t.length > 50) return null
        // Reject pure pronouns / stop-words.
        if (STOP_WORDS.contains(t)) return null
        return t
    }

    /** Build the user-visible sentence in their language. The extractor
     *  doesn't try to re-translate the captured fragment — it stays in
     *  whatever language the user wrote — only the wrapper sentence is
     *  translated. */
    private fun formatSentence(language: String, kind: String, target: String): String {
        val isPt = language.startsWith("pt", ignoreCase = true)
        val isGu = language.startsWith("gu", ignoreCase = true)
        return when (kind) {
            "allergy" -> when {
                isPt -> "É intolerante / alérgico(a) a $target."
                isGu -> "$target ની એલર્જી / અસહિષ્ણુતા છે."
                else -> "Has an intolerance/allergy to $target."
            }
            "dislike" -> when {
                isPt -> "Não gosta de $target."
                isGu -> "$target પસંદ નથી."
                else -> "Dislikes $target."
            }
            "like" -> when {
                isPt -> "Gosta de $target."
                isGu -> "$target પસંદ છે."
                else -> "Likes $target."
            }
            else -> target.replaceFirstChar { it.uppercase() } + "."
        }
    }

    /** Lower-case, alphanumeric tokens longer than 2 chars, with a small
     *  stop-word filter. CSV joined for storage in UserInsight.keywords. */
    internal fun keywordize(text: String): String {
        return text
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && !STOP_WORDS.contains(it) }
            .distinct()
            .take(8)
            .joinToString(",")
    }

    // -------------------------------------------------------------------
    // Pattern banks. Keep narrow — false positives mean junk cards in the
    // Memory Screen, which trains Hugo to ignore them.
    // -------------------------------------------------------------------

    private val ALLERGY_PATTERNS = listOf(
        // pt
        Regex("""(?:sou|tenho)\s+(?:alérgic[oa]|alergia|intolerante)(?:\s+(?:a|ao|à|aos|às|de))\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
        Regex("""não\s+(?:posso|consigo)\s+(?:comer|tomar|ingerir)\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
        // en
        Regex("""\bi(?:'m| am)\s+(?:allergic|intolerant)\s+to\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
        Regex("""\bi\s+can(?:not|'t)\s+(?:eat|have|tolerate)\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
    )

    private val DISLIKE_PATTERNS = listOf(
        // pt
        Regex("""(?:odeio|detesto|não\s+gosto|nao\s+gosto)\s+(?:de\s+)?([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
        // en
        Regex("""\bi\s+(?:hate|dislike|don'?t\s+(?:like|enjoy))\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
    )

    private val LIKE_PATTERNS = listOf(
        // pt
        Regex("""(?:adoro|gosto\s+muito\s+de|amo|sou\s+fã\s+de)\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
        // en
        Regex("""\bi\s+(?:love|really\s+like|adore)\s+([^.,!?]{2,40})""", RegexOption.IGNORE_CASE),
    )

    private val GOAL_PATTERNS = listOf(
        // pt
        Regex("""(?:o\s+meu\s+)?objet?ivo\s+é\s+[^.!?]{4,80}""", RegexOption.IGNORE_CASE),
        Regex("""quer[oe]\s+(?:perder|ganhar|atingir|alcançar|chegar\s+aos?)\s+[^.!?]{2,60}""", RegexOption.IGNORE_CASE),
        // en
        Regex("""\bmy\s+goal\s+is\s+[^.!?]{4,80}""", RegexOption.IGNORE_CASE),
        Regex("""\bi\s+want\s+to\s+(?:lose|gain|reach|hit)\s+[^.!?]{2,60}""", RegexOption.IGNORE_CASE),
    )

    private val ROUTINE_PATTERNS = listOf(
        // pt — weekday routines
        Regex("""(?:treino|corro|nado|pedalo|faço\s+(?:cardio|gym|musculação))\s+(?:às|aos|todas?\s+as?|nas?)\s+(?:segundas?|terças?|quartas?|quintas?|sextas?|sábados?|domingos?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:treino|corro|nado|pedalo)\s+(?:de\s+manhã|à\s+noite|à\s+tarde|todos\s+os\s+dias|ao\s+almoço)""", RegexOption.IGNORE_CASE),
        // en
        Regex("""\bi\s+(?:train|run|swim|cycle|work\s+out)\s+on\s+(?:mondays?|tuesdays?|wednesdays?|thursdays?|fridays?|saturdays?|sundays?)""", RegexOption.IGNORE_CASE),
        Regex("""\bi\s+(?:train|run|swim|cycle|work\s+out)\s+(?:every\s+(?:morning|evening|night|day)|in\s+the\s+morning)""", RegexOption.IGNORE_CASE),
    )

    /** Combined pt + en stop-words. Keep tight — over-filtering kills recall. */
    private val STOP_WORDS = setOf(
        // pt
        "que", "uma", "uns", "umas", "para", "com", "sem", "muito", "pouco",
        "isso", "essa", "esse", "este", "esta", "isto", "tudo", "nada", "como",
        "quando", "onde", "porque", "pois", "mas", "também", "tambem", "aqui",
        // en
        "the", "and", "but", "for", "with", "without", "very", "much", "this",
        "that", "these", "those", "all", "none", "when", "where", "because",
        "also", "here", "there",
    )
}
