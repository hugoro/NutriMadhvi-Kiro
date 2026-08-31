package com.example.data.local

/**
 * Phase D: lightweight name-similarity helper used to spot likely-duplicate
 * meal proposals before a card is shown to the user.
 *
 * Use case: assistant says "Vou registar 2 panquecas?" 5 minutes after we
 * already logged "panquecas com mel" → the card should warn instead of
 * silently appending a second entry.
 *
 * Implementation: normalized Levenshtein distance. Cheap enough to run
 * inline on the main thread for a 24h window's worth of entries (~tens).
 * Threshold is the caller's choice — `0.7` is a sensible default for the
 * Portuguese/Gujarati food vocabulary in this app.
 */
object MealSimilarity {

    /** Returns `1.0` for identical strings (after normalization), `0.0` for
     *  fully unrelated. Empty inputs return `0.0`. */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val distance = levenshtein(na, nb)
        val longer = maxOf(na.length, nb.length)
        return 1.0 - (distance.toDouble() / longer)
    }

    /**
     * Find the single best-matching recent FoodEntry, or null when nothing
     * exceeds [threshold]. Caller filters [foods] to the time window first.
     */
    fun findRecentSimilar(
        foods: List<FoodEntry>,
        candidateName: String,
        threshold: Double = 0.7
    ): FoodEntry? {
        if (foods.isEmpty() || candidateName.isBlank()) return null
        var bestEntry: FoodEntry? = null
        var bestScore = 0.0
        foods.forEach { f ->
            val score = similarity(candidateName, f.name)
            if (score > bestScore) {
                bestScore = score
                bestEntry = f
            }
        }
        return if (bestScore >= threshold) bestEntry else null
    }

    private fun normalize(s: String): String {
        // lowercase, strip diacritics by NFD + filter non-ASCII letter, collapse spaces
        val nfd = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (ch in nfd) {
            if (ch.isLetterOrDigit() || ch == ' ') sb.append(ch)
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
