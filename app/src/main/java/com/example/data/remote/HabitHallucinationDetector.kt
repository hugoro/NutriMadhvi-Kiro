package com.example.data.remote

/**
 * Heuristic that flags assistant replies which TALK like they saved a habit
 * (verbs: "adicionei o hábito", "saved as a habit", "id=4 trigger=...") but
 * did NOT emit a [HABIT_PROPOSAL] block.
 *
 * Used as the second defence layer in the chat-redesign 2026-06-12 fix for
 * the habit-hallucination bug: layer 1 reinforces the system prompt to make
 * the model stop doing this; layer 2 (this file) catches the cases the
 * prompt fails to prevent and surfaces them to the user as an amber warning
 * card under the offending bubble.
 *
 * Tested against the Madalena case: the model produced
 *
 *     "Com certeza! O hábito foi atualizado no sistema:
 *      id=4, trigger=Madalena, descriptor=..."
 *
 * which the regex catches via either [SAVE_VERB] (verb+habit on the same
 * short clause) or [ID_TRIGGER] (id=N + trigger/descriptor pair).
 *
 * Callers should ONLY consult this when [ParsedActionMessage.habitProposals]
 * is empty — the legitimate case (assistant emitted a block AND describes
 * the action) MUST not trigger a warning.
 */
object HabitHallucinationDetector {

    /**
     * Verb-of-persistence somewhere in the same sentence as the word
     * "hábito"/"habit"/"memorizad…"/"guardad…". Sentences are split on `.`,
     * `!`, `?`, and newlines — within each chunk verb and noun can appear in
     * any order with any gap. This handles the natural variants the model
     * uses ("Já registei e guardei os detalhes destas madalenas na tua lista
     * de hábitos") that the previous narrow `verb [≤40 chars] habit` pattern
     * missed because the gap exceeded the window.
     *
     * Languages covered: pt (adicion/atualiz/memoriz/grav/guard/regist) +
     * en (added/saved/stored/memorized/recorded/updated/registered).
     * The Portuguese stems use `(?:ei|ado|ada|adas|ados)` to catch both 1st
     * person past and past participle ("memorizei" / "memorizado").
     *
     * `(?iu)` enables case-insensitive + UNICODE mode so `\b` and `\w` match
     * accented characters correctly (otherwise "hábito" would split at "á").
     */
    private val SAVE_VERB = Regex(
        """(?iu)\b(adicion(?:ei|ada|ado|adas|ados)|atualiz(?:ei|ada|ado|adas|ados)|memoriz(?:ei|ada|ado|adas|ados)|grav(?:ei|ada|ado|adas|ados)|guard(?:ei|ada|ado|adas|ados)|regist(?:r?ei|r?ada|r?ado|r?adas|r?ados)|added|saved|stored|memorized|recorded|updated|registered)\b"""
    )
    private val HABIT_NOUN = Regex(
        """(?iu)\b(h[áa]bito|h[áa]bitos|habit|habits|memoriz(?:ad[oa]s?|ar)|guard(?:ad[oa]s?|ar))\b"""
    )
    /** Sentence boundaries: period, exclamation, question, line break, or
     *  the bullet/dash characters that often appear in markdown lists. */
    private val SENTENCE_SPLIT = Regex("""[.!?\n\r•·‣◦]+""")

    /**
     * "id=N" followed (within 80 chars on the same line) by trigger/descriptor
     * keywords. This catches the hallucinated-row pattern even when the
     * surrounding verb is missing or paraphrased — the model often slips
     * directly into "id=4, trigger=..." without explicitly saying "saved".
     */
    private val ID_TRIGGER = Regex(
        """(?iu)\bid\s*=\s*\d+\b[^\n]{0,80}\b(trigger|descriptor|gatilho|descritor)\b"""
    )

    /**
     * Returns true when [text] reads like the assistant claimed to have
     * persisted a habit. Caller is expected to have already verified that no
     * [HABIT_PROPOSAL] block was emitted in the same response.
     */
    fun looksLikeFakeSave(text: String): Boolean {
        if (text.isBlank()) return false
        if (ID_TRIGGER.containsMatchIn(text)) return true
        // Sentence-based scan: each sentence must contain BOTH a save-verb
        // AND a habit-noun. Order within the sentence does not matter.
        SENTENCE_SPLIT.split(text).forEach { sentence ->
            if (sentence.isBlank()) return@forEach
            if (SAVE_VERB.containsMatchIn(sentence) &&
                HABIT_NOUN.containsMatchIn(sentence)
            ) {
                return true
            }
        }
        return false
    }
}
