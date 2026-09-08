package com.example.data.ai.provider

/**
 * The event vocabulary a [ChatProvider] streams (plan §7, §13).
 *
 * Intentionally isomorphic to the existing
 * [com.example.data.remote.StreamChunk] (Partial/Done/Error) so the
 * orchestrator's collector logic is adapted, not rewritten (§10, §25). The
 * only additions are normalization: [Failed] carries a typed [ChatError]
 * instead of a bare `Throwable`, and [Completed] carries the originating
 * [ProviderModelId] and normalized [ChatUsage] for the observability
 * requirements (§20).
 *
 * Sequencing contract (enforced by every adapter, exercised by Phase 5 tests):
 *   Delta* (Completed | Failed)
 * — zero or more deltas, then exactly one terminal event, then the flow
 * completes. Never two terminals.
 */
sealed interface ChatEvent {

    /** A new text fragment. [text] is the delta; [accumulated] is everything
     *  emitted so far this turn (adapters accumulate before emitting, matching
     *  StreamChunk.Partial.accumulatedSoFar). */
    data class Delta(
        val text: String,
        val accumulated: String,
    ) : ChatEvent

    /** Clean terminal success. [fullText] is the complete answer; [usage] is
     *  token accounting when the provider reports it (null otherwise — logged
     *  as "tokens=n/a", never fabricated). [providerModel] names who
     *  answered, for the fallback/observability record. */
    data class Completed(
        val fullText: String,
        val providerModel: ProviderModelId,
        val usage: ChatUsage? = null,
    ) : ChatEvent

    /** Terminal failure. The router acts on [error].kind; [error].partialText
     *  carries any prefix already streamed so §13's duplicate-prevention can
     *  reconcile a mid-stream death without persisting two answers. */
    data class Failed(
        val error: ChatError,
    ) : ChatEvent
}

/**
 * Normalized token usage (plan §20 observability). Nullable fields because
 * not every provider reports every count — an absent count is left null and
 * reported as "n/a", never guessed. Mirrors Gemini's
 * [com.example.data.remote.UsageMetadata] and OpenAI's `usage` block.
 */
data class ChatUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)
