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
 * ### Streaming failover safety (Phase 5, plan §13)
 * The router serves one turn from one provider, but two failure timings differ:
 *   - **Pre-first-token failure** (no [ChatEvent.Delta] emitted yet): the next
 *     candidate is tried transparently; the consumer never saw output.
 *   - **Mid-stream failure** (failure *after* partial deltas): §13 forbids
 *     appending a second full answer. The router emits a [ChatEvent.Reset]
 *     naming the dying and taking-over providers, then streams the fallback.
 *     [Reset] instructs the consumer to **discard the transient buffer** and
 *     re-render from the deltas that follow — the abandoned partial is never
 *     persisted, so "one turn = one persisted answer" holds. If no healthy
 *     fallback remains after a mid-stream death, the router surfaces a
 *     controlled [ChatEvent.Failed] carrying the partial text (§13 "otherwise
 *     show a controlled failure") — it does NOT invent an answer.
 *
 * Because output stays transient until [ChatEvent.Completed] and a [Reset]
 * wipes it, the duplicate-answer invariant is structurally impossible to
 * violate here. Nothing in the router persists anything.
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
     *   - [ChatEvent.Failed] (all candidates skipped/failed, or a mid-stream
     *     death with no healthy fallback — see class docs).
     * May emit [ChatEvent.Reset] at a mid-stream failover boundary (§13).
     */
    fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        var lastFailure: ChatError? = null

        for (index in chain.indices) {
            val provider = chain[index]
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
                        // Providers never emit Reset (only the router does); if
                        // one somehow did, forward it — the consumer handles it.
                        is ChatEvent.Reset -> emit(event)
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

            // Reduce a "no terminal event" close to an INVALID_RESPONSE failure
            // so both the failed and the silent-close paths share one handler.
            val failure: ChatError = when (val t = terminal) {
                is ChatEvent.Completed -> {
                    breaker.recordSuccess(idValue)
                    onRouteEvent(RouteTrace.Succeeded(idValue))
                    return@flow
                }
                is ChatEvent.Failed -> t.error
                else -> ChatError(
                    kind = ChatErrorKind.INVALID_RESPONSE,
                    message = "Provider closed stream without a terminal event",
                    providerModel = provider.id,
                )
            }

            breaker.recordFailure(idValue)
            lastFailure = failure
            onRouteEvent(RouteTrace.Failed(idValue, failure.kind, afterPartial = sawDelta))

            if (sawDelta) {
                // Mid-stream death (§13). We may only continue to a fallback by
                // telling the consumer to discard the abandoned partial first.
                val next = nextServable(startIndex = index + 1, request = request)
                if (next != null) {
                    emit(ChatEvent.Reset(fromProviderModel = provider.id, toProviderModel = next.id))
                    // Fall through: the loop advances and will reach `next`,
                    // tracing the intervening unservable candidates as Skipped.
                    continue
                }
                // No healthy fallback → controlled failure carrying the partial.
                emit(ChatEvent.Failed(failure))
                return@flow
            }
            // Pre-token failure → try the next candidate transparently.
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

    /**
     * First candidate at or after [startIndex] that would not be skipped for
     * [request] — the look-ahead a mid-stream failover uses to decide whether a
     * [ChatEvent.Reset] is warranted (there is somewhere to go) versus a
     * controlled failure (nowhere healthy left). Read-only: mutates nothing.
     */
    private fun nextServable(startIndex: Int, request: ChatRequest): ChatProvider? {
        for (i in startIndex until chain.size) {
            if (skipReason(chain[i], request) == null) return chain[i]
        }
        return null
    }

    private fun skipReason(provider: ChatProvider, request: ChatRequest): SkipReason? = when {
        breaker.isOpen(provider.id.value) -> SkipReason.CIRCUIT_OPEN
        !provider.isAvailable() -> SkipReason.UNAVAILABLE
        !provider.capabilities.canServe(request) -> SkipReason.CANNOT_SERVE
        else -> null
    }
}
