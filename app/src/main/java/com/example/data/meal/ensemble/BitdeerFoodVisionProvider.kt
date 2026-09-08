package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException

/**
 * Bitdeer vision provider — Qwen3-VL-32B.
 *
 * Bitdeer exposes an OpenAI-compatible `/v1/chat/completions` endpoint at
 * `api-inference.bitdeer.ai`, so the wire format is identical to the Groq path:
 * we reuse [GroqChatRequest] / [GroqChatResponse] DTOs verbatim and only swap
 * the transport ([BitdeerApiService] / [BitdeerRetrofitClient]).
 *
 * This is a **blocking** structured-JSON call, matching the Qwen-Groq provider
 * design: Bitdeer does not offer SSE streaming on this endpoint, and for a
 * vision turn the model's latency dominates; streaming progress would be
 * cosmetic. The ensemble orchestrator emits its own phase events around the call.
 *
 * Defensive by construction: any malformed response becomes a typed
 * [FoodVisionOutcome.Failure] and can never reach the persistence pipeline.
 * No image re-encoding or file touching — the caller passes already-loaded
 * base64 bytes (the same contract as [QwenGroqFoodVisionProvider]).
 *
 * Rate-limit note: Bitdeer does not expose `x-ratelimit-remaining-tokens`
 * headers, so there is no pre-flight budget check. A 429 surfaces as
 * [FoodVisionErrorKind.RATE_LIMITED] and the ensemble falls through to Gemini.
 */
class BitdeerFoodVisionProvider internal constructor(
    private val apiKey: String,
    private val promptBuilder: (FoodVisionRequest) -> String,
    private val service: BitdeerApiService = BitdeerRetrofitClient.service,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : FoodVisionProvider {

    override val id: String = PROVIDER_ID

    override fun isAvailable(): Boolean =
        apiKey.isNotBlank() && apiKey != PLACEHOLDER_KEY

    override suspend fun analyze(request: FoodVisionRequest): FoodVisionOutcome {
        if (!isAvailable()) {
            return FoodVisionOutcome.Failure(
                provider = id,
                kind = FoodVisionErrorKind.NOT_CONFIGURED,
                message = "BITDEER_API_KEY not configured",
            )
        }

        val started = nowMs()
        fun elapsed() = nowMs() - started

        val parts = buildList {
            add(GroqContentPart.text(promptBuilder(request)))
            val image = request.base64Image?.takeIf { it.isNotBlank() }
            if (image != null) add(GroqContentPart.image(image, request.mimeType))
        }

        val payload = GroqChatRequest(
            model = model,
            messages = listOf(GroqMessage(role = "user", content = parts)),
            // Qwen3-VL-32B on Bitdeer does not support reasoning_effort; set null
            // so the field is omitted from the serialized body.
            reasoningEffort = null,
        )

        val response = try {
            withTimeout(timeoutMs) {
                service.chatCompletions(bearer = "Bearer $apiKey", request = payload)
            }
        } catch (e: TimeoutCancellationException) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.TIMEOUT, elapsed(), "timed out after ${timeoutMs}ms", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            return FoodVisionOutcome.Failure(id, httpErrorKind(e.code()), elapsed(), "HTTP ${e.code()}", e)
        } catch (e: IOException) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.REQUEST_FAILED, elapsed(), e.message, e)
        } catch (e: Exception) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.REQUEST_FAILED, elapsed(), e.message, e)
        }

        val content = response.choices?.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.INVALID_JSON, elapsed(), "empty completion")
        }

        val parsed = parseResult(content)
            ?: return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.INVALID_JSON, elapsed(), "unparseable JSON")

        if (parsed.name.isNullOrBlank() && parsed.ingredients.isNullOrEmpty()) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.RESULT_UNCERTAIN, elapsed(), "empty result")
        }

        val safeApplied = parsed.appliedHabitIds
            ?.filter { it in request.offeredHabitIds }
            ?.distinct()

        val stamped = parsed.copy(
            appliedHabitIds = safeApplied,
            analysisModel = model,
            escalated = false,
        )

        return FoodVisionOutcome.Success(
            FoodAnalysisEvidence(
                provider = id,
                result = stamped,
                latencyMs = elapsed(),
                confidence = ConfidenceScorer.score(stamped),
                requestId = response.id,
            )
        )
    }

    private fun parseResult(raw: String): FoodAnalysisResult? {
        adapter.let { a ->
            runCatching { a.fromJson(raw) }.getOrNull()?.let { return it }
            val slice = extractJsonObject(raw) ?: return null
            return runCatching { a.fromJson(slice) }.getOrNull()
        }
    }

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

    private fun httpErrorKind(code: Int): FoodVisionErrorKind = when {
        code == 429 -> FoodVisionErrorKind.RATE_LIMITED
        code == 413 -> FoodVisionErrorKind.PAYLOAD_TOO_LARGE
        code in 500..599 -> FoodVisionErrorKind.UNAVAILABLE
        else -> FoodVisionErrorKind.REQUEST_FAILED
    }

    private companion object {
        const val PROVIDER_ID = "bitdeer-qwen3vl"
        const val DEFAULT_MODEL = "Qwen/Qwen3-VL-32B"
        const val PLACEHOLDER_KEY = "MY_BITDEER_API_KEY"
        const val DEFAULT_TIMEOUT_MS = 90_000L

        val adapter by lazy {
            Moshi.Builder().build().adapter(FoodAnalysisResult::class.java)
        }
    }
}

/**
 * Production factory. Keeps [BitdeerApiService] internal while allowing
 * callers in [MealAnalysisOrchestrator] (different package) to build a provider.
 */
fun createBitdeerProvider(
    apiKey: String,
    promptBuilder: (FoodVisionRequest) -> String,
): FoodVisionProvider = BitdeerFoodVisionProvider(
    apiKey = apiKey,
    promptBuilder = promptBuilder,
)
