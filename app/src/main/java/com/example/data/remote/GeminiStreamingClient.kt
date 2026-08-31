package com.example.data.remote

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
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Server-Sent Events (SSE) streaming endpoint for Gemini's
 * `:streamGenerateContent`. Retrofit's converters do not handle the
 * line-oriented `data: {...}` framing well, so this is a hand-rolled
 * OkHttp call.
 *
 * Key choices:
 *   - **Separate OkHttpClient** with `Logging.NONE` (BODY-level logging
 *     buffers the entire response and silently breaks streams).
 *   - **300s read timeout** — a long Gemini answer can take >60s to fully
 *     stream; per-chunk activity is what matters, not the total.
 *   - The flow emits raw [StreamChunk]s; the orchestrator accumulates the
 *     text and runs the full proposal parser ONLY at [StreamChunk.Done].
 *     Running the parser per-chunk would briefly expose half-formed
 *     markers in the live bubble.
 *
 * Cancellation: cancelling the collecting coroutine cancels the OkHttp
 * `Call` via the `awaitClose` block, which terminates the network read.
 */
sealed class StreamChunk {
    /** A new piece of text just landed. Concatenate with previous Partials
     *  (NOT replace) — Gemini emits incrementals. */
    data class Partial(val deltaText: String, val accumulatedSoFar: String) : StreamChunk()

    /** Final accumulated text. Emitted only when Gemini's final candidate
     *  reported [finishReason] == "STOP". Any other value (`MAX_TOKENS`,
     *  `SAFETY`, `RECITATION`, `OTHER`, or the socket closing without ever
     *  emitting a finishReason) is surfaced as [Error] instead so the
     *  orchestrator can fall back to non-streaming. See 2026-07-05
     *  investigation of session 102 message 559: the loop used to always
     *  emit Done, which committed a truncated bubble ("**88.3 kg para")
     *  to Room.
     *
     *  2026-07-06 (Frente G) — carries the [UsageMetadata] Gemini reports
     *  in its final SSE chunk when available. Null when the endpoint
     *  didn't emit one at all (older free-tier flow) — the orchestrator
     *  logs "tokens=n/a" in that case. */
    data class Done(
        val fullText: String,
        val finishReason: String,
        val usage: UsageMetadata? = null,
    ) : StreamChunk()

    /** Network or parse failure mid-stream, OR truncation surfaced as an
     *  error. The orchestrator falls back to the non-streaming
     *  `generateContent` path. When [partial] is non-null the collector
     *  already committed a live bubble containing that prefix and should
     *  reuse it or replace it — the fallback text should REPLACE, not
     *  concatenate. */
    data class Error(val cause: Throwable, val partial: String? = null) : StreamChunk()
}

object GeminiStreamingClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    /** Logging is OFF for streaming — the BODY-level interceptor used by
     *  the canonical client buffers responses, killing the stream. Enable
     *  HEADERS for debugging the auth/handshake. */
    private val streamLogging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    private val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(streamLogging)
            .build()
    }

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val requestAdapter by lazy { moshi.adapter(GeminiRequest::class.java) }
    private val responseAdapter by lazy { moshi.adapter(GeminiResponse::class.java) }

    /**
     * Stream a Gemini reply. Each network "data: {...}" line becomes a
     * [StreamChunk.Partial]; the final accumulated text comes through as
     * [StreamChunk.Done] when the stream closes. Errors are emitted as
     * [StreamChunk.Error] and then the flow completes — the caller
     * decides whether to fall back to non-streaming.
     *
     * The Flow runs on [Dispatchers.IO]; the caller does not need a
     * separate dispatcher.
     */
    fun streamGenerateContent(
        model: String,
        apiKey: String,
        request: GeminiRequest
    ): Flow<StreamChunk> = callbackFlow {
        val bodyJson = requestAdapter.toJson(request)
        val url = "${BASE_URL}v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val httpReq = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = streamingClient.newCall(httpReq)
        var fullText = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(500).orEmpty()
                    trySend(StreamChunk.Error(IllegalStateException("HTTP ${response.code}: $body")))
                    close()
                    return@callbackFlow
                }
                val source = response.body?.source()
                if (source == null) {
                    trySend(StreamChunk.Error(IllegalStateException("Empty response body")))
                    close()
                    return@callbackFlow
                }

                // SSE parsing: each event is a `data: ...` line followed by a
                // blank line. The Gemini stream uses single-line JSON per event.
                // We exhaust readUtf8Line() until null (server closed) or the
                // call is cancelled.
                //
                // Truncation contract: Gemini attaches `finishReason` to the
                // final candidate. STOP = clean end. MAX_TOKENS/SAFETY/RECITATION
                // /OTHER = cut off — surface as Error so the orchestrator can
                // fall back. A null read without ever seeing any finishReason is
                // also truncation (network drop mid-stream).
                var finishReason: String? = null
                var truncatedRead = false
                var lastUsage: UsageMetadata? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line()
                    if (line == null) {
                        truncatedRead = (finishReason == null)
                        break
                    }
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val resp = runCatching { responseAdapter.fromJson(payload) }.getOrNull() ?: continue
                    if (resp.error != null) {
                        trySend(
                            StreamChunk.Error(
                                IllegalStateException("Gemini error: ${resp.error.message}"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() }
                            )
                        )
                        close()
                        return@callbackFlow
                    }
                    val cand = resp.candidates?.firstOrNull()
                    cand?.finishReason?.let { finishReason = it }
                    resp.usageMetadata?.let { lastUsage = it }
                    val delta = cand?.content?.parts?.firstOrNull()?.text
                    if (delta != null) {
                        fullText.append(delta)
                        trySend(StreamChunk.Partial(delta, fullText.toString()))
                    }
                }

                when {
                    finishReason != null && finishReason != "STOP" -> {
                        trySend(
                            StreamChunk.Error(
                                IllegalStateException("Stream truncated: finishReason=$finishReason"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                    truncatedRead || finishReason == null -> {
                        trySend(
                            StreamChunk.Error(
                                IllegalStateException("Stream ended without finishReason (silent truncation)"),
                                partial = fullText.toString().takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                    else -> {
                        trySend(StreamChunk.Done(fullText.toString(), finishReason!!, lastUsage))
                    }
                }
                close()
            }
        } catch (t: Throwable) {
            if (!call.isCanceled()) {
                trySend(
                    StreamChunk.Error(
                        t,
                        partial = fullText.toString().takeIf { it.isNotEmpty() }
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
