package com.example.data.remote

import com.squareup.moshi.Moshi
import java.util.Locale

/**
 * Deterministic repair of the model-emitted `fullSummary` 3-line format.
 *
 * Background: the meal-analysis prompts ([GeminiRepository.buildFoodAnalysisPromptCore])
 * REQUIRE a 3-line fullSummary — line 1 dish name + total portion in grams,
 * line 2 "Ingredientes visíveis: X (~Xg), Y (~Yg)…", line 3 assumptions +
 * confidence. Compliance is model-dependent: `gemini-3.6-flash` reliably
 * honours the format, but `gemini-3.5-flash` (the cascade fallback) often
 * returns only a one-line restatement of the dish name with NO gram
 * weights (live evidence: food_entries 584/585/586 on the 2026-10-05
 * device DB). The app previously stored whatever the model returned, so
 * those entries show a description without the per-ingredient weights the
 * user expects to validate.
 *
 * The ingredients array, however, is emitted reliably — `weightGrams` is a
 * required (non-null) [IngredientDto] field and the streaming UI already
 * depends on it. This repair therefore reconstructs the summary from the
 * data the model DID give us:
 *
 *  - If the summary already looks compliant (≥2 real lines AND at least
 *    one `(Xg)` / `~Xg` weight marker) it is returned unchanged — the
 *    model's prose is kept.
 *  - Otherwise the ingredient line is rebuilt from [ingredients]:
 *    "<label>: name (~Xg), name (~Yg), …".
 *  - If the first line carries no total weight, "(~Ng)" is appended from
 *    the sum of the available weights.
 *  - If the summary's own last line already reads like an assumptions line
 *    it is kept as line 3; otherwise the line-3 slot is dropped (we do not
 *    invent an "Assumido" prose — there is no trustworthy source for it).
 *
 * Pure function, no I/O, no DB: safe to call on every write path AND on
 * read in the edit screen (same heal-on-read pattern as
 * [sanitizeAiNewlines], which fixes entries persisted before the fix).
 */
internal object FullSummaryRepair {

    private val moshi: Moshi = Moshi.Builder().build()

    /**
     * Repairs [fullSummary] when it does not carry the gram-weighted
     * 3-line structure the prompt requires, using [ingredientsJson]
     * (persisted per-entry `ingredients` array) as the deterministic
     * source of weights.
     *
     * Returns `fullSummary` unchanged when: it is blank, already
     * compliant, or no parseable ingredient with a weight is available
     * (nothing to repair from — better to leave the model's prose than
     * fabricate one).
     */
    internal fun repair(
        fullSummary: String?,
        ingredientsJson: String?,
        language: String,
    ): String? {
        val summary = fullSummary?.sanitizeAiNewlines()
        if (summary.isNullOrBlank()) return fullSummary

        val weighted = decode(ingredientsJson).orEmpty()
            .filter { !it.name.isNullOrBlank() && it.weightGrams > 0.0 }

        // Already in the new one-ingredient-per-line format (bullet markers):
        // leave it untouched so repeated reads/writes are idempotent and the
        // user's manual edits to the block survive. 2026-10-10.
        if (summary.contains("\n•") || summary.startsWith("•")) return summary

        // No weighted ingredients to rebuild from: keep the model's prose
        // rather than fabricate a breakdown. If the prose already carries
        // gram markers we still return it unchanged (nothing better to do).
        if (weighted.isEmpty()) return summary

        val lines = summary.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        // Line 1: keep the model's dish description; append the total
        // weight when it carries none ("…com amêndoas laminadas." →
        // "…com amêndoas laminadas. (~103g)").
        val totalG = weighted.sumOf { it.weightGrams }
        val first = lines.first()
        val line1 = if (hasGrams(first)) {
            first
        } else {
            first.trimEnd('.', '!', '?') + " (~${formatGrams(totalG)}g)"
        }

        // Ingredient block: one ingredient per line (2026-10-10). Each
        // ingredient on its own `• name (~Xg)` line so the user can scan the
        // breakdown vertically instead of parsing a comma-run. The header
        // label stays on its own line above the bullet list.
        val ingredientLines = buildList {
            add("${ingredientsLabel(language)}:")
            weighted.forEach { add("• ${it.name.trim()} (~${formatGrams(it.weightGrams)}g)") }
        }

        // Line 3: keep an assumptions line the model actually produced
        // (detected by the ASSUMED label from summaryLabelInstruction,
        // any language) — never invent one.
        val assumedLabel = assumedLabel(language)
        val lastIsAssumptions = lines.size >= 2 && lines.last().startsWith(assumedLabel, ignoreCase = true)
        val line3 = if (lastIsAssumptions) lines.last() else null

        return (listOf(line1) + ingredientLines + listOfNotNull(line3)).joinToString("\n")
    }

    private fun hasGrams(text: String): Boolean =
        GROUND_MARKER.containsMatchIn(text)

    /** `(250 g)`, `(~250g)`, `(120g)`, `~30 g` — tolerant of the ~ and of a
     *  space before the unit; the prompt example uses `(~250 g)`. */
    private val GROUND_MARKER = Regex("""\(?\s*~?\s*\d+(?:[.,]\d+)?\s*g\b""", RegexOption.IGNORE_CASE)

    private fun formatGrams(g: Double): String {
        val rounded = Math.round(g)
        return if (rounded.toDouble() == g) rounded.toString()
        else "%.1f".format(Locale.US, g)
    }

    /**
     * Labels must match [GeminiRepository.summaryLabelInstruction] exactly,
     * because the model emits those strings into compliant summaries and
     * the line-3 detection below compares against them.
     */
    private fun ingredientsLabel(language: String): String = when (language) {
        "pt-PT", "pt" -> "Ingredientes visíveis"
        "gu" -> "દૃશ્યમાન ઘટકો"
        else -> "Visible ingredients"
    }

    private fun assumedLabel(language: String): String = when (language) {
        "pt-PT", "pt" -> "Assumido"
        "gu" -> "ધારણા"
        else -> "Assumed"
    }

    internal fun decode(json: String?): List<IngredientDto>? =
        if (json.isNullOrBlank()) null
        else try {
            // Same Moshi parameterized-type construction as [encodeIngredients]
            // (the inverse) so decode can never drift from the writer.
            val type = com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                IngredientDto::class.java
            )
            moshi.adapter<List<IngredientDto>>(type).fromJson(json)
        } catch (e: Exception) {
            null
        }
}
