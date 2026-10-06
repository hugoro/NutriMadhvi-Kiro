package com.example.data.ai.provider.groq

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * OpenAI-compatible streaming chat-completions DTOs for Groq (plan §8).
 *
 * Distinct from the ensemble/vision [com.example.data.meal.ensemble] Groq
 * DTOs on purpose: those force `response_format=json_object` and
 * `reasoning_effort=none` for one-shot vision JSON, whereas conversational
 * chat streams free-form Markdown text. Keeping the two request shapes
 * separate avoids one accidentally inheriting the other's constraints.
 *
 * Text-only: chat streaming to Qwen3.8-27B carries plain text content
 * (a `String`, not the multimodal content-part array the vision path uses).
 * Chat image attachments, if ever supported, would route to a multimodal
 * provider — Qwen3.8-27B on Groq is the text chat model here (§18).
 */
@JsonClass(generateAdapter = true)
data class GroqStreamRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<GroqStreamMessage>,
    @Json(name = "stream") val stream: Boolean = true,
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "top_p") val topP: Double? = null,
    @Json(name = "max_tokens") val maxTokens: Int? = null,
    /** 2026-10-06 (Frente Thinking) — `low` instead of `none`: Qwen3.8-27B
     *  on Groq streams its reasoning in a SEPARATE `delta.reasoning` field
     *  (verified live 2026-10-06), so the thinking is rendered in the live
     *  reasoning card while `delta.content` stays the clean answer. The old
     *  `none` default killed the reasoning pass for low latency; with a
     *  separate stream channel the latency cost no longer lands in the
     *  answer text the user reads. Structured-output turns (response_format
     *  set) still force `none` below. */
    @Json(name = "reasoning_effort") val reasoningEffort: String? = "low",
    /** Ask Groq to emit a final usage chunk in the stream so token accounting
     *  is available without a second call (§20). */
    @Json(name = "stream_options") val streamOptions: GroqStreamOptions? =
        GroqStreamOptions(includeUsage = true),
    /** Only set for structured-output turns (§16). Null → free-form text. */
    @Json(name = "response_format") val responseFormat: GroqResponseFormatRef? = null,
)

@JsonClass(generateAdapter = true)
data class GroqStreamOptions(
    @Json(name = "include_usage") val includeUsage: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class GroqResponseFormatRef(
    @Json(name = "type") val type: String,
)

@JsonClass(generateAdapter = true)
data class GroqStreamMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String,
)

// --- streaming response chunk ---

@JsonClass(generateAdapter = true)
data class GroqStreamResponseChunk(
    @Json(name = "choices") val choices: List<GroqStreamChoice>? = null,
    @Json(name = "usage") val usage: GroqStreamUsage? = null,
)

@JsonClass(generateAdapter = true)
data class GroqStreamChoice(
    @Json(name = "delta") val delta: GroqStreamDelta? = null,
    @Json(name = "finish_reason") val finishReason: String? = null,
)

@JsonClass(generateAdapter = true)
data class GroqStreamDelta(
    @Json(name = "content") val content: String? = null,
    /** 2026-10-06 (Frente Thinking) — Qwen's extended reasoning, streamed
     *  in its own field when `reasoning_effort` != "none". Kept separate
     *  from [content]: reasoning is live UI feedback, never answer text. */
    @Json(name = "reasoning") val reasoning: String? = null,
)

@JsonClass(generateAdapter = true)
data class GroqStreamUsage(
    @Json(name = "prompt_tokens") val promptTokens: Int? = null,
    @Json(name = "completion_tokens") val completionTokens: Int? = null,
    @Json(name = "total_tokens") val totalTokens: Int? = null,
)
