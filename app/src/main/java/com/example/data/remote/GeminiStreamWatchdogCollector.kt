package com.example.data.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object GeminiStreamWatchdogCollector {

    internal data class Spec(
        val ttftTimeoutMs: Long,
        val idleTimeoutMs: Long,
        val tickMs: Long,
        val ttftReason: String = "TTFT timeout",
        val idleReason: String = "idle stream timeout",
    )

    internal data class Result(
        val fullText: String,
        val chunkCount: Int,
        val error: Throwable? = null,
    )

    internal fun timeoutReason(
        startedAtMs: Long,
        lastPartialAtMs: Long?,
        nowMs: Long,
        spec: Spec,
    ): String? = when {
        lastPartialAtMs == null && nowMs - startedAtMs >= spec.ttftTimeoutMs -> spec.ttftReason
        lastPartialAtMs != null && nowMs - lastPartialAtMs >= spec.idleTimeoutMs -> spec.idleReason
        else -> null
    }

    internal fun normalizeThrowable(
        cause: Throwable,
        watchdogReason: String?
    ): Throwable? {
        if (cause is CancellationException && watchdogReason == null) return null
        return if (watchdogReason != null) {
            CancellationException(watchdogReason).also { normalized ->
                normalized.initCause(cause)
            }
        } else {
            cause
        }
    }

    internal suspend fun collect(
        model: String,
        apiKey: String,
        request: GeminiRequest,
        logTag: String,
        spec: Spec,
        clearTextOnStreamError: Boolean = false,
        clearTextOnThrowable: Boolean = false,
        onTimeoutLog: (String) -> String,
        onFirstToken: suspend () -> Unit = {},
        onPartial: suspend (String, Int) -> Unit = { _, _ -> },
        onDone: suspend (String, Int, StreamChunk.Done) -> Unit = { _, _, _ -> },
        onError: suspend (StreamChunk.Error, Int, String) -> Unit = { _, _, _ -> },
    ): Result {
        var fullText = ""
        var chunkCount = 0
        var streamError: Throwable? = null
        val watchdogReason = AtomicReference<String?>(null)
        try {
            coroutineScope {
                val startedAtMs = System.currentTimeMillis()
                val lastPartialAtMs = AtomicLong(-1L)
                val watchdogJob = launch {
                    while (true) {
                        val reason = timeoutReason(
                            startedAtMs = startedAtMs,
                            lastPartialAtMs = lastPartialAtMs.get().takeIf { it >= 0L },
                            nowMs = System.currentTimeMillis(),
                            spec = spec,
                        )
                        if (reason != null) {
                            Log.w(logTag, onTimeoutLog(reason))
                            watchdogReason.set(reason)
                            this@coroutineScope.cancel(CancellationException(reason))
                            break
                        }
                        delay(spec.tickMs)
                    }
                }
                try {
                    GeminiStreamingClient.streamGenerateContent(
                        model = model,
                        apiKey = apiKey,
                        request = request
                    ).collect { chunk ->
                        when (chunk) {
                            is StreamChunk.Partial -> {
                                chunkCount += 1
                                fullText = chunk.accumulatedSoFar
                                lastPartialAtMs.set(System.currentTimeMillis())
                                onFirstToken()
                                onPartial(fullText, chunkCount)
                            }
                            is StreamChunk.Done -> {
                                fullText = chunk.fullText
                                onDone(fullText, chunkCount, chunk)
                            }
                            is StreamChunk.Error -> {
                                if (clearTextOnStreamError) fullText = ""
                                streamError = chunk.cause
                                onError(chunk, chunkCount, fullText)
                            }
                        }
                    }
                } finally {
                    watchdogJob.cancel()
                }
            }
        } catch (t: Throwable) {
            if (clearTextOnThrowable) fullText = ""
            val normalized = normalizeThrowable(t, watchdogReason.get())
            if (normalized == null) throw t
            streamError = normalized
        }
        return Result(
            fullText = fullText,
            chunkCount = chunkCount,
            error = streamError,
        )
    }
}
