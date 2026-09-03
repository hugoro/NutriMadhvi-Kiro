package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Maps free-text food labels onto canonical concepts so two providers can be
 * compared meaningfully.
 *
 * The problem this solves:
 *
 * ```text
 * Qwen:    "yellow lentil curry"
 * Gemini:  "lentil curry"          -> same concept, strong agreement
 *
 * Qwen:    "chicken breast"
 * Gemini:  "chicken thigh"         -> genuine disagreement
 * ```
 *
 * Naive string equality would call the first pair a disagreement and trigger
 * a pointless (paid) adjudication round; naive substring matching would call
 * the second pair an agreement and silently accept the wrong cut.
 *
 * Pure and language-aware for PT/EN, which are the languages the app ships.
 */
object FoodLabelCanonicalizer {

    /** Descriptors that qualify a food without changing what it *is*.
     *  Dropping them is what makes "yellow lentil curry" ≡ "lentil curry". */
    private val MODIFIERS = setOf(
        // colour / appearance
        "yellow", "amarelo", "amarela", "red", "vermelho", "vermelha",
        "green", "verde", "white", "branco", "branca", "brown", "castanho",
        "castanha", "dark", "escuro", "escura", "light", "claro", "clara",
        // preparation that does not change identity for comparison purposes
        "homemade", "caseiro", "caseira", "fresh", "fresco", "fresca",
        "traditional", "tradicional", "classic", "clássico", "classico",
        "style", "estilo", "spicy", "picante", "mild", "suave",
        "small", "pequeno", "pequena", "large", "grande", "medium", "médio",
        "medio", "portion", "porção", "porcao", "serving", "dose",
        // filler
        "with", "com", "and", "e", "of", "de", "da", "do", "a", "o", "the",
        "um", "uma", "plate", "prato", "dish", "bowl", "tigela", "cup",
    )

    /** Cross-language synonyms collapsed onto one token so PT and EN answers
     *  can agree. Keys are already normalised (lowercase, unaccented). */
    private val SYNONYMS = mapOf(
        "lentils" to "lentil", "lentilha" to "lentil", "lentilhas" to "lentil",
        "dal" to "lentil", "daal" to "lentil", "dhal" to "lentil",
        "chickpeas" to "chickpea", "grao" to "chickpea", "grao-de-bico" to "chickpea",
        "beans" to "bean", "feijao" to "bean", "feijoes" to "bean",
        "rice" to "rice", "arroz" to "rice",
        "bread" to "bread", "pao" to "bread", "roll" to "bread",
        "naan" to "bread", "baguete" to "bread", "carcaca" to "bread",
        "chicken" to "chicken", "frango" to "chicken", "galinha" to "chicken",
        "pork" to "pork", "porco" to "pork", "suino" to "pork",
        "beef" to "beef", "vaca" to "beef", "bovino" to "beef", "novilho" to "beef",
        "fish" to "fish", "peixe" to "fish",
        "cod" to "cod", "bacalhau" to "cod",
        "potato" to "potato", "batata" to "potato", "batatas" to "potato",
        "tea" to "tea", "cha" to "tea", "chai" to "tea",
        "coffee" to "coffee", "cafe" to "coffee",
        "milk" to "milk", "leite" to "milk",
        "curry" to "curry", "caril" to "curry",
        "salad" to "salad", "salada" to "salad",
        "soup" to "soup", "sopa" to "soup",
        "egg" to "egg", "ovo" to "egg", "ovos" to "egg",
        "cheese" to "cheese", "queijo" to "cheese",
        "pasta" to "pasta", "massa" to "pasta", "esparguete" to "pasta",
        "spaghetti" to "pasta", "noodles" to "pasta",
    )

    /**
     * Cut/part distinctions that are *material*: two labels sharing a base
     * food but differing on one of these are a genuine disagreement, because
     * they carry different macros (breast vs thigh, loin vs belly).
     */
    private val MATERIAL_QUALIFIERS = setOf(
        "breast", "peito", "thigh", "coxa", "perna", "wing", "asa",
        "loin", "lombo", "belly", "barriga", "entrecosto", "rib", "costela",
        "pernil", "ham", "presunto", "bacon", "sausage", "salsicha", "chourico",
        "fillet", "file", "filete", "steak", "bife",
        "fried", "frito", "frita", "grilled", "grelhado", "grelhada",
        "roast", "roasted", "assado", "assada", "boiled", "cozido", "cozida",
        "raw", "cru", "crua", "baked", "forno", "smoked", "fumado", "fumada",
        "skimmed", "magro", "desnatado", "whole", "gordo", "integral",
    )

