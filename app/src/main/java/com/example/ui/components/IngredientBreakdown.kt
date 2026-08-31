package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Decoded view of one row from the LLM-returned `ingredients` array stored in
 * `FoodEntry.ingredientsJson`. Field order mirrors `IngredientDto` in the
 * data-remote layer; we re-declare it here to keep the UI module free of a
 * direct dependency on remote DTOs.
 *
 * All macro fields nullable to tolerate models that omit a row's macros for
 * a marginal contribution; the threshold filter in [formatIngredientHint]
 * handles those gracefully.
 */
@JsonClass(generateAdapter = true)
data class StoredIngredient(
    val name: String,
    val weightGrams: Double,
    val calories: Int? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    /** Grams of saturated fat inside [fat]. Mirrors `IngredientDto.saturatedFatG`
     *  from the remote layer. Nullable so legacy `ingredientsJson` blobs
     *  (pre-v26) decode cleanly — Moshi treats a missing key as `null`. */
    val saturatedFatG: Double? = null,
    /** Grams of WHO free sugars inside [carbs]. Mirrors
     *  `IngredientDto.freeSugarsG`. Nullable so legacy blobs decode cleanly —
     *  Moshi treats a missing key as `null`. */
    val freeSugarsG: Double? = null
)

/** Which macro a hint refers to. Drives both the per-ingredient sort key and
 *  the threshold for inclusion. */
enum class Macro { PROTEIN, CARBS, FAT }

/** Decode the JSON column into the in-memory list. Returns an empty list on
 *  any parse failure or NULL — safer than throwing in a UI render path. */
fun parseIngredients(json: String?): List<StoredIngredient> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val moshi = Moshi.Builder().build()
        val type = Types.newParameterizedType(List::class.java, StoredIngredient::class.java)
        moshi.adapter<List<StoredIngredient>>(type).fromJson(json) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun StoredIngredient.contribution(macro: Macro): Double = when (macro) {
    Macro.PROTEIN -> protein ?: 0.0
    Macro.CARBS -> carbs ?: 0.0
    Macro.FAT -> fat ?: 0.0
}

/**
 * Build the human-readable hint string shown under a macro bar.
 *
 * Picks ingredients whose contribution to [macro] meets [minThresholdGrams],
 * sorts by contribution descending, then renders each as the grams of THAT
 * macro (not the ingredient's total portion weight) + name, e.g.
 * "(20.0g manteiga; 13.5g queijo)" under the Fat bar. Returns `null` when
 * nothing qualifies — caller should hide the row entirely instead of rendering
 * empty parens.
 *
 * The threshold (default 0.5g) keeps the hint tight: a 100g pasta contributes
 * 0.3g of fat, which is technically nonzero but visually noise. Tune via the
 * parameter if a particular bar feels too sparse.
 */
fun formatIngredientHint(
    ingredients: List<StoredIngredient>,
    macro: Macro,
    minThresholdGrams: Double = 0.5
): String? {
    if (ingredients.isEmpty()) return null
    val relevant = ingredients
        .filter { it.contribution(macro) >= minThresholdGrams && it.weightGrams > 0 }
        .sortedByDescending { it.contribution(macro) }
    if (relevant.isEmpty()) return null
    val parts = relevant.joinToString(separator = "; ") { ing ->
        val grams = String.format("%.1fg", ing.contribution(macro))
        "$grams ${ing.name}"
    }
    return "($parts)"
}

/**
 * Variant of `MacroBar` (defined in `ChatScreen.kt`) that renders an optional
 * second line below the bar with the per-macro ingredient breakdown. When
 * `ingredientHint` is null/blank, layout is identical to the plain bar.
 *
 * Kept here (next to the helpers it uses) rather than in `ChatScreen.kt` to
 * keep the new UI surface co-located with the data shape it consumes.
 */
