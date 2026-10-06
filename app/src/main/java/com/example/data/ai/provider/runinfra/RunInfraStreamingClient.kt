package com.example.data.ai.provider.runinfra

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled Server-Sent Events client for RunInfra's OpenAI-compatible
 * chat-completions endpoint in **streaming** mode (`"stream": true`) — plan §9.
 *
 * RunInfra is the real Groq failover (§9: "not an optional placeholder"), so
 * this mirrors [com.example.data.ai.provider.groq.GroqStreamingClient] framing
 * 1:1: `data: {…}` lines, a clean `finish_reason == "stop"` contract, and any
 * other finish (or the socket closing before one) surfaced as [Error] so the
 * router fails over rather than committing a truncated answer.
 *
 * ### Endpoint is configuration, not a constant to trust blindly
 * [baseUrl] and the model id are **injected** and validated against the live
 * paid account before promotion (§9 items 1–2: load credit, validate
 * endpoint/model, validate streaming, measure TTFT). The [DEFAULT_BASE_URL]
 * here is the documented OpenAI-compatible convention.
 *
 * §9 live validation done 2026-09-08 against the paid account:
 *   - `https://api.runinfra.ai/` + `v1/chat/completions`, Bearer auth → HTTP 200;
 *   - served model id is `qwen3-8-27b` (dashes; the dotted form is NOT served);
 *   - non-streaming and streaming completions both return `finish_reason==stop`;
 *   - streaming emits `data: {…}` SSE chunks + a `[DONE]` sentinel; TTFT ~1.4–2.0s.
 * The base URL / path / auth scheme are therefore confirmed correct; RunInfra
 * remains gated behind `CHAT_ROUTER_ENABLED` (default false) until the flag flips.
 *
 * Cancellation: cancelling the collector cancels the OkHttp `Call` via
 * `awaitClose` (§8 cancellation). Never logs the request body or Authorization
 * header (§20).
 */
sealed class RunInfraStreamChunk {
    data class Partial(val deltaText: String, val accumulatedSoFar: String) : RunInfraStreamChunk()

    /** 2026-10-06 (Frente Thinking) — a `delta.reasoning_content` fragment
     *  (Qwen's reasoning on RunInfra; the OpenAI field name, vs Groq's
     *  `reasoning`). Never merged into [Partial] text. */
    data class Reasoning(val deltaText: String, val accumulatedSoFar: String) : RunInfraStreamChunk()

    data class Done(
        val fullText: String,
        val finishReason: String,
        val usage: RunInfraUsage? = null,
    ) : RunInfraStreamChunk()

    /** [httpStatus] is the HTTP status when the failure was an unsuccessful
     *  response (so 429/5xx/4xx can be classified), else null for
     *  transport/parse failures. [partial] carries any text already streamed. */
    data class Error(
        val cause: Throwable,
        val httpStatus: Int? = null,
        val partial: String? = null,
    ) : RunInfraStreamChunk()
}

data class RunInfraUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

class RunInfraStreamingClient(
    /** OpenAI-compatible base URL, ending in `/`. Injected + validated live
     *  (§9). */
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultClient,
) {
    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val requestAdapter by lazy { moshi.adapter(RunInfraStreamRequest::class.java) }
    private val chunkAdapter by lazy { moshi.adapter(RunInfraStreamResponseChunk::class.java) }

    /**
     * Stream a RunInfra chat completion. [request] must have `stream=true`.
     * [apiKey] is used as the bearer token and never logged.
     */
    fun stream(
        apiKey: String,
        request: RunInfraStreamRequest,
    ): Flow<RunInfraStreamChunk> = callbackFlow {
        val bodyJson = requestAdapter.toJson(request)
        val httpReq = Request.Builder()
            .url("$baseUrl$PATH")
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = httpClient.newCall(httpReq)
        val fullText = StringBuilder()
        val reasoningText = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(500).orEmpty()
                    trySend(
                        RunInfraStreamChunk.Error(
                            IllegalStateException("HTTP ${response.code}: $body"),
                            httpStatus = response.code,
                        )
                    )
                    close()
                    return@callbackFlow
                }
                val source = response.body?.source()
                if (source == null) {
                    trySend(RunInfraStreamChunk.Error(IllegalStateException("Empty response body")))
                    close()
                    return@callbackFlow
                }

                var finishReason: String? = null
                var truncatedRead = false
                var lastUsage: RunInfraUsage? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line()
                    if (line == null) {
                        truncatedRead = (finishReason == null)
                        break
                    }
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") continue

                    val chunk = runCatching { chunkAdapter.fromJson(payload) }.getOrNull() ?: continue
                    chunk.usage?.let {
                        lastUsage = RunInfraUsage(it.promptTokens, it.completionTokens, it.totalTokens)
                    }
                    val choice = chunk.choices?.firstOrNull()
                    choice?.finishReason?.let { finishReason = it }
                    val reasoningDelta = choice?.delta?.reasoningContent
                    if (!reasoningDelta.isNullOrEmpty()) {
                        // Extended-thinking fragment — its own channel, kept
                        // OUT of the answer text (persisted text is identical
                        // with or without reasoning enabled).
                        reasoningText.append(reasoningDelta)
                        trySend(RunInfraStreamChunk.Reasoning(reasoningDelta, reasoningText.toString()))
                    }
                    val delta = choice?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        fullText.append(delta)
                        trySend(RunInfraStreamChunk.Partial(delta, fullText.toString()))
                    }
                }

                when {
                    finishReason != null && finishReason != "stop" -> {
                        trySend(
                            RunInfraStreamChunk.Error(
                                IllegalStateException("Stream truncated: finish_reason=$finishReason"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                    truncatedRead || finishReason == null -> {
                        trySend(
                            RunInfraStreamChunk.Error(
                                IllegalStateException("Stream ended without finish_reason (silent truncation)"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                    else -> {
                        trySend(RunInfraStreamChunk.Done(fullText.toString(), finishReason!!, lastUsage))
                    }
                }
                close()
            }
        } catch (t: Throwable) {
            if (!call.isCanceled()) {
                trySend(
                    RunInfraStreamChunk.Error(
                        t,
                        partial = fullText.toString().takeIf { it.isNotEmpty() },
                    )
                )
            }
            close()
        }

        awaitClose {
            if (!call.isCanceled()) call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Documented OpenAI-compatible convention; a starting point for the
         *  §9 live validation, NOT a confirmed production endpoint. */
        const val DEFAULT_BASE_URL = "https://api.runinfra.ai/"
        private const val PATH = "v1/chat/completions"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