    /** Strips accents and lowercases so "porção" and "porcao" are one token. */
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /**
     * Reduces a label to its canonical concept tokens.
     *
     * "yellow lentil curry" and "lentil curry (dal)" both reduce to
     * `[curry, lentil]`; "chicken breast" reduces to `[breast, chicken]`
     * while "chicken thigh" reduces to `[chicken, thigh]`.
     */
    fun canonicalTokens(label: String?): Set<String> {
        if (label.isNullOrBlank()) return emptySet()
        return normalize(label)
            .replace(Regex("[()\\[\\]{},;:/\\\\]"), " ")
            .split(Regex("[\\s.-]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
            .map { SYNONYMS[it] ?: it }
            .filter { it !in MODIFIERS }
            .toSet()
    }

    /** Stable string form of the canonical concept, for logging/grouping. */
    fun canonicalLabel(label: String?): String =
        canonicalTokens(label).sorted().joinToString("_").ifBlank { "unknown" }

    /**
     * Jaccard-style overlap of canonical tokens, in `0.0..1.0`.
     * Two labels with no tokens in common score 0.0; identical concepts 1.0.
     */
    fun similarity(left: String?, right: String?): Double {
        val a = canonicalTokens(left)
        val b = canonicalTokens(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /**
     * True when the two labels describe genuinely different foods.
     *
     * Material disagreement requires either near-zero conceptual overlap, or
     * a conflicting cut/preparation qualifier on a shared base food — the
     * "chicken breast vs chicken thigh" case that plain overlap would miss,
     * because those two share the dominant `chicken` token.
     */
    fun isMaterialDisagreement(left: String?, right: String?): Boolean {
        val a = canonicalTokens(left)
        val b = canonicalTokens(right)
        if (a.isEmpty() || b.isEmpty()) return false // nothing to compare

        val aQual = a.intersect(MATERIAL_QUALIFIERS)
        val bQual = b.intersect(MATERIAL_QUALIFIERS)
        // Both named a cut/preparation and they disagree → material.
        if (aQual.isNotEmpty() && bQual.isNotEmpty() && aQual.intersect(bQual).isEmpty()) {
            return true
        }

        // Otherwise fall back to conceptual overlap on the base food, ignoring
        // qualifiers so "lentil curry" vs "yellow lentil curry" stays agreement.
        val aBase = a - MATERIAL_QUALIFIERS
        val bBase = b - MATERIAL_QUALIFIERS
        if (aBase.isEmpty() || bBase.isEmpty()) return false
        val overlap = aBase.intersect(bBase).size.toDouble() / max(aBase.size, bBase.size).toDouble()
        return overlap < 0.5
    }

    /**
     * Compares two provider results and reports the differences that matter
     * for adjudication: the dish identity and the total portion weight.
     */
    fun compare(left: FoodAnalysisResult, right: FoodAnalysisResult): List<AnalysisDisagreement> {
        val out = mutableListOf<AnalysisDisagreement>()

        val leftName = left.name.orEmpty()
        val rightName = right.name.orEmpty()
        if (leftName.isNotBlank() && rightName.isNotBlank() &&
            canonicalLabel(leftName) != canonicalLabel(rightName)
        ) {
            out += AnalysisDisagreement(
                field = "name",
                left = leftName,
                right = rightName,
                material = isMaterialDisagreement(leftName, rightName),
            )
        }

        val leftWeight = left.ingredients.orEmpty().sumOf { it.weightGrams }
        val rightWeight = right.ingredients.orEmpty().sumOf { it.weightGrams }
        if (leftWeight > 0.0 && rightWeight > 0.0) {
            val drift = abs(leftWeight - rightWeight) / max(leftWeight, rightWeight)
            if (drift > 0.20) {
                out += AnalysisDisagreement(
                    field = "portionGrams",
                    left = "%.0f".format(leftWeight),
                    right = "%.0f".format(rightWeight),
                    // Portion drift alone is a softer signal than a wrong food;
                    // only a large gap justifies a paid adjudication round.
                    material = drift > 0.50,
                )
            }
        }

        return out
    }
}
