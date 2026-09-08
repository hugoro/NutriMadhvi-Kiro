package com.example.data.ai.provider.runinfra

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * OpenAI-compatible streaming chat-completions DTOs for RunInfra (plan §9).
 *
 * RunInfra hosts Qwen3.8-27B behind an OpenAI-style `/v1/chat/completions`
 * endpoint — the same wire contract Groq uses. The request/response shapes are
 * therefore near-identical to the Groq streaming DTOs; they are kept as a
 * separate type family (rather than shared) so each provider can carry only the
 * knobs that provider actually honours, and so a change to one provider's
 * request shape can never silently alter the other's (plan §3.4, §25).
 *
 * The one deliberate difference from Groq: no `reasoning_effort` field.
 * `reasoning_effort=none` is a Groq-hosted-Qwen latency knob (see the
 * groq-api-key-failover memory); RunInfra's endpoint is not assumed to accept
 * it, so it is omitted rather than sent speculatively. Endpoint and model id
 * are validated live before promotion (§9 items 1–2).
 *
 * Text-only: chat streaming carries plain-text `content` (a `String`), never
 * the multimodal content-part array — RunInfra must not receive image-required
 * turns (§18; "Do not route Vision through RunInfra", §16/§17).
 */
@JsonClass(generateAdapter = true)
data class RunInfraStreamRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<RunInfraStreamMessage>,
    @Json(name = "stream") val stream: Boolean = true,
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "top_p") val topP: Double? = null,
    @Json(name = "max_tokens") val maxTokens: Int? = null,
    /** Ask for a trailing usage chunk so token accounting needs no 2nd call. */
    @Json(name = "stream_options") val streamOptions: RunInfraStreamOptions? =
        RunInfraStreamOptions(includeUsage = true),
    /** Only set for structured-output turns (§16). Null → free-form text. */
    @Json(name = "response_format") val responseFormat: RunInfraResponseFormatRef? = null,
)

@JsonClass(generateAdapter = true)
data class RunInfraStreamOptions(
    @Json(name = "include_usage") val includeUsage: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class RunInfraResponseFormatRef(
    @Json(name = "type") val type: String,
)

@JsonClass(generateAdapter = true)
data class RunInfraStreamMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String,
)

// --- streaming response chunk ---

@JsonClass(generateAdapter = true)
data class RunInfraStreamResponseChunk(
    @Json(name = "choices") val choices: List<RunInfraStreamChoice>? = null,
    @Json(name = "usage") val usage: RunInfraStreamUsage? = null,
)

@JsonClass(generateAdapter = true)
data class RunInfraStreamChoice(
    @Json(name = "delta") val delta: RunInfraStreamDelta? = null,
    @Json(name = "finish_reason") val finishReason: String? = null,
)

@JsonClass(generateAdapter = true)
data class RunInfraStreamDelta(
    @Json(name = "content") val content: String? = null,
)

@JsonClass(generateAdapter = true)
data class RunInfraStreamUsage(
    @Json(name = "prompt_tokens") val promptTokens: Int? = null,
    @Json(name = "completion_tokens") val completionTokens: Int? = null,
    @Json(name = "total_tokens") val totalTokens: Int? = null,
)
