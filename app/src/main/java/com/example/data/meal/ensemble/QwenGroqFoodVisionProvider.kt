package com.example.data.meal.ensemble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.data.remote.FoodAnalysisResult
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Qwen-on-Groq vision provider.
 *
 * Implemented as a blocking structured-JSON call rather than a streaming one:
 * the plan explicitly defers duplicating the Gemini streaming machinery until
 * the ensemble is proven, and Qwen's sub-second latency makes streaming
 * progress cosmetic anyway. The existing progress card keeps working because
 * the orchestrator still emits its own phase events around this call.
 *
 * Defensive by construction: any malformed response is turned into a typed
 * [FoodVisionOutcome.Failure] and can never reach the persistence pipeline.
 */
class QwenGroqFoodVisionProvider internal constructor(
    private val apiKey: String,
    private val promptBuilder: (FoodVisionRequest) -> String,
    private val service: GroqApiService = GroqRetrofitClient.service,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Re-encodes an oversized base64 JPEG to fit within the given budget,
     * returning null when it cannot. Injected so this class stays free of
     * Android framework types and remains unit-testable on the JVM.
     */
    private val imageShrinker: ((String, Int) -> String?)? = null,
) : FoodVisionProvider {

    override val id: String = PROVIDER_ID

    override fun isAvailable(): Boolean =
        apiKey.isNotBlank() && apiKey != PLACEHOLDER_KEY

    override suspend fun analyze(request: FoodVisionRequest): FoodVisionOutcome {
        if (!isAvailable()) {
            return FoodVisionOutcome.Failure(
                provider = id,
                kind = FoodVisionErrorKind.NOT_CONFIGURED,
                message = "GROQ_API_KEY not configured",
            )
        }

        val started = nowMs()
        fun elapsed() = nowMs() - started

        // Groq answers an oversized image with HTTP 413, and a single request
        // that alone exceeds the per-minute token budget fails identically
        // however long we wait. Shrinking up front is therefore the only way
        // through; retrying the original bytes would burn a call to learn nothing.
        val image = request.base64Image?.takeIf { it.isNotBlank() }
        val usableImage = when {
            image == null -> null
            image.length <= MAX_IMAGE_BASE64_CHARS -> image
            else -> {
                val shrunk = imageShrinker?.invoke(image, MAX_IMAGE_BASE64_CHARS)
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_IMAGE_BASE64_CHARS }
                if (shrunk == null) {
                    return FoodVisionOutcome.Failure(
                        provider = id,
                        kind = FoodVisionErrorKind.PAYLOAD_TOO_LARGE,
                        latencyMs = elapsed(),
                        message = "image too large for $id (${image.length} chars > $MAX_IMAGE_BASE64_CHARS)",
                    )
                }
                shrunk
            }
        }

        val parts = buildList {
            add(GroqContentPart.text(promptBuilder(request)))
            usableImage?.let { add(GroqContentPart.image(it, request.mimeType)) }
        }


        // Pre-flight the per-minute token allowance. Groq reports what is left
        // on every response, so once a window is known to be exhausted we can
        // fail here in microseconds instead of spending a round trip only to be
        // told the same thing by an HTTP 429. Fails open when nothing is known.
        val estimated = estimateTokens(parts)
        val budgetNow = nowMs()
        if (!GroqTokenBudget.canAfford(model, estimated, budgetNow)) {
            return FoodVisionOutcome.Failure(
                provider = id,
                kind = FoodVisionErrorKind.RATE_LIMITED,
                latencyMs = elapsed(),
                message = "budget exhausted for $model: need ~$estimated, " +
                    "${GroqTokenBudget.remainingFor(model, budgetNow) ?: 0} left, " +
                    "resets in ${GroqTokenBudget.millisUntilReset(model, budgetNow)}ms",
            )
        }
        val payload = GroqChatRequest(
            model = model,
            messages = listOf(GroqMessage(role = "user", content = parts)),
        )

        val response = try {
            withTimeout(timeoutMs) {
                service.chatCompletions(bearer = "Bearer $apiKey", request = payload, model = model)
            }
        } catch (e: TimeoutCancellationException) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.TIMEOUT, elapsed(), "timed out after ${timeoutMs}ms", e)
        } catch (e: CancellationException) {
            // Caller-driven cancellation must propagate, not be swallowed as a
            // provider failure — otherwise a cancelled screen still "fails".
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

        // A parse that yields neither a name nor any ingredient is structurally
        // valid but semantically empty — treat it as uncertain rather than
        // letting a blank meal through to enrichment.
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

    /**
     * Tolerant JSON extraction. Even with `response_format=json_object`,
     * models occasionally wrap the payload in prose or a markdown fence, so we
     * retry on the outermost balanced object before giving up.
     */
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

    /**
     * Approximates the token cost of a request before sending it.
     *
     * Deliberately coarse: the goal is only to decide whether a call is
     * hopeless against a known-exhausted window, so a rough over-estimate is
     * the safe direction — under-estimating would let a doomed request through
     * and cost the round trip we are trying to avoid. Text is charged at the
     * usual ~4 chars/token; images are charged from their base64 length, whose
     * ratio was taken from observed `input_tokens` on real meal photos.
     */
    private fun estimateTokens(parts: List<GroqContentPart>): Int {
        var total = RESPONSE_TOKEN_ALLOWANCE
        for (part in parts) {
            part.text?.let { total += it.length / TEXT_CHARS_PER_TOKEN }
            part.imageUrl?.url?.let { total += it.length / IMAGE_CHARS_PER_TOKEN }
        }
        return total
    }

    private companion object {
        const val PROVIDER_ID = "qwen-groq"
        const val DEFAULT_MODEL = "qwen/qwen3.6-27b"
        const val PLACEHOLDER_KEY = "MY_GROQ_API_KEY"

        /** Rough text encoding ratio shared by most OpenAI-compatible models. */
        const val TEXT_CHARS_PER_TOKEN = 4

        /**
         * Base64 chars per token for images, derived from logged requests: a
         * ~560k-char photo bills around 2800 input tokens.
         */
        const val IMAGE_CHARS_PER_TOKEN = 200

        /** The completion is billed too, so reserve room for it. */
        const val RESPONSE_TOKEN_ALLOWANCE = 512

        /** Vision turns are heavier than text; mirrors the widened cap the
         *  chat path already uses for image sends. */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /**
         * Upper bound on the base64 length of the attached image.
         *
         * The real constraint is Groq's free-tier budget of 8000 tokens per
         * minute, not a byte-size cap: an image large enough to exceed that
         * budget on its own is rejected with HTTP 413 and the body
         * `code=rate_limit_exceeded, type=tokens` ("Request too large ... on
         * tokens per minute (TPM): Limit 8000"). Waiting does not help, since
         * the request can never fit in a fresh window.
         *
         * Measured against the live API: a 1792px JPEG (≈559k chars) is
         * accepted, while the app's standard 2048px upload (≈699k) is not.
         * 560k keeps a margin under that ceiling while preserving as much
         * detail as the budget allows, which matters because image
         * interpretation quality degrades with aggressive downscaling.
         */
        const val MAX_IMAGE_BASE64_CHARS = 560_000

        val adapter by lazy {
            Moshi.Builder().build().adapter(FoodAnalysisResult::class.java)
        }
    }
}

