package com.example.data.ai.provider.groq

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
 * Hand-rolled Server-Sent Events client for Groq's OpenAI-compatible
 * `/openai/v1/chat/completions` endpoint in **streaming** mode
 * (`"stream": true`) — plan §8.
 *
 * Why a second SSE client rather than the existing Retrofit
 * [com.example.data.meal.ensemble.GroqRetrofitClient]? That path is a
 * `suspend` call returning a fully-buffered `GroqChatResponse` with
 * `response_format=json_object` and `reasoning_effort=none`, purpose-built for
 * the one-shot vision JSON. Streaming chat needs the line-oriented `data:
 * {...}` framing Retrofit's converter cannot handle — the same reason
 * [com.example.data.remote.GeminiStreamingClient] exists alongside the
 * canonical Gemini Retrofit client. This mirrors that precedent deliberately
 * (§25: do not rewrite what works; add the streaming variant beside it).
 *
 * Event vocabulary is intentionally identical to
 * [com.example.data.remote.StreamChunk] so the adapter that consumes it maps
 * 1:1 onto the neutral [com.example.data.ai.provider.ChatEvent]:
 *   - [GroqStreamChunk.Partial] per delta,
 *   - [GroqStreamChunk.Done] once, only when a choice reported a clean
 *     `finish_reason == "stop"` (mirrors Gemini's STOP contract — any other
 *     finish, or the socket closing before one, is surfaced as [Error] so the
 *     router fails over rather than committing a truncated answer),
 *   - [GroqStreamChunk.Error] on HTTP failure, mid-stream drop or truncation,
 *     carrying the HTTP status text so the adapter can classify 429/5xx/4xx.
 *
 * Cancellation: cancelling the collecting coroutine cancels the OkHttp `Call`
 * via `awaitClose`, tearing down the network read (§8 cancellation).
 *
 * Never logs the request body or Authorization header — a chat turn can carry
 * a transient image data URL, and the key must never reach logcat (§20).
 */
sealed class GroqStreamChunk {
    data class Partial(val deltaText: String, val accumulatedSoFar: String) : GroqStreamChunk()

    /** 2026-10-06 (Frente Thinking) — a `delta.reasoning` fragment (Qwen's
     *  extended thinking, streamed separately from `delta.content` when
     *  `reasoning_effort` != "none"). Never merged into [Partial] text: the
     *  persisted answer stays clean, the thinking feeds the live card. */
    data class Reasoning(val deltaText: String, val accumulatedSoFar: String) : GroqStreamChunk()

    data class Done(
        val fullText: String,
        val finishReason: String,
        val usage: GroqUsage? = null,
    ) : GroqStreamChunk()

    /** [httpStatus] is the HTTP status code when the failure was an
     *  unsuccessful response (so 429/5xx/4xx can be classified), else null for
     *  transport/parse failures. [partial] carries any text already streamed. */
    data class Error(
        val cause: Throwable,
        val httpStatus: Int? = null,
        val partial: String? = null,
    ) : GroqStreamChunk()
}

data class GroqUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

object GroqStreamingClient {
    private const val BASE_URL = "https://api.groq.com/"
    private const val PATH = "openai/v1/chat/completions"

    private val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Long chat answers stream over many seconds; per-chunk activity is
            // what matters, so the read timeout is generous like Gemini's.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val requestAdapter by lazy { moshi.adapter(GroqStreamRequest::class.java) }
    private val chunkAdapter by lazy { moshi.adapter(GroqStreamResponseChunk::class.java) }

    /**
     * Stream a Groq chat completion. [request] must have `stream=true`.
     * [apiKey] is used as the bearer token and never logged.
     */
    fun stream(
        apiKey: String,
        request: GroqStreamRequest,
    ): Flow<GroqStreamChunk> = callbackFlow {
        val bodyJson = requestAdapter.toJson(request)
        val httpReq = Request.Builder()
            .url("$BASE_URL$PATH")
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = streamingClient.newCall(httpReq)
        val fullText = StringBuilder()
        val reasoningText = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(500).orEmpty()
                    trySend(
                        GroqStreamChunk.Error(
                            IllegalStateException("HTTP ${response.code}: $body"),
                            httpStatus = response.code,
                        )
                    )
                    close()
                    return@callbackFlow
                }
                val source = response.body?.source()
                if (source == null) {
                    trySend(GroqStreamChunk.Error(IllegalStateException("Empty response body")))
                    close()
                    return@callbackFlow
                }

                var finishReason: String? = null
                var truncatedRead = false
                var lastUsage: GroqUsage? = null
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
                    // Groq terminates the SSE stream with a literal `[DONE]`
                    // sentinel line after the final chunk.
                    if (payload == "[DONE]") continue

                    val chunk = runCatching { chunkAdapter.fromJson(payload) }.getOrNull() ?: continue
                    chunk.usage?.let {
                        lastUsage = GroqUsage(it.promptTokens, it.completionTokens, it.totalTokens)
                    }
                    val choice = chunk.choices?.firstOrNull()
                    choice?.finishReason?.let { finishReason = it }
                    val reasoningDelta = choice?.delta?.reasoning
                    if (!reasoningDelta.isNullOrEmpty()) {
                        // Extended-thinking fragment — its own channel, kept
                        // OUT of the answer text (persisted text is identical
                        // with or without reasoning enabled).
                        reasoningText.append(reasoningDelta)
                        trySend(GroqStreamChunk.Reasoning(reasoningDelta, reasoningText.toString()))
                    }
                    val delta = choice?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        fullText.append(delta)
                        trySend(GroqStreamChunk.Partial(delta, fullText.toString()))
                    }
                }

                when {
                    finishReason != null && finishReason != "stop" -> {
                        trySend(
                            GroqStreamChunk.Error(
                                IllegalStateException("Stream truncated: finish_reason=$finishReason"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                    truncatedRead || finishReason == null -> {
                        trySend(
                            GroqStreamChunk.Error(
                                IllegalStateException("Stream ended without finish_reason (silent truncation)"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                    else -> {
                        trySend(GroqStreamChunk.Done(fullText.toString(), finishReason!!, lastUsage))
                    }
                }
                close()
            }
        } catch (t: Throwable) {
            if (!call.isCanceled()) {
                trySend(
                    GroqStreamChunk.Error(
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
}
