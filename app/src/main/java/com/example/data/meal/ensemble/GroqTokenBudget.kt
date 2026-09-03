package com.example.data.meal.ensemble

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks Groq's per-minute token allowance so a request that cannot possibly
 * be served is never sent.
 *
 * Groq returns the remaining budget on every response — including error
 * responses — via `x-ratelimit-remaining-tokens` and `x-ratelimit-reset-tokens`.
 * Recording those lets the caller answer "is this model exhausted right now?"
 * locally, instead of discovering it by burning a round trip and a user-visible
 * few seconds on an HTTP 429.
 *
 * Two properties matter for correctness:
 *
 *  - **Per model.** The allowance is scoped to a model, so exhausting
 *    `qwen3.6` says nothing about `qwen3.8`. Keying by model id is what makes
 *    "pick a model that still has budget" possible.
 *  - **Fail open.** With no observation yet, [canAfford] returns true. A budget
 *    tracker that blocks on missing information would silently disable the
 *    provider on first use, which is a worse failure than one wasted 429.
 */
internal object GroqTokenBudget {

    private data class Snapshot(
        val remainingTokens: Int,
        /** Wall-clock ms at which the allowance is known to have refilled. */
        val resetAtMs: Long,
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /**
     * Records what the API just told us about [model]'s remaining allowance.
     *
     * [resetAfterMs] is the server's own countdown to a full window, so it is
     * anchored to the local clock at the moment of observation.
     */
    fun record(model: String, remainingTokens: Int, resetAfterMs: Long, nowMs: Long) {
        if (model.isBlank() || remainingTokens < 0) return
        snapshots[model] = Snapshot(
            remainingTokens = remainingTokens,
            resetAtMs = nowMs + resetAfterMs.coerceAtLeast(0L),
        )
    }

    /**
     * True when [model] is believed to have room for [estimatedTokens].
     *
     * Returns true when nothing is known, and true again once the recorded
     * window has elapsed — at that point the allowance has refilled and the
     * stale reading must not keep blocking traffic.
     */
    fun canAfford(model: String, estimatedTokens: Int, nowMs: Long): Boolean {
        val snapshot = snapshots[model] ?: return true
        if (nowMs >= snapshot.resetAtMs) return true
        return snapshot.remainingTokens >= estimatedTokens
    }

    /** Milliseconds until [model]'s allowance refills; 0 when it is usable now. */
    fun millisUntilReset(model: String, nowMs: Long): Long {
        val snapshot = snapshots[model] ?: return 0L
        return (snapshot.resetAtMs - nowMs).coerceAtLeast(0L)
    }

    /** Remaining tokens last reported for [model], or null if never observed. */
    fun remainingFor(model: String, nowMs: Long): Int? {
        val snapshot = snapshots[model] ?: return null
        return if (nowMs >= snapshot.resetAtMs) null else snapshot.remainingTokens
    }

    /** Test hook; production code never needs to forget an observation. */
    fun reset() = snapshots.clear()

    /**
     * Parses Groq's duration strings (`"1m26.4s"`, `"134ms"`, `"7.66s"`) into
     * milliseconds. Unknown shapes yield null so the caller can fall back to a
     * conservative default rather than trusting a misparse.
     */
    fun parseResetDuration(raw: String?): Long? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return null
        val match = DURATION_PART.findAll(value).toList()
        if (match.isEmpty()) return null
        var total = 0.0
        for (part in match) {
            val amount = part.groupValues[1].toDoubleOrNull() ?: return null
            total += when (part.groupValues[2]) {
                "ms" -> amount
                "s" -> amount * 1_000
                "m" -> amount * 60_000
                "h" -> amount * 3_600_000
                else -> return null
            }
        }
        return total.toLong()
    }

    private val DURATION_PART = Regex("([0-9]*\\.?[0-9]+)(ms|s|m|h)")
}
