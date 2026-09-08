package com.example.data.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 — pure contract tests for the neutral identity + capability gate. */
class ProviderContractTest {

    @Test
    fun modelId_parses_and_splits() {
        val id = ProviderModelId.parse("groq:qwen3.8-27b")
        assertEquals("groq", id.provider)
        assertEquals("qwen3.8-27b", id.model)
        assertEquals("groq:qwen3.8-27b", id.value)
    }

    @Test
    fun modelId_of_roundtrips_with_provider_and_model() {
        val id = ProviderModelId.of("runinfra", "qwen3.8-27b")
        assertEquals("runinfra:qwen3.8-27b", id.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelId_rejects_provider_with_colon() {
        ProviderModelId.of("gro:q", "m")
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelId_parse_rejects_missing_model() {
        ProviderModelId.parse("groq:")
    }

    @Test
    fun text_only_provider_cannot_serve_image_request() {
        val textOnly = ProviderCapabilities(multimodalInput = false)
        val imageReq = ChatRequest(
            messages = listOf(ChatMessagePart(ChatRole.USER, "?")),
            images = listOf(ChatImage(ByteArray(1), "image/jpeg")),
        )
        // §18: RunInfra (text-only) must never receive an image-required turn.
        assertFalse(textOnly.canServe(imageReq))
    }

    @Test
    fun multimodal_provider_serves_image_request() {
        val multi = ProviderCapabilities(multimodalInput = true)
        val imageReq = ChatRequest(
            messages = listOf(ChatMessagePart(ChatRole.USER, "?")),
            images = listOf(ChatImage(ByteArray(1), "image/jpeg")),
        )
        assertTrue(multi.canServe(imageReq))
    }

    @Test
    fun any_provider_serves_text_request() {
        val textOnly = ProviderCapabilities(multimodalInput = false)
        val textReq = ChatRequest(messages = listOf(ChatMessagePart(ChatRole.USER, "olá")))
        assertTrue(textOnly.canServe(textReq))
    }

    @Test
    fun every_error_kind_fails_over() {
        // The chain's resilience contract: no kind should trap the turn on a
        // dead provider. Same-provider retry suppression is the breaker's job.
        ChatErrorKind.values().forEach { assertTrue(it.name, it.shouldFailOver()) }
    }
}
