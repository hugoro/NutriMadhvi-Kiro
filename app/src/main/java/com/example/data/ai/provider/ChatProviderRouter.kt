package com.example.data.ai.provider

import com.example.data.chat.ModelCircuitBreaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Chat failover router — `Groq → RunInfra → Gemini` (plan §11, Phase 4).
 *
 * A **failover chain, not an ensemble**: one user turn is served by exactly
 * one provider. The router walks an ordered candidate list and, for each,
 * skips it when any of these hold, moving to the next candidate:
 *   1. its circuit breaker is open (§12 — recent repeated failures),
 *   2. it reports `!isAvailable()` (missing key, or Groq in a 429 cooldown so
 *      the router jumps straight past it — §11/§12),
 *   3. it cannot serve the request — e.g. an image-required turn offered to a
 *      text-only provider like RunInfra (§18 `canServe`).
 *
 * The first surviving candidate streams. Its outcome drives the breaker:
 *   - a terminal [ChatEvent.Completed] → `recordSuccess`, and the router stops;
 *   - a terminal [ChatEvent.Failed] → `recordFailure`, and the router advances
 *     to the next candidate (every [ChatErrorKind] is fail-over-able; the kind
 *     is preserved for logging/metrics, not to gate the decision).
 *
 * ### Phase 4 scope: pre-token failover only
 * This router performs **pre-first-token** failover: if a provider fails
 * before emitting any [ChatEvent.Delta], the next candidate is tried and the
 * consumer never saw output. If a provider fails *after* partial output, the
 * §13 hard requirement — "one user turn must never produce two persisted
 * assistant answers" — governs, and safe mid-stream restart is Phase 5. To
 * keep that invariant impossible to violate here, once this router has
 * forwarded a [ChatEvent.Delta] from a provider it will NOT silently start a
 * second provider: a subsequent [ChatEvent.Failed] is forwarded as-is (carrying
 * any partial text) so the caller decides. Phase 5 replaces that terminal
 * forward with a transient-buffer restart. Nothing here persists anything.
 *
 * Cancellation propagates: a [CancellationException] from the collected
 * provider flow is rethrown, tearing down the provider call (§8, §13).
 *
 * Pure/testable: providers and breaker are injected; no Android, no clock.
 */
class ChatProviderRouter(
    private val chain: List<ChatProvider>,
    private val breaker: ModelCircuitBreaker,
    private val onRouteEvent: (RouteTrace) -> Unit = {},
) {

    /** Emitted to [onRouteEvent] for observability (never carries keys or image
     *  bytes). Lets Phase 10 log which providers were skipped/tried/failed. */
    sealed interface RouteTrace {
        data class Skipped(val providerModel: String, val reason: SkipReason) : RouteTrace
        data class Attempting(val providerModel: String) : RouteTrace
        data class Failed(val providerModel: String, val kind: ChatErrorKind, val afterPartial: Boolean) : RouteTrace
        data class Succeeded(val providerModel: String) : RouteTrace
        /** No candidate could serve the turn. */
        data object Exhausted : RouteTrace
    }

    enum class SkipReason { CIRCUIT_OPEN, UNAVAILABLE, CANNOT_SERVE }

    /**
     * Stream a reply for [request], failing over across [chain] as needed.
     * Emits neutral [ChatEvent]s so the orchestrator consumes the router
     * exactly as it would a single provider. Terminates with exactly one of:
     *   - [ChatEvent.Completed] (some provider succeeded), or
     *   - [ChatEvent.Failed] (all candidates skipped/failed, or a provider
     *     failed after already streaming partial output — see class docs).
     */
    fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        var lastFailure: ChatError? = null

        for (provider in chain) {
            val idValue = provider.id.value

            val skip = skipReason(provider, request)
            if (skip != null) {
                onRouteEvent(RouteTrace.Skipped(idValue, skip))
                continue
            }

            onRouteEvent(RouteTrace.Attempting(idValue))

            var sawDelta = false
            var terminal: ChatEvent? = null

            try {
                provider.stream(request).collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> {
                            sawDelta = true
                            emit(event)
                        }
                        is ChatEvent.Completed -> {
                            terminal = event
                            emit(event)
                        }
                        is ChatEvent.Failed -> {
                            terminal = event
                            // Do not emit yet: a pre-token failure should fail
                            // over silently. Only surface it if we can't recover.
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // A provider that throws instead of emitting Failed is treated
                // as a normalized UNKNOWN failure so the chain still advances.
                terminal = ChatEvent.Failed(
                    ChatError(
                        kind = ChatErrorKind.UNKNOWN,
                        message = t.message?.take(300) ?: t.javaClass.simpleName,
                        providerModel = provider.id,
                        cause = t,
                        partialText = null,
                    )
                )
            }

            when (val t = terminal) {
                is ChatEvent.Completed -> {
                    breaker.recordSuccess(idValue)
                    onRouteEvent(RouteTrace.Succeeded(idValue))
                    return@flow
                }
                is ChatEvent.Failed -> {
                    breaker.recordFailure(idValue)
                    lastFailure = t.error
                    onRouteEvent(RouteTrace.Failed(idValue, t.error.kind, afterPartial = sawDelta))
                    if (sawDelta) {
                        // Post-partial failure: §13 forbids starting a second
                        // provider here in Phase 4 (would risk a duplicate
                        // answer). Surface the failure with its partial text.
                        emit(t)
                        return@flow
                    }
                    // Pre-token failure → try the next candidate.
                }
                else -> {
                    // Provider completed the flow without any terminal event.
                    // Treat as an invalid (empty) response and advance.
                    val err = ChatError(
                        kind = ChatErrorKind.INVALID_RESPONSE,
                        message = "Provider closed stream without a terminal event",
                        providerModel = provider.id,
                    )
                    breaker.recordFailure(idValue)
                    lastFailure = err
                    onRouteEvent(RouteTrace.Failed(idValue, err.kind, afterPartial = sawDelta))
                    if (sawDelta) {
                        emit(ChatEvent.Failed(err))
                        return@flow
                    }
                }
            }
        }

        // Chain exhausted with no success.
        onRouteEvent(RouteTrace.Exhausted)
        emit(
            ChatEvent.Failed(
                lastFailure ?: ChatError(
                    // Nothing even ran: every candidate was skipped (no key /
                    // circuit open / could-not-serve). That is a
                    // configuration/availability condition, not a transport
                    // failure — closest normalized kind is AUTH_OR_CONFIG.
                    kind = ChatErrorKind.AUTH_OR_CONFIG,
                    message = "No provider could serve this request "
                        + "(all skipped: unavailable, circuit-open, or cannot-serve)",
                    providerModel = chain.lastOrNull()?.id ?: ProviderModelId.of("router", "none"),
                )
            )
        )
    }

    private fun skipReason(provider: ChatProvider, request: ChatRequest): SkipReason? = when {
        breaker.isOpen(provider.id.value) -> SkipReason.CIRCUIT_OPEN
        !provider.isAvailable() -> SkipReason.UNAVAILABLE
        !provider.capabilities.canServe(request) -> SkipReason.CANNOT_SERVE
        else -> null
    }
}
