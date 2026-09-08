package com.example.data.ai.provider.runinfra

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
 * RunInfra chat provider — Qwen3.8-27B (plan §9). The **real** Groq failover
 * in the chat chain `Groq → RunInfra → Gemini` (§11), not an optional
 * placeholder.
 *
 * Adapts the neutral [ChatRequest] onto RunInfra's OpenAI-style streaming
 * transport ([RunInfraStreamingClient]) and relabels [RunInfraStreamChunk]
 * events as neutral [ChatEvent]s with normalized [ChatError]s — the §8
 * requirements (streaming, cancellation, timeout, 429 detection, response
 * validation, normalized errors) apply here too since RunInfra carries the
 * load whenever Groq is unhealthy.
 *
 * Text-only (§18): declares `multimodalInput = false`, so the router never
 * sends an image-required turn here — "Do not route Vision through RunInfra"
 * (§16/§17). An image turn skips RunInfra and goes to a multimodal provider or
 * the Gemini last resort.
 *
 * ### Why no self-skip budget (unlike Groq)
 * [com.example.data.ai.provider.groq.GroqChatProvider] consults a 429 budget in
 * `isAvailable()` so the router jumps *past* Groq to RunInfra without provoking
 * another Groq 429. RunInfra is the destination of that jump — if it also 429s,
 * the correct move is to fall through to Gemini immediately, which the
 * classified [ChatErrorKind.RATE_LIMITED] → failover already achieves. Repeat-
 * failure suppression across turns is the circuit breaker's job (§12, Phase 4),
 * applied uniformly by `provider:model`, so RunInfra needs no bespoke tracker.
 *
 * @param model RunInfra-hosted Qwen model id. Injectable + defaulted (§3.4);
 *   pinned by router config and validated live before promotion (§9).
 * @param apiKeyProvider resolves the key lazily (rotation-safe, never logged).
 *   Blank ⇒ not configured.
 * @param streamer injectable transport for deterministic tests.
 */
class RunInfraChatProvider(
    private val model: String = DEFAULT_MODEL,
    private val apiKeyProvider: () -> String,
    private val streamer: (String, RunInfraStreamRequest) -> Flow<RunInfraStreamChunk> =
        RunInfraStreamingClient()::stream,
) : ChatProvider {

    override val id: ProviderModelId = ProviderModelId.of(PROVIDER, model)

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        textInput = true,
        multimodalInput = false, // §18: text-only; never route Vision here
        streaming = true,
        structuredOutput = true,
    )

    override fun isAvailable(): Boolean {
        val key = runCatching { apiKeyProvider() }.getOrNull().orEmpty()
        return key.isNotBlank() && key != PLACEHOLDER_KEY
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val apiKey = apiKeyProvider()
        val runInfraRequest = buildRequest(request)

        streamer(apiKey, runInfraRequest).collect { chunk ->
            when (chunk) {
                is RunInfraStreamChunk.Partial ->
                    emit(ChatEvent.Delta(chunk.deltaText, chunk.accumulatedSoFar))

                is RunInfraStreamChunk.Done ->
                    emit(
                        ChatEvent.Completed(
                            fullText = chunk.fullText,
                            providerModel = id,
                            usage = chunk.usage?.toChatUsage(),
                        )
                    )

                is RunInfraStreamChunk.Error ->
                    emit(ChatEvent.Failed(chunk.toChatError()))
            }
        }
    }

    private fun buildRequest(request: ChatRequest): RunInfraStreamRequest {
        val messages = mutableListOf<RunInfraStreamMessage>()
        // System instruction preserved verbatim as an OpenAI `system` message
        // (§16 — a model change must not rewrite the prompt).
        request.systemInstruction?.takeIf { it.isNotBlank() }?.let {
            messages.add(RunInfraStreamMessage(role = "system", content = it))
        }
        request.messages.forEach { msg ->
            messages.add(RunInfraStreamMessage(role = msg.role.toOpenAiRole(), content = msg.text))
        }
        return RunInfraStreamRequest(
            model = model,
            messages = messages,
            stream = true,
            temperature = request.generation.temperature?.toDouble(),
            topP = request.generation.topP?.toDouble(),
            maxTokens = request.generation.maxOutputTokens,
            responseFormat = if (request.generation.structured) {
                RunInfraResponseFormatRef("json_object")
            } else null,
        )
    }

    private fun ChatRole.toOpenAiRole(): String = when (this) {
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
        ChatRole.SYSTEM -> "system"
    }

    private fun RunInfraUsage.toChatUsage(): ChatUsage =
        ChatUsage(promptTokens, completionTokens, totalTokens)

    private fun RunInfraStreamChunk.Error.toChatError(): ChatError = ChatError(
        kind = classify(this),
        message = cause.message?.take(300) ?: cause.javaClass.simpleName,
        providerModel = id,
        cause = cause,
        partialText = partial,
    )

    private fun classify(error: RunInfraStreamChunk.Error): ChatErrorKind {
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
        const val PROVIDER = "runinfra"
        /** Default RunInfra-hosted Qwen chat model. Pinned by router config;
         *  validated live before promotion (§9). */
        const val DEFAULT_MODEL = "qwen3.8-27b"
        private const val PLACEHOLDER_KEY = "MY_RUNINFRA_API_KEY"
    }
}
