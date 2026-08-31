package com.example.data.remote

import com.example.data.local.MealCategory
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/** Parsed contents of a single [HABIT_PROPOSAL]{...}[/HABIT_PROPOSAL] block
 *  emitted by the chat assistant. */
data class HabitProposal(
    val trigger: String,
    val descriptor: String,
    val mealCategoryHint: MealCategory?
)

/** Result of stripping habit-proposal markers from an assistant reply. */
data class ParsedHabitMessage(
    /** The reply with all [HABIT_PROPOSAL]...[/HABIT_PROPOSAL] markers removed
     *  and surrounding whitespace tidied. Safe to render as the visible bubble. */
    val cleanedText: String,
    /** Zero or more proposals extracted from the original text. */
    val proposals: List<HabitProposal>
)

@JsonClass(generateAdapter = true)
internal data class HabitProposalDto(
    val trigger: String? = null,
    val descriptor: String? = null,
    val mealCategoryHint: String? = null
)

object HabitProposalParser {

    private val BLOCK_REGEX = Regex(
        """\[HABIT_PROPOSAL\](.*?)\[/HABIT_PROPOSAL\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    /** Phase 4 hardening (2026-06-13): models occasionally invent variants of
     *  the canonical [HABIT_PROPOSAL] marker — e.g. `[SAVE_HABIT: trigger="X",
     *  descriptor="Y"]` with key=value syntax and no closing tag. We don't
     *  want to surface this as a user-facing error: the user's intent was
     *  clear, only the model's formatting was wrong. So we capture these
     *  malformed markers and try to salvage `trigger`/`descriptor` from
     *  them — when successful, they become legitimate HabitProposal cards.
     *  The pattern matches both opener-only forms and the rare closing tag. */
    private val BOGUS_MARKER_REGEX = Regex(
        """\[/?(?:SAVE|STORE|MEMORIZE|ADD|REMEMBER|REGISTER)_HABIT[^\]]*\]""",
        RegexOption.IGNORE_CASE
    )
    /** Pulls `key="value"` pairs out of a malformed marker body — handles the
     *  observed `trigger="Madalena", descriptor="..."`-style payloads. The
     *  value can contain anything except a literal `"` (commas, parens,
     *  accented chars, kcal symbols, etc. are all fine). */
    private val KV_REGEX = Regex(
        """(?i)\b(trigger|descriptor|mealCategoryHint)\s*=\s*"([^"]+)"""
    )
    /** Phase 4 v4 (2026-06-13): catches the bracket-less form Hugo observed
     *  in the wild — the model writes `id=4, trigger="Madalenas",
     *  descriptor="..."` directly in the markdown body without any
     *  `[SAVE_HABIT]`-style wrapper. The triplet `id=N` + `trigger="..."` +
     *  `descriptor="..."` within ~300 chars is specific enough that natural
     *  language won't trip it; the order between `trigger` and `descriptor`
     *  may swap, so we anchor on `id=N` and search both sides. We also
     *  capture as much surrounding markdown as possible (bullet, leading
     *  bold/italic stars, bullet line) so the cleaning stage can wipe the
     *  whole synthetic-row line — leaving only the assistant's natural-
     *  language sentences in the bubble. */
    private val FREEFORM_HABIT_LINE = Regex(
        """(?im)^[\s>•·*\-]*(?:\*+\s*)?\bid\s*=\s*\d+\s*,?\s*[^\n]*?\btrigger\s*=\s*"([^"]+)"[^\n]*?\bdescriptor\s*=\s*"([^"]+)"[^\n]*$"""
    )
    /** Same as FREEFORM_HABIT_LINE but with descriptor before trigger. We try
     *  both orderings; the first one that hits wins per match position. */
    private val FREEFORM_HABIT_LINE_REV = Regex(
        """(?im)^[\s>•·*\-]*(?:\*+\s*)?\bid\s*=\s*\d+\s*,?\s*[^\n]*?\bdescriptor\s*=\s*"([^"]+)"[^\n]*?\btrigger\s*=\s*"([^"]+)"[^\n]*$"""
    )
    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val adapter by lazy { moshi.adapter(HabitProposalDto::class.java) }

    /**
     * Try to recover a HabitProposal from a malformed marker payload (the
     * substring inside `[SAVE_HABIT…]` etc., with the brackets and the
     * keyword prefix already trimmed off). Two strategies tried in order:
     *
     * 1. Key-value pairs (`trigger="X", descriptor="Y"`) — the form actually
     *    observed in the wild from Gemini.
     * 2. JSON body (`{"trigger":"X","descriptor":"Y"}`) — in case the model
     *    used the right body but the wrong tag name.
     *
     * Returns null when neither strategy yields a non-blank trigger AND
     * descriptor — caller falls back to silent strip.
     */
    private fun tryExtractFromBogus(body: String): HabitProposal? {
        if (body.isBlank()) return null
        val kvHits = KV_REGEX.findAll(body)
            .associate { m -> m.groupValues[1].lowercase() to m.groupValues[2] }
        var trigger = kvHits["trigger"]?.trim().orEmpty()
        var descriptor = kvHits["descriptor"]?.trim().orEmpty()
        var mealHintRaw = kvHits["mealcategoryhint"]?.trim()
        if (trigger.isBlank() || descriptor.isBlank()) {
            // Try JSON: peel a leading `:` or whitespace before the brace.
            val jsonStart = body.indexOf('{')
            val jsonEnd = body.lastIndexOf('}')
            if (jsonStart in 0 until jsonEnd) {
                val payload = body.substring(jsonStart, jsonEnd + 1)
                val dto = runCatching { adapter.fromJson(payload) }.getOrNull()
                if (dto != null) {
                    if (trigger.isBlank()) trigger = dto.trigger?.trim().orEmpty()
                    if (descriptor.isBlank()) descriptor = dto.descriptor?.trim().orEmpty()
                    if (mealHintRaw.isNullOrBlank()) mealHintRaw = dto.mealCategoryHint?.trim()
                }
            }
        }
        if (trigger.isBlank() || descriptor.isBlank()) return null
        val cat = mealHintRaw
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let { runCatching { MealCategory.valueOf(it.uppercase()) }.getOrNull() }
        return HabitProposal(trigger, descriptor, cat)
    }

    /** Parse [raw], extracting every well-formed habit proposal and returning
     *  the cleaned visible text. Malformed JSON is silently skipped — the
     *  visible text still has the marker stripped so the user never sees the
     *  protocol leak through. Malformed *markers* (SAVE_HABIT/etc.) are
     *  salvaged into legitimate proposals when their payload yields a
     *  trigger+descriptor pair; otherwise stripped silently. */
    fun parse(raw: String): ParsedHabitMessage {
        val hasBogus = BOGUS_MARKER_REGEX.containsMatchIn(raw)
        val hasFreeform = FREEFORM_HABIT_LINE.containsMatchIn(raw) ||
            FREEFORM_HABIT_LINE_REV.containsMatchIn(raw)
        if (!raw.contains("[HABIT_PROPOSAL]") && !hasBogus && !hasFreeform) {
            return ParsedHabitMessage(raw, emptyList())
        }
        val proposals = mutableListOf<HabitProposal>()
        BLOCK_REGEX.findAll(raw).forEach { m ->
            val payload = m.groupValues[1].trim()
            val dto = runCatching { adapter.fromJson(payload) }.getOrNull() ?: return@forEach
            val trigger = dto.trigger?.trim().orEmpty()
            val descriptor = dto.descriptor?.trim().orEmpty()
            if (trigger.isBlank() || descriptor.isBlank()) return@forEach
            val cat = dto.mealCategoryHint
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let { runCatching { MealCategory.valueOf(it.uppercase()) }.getOrNull() }
            proposals += HabitProposal(trigger, descriptor, cat)
        }
        // Salvage stage: try to convert any bogus marker into a real proposal.
        // Dedup against the canonical batch: if the model emitted both the
        // correct block AND a malformed echo with the same trigger, keep
        // only the canonical one.
        if (hasBogus) {
            BOGUS_MARKER_REGEX.findAll(raw).forEach { m ->
                // Strip the leading `[KEYWORD_HABIT` + (optional `:`/space) and
                // the trailing `]` to expose the body.
                val full = m.value
                val bodyStart = full.indexOfAny(charArrayOf(':', ' ', '{')).let {
                    if (it < 0) full.length - 1 else it
                }
                val body = full.substring(bodyStart, full.length - 1)
                    .trimStart(':', ' ', '\t')
                val salvaged = tryExtractFromBogus(body)
                if (salvaged != null) {
                    val dup = proposals.any {
                        it.trigger.equals(salvaged.trigger, ignoreCase = true)
                    }
                    if (!dup) {
                        proposals += salvaged
                        android.util.Log.i(
                            "HABIT",
                            "Salvaged habit proposal from malformed marker: trigger=${salvaged.trigger}"
                        )
                    } else {
                        android.util.Log.i(
                            "HABIT",
                            "Skipped malformed marker — duplicate of canonical proposal: ${salvaged.trigger}"
                        )
                    }
                } else {
                    android.util.Log.w(
                        "HABIT",
                        "Bogus habit marker stripped — body had no extractable trigger+descriptor"
                    )
                }
            }
        }
        // Free-form salvage stage: catch lines like `id=4, trigger="X",
        // descriptor="Y"` that have no [SAVE_HABIT...] wrapper at all — the
        // model just writes them in markdown bullets. The whole line gets
        // wiped from the cleaned text below; the trigger+descriptor pair
        // becomes a real HabitProposal so the user sees a confirm card.
        val freeformLines = mutableListOf<String>()
        FREEFORM_HABIT_LINE.findAll(raw).forEach { m ->
            val trigger = m.groupValues[1].trim()
            val descriptor = m.groupValues[2].trim()
            registerFreeformSalvage(trigger, descriptor, proposals, freeformLines, m.value)
        }
        FREEFORM_HABIT_LINE_REV.findAll(raw).forEach { m ->
            val descriptor = m.groupValues[1].trim()
            val trigger = m.groupValues[2].trim()
            registerFreeformSalvage(trigger, descriptor, proposals, freeformLines, m.value)
        }
        // Strip ALL marker pairs even if their JSON failed — never leak protocol.
        // BOGUS_MARKER_REGEX runs second to scrub model-invented variants
        // (whether or not the salvage above succeeded — the visible text
        // never shows protocol fragments). Free-form lines are removed last
        // so the leading bullet/markdown survives only when not synthetic.
        var cleaned = BLOCK_REGEX.replace(raw, "")
            .let { BOGUS_MARKER_REGEX.replace(it, "") }
        // Wipe each captured free-form line verbatim. Using literal substring
        // replace (not regex) avoids any chance of greedy collateral damage.
        for (line in freeformLines) {
            cleaned = cleaned.replace(line, "")
        }
        cleaned = cleaned
            .replace(Regex("[ \\t]+\\n"), "\n")          // trailing spaces before newline
            .replace(Regex("\\n{3,}"), "\n\n")           // collapse triple+ newlines
            .trim()
        return ParsedHabitMessage(cleaned, proposals)
    }

    /** Helper for the free-form salvage path. Validates non-blank fields,
     *  dedups against the existing proposals, appends, logs, and remembers
     *  the matched line so the cleaning stage can drop it. */
    private fun registerFreeformSalvage(
        trigger: String,
        descriptor: String,
        proposals: MutableList<HabitProposal>,
        freeformLines: MutableList<String>,
        matchedLine: String
    ) {
        if (trigger.isBlank() || descriptor.isBlank()) return
        val dup = proposals.any { it.trigger.equals(trigger, ignoreCase = true) }
        if (dup) {
            android.util.Log.i(
                "HABIT",
                "Skipped free-form synthetic row — duplicate of existing proposal: $trigger"
            )
        } else {
            proposals += HabitProposal(trigger, descriptor, mealCategoryHint = null)
            android.util.Log.i(
                "HABIT",
                "Salvaged habit proposal from free-form synthetic row: trigger=$trigger"
            )
        }
        // Always remember the line for cleaning, even if dedupped — we never
        // want the synthetic `id=N, trigger=...` text in the visible bubble.
        freeformLines += matchedLine
    }
}
