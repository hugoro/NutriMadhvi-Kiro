package com.example.data.ai.provider

/**
 * What a provider/model can do, so the capability-aware router (plan §11,
 * §26) can skip a provider for a request it cannot serve rather than send it
 * and fail.
 *
 * The decisive one for correctness is [multimodalInput]: plan §17/§18 require
 * that an image-required chat turn is NEVER routed to a text-only provider
 * (RunInfra). The router asks each candidate `canServe(request)` and text-only
 * RunInfra answers false for an image turn — so it is skipped in the chain,
 * not attempted and failed.
 */
data class ProviderCapabilities(
    /** Can accept plain-text turns. Every chat provider is true here. */
    val textInput: Boolean = true,
    /** Can accept image parts in a chat turn. False for text-only providers
     *  such as RunInfra (§18). */
    val multimodalInput: Boolean = false,
    /** Emits incremental deltas via [ChatProvider.stream]. Providers that can
     *  only return a whole answer still expose it through the same flow (one
     *  Delta + Completed), so this flag is informational for diagnostics and
     *  benchmark reporting rather than a routing gate. */
    val streaming: Boolean = true,
    /** Can be asked for machine-readable structured output (tool calls,
     *  proposals, charts — §16). Used by the bounded agentic layer (§15). */
    val structuredOutput: Boolean = false,
) {
    /** True when this provider can serve [request]'s hard requirements.
     *  Currently only image capability is a hard gate — a text turn is
     *  serveable by everyone. */
    fun canServe(request: ChatRequest): Boolean {
        if (request.hasImages && !multimodalInput) return false
        return true
    }
}