/**
 * Production factory. Kept separate from the constructor so the Groq
 * transport types can stay `internal` (they are an implementation detail of
 * this package) while callers outside it can still build a provider.
 */
fun createQwenGroqProvider(
    apiKey: String,
    promptBuilder: (FoodVisionRequest) -> String,
): FoodVisionProvider = QwenGroqFoodVisionProvider(
    apiKey = apiKey,
    promptBuilder = promptBuilder,
    imageShrinker = ::shrinkBase64Jpeg,
)

/**
 * Re-encodes an oversized base64 image so it fits within [budgetChars].
 *
 * Downscales progressively rather than jumping straight to a small size,
 * because vision quality on food photos is sensitive to resolution: the goal
 * is the *largest* image the API will accept, not the smallest that fits.
 * Returns null if it cannot get under budget, in which case the caller
 * escalates to another provider instead of sending a request that would be
 * rejected anyway.
 */
private fun shrinkBase64Jpeg(base64: String, budgetChars: Int): String? = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null

    try {
        // Each step is a modest reduction so we stop at the first size that fits.
        for (scale in doubleArrayOf(1.0, 0.85, 0.72, 0.6, 0.5, 0.4)) {
            val scaled = if (scale == 1.0) source else {
                val w = (source.width * scale).toInt().coerceAtLeast(1)
                val h = (source.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, w, h, true)
            }
            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (encoded.length <= budgetChars) return@runCatching encoded
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }
        null
    } finally {
        source.recycle()
    }
}.getOrNull()
