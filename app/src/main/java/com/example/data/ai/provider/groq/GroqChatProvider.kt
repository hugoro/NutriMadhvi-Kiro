package com.example.data.ai.provider.groq

import com.example.data.ai.provider.ChatError
import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatProvider
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ChatRole
import com.example.data.ai.provider.ChatUsage
import com.example.data.ai.provider.ProviderCapabilities
import com.example.data.ai.provider.ProviderModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Groq chat provider — Qwen3.8-27B (plan §8, benchmark primary §2).
 *
 * Adapts the neutral [ChatRequest] onto Groq's OpenAI-style streaming
 * transport ([GroqStreamingClient]) and relabels [GroqStreamChunk] events as
 * neutral [ChatEvent]s with a normalized [ChatError]. Requirements from §8:
 * streaming, cancellation, timeout handling, 429 detection, response
 * validation, normalized errors — all satisfied by delegating framing to the
 * streaming client and classifying its typed failures here.
 *
 * Text-only in the chat role: Qwen3.8-27B on Groq answers conversational
 * turns. [capabilities] therefore declares `multimodalInput = false`, so the
 * router never sends an image-required turn here (§18) — that goes to a
 * multimodal provider or the Gemini last resort. (Vision meal-photo analysis
 * is an entirely separate pipeline, §17.)
 *
 * ### 429 / free-tier reality (§2, §8)
 * The benchmark account is Free tier and Developer upgrade was unavailable;
 * 429s were observed. Two defences:
 *   1. [isAvailable] consults [GroqStreamBudget]: if the last response said the
 *      token allowance is spent and hasn't reset, report unavailable so the
 *      router *skips straight to RunInfra* instead of provoking another 429
 *      (§11: "A healthy alternate provider is preferable to repeated retries
 *      against an unhealthy provider").
 *   2. A live 429 is classified [ChatErrorKind.RATE_LIMITED] → fast failover
 *      (§12: "A Groq 429 should cause fast failover to RunInfra rather than
 *      repeated Groq retries").
 *
 * @param model the Groq-hosted Qwen model id. Injectable and defaulted so a
 *   model swap is configuration, not code (§3.4). The exact Groq id is pinned
 *   by configuration in the router phase; validated against the live account
 *   before promotion (§6/§23).
 * @param apiKeyProvider resolves the key lazily (rotation-safe, never
 *   captured/logged). Blank ⇒ not configured.
 * @param nowMs injectable clock for deterministic budget tests.
 */
class GroqChatProvider(
    private val model: String = DEFAULT_MODEL,
    private val apiKeyProvider: () -> String,
    private val streamer: (String, GroqStreamRequest) -> Flow<GroqStreamChunk> =
        { key, req -> GroqStreamingClient.stream(key, req) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ChatProvider {

    override val id: ProviderModelId = ProviderModelId.of(PROVIDER, model)

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        textInput = true,
        multimodalInput = false, // §18: text-only in chat
        streaming = true,
        structuredOutput = true,
    )

    override fun isAvailable(): Boolean {
        val key = runCatching { apiKeyProvider() }.getOrNull().orEmpty()
        if (key.isBlank() || key == PLACEHOLDER_KEY) return false
        // Skip Groq when its token window is known-exhausted and not yet reset,
        // so the router fails over without triggering another 429.
        return GroqStreamBudget.isUsable(model, nowMs())
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val apiKey = apiKeyProvider()
        val groqRequest = buildRequest(request)

        streamer(apiKey, groqRequest).collect { chunk ->
            when (chunk) {
                is GroqStreamChunk.Partial ->
                    emit(ChatEvent.Delta(chunk.deltaText, chunk.accumulatedSoFar))

                is GroqStreamChunk.Reasoning ->
                    emit(ChatEvent.Thinking(chunk.deltaText, chunk.accumulatedSoFar))

                is GroqStreamChunk.Done ->
                    emit(
                        ChatEvent.Completed(
                            fullText = chunk.fullText,
                            providerModel = id,
                            usage = chunk.usage?.toChatUsage(),
                        )
                    )

                is GroqStreamChunk.Error -> {
                    // Record a 429 against the budget so subsequent isAvailable
                    // checks skip Groq until the window resets.
                    if (chunk.httpStatus == 429) {
                        GroqStreamBudget.record429(model, nowMs())
                    }
                    emit(ChatEvent.Failed(chunk.toChatError()))
                }
            }
        }
    }

    private fun buildRequest(request: ChatRequest): GroqStreamRequest {
        val messages = mutableListOf<GroqStreamMessage>()
        // System instruction becomes an OpenAI `system` message rather than a
        // separate field. Truncated to MAX_SYSTEM_CHARS to stay within Groq's
        // Tier-1 token budget (~6000 TPM): the persona and tone rules are at
        // the top; the rolling data snapshot at the end is trimmed first.
        request.systemInstruction?.takeIf { it.isNotBlank() }?.let {
            messages.add(GroqStreamMessage(role = "system", content = it.take(MAX_SYSTEM_CHARS)))
        }
        request.messages.forEach { msg ->
            messages.add(GroqStreamMessage(role = msg.role.toOpenAiRole(), content = msg.text))
        }
        return GroqStreamRequest(
            model = model,
            messages = messages,
            stream = true,
            temperature = request.generation.temperature?.toDouble(),
            topP = request.generation.topP?.toDouble(),
            maxTokens = request.generation.maxOutputTokens,
            // Explicit: the server-side default for Qwen's reasoning effort is
            // not "none" and not documented — pin it. Structured turns keep
            // the reasoning pass off (JSON output, no thinking budget).
            reasoningEffort = if (request.generation.structured) "none" else "low",
            responseFormat = if (request.generation.structured) {
                GroqResponseFormatRef("json_object")
            } else null,
        )
    }

    private fun ChatRole.toOpenAiRole(): String = when (this) {
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
        ChatRole.SYSTEM -> "system"
    }

    private fun GroqUsage.toChatUsage(): ChatUsage =
        ChatUsage(promptTokens, completionTokens, totalTokens)

    private fun GroqStreamChunk.Error.toChatError(): ChatError = ChatError(
        kind = classify(this),
        message = cause.message?.take(300) ?: cause.javaClass.simpleName,
        providerModel = id,
        cause = cause,
        partialText = partial,
    )

    private fun classify(error: GroqStreamChunk.Error): ChatErrorKind {
        error.httpStatus?.let { code ->
            return when {
                code == 429 -> ChatErrorKind.RATE_LIMITED
                code in 500..599 -> ChatErrorKind.SERVER_ERROR
                code == 401 || code == 403 -> ChatErrorKind.AUTH_OR_CONFIG
                code in 400..499 -> ChatErrorKind.INVALID_REQUEST
                else -> ChatErrorKind.UNKNOWN
            }
        }
        val msg = error.cause.message.orEmpty()
        return when {
            "truncat" in msg || "finish_reason" in msg || "Empty response" in msg ->
                ChatErrorKind.INVALID_RESPONSE
            isTimeout(error.cause) -> ChatErrorKind.TIMEOUT
            isNetwork(error.cause) -> ChatErrorKind.NETWORK
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
        const val PROVIDER = "groq"
        /** Default Groq-hosted Qwen chat model. The exact id is pinned by the
         *  router configuration and validated live before promotion (§6). */
        const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
        private const val PLACEHOLDER_KEY = "MY_GROQ_API_KEY"
        /**
         * Groq Tier-1 allows ~6000 tokens/min. The NutriMadhvi system prompt
         * (persona + snapshot + KB) easily exceeds that. Cap the system message
         * at ~4000 tokens (≈16000 chars at ~4 chars/token) so the persona and
         * tone rules at the top are preserved and the variable data snapshot at
         * the bottom is trimmed. RunInfra and Gemini receive the full prompt.
         */
        private const val MAX_SYSTEM_CHARS = 16_000
    }
}
