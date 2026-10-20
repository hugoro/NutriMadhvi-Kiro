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
 *
 * The [ChatProviderRouter] may additionally emit [Reset] between a
 * mid-stream failover boundary (§13): the sequence it produces is
 *   (Delta* Reset)* Delta* (Completed | Failed)
 * i.e. zero or more "attempts", each a run of deltas that a [Reset] discards,
 * followed by one final run that ends in a single terminal. Individual
 * providers never emit [Reset]; only the router does.
 */
sealed interface ChatEvent {

    /** A new text fragment. [text] is the delta; [accumulated] is everything
     *  emitted so far this turn (adapters accumulate before emitting, matching
     *  StreamChunk.Partial.accumulatedSoFar). */
    data class Delta(
        val text: String,
        val accumulated: String,
    ) : ChatEvent

    /**
     * 2026-10-06 (Frente Thinking) — extended-thinking fragment: the model's
     *  reasoning, streamed (usually) before the answer text. [text] is the
     *  fragment; [accumulated] is the full thinking text so far this turn.
     *
     *  UI feedback ONLY: never persisted, never part of the answer, and a
     *  well-behaved provider keeps it OUT of [Delta]/[Completed] (Gemini's
     *  `thought: true` parts and Qwen's `delta.reasoning` field map here,
     *  not to [Delta]). The router forwards these transparently (they
     *  neither commit output nor trigger [Reset] logic), and the consumer
     *  may drop them when reasoning display is not wanted.
     *
     *  Sequencing: Thinking fragments may appear before the first [Delta]
     *  (the common case) and in principle interleaved — they are pure
     *  side-channel feedback and carry no §13 commit semantics.
     */
    data class Thinking(
        val text: String,
        val accumulated: String,
    ) : ChatEvent

    /**
     * A mid-stream failover boundary (plan §13 "replace the transient buffer").
     * Emitted ONLY by [ChatProviderRouter] when a provider dies *after* it had
     * already streamed partial output and a healthy fallback exists. The
     * consumer MUST discard everything buffered for this turn so far and
     * re-render from the [Delta]s that follow — the previous provider's partial
     * text is abandoned, never appended to the fallback's answer. This is what
     * guarantees "one user turn never produces two persisted assistant answers":
     * output stays transient until [Completed], and a [Reset] wipes the transient
     * buffer rather than committing it.
     *
     * [fromProviderModel] is who died; [toProviderModel] is who is taking over —
     * for the observability/fallback record (never carries keys or image bytes).
     */
    data class Reset(
        val fromProviderModel: ProviderModelId,
        val toProviderModel: ProviderModelId,
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