@Composable
fun MacroBarWithIngredients(
    label: String,
    value: Double,
    color: Color,
    unit: String,
    ingredientHint: String?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.width(100.dp),
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(Color(0xFF334155), CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(java.lang.Math.min((value / 100.0).toFloat(), 1.0f))
                        .background(color, CircleShape)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = String.format("%.1f %s", value, unit),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!ingredientHint.isNullOrBlank()) {
            Text(
                text = ingredientHint,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 100.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Curated list of ingredient-name substrings that typically imply significant
 * free-sugar content, in the WHO sense: added sugars + sugars naturally in
 * honey, syrups, and fruit juices. ([topFreeSugarSourceHint] picks the
 * highest-carb matching ingredient as the culprit label.)
 *
 * INTENTIONALLY does NOT include whole fruit or whole dairy — the WHO
 * definition excludes those, and matching e.g. "banana" would misclassify a
 * fruit bowl as having free sugars.
 *
 * Multilingual: pt-PT + en cover Hugo's use cases. Gu is out of scope for
 * now; a `null` return (no match) is a valid outcome and drops the "sobretudo
 * de …" tail cleanly.
 */
private val HIGH_FREE_SUGAR_HINTS = listOf(
    // Added/pure sugars & syrups
    "açúcar", "açucar", "sugar", "xarope", "syrup",
    "mel", "honey",
    // Liquid sugar (WHO explicitly counts fruit juices)
    "sumo", "juice", "refrigerante", "soda", "coca-cola", "coca cola",
    "iced tea", "chá gelado", "cha gelado", "bebida energética", "energy drink",
    // Confectionary & preserves
    "compota", "geleia", "jam", "marmelada", "marmalade",
    "doce de", "doce d'", "gomas", "candies", "candy",
    "chocolate",
    "bolo", "cake", "muffin", "donut", "brownie",
    "bolacha", "biscoito", "cookie",
    // Sweet cereals / breakfast staples with added sugar
    "cereais açucarados", "sugary cereal", "chocapic", "nesquik",
    "granola",  // most commercial granolas have added sugar
    "iogurte açucarado", "iogurte de sabor", "flavoured yoghurt"
)

/**
 * Negation phrases that CANCEL a sugar hint. The hints above match by naive
 * substring, so "café com leite (sem açúcar)" would match "açúcar" *inside*
 * "sem açúcar" — a self-contradicting false positive (bug 2026-07-11: a
 * sugar-free coffee showing up in "Fontes de açúcar livre"). We strip these
 * phrases from the name BEFORE the hint check, so the negated mention no
 * longer carries its own keyword. "com açúcar" is deliberately NOT here — a
 * positive sugar mention must still match.
 *
 * pt-PT + en, matching the coverage of [HIGH_FREE_SUGAR_HINTS].
 */
private val FREE_SUGAR_NEGATIONS = listOf(
    "sem adição de açúcar", "sem adicao de acucar",
    "sem açúcar adicionado", "sem acucar adicionado",
    "no added sugar", "without added sugar",
    "sem açúcar", "sem acucar",
    "zero açúcar", "zero acucar", "0 açúcar", "0 acucar",
    "sugar-free", "sugar free", "no sugar", "unsweetened"
)

/**
 * Returns the single ingredient name most likely to be the primary free-sugar
 * source in [items], or `null` when nothing matches the hint list.
 *
 * The LLM is authoritative for the AMOUNT (`FoodEntry.freeSugarsG`) — this
 * helper only picks a LABEL for the "sobretudo de …" tail in the edit-meal
 * free-sugars line. A missing label just drops that tail; the numbers keep
 * rendering.
 *
 * Match is case-insensitive substring against each ingredient's name, AFTER
 * stripping [FREE_SUGAR_NEGATIONS] so "sem açúcar" / "sugar-free" cancel their
 * own keyword instead of matching it. Candidates are checked highest-carb
 * first, so when several ingredients qualify the label points at the biggest
 * carbohydrate contributor (the likeliest real culprit) rather than whichever
 * the LLM happened to emit first.
 */
fun topFreeSugarSourceHint(items: List<StoredIngredient>): String? {
    if (items.isEmpty()) return null
    val hit = items
        .sortedByDescending { it.carbs ?: 0.0 }
        .firstOrNull { ing ->
            var n = ing.name.lowercase()
            for (neg in FREE_SUGAR_NEGATIONS) n = n.replace(neg, " ")
            HIGH_FREE_SUGAR_HINTS.any { n.contains(it) }
        }
    return hit?.name
}
