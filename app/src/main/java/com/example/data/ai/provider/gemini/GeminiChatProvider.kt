package com.example.data.ai.provider.gemini

import com.example.data.ai.provider.ChatError
import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatGeneration
import com.example.data.ai.provider.ChatImage
import com.example.data.ai.provider.ChatProvider
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ChatRole
import com.example.data.ai.provider.ChatUsage
import com.example.data.ai.provider.ProviderCapabilities
import com.example.data.ai.provider.ProviderModelId
import com.example.data.remote.Content
import com.example.data.remote.GenerationConfig
import com.example.data.remote.GeminiRequest
import com.example.data.remote.GeminiStreamingClient
import com.example.data.remote.InlineData
import com.example.data.remote.Part
import com.example.data.remote.StreamChunk
import com.example.data.remote.ThinkingConfig
import com.example.data.remote.UsageMetadata
import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adapts the existing Gemini SSE transport to the provider-neutral
 * [ChatProvider] contract (plan §10 — *"Adapt it to the provider-neutral
 * interface rather than rewriting it. Preserve: streaming; watchdogs;
 * caching; model selection; context handling; existing fallback mechanisms
 * where applicable."*).
 *
 * This class is a THIN mapper. It does not re-implement SSE parsing, timeouts
 * or the truncation contract — all of that stays in
 * [GeminiStreamingClient.streamGenerateContent], which continues to be the
 * single source of the `finishReason == "STOP"` rule. This adapter only:
 *   1. builds a [GeminiRequest] from the neutral [ChatRequest] (preserving the
 *      context-cache reference and thinking level via the Gemini pass-through),
 *   2. relabels [StreamChunk] events as [ChatEvent] with a normalized
 *      [ChatError] on failure.
 *
 * ### Phase 1 boundary (plan §21)
 * Phase 1 is "wrap Gemini first, no user-visible change". This adapter is
 * created now but the orchestrator is NOT yet re-pointed at it — the live chat
 * path still calls the streaming client directly. The router (Phase 4) is what
 * will consume providers. Introducing the wrapper without switching the call
 * site is exactly what keeps Phase 1 behaviourally inert.
 *
 * Watchdogs, caching and the non-streaming fallback are orchestrator-level
 * concerns layered *around* the raw stream today; they are preserved by
 * leaving the orchestrator in charge of them (§10). This adapter exposes the
 * raw provider stream; the streaming-safety layer (§13, Phase 5) reconciles
 * mid-stream failover on top of it.
 *
 * @param model the concrete Gemini model id (e.g. a flash model). Model
 *   *selection* stays with the orchestrator/registry — this adapter is handed
 *   an already-resolved model so a model swap is configuration, not code
 *   (§3.4).
 * @param apiKeyProvider resolves the Gemini key lazily at call time. A
 *   function (not a stored string) so a rotated key is picked up and so the
 *   key is never captured/logged. Returns blank when unset.
 * @param base64Encoder injectable so unit tests need no Android framework;
 *   defaults to android.util.Base64 in production.
 */
class GeminiChatProvider(
    private val model: String,
    private val apiKeyProvider: () -> String,
    private val streamer: (String, String, GeminiRequest) -> Flow<StreamChunk> =
        { m, key, req -> GeminiStreamingClient.streamGenerateContent(m, key, req) },
    private val base64Encoder: (ByteArray) -> String = { bytes ->
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    },
) : ChatProvider {

    override val id: ProviderModelId = ProviderModelId.of(PROVIDER, model)

    /** Gemini is the last-resort chat provider AND the vision fallback: it
     *  accepts images and supports structured output. */
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        textInput = true,
        multimodalInput = true,
        streaming = true,
        structuredOutput = true,
    )

    override fun isAvailable(): Boolean {
        val key = runCatching { apiKeyProvider() }.getOrNull().orEmpty()
        // Blank, or the .env placeholder, means not configured — the router
        // must skip (not fail) us. See env-key-name-drift trap: a placeholder
        // key silently kills AI, so treat it as unavailable, not as an auth
        // failure that gets recorded against the breaker.
        return key.isNotBlank() && key != PLACEHOLDER_KEY
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val apiKey = apiKeyProvider()
        val geminiRequest = buildRequest(request)

        streamer(model, apiKey, geminiRequest).collect { chunk ->
            when (chunk) {
                is StreamChunk.Partial ->
                    emit(ChatEvent.Delta(chunk.deltaText, chunk.accumulatedSoFar))

                is StreamChunk.Done ->
                    emit(
                        ChatEvent.Completed(
                            fullText = chunk.fullText,
                            providerModel = id,
                            usage = chunk.usage?.toChatUsage(),
                        )
                    )

                is StreamChunk.Error ->
                    emit(ChatEvent.Failed(chunk.toChatError()))
            }
        }
    }

    private fun buildRequest(request: ChatRequest): GeminiRequest {
        val contents = request.messages.map { msg ->
            val parts = mutableListOf(Part(text = msg.text))
            // Images ride on the final user turn. We attach them to every
            // USER part carrying images; in practice the current turn is the
            // last user message. Bytes are base64-encoded transiently here and
            // never persisted (§4).
            Content(role = msg.role.toGeminiRole(), parts = parts)
        }.toMutableList()

        // Attach transient images to the last user turn, if any.
        if (request.hasImages && contents.isNotEmpty()) {
            val lastUserIdx = contents.indexOfLast { it.role == "user" }
            if (lastUserIdx >= 0) {
                val withImages = contents[lastUserIdx].parts.toMutableList()
                request.images.forEach { img ->
                    withImages.add(Part(inlineData = img.toInlineData()))
                }
                contents[lastUserIdx] = contents[lastUserIdx].copy(parts = withImages)
            }
        }

        val systemInstruction = request.systemInstruction
            ?.takeIf { it.isNotBlank() }
            ?.let { Content(parts = listOf(Part(text = it))) }

        return GeminiRequest(
            contents = contents,
            generationConfig = request.generation.toGenerationConfig(request.geminiHints?.thinkingLevel),
            systemInstruction = systemInstruction,
            cachedContent = request.geminiHints?.cachedContent,
        )
    }

    private fun ChatImage.toInlineData(): InlineData =
        InlineData(mimeType = mimeType, data = base64Encoder(bytes))

    private fun ChatGeneration.toGenerationConfig(thinkingLevel: String?): GenerationConfig? {
        val thinking = thinkingLevel?.let { ThinkingConfig(thinkingLevel = it) }
        // Return null only when there is genuinely nothing to send, so the
        // Moshi payload matches the pre-refactor shape for a plain turn.
        if (temperature == null && topP == null && maxOutputTokens == null &&
            !structured && thinking == null
        ) {
            return null
        }
        return GenerationConfig(
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxOutputTokens,
            responseMimeType = if (structured) "application/json" else null,
            thinkingConfig = thinking,
        )
    }

    private fun ChatRole.toGeminiRole(): String = when (this) {
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "model"
        // Gemini has no inline "system" role in contents; a system turn should
        // have been lifted into systemInstruction upstream. If one slips in,
        // send it as a user turn rather than dropping it.
        ChatRole.SYSTEM -> "user"
    }

    private fun UsageMetadata.toChatUsage(): ChatUsage = ChatUsage(
        promptTokens = promptTokenCount,
        completionTokens = candidatesTokenCount,
        totalTokens = totalTokenCount,
    )

    /**
     * Map a raw [StreamChunk.Error] onto the normalized [ChatError] the router
     * acts on. The streaming client encodes the cause in the exception message
     * (HTTP code, "Stream truncated", "silent truncation", etc.), so we
     * classify from that plus the throwable type. Truncation → INVALID_RESPONSE
     * because the answer is unusable and a fallback should replace it (§13).
     */
    private fun StreamChunk.Error.toChatError(): ChatError {
        val kind = classify(cause)
        return ChatError(
            kind = kind,
            message = cause.message?.take(300) ?: cause.javaClass.simpleName,
            providerModel = id,
            cause = cause,
            partialText = partial,
        )
    }

    private fun classify(cause: Throwable): ChatErrorKind {
        val msg = cause.message.orEmpty()
        return when {
            "HTTP 429" in msg -> ChatErrorKind.RATE_LIMITED
            Regex("HTTP 5\\d\\d") in msg -> ChatErrorKind.SERVER_ERROR
            "HTTP 401" in msg || "HTTP 403" in msg -> ChatErrorKind.AUTH_OR_CONFIG
            Regex("HTTP 4\\d\\d") in msg -> ChatErrorKind.INVALID_REQUEST
            "truncat" in msg || "finishReason" in msg ||
                "Empty response" in msg || "Gemini error" in msg -> ChatErrorKind.INVALID_RESPONSE
            isTimeout(cause) -> ChatErrorKind.TIMEOUT
            isNetwork(cause) -> ChatErrorKind.NETWORK
            else -> ChatErrorKind.UNKNOWN
        }
    }

    private fun isTimeout(cause: Throwable): Boolean =
        cause is java.net.SocketTimeoutException ||
            cause is java.util.concurrent.TimeoutException ||
            "timeout" in cause.message.orEmpty().lowercase()

    private fun isNetwork(cause: Throwable): Boolean =
        cause is java.net.UnknownHostException ||
            cause is java.net.ConnectException ||
            cause is java.io.IOException

    companion object {
        const val PROVIDER = "gemini"
        private const val PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"
    }
}
