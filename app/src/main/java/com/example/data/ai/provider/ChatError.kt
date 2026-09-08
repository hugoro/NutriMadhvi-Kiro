package com.example.data.ai.provider

/**
 * Normalized failure taxonomy shared by every chat provider (plan §11, §12).
 *
 * Each transport speaks its own dialect of failure — Gemini surfaces an HTTP
 * code and a `finishReason`, Groq an OpenAI-style error body with a 429, a
 * dropped socket is an `IOException`. The router must not care which
 * transport produced a failure; it decides "retry here, fail over, or give
 * up" purely from the [ChatErrorKind]. So every provider adapter maps its
 * native failure onto exactly one of these kinds.
 *
 * The kinds mirror the distinctions the plan explicitly requires the breaker
 * and router to make (§12): 429 vs timeout vs 5xx vs network vs
 * auth/config vs invalid-request vs invalid-response vs unsupported-capability.
 */
enum class ChatErrorKind {
    /** HTTP 429 / provider-signalled rate limit. Plan §12: a Groq 429 must
     *  cause *fast* failover to RunInfra, not repeated Groq retries. */
    RATE_LIMITED,

    /** The call exceeded a time budget (connect/read timeout, watchdog, or
     *  the orchestrator's outer withTimeout). Transient → fail over. */
    TIMEOUT,

    /** HTTP 5xx / provider-side server error. Transient → fail over. */
    SERVER_ERROR,

    /** Connectivity failure before/around the call (DNS, connection refused,
     *  socket reset, offline). Transient → fail over. */
    NETWORK,

    /** Missing/invalid credentials or misconfiguration (401/403, placeholder
     *  key, unknown model id). NOT transient — retrying the same provider is
     *  pointless; skip it and move on. Distinct from "not configured", which
     *  is handled earlier by [ChatProvider.isAvailable] so such a provider is
     *  never invoked at all. */
    AUTH_OR_CONFIG,

    /** HTTP 4xx (other than 429) — the request itself is malformed/rejected.
     *  Plan §22: "permanent 4xx not retried indefinitely." Failing over may
     *  help if the next provider is more lenient, but the same provider must
     *  not be hammered. */
    INVALID_REQUEST,

    /** The transport returned 2xx but the payload was empty, unparseable, or
     *  truncated (Gemini's non-STOP finishReason, Bitdeer's empty SSE). The
     *  answer is unusable → fail over. */
    INVALID_RESPONSE,

    /** The provider cannot serve this request's requirements (e.g. an
     *  image-required turn sent to a text-only provider). Should be prevented
     *  by capability routing (§18); surfaced here as a defensive backstop. */
    UNSUPPORTED_CAPABILITY,

    /** Anything not classified above. Treated as transient by the router
     *  (fail over) but flagged in diagnostics so unknowns get a real kind. */
    UNKNOWN,
    ;

    /**
     * Whether a *different, healthy* provider should be tried after this
     * failure. Every kind here is worth failing over on — the point of the
     * chain is resilience. What must NOT happen is retrying the *same*
     * unhealthy provider; that is the circuit breaker's job (§11: "A healthy
     * alternate provider is preferable to repeated retries against an
     * unhealthy provider").
     *
     * AUTH_OR_CONFIG and INVALID_REQUEST still return true because the *next*
     * provider is independent and may succeed — but the breaker records the
     * failure against the *originating* provider:model so it is not retried.
     */
    fun shouldFailOver(): Boolean = true
}

/**
 * A normalized provider failure: the [kind] the router acts on, a
 * human-readable [message] for logs (never containing secrets — §20), the
 * originating [providerModel] for breaker accounting, and the optional
 * underlying [cause].
 *
 * [partialText] carries any assistant text already streamed before the
 * failure. It exists so the streaming-failover safety layer (§13) can decide
 * how to reconcile a mid-stream death — the hard rule being that a failure
 * after partial output must never append a *second* full answer. The provider
 * only reports the prefix; the orchestrator owns the "transient buffer,
 * replace on fallback" logic.
 */
data class ChatError(
    val kind: ChatErrorKind,
    val message: String,
    val providerModel: ProviderModelId,
    val cause: Throwable? = null,
    val partialText: String? = null,
) {
    /** True when any assistant text was emitted before this failure — the
     *  signal §13's duplicate-prevention logic keys off. */
    val hadPartialOutput: Boolean get() = !partialText.isNullOrEmpty()
}
