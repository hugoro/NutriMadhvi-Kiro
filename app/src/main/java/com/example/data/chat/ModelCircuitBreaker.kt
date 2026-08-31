package com.example.data.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-07-06 (Frente F) — per-model in-memory circuit breaker.
 *
 * When Gemini's Flash goes 503 twice in a row, the fleet loop in
 * [ChatOrchestrator.streamReply] wastes another attempt on the same
 * doomed model before falling through to Flash-Lite. Multiply that
 * across a burst of user messages during an outage and it turns
 * into 8-15 s of pointless retrying on every send.
 *
 * This breaker tracks consecutive failures per model NAME (the same
 * `modelName` strings the orchestrator iterates over). After
 * [FAILURE_THRESHOLD] consecutive failures the model is marked "open"
 * for [OPEN_DURATION_MS]; while open, `isOpen(name)` returns `true`
 * and the orchestrator's fleet loop `continue`s past it. After the
 * cooldown elapses the model auto half-opens — the next call is
 * allowed through and its outcome resets the breaker.
 *
 * In-memory only: reset on VM restart. That's fine — a full process
 * kill is the ultimate cooldown. Persistence would introduce more
 * failure modes than it solves for a 60-second window.
 *
 * Thread-safe via [ConcurrentHashMap] because the orchestrator's
 * background scope and its main flow can both hit the breaker.
 */
class ModelCircuitBreaker(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private data class State(
        val consecutiveFailures: Int = 0,
        val openedAtMs: Long? = null,
    )

    private val states = ConcurrentHashMap<String, State>()

    /** True when [model] is in the "open" state and should be skipped
     *  by the fleet loop. Also auto-transitions to half-open when the
     *  cooldown elapses so the next call is allowed through. */
    fun isOpen(model: String): Boolean {
        val now = nowMs()
        var shouldSkip = false
        var cooledDownAfterMs: Long? = null
        states.computeIfPresent(model) { _, state ->
            val openedAt = state.openedAtMs
            if (openedAt == null) {
                shouldSkip = false
                state
            } else {
                val elapsed = now - openedAt
                if (elapsed >= OPEN_DURATION_MS) {
                    cooledDownAfterMs = elapsed
                    shouldSkip = false
                    state.copy(openedAtMs = null)
                } else {
                    shouldSkip = true
                    state
                }
            }
        }
        cooledDownAfterMs?.let { elapsed ->
            android.util.Log.d(
                "CHAT_BREAKER",
                "$model auto half-opened after ${elapsed}ms cooldown"
            )
        }
        return shouldSkip
    }

    /** Register a failed stream attempt for [model]. Opens the breaker
     *  when consecutive failures reach [FAILURE_THRESHOLD]. */
    fun recordFailure(model: String) {
        var openedFailures: Int? = null
        states.compute(model) { _, state ->
            val previous = state ?: State()
            val nextFailures = previous.consecutiveFailures + 1
            if (nextFailures >= FAILURE_THRESHOLD && previous.openedAtMs == null) {
                openedFailures = nextFailures
                previous.copy(
                    consecutiveFailures = nextFailures,
                    openedAtMs = nowMs()
                )
            } else {
                previous.copy(consecutiveFailures = nextFailures)
            }
        }
        openedFailures?.let { failures ->
            android.util.Log.w(
                "CHAT_BREAKER",
                "opened model=$model after ${failures} consecutive failures " +
                    "(cooldown=${OPEN_DURATION_MS}ms)"
            )
        }
    }

    /** Register a successful stream attempt for [model]. Clears both
     *  failure count and open state — a single good call fully closes
     *  the breaker. */
    fun recordSuccess(model: String) {
        var wasOpen = false
        val updated = states.computeIfPresent(model) { _, state ->
            wasOpen = state.openedAtMs != null || state.consecutiveFailures > 0
            state.copy(consecutiveFailures = 0, openedAtMs = null)
        }
        if (updated != null && wasOpen) {
            android.util.Log.d("CHAT_BREAKER", "closed model=$model (success recorded)")
        }
    }

    /** For tests. Not called from production code. */
    internal fun reset() {
        states.clear()
    }

    companion object {
        internal const val FAILURE_THRESHOLD: Int = 2
        internal const val OPEN_DURATION_MS: Long = 60_000L
    }
}
