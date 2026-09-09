package com.example.data.meal.ensemble

import com.example.data.nutrition.CorrectionCalibration
import com.example.data.nutrition.MacroPlausibilityGate
import com.example.data.remote.FoodAnalysisResult
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * A fast, text-only "second opinion" on a meal-analysis result.
 *
 * ## What it is
 * After the main model (Gemini / ensemble winner) produces a result, this judge
 * — a small, fast Qwen model on Groq — re-reads the *structured result* (no
 * image) against the user's own [CorrectionCalibration] and adjusts only clear
 * mismatches (e.g. the calibration says this user's "bread" is usually thin
 * toast and the result over-weighted it). It replies in the same JSON schema.
 *
 * ## What it is NOT
 * Not the ensemble adjudicator: that one is image-anchored and needs two
 * candidate answers to referee. This judge takes ONE result + the user's
 * correction history and is text-only, so it's cheap and fast.
 *
 * ## Guardrails (never make things worse)
 * - **Gated**: the caller only invokes it when the main result's self-rated
 *   confidence is `low`/`medium` and a non-empty calibration exists — a
 *   confident result on a user with no correction history is left alone (no
 *   latency, no risk).
 * - **Plausibility-checked**: any macro the judge changes must still pass
 *   [MacroPlausibilityGate] (Atwater consistency). A judge that returns
 *   internally-inconsistent numbers is rejected and the original is kept.
 * - **Fail-open**: any transport/parse/timeout error returns the original
 *   result unchanged. The judge can only ever help, never block a save.
 * - **Never logs** keys or image bytes (it never sees the image).
 *
 * Reuses the blocking [GroqApiService] transport (same as
 * [QwenGroqFoodVisionProvider]) and the tolerant balanced-brace JSON extractor.
 */
class MealResultJudge internal constructor(
    private val apiKey: String,
    private val service: GroqApiService = GroqRetrofitClient.service,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    fun isAvailable(): Boolean = apiKey.isNotBlank() && apiKey != PLACEHOLDER_KEY

    /**
     * Review [result] against [calibration]. Returns an adjusted
     * [FoodAnalysisResult] when the judge produced a plausible correction,
     * otherwise the original [result] unchanged. Never throws (except
     * [CancellationException], which propagates).
     */
    suspend fun review(
        result: FoodAnalysisResult,
        calibration: CorrectionCalibration,
    ): FoodAnalysisResult {
        if (!isAvailable()) return result
        if (calibration.isEmpty) return result
        // Nothing to judge without at least a name or ingredients.
        if (result.name.isNullOrBlank() && result.ingredients.isNullOrEmpty()) return result

        val prompt = buildPrompt(result, calibration)
        val payload = GroqChatRequest(
            model = model,
            messages = listOf(GroqMessage(role = "user", content = listOf(GroqContentPart.text(prompt)))),
        )

        val content = try {
            withTimeout(timeoutMs) {
                service.chatCompletions(bearer = "Bearer $apiKey", request = payload, model = model)
            }.choices?.firstOrNull()?.message?.content
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("MealResultJudge", "timed out after ${timeoutMs}ms — keeping original")
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MealResultJudge", "call failed (${e.javaClass.simpleName}) — keeping original")
            return result
        }
        if (content.isNullOrBlank()) return result

        val judged = parse(content) ?: return result

        // Only accept the judged numbers if they are internally consistent.
        // Atwater on the meal totals: kcal ≈ 4P + 4C + 9F. A judge that pushed
        // an inconsistent set of numbers is rejected wholesale.
        val kcal = judged.calories?.toDouble()
        if (kcal != null && kcal > 0) {
            val gate = MacroPlausibilityGate.checkPer100g(
                kcal = kcal,
                protein = judged.protein ?: 0.0,
                carbs = judged.carbs ?: 0.0,
                fat = judged.fat ?: 0.0,
            )
            if (gate is MacroPlausibilityGate.Result.Suspicious) {
                android.util.Log.w("MealResultJudge", "rejected implausible verdict: ${gate.reason}")
                return result
            }
        }

        android.util.Log.i(
            "MealResultJudge",
            "adjusted name='${result.name}'→'${judged.name}' kcal=${result.calories}→${judged.calories}"
        )
        // Preserve the fields the judge has no business changing — keep the
        // original model/confidence/habit metadata, take only the judged
        // name + macros + summary + ingredients.
        return result.copy(
            name = judged.name ?: result.name,
            calories = judged.calories ?: result.calories,
            protein = judged.protein ?: result.protein,
            carbs = judged.carbs ?: result.carbs,
            fat = judged.fat ?: result.fat,
            freeSugarsG = judged.freeSugarsG ?: result.freeSugarsG,
            saturatedFatG = judged.saturatedFatG ?: result.saturatedFatG,
            fullSummary = judged.fullSummary ?: result.fullSummary,
            ingredients = judged.ingredients ?: result.ingredients,
        )
    }

    private fun buildPrompt(result: FoodAnalysisResult, calibration: CorrectionCalibration): String {
        val ingredients = result.ingredients.orEmpty().joinToString(", ") {
            "${it.name} ${"%.0f".format(it.weightGrams)}g"
        }
        // PII-free compact rendering — no image, no keys.
        val candidate = """{"name":"${result.name.orEmpty()}","calories":${result.calories ?: 0},""" +
            """"protein":${result.protein ?: 0.0},"carbs":${result.carbs ?: 0.0},"fat":${result.fat ?: 0.0},""" +
            """"ingredients":"$ingredients"}"""
        return """
            You are reviewing a nutrition analysis for possible correction. You do NOT see the photo — you only have the structured result and this user's past-correction calibration. Adjust ONLY clear mismatches the calibration points to; otherwise return the result unchanged.

            ANALYSIS TO REVIEW:
            $candidate

            ${calibration.toPromptBlock().trim()}

            Rules:
              - Apply the calibration as a nudge, not a rewrite. If nothing clearly matches, return the numbers unchanged.
              - Keep meal totals internally consistent: kcal ≈ 4*protein + 4*carbs + 9*fat.
              - Do NOT invent ingredients that weren't in the analysis.
              - Reply with ONLY this JSON, no markdown, no prose:
                {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"freeSugarsG":0.0,"saturatedFatG":0.0,"fullSummary":"one line","ingredients":[{"name":"...","weightGrams":0,"calories":0,"protein":0.0,"carbs":0.0,"fat":0.0}]}
        """.trimIndent()
    }

    private fun parse(raw: String): FoodAnalysisResult? {
        runCatching { adapter.fromJson(raw) }.getOrNull()?.let { return it }
        val slice = extractJsonObject(raw) ?: return null
        return runCatching { adapter.fromJson(slice) }.getOrNull()
    }

    /** Tolerant balanced-brace extraction, identical to
     *  [QwenGroqFoodVisionProvider.extractJsonObject]. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val ch = raw[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private companion object {
        const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
        const val PLACEHOLDER_KEY = "MY_GROQ_API_KEY"
        const val DEFAULT_TIMEOUT_MS = 8_000L

        val adapter by lazy {
            Moshi.Builder().build().adapter(FoodAnalysisResult::class.java)
        }
    }
}

/**
 * Factory kept outside the class so [GroqApiService] can stay `internal` to
 * this package while callers elsewhere (the meal orchestrator) build a judge.
 * Returns null when no usable Groq key is configured — the caller then skips
 * the judge entirely.
 */
fun createMealResultJudge(apiKey: String): MealResultJudge? {
    val judge = MealResultJudge(apiKey = apiKey)
    return if (judge.isAvailable()) judge else null
}
