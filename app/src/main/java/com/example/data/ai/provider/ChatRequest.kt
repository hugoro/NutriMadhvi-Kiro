package com.example.data.ai.provider

/**
 * Provider-neutral chat request (plan §7).
 *
 * This is deliberately a *domain* description of a turn — a system
 * instruction, an ordered conversation, optional image attachments, and a few
 * generation hints — NOT a Gemini payload and NOT an OpenAI payload. Each
 * provider adapter maps it onto its own transport:
 *   - [GeminiChatProvider] rebuilds a `GeminiRequest` (contents +
 *     systemInstruction + generationConfig, and reuses the existing context
 *     cache reference when present).
 *   - the Groq/RunInfra adapters (§8, §9) map it onto OpenAI-style
 *     `messages: [{role, content}]`.
 *
 * Why not pass the existing `GeminiRequest` around? Because §7 requires
 * `ChatOrchestrator` to hold no provider-specific HTTP logic, and §26 states
 * "provider names are configuration, not architecture." A Gemini-shaped
 * request leaking into the router would tie the whole chain to one vendor's
 * JSON. The orchestrator keeps building its Gemini context exactly as today
 * for the Gemini hop, but the *neutral* fields below are what the router and
 * the non-Gemini adapters consume.
 *
 * ### Images (plan §4, §18)
 * [images] is a transient, in-memory representation only. The bytes are a
 * disposable copy for the current turn; no adapter learns the on-disk path,
 * and the stored user image is never touched. A request carrying images may
 * only be routed to a provider whose [ProviderCapabilities.multimodalInput]
 * is true (RunInfra, being text-only, is skipped — never failed).
 */
data class ChatRequest(
    /** The persona / domain system instruction. Preserved verbatim from the
     *  existing NutriMadhvi prompt strategy (§16) — the model changing must
     *  not rewrite the prompt. Null when a provider takes no system role. */
    val systemInstruction: String? = null,

    /** The conversation so far, oldest-first, already trimmed to the rolling
     *  window by [ChatContextBuilder]. The final entry is the current user
     *  turn. */
    val messages: List<ChatMessagePart>,

    /** Transient image attachments for THIS turn only (§4/§18). Empty for the
     *  overwhelmingly common text turn. */
    val images: List<ChatImage> = emptyList(),

    /** Generation hints that survive provider translation. Each adapter maps
     *  what its transport supports and ignores the rest. */
    val generation: ChatGeneration = ChatGeneration(),

    /**
     * Opaque, provider-specific escape hatch used ONLY by the Gemini adapter
     * to carry forward capabilities that have no neutral equivalent and must
     * be preserved (§10: "Preserve … caching … existing fallback
     * mechanisms"): currently the `cachedContents/{id}` context-cache
     * reference and the thinking-level string. Non-Gemini adapters ignore it.
     * This is intentionally a narrow, typed bag rather than a free `Map` so
     * it cannot become a dumping ground that re-couples the orchestrator to a
     * vendor.
     */
    val geminiHints: GeminiPassThrough? = null,
) {
    /** True when this turn carries image data — the routing gate for §18. */
    val hasImages: Boolean get() = images.isNotEmpty()
}

/** One conversation turn. [role] is the neutral role; adapters map it to
 *  their transport ("model" for Gemini, "assistant" for OpenAI-style). */
data class ChatMessagePart(
    val role: ChatRole,
    val text: String,
)

enum class ChatRole { SYSTEM, USER, ASSISTANT }

/**
 * A transient image for the current turn. Held as raw [bytes] + [mimeType];
 * adapters base64-encode into their own payload on the fly. Never a path,
 * never a `content://` URI, never the stored file — see §4 image rules and
 * the persistent-food-images pattern (copy to filesDir, but here we only ever
 * hand a disposable in-memory copy to the provider).
 */
data class ChatImage(
    val bytes: ByteArray,
    val mimeType: String,
) {
    // Value-based equality on a ByteArray needs an explicit contract; the
    // default identity equals would make two logically-equal requests compare
    // unequal, which breaks test assertions and dedupe.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatImage) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

/**
 * Provider-neutral generation hints. All optional; each adapter applies what
 * its transport supports (e.g. Gemini maps [maxOutputTokens] to
 * `generationConfig.maxOutputTokens`; Groq to `max_tokens`).
 *
 * [structured] requests machine-readable output (§16) and is used by the
 * bounded agentic layer (§15) for tool calls / proposals. The router must
 * only pick a provider whose [ProviderCapabilities.structuredOutput] is true
 * when this is set.
 */
data class ChatGeneration(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxOutputTokens: Int? = null,
    val structured: Boolean = false,
)

/**
 * Gemini-only pass-through (see [ChatRequest.geminiHints]). Carries the two
 * Gemini capabilities that have no cross-provider meaning but must survive
 * the abstraction (§10). Kept in the neutral package because only the Gemini
 * adapter reads it, and keeping it typed here documents exactly which
 * vendor-specific knobs are still honoured.
 */
data class GeminiPassThrough(
    /** `cachedContents/{id}` reference for the stable-prefix context cache. */
    val cachedContent: String? = null,
    /** Thinking level ("none"/"low"/"medium"/"high") — chat caps this to
     *  bound pre-stream latency. */
    val thinkingLevel: String? = null,
)
