package com.example.data.ai.provider.groq

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal, chat-scoped 429 memory for the Groq streaming provider (plan §8,
 * §11, §12).
 *
 * Purpose: after Groq returns a 429, the router should *skip* Groq and go
 * straight to RunInfra until the rate-limit window plausibly resets — not
 * keep provoking fresh 429s. This holds a per-model "blocked until" timestamp
 * set on a 429 and honoured by [isUsable].
 *
 * Why not reuse [com.example.data.meal.ensemble.GroqTokenBudget]? That one is
 * `internal` to the ensemble/vision package and is fed from response *headers*
 * by a Retrofit interceptor. The streaming chat path is a separate hand-rolled
 * client; keeping a tiny dedicated tracker avoids widening the ensemble type's
 * visibility and keeps the chat 429 policy independently testable. The two
 * observe the same account but serve different call paths.
 *
 * In-memory only (no persistence, no Room — §4/§19 forbid config migrations);
 * a cold start simply starts optimistic, which is correct — the first call
 * discovers the real state.
 */
object GroqStreamBudget {

    /** Wall-clock ms until which the model is considered rate-limited. */
    private val blockedUntilMs = ConcurrentHashMap<String, Long>()

    /** Conservative cooldown after a 429 when the provider gives no better
     *  signal. One minute matches the common per-minute token window. */
    const val DEFAULT_COOLDOWN_MS = 60_000L

    /** Record that [model] just returned a 429; block it for [cooldownMs]. */
    fun record429(model: String, nowMs: Long, cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
        if (model.isBlank()) return
        blockedUntilMs[model] = nowMs + cooldownMs.coerceAtLeast(0L)
    }

    /** True when [model] is not currently in a post-429 cooldown. Optimistic
     *  by default (unknown model ⇒ usable). */
    fun isUsable(model: String, nowMs: Long): Boolean {
        val until = blockedUntilMs[model] ?: return true
        if (nowMs >= until) {
            blockedUntilMs.remove(model)
            return true
        }
        return false
    }

    /** Milliseconds until [model] leaves cooldown; 0 when usable now. */
    fun millisUntilUsable(model: String, nowMs: Long): Long {
        val until = blockedUntilMs[model] ?: return 0L
        return (until - nowMs).coerceAtLeast(0L)
    }

    /** Test hook. */
    fun reset() = blockedUntilMs.clear()
}
