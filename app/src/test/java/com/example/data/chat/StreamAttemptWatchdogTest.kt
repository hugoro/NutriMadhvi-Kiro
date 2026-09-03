package com.example.data.chat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAttemptWatchdogTest {

    @Test
    fun `ttft timeout fires before first partial`() {
        val started = 1_000L
        val watchdog = ChatOrchestrator.StreamAttemptWatchdog(startedAtMs = started)

        assertNull(
            watchdog.timeoutReason(
                nowMs = started + ChatOrchestrator.TTFT_TIMEOUT_MS - 1L
            )
        )
        assertEquals(
            ChatOrchestrator.StreamAttemptWatchdog.REASON_TTFT,
            watchdog.timeoutReason(
                nowMs = started + ChatOrchestrator.TTFT_TIMEOUT_MS
            )
        )
    }

    @Test
    fun `idle timeout fires only after first partial`() {
        val started = 5_000L
        val firstPartial = 7_000L
        val watchdog = ChatOrchestrator.StreamAttemptWatchdog(
            startedAtMs = started,
            lastPartialAtMs = firstPartial,
        )

        assertNull(
            watchdog.timeoutReason(
                nowMs = firstPartial + ChatOrchestrator.STREAM_IDLE_TIMEOUT_MS - 1L
            )
        )
        assertEquals(
            ChatOrchestrator.StreamAttemptWatchdog.REASON_IDLE,
            watchdog.timeoutReason(
                nowMs = firstPartial + ChatOrchestrator.STREAM_IDLE_TIMEOUT_MS
            )
        )
    }

    @Test
    fun `idle timeout suppresses ttft once first partial exists`() {
        val started = 100L
        val firstPartial = started + 50L
        val watchdog = ChatOrchestrator.StreamAttemptWatchdog(
            startedAtMs = started,
            lastPartialAtMs = firstPartial,
        )

        assertNull(
            watchdog.timeoutReason(
                nowMs = started + ChatOrchestrator.TTFT_TIMEOUT_MS + 1L,
                idleTimeoutMs = ChatOrchestrator.STREAM_IDLE_TIMEOUT_MS * 2,
            )
        )
    }

    @Test
    fun `real cancellation is not treated as watchdog timeout`() {
        val cancel = CancellationException("user switched session")

        val normalized = ChatOrchestrator.StreamAttemptWatchdog.normalizeWatchdogThrowable(cancel, watchdogReason = null)

        assertNull(normalized)
    }

    @Test
    fun `watchdog cancellation keeps explicit reason`() {
        val cancel = CancellationException("job cancelled")

        val normalized = ChatOrchestrator.StreamAttemptWatchdog.normalizeWatchdogThrowable(
            cause = cancel,
            watchdogReason = ChatOrchestrator.StreamAttemptWatchdog.REASON_TTFT,
        )

        assertTrue(normalized is CancellationException)
        assertEquals(ChatOrchestrator.StreamAttemptWatchdog.REASON_TTFT, normalized?.message)
        assertSame(cancel, normalized?.cause)
    }

    @Test
    fun `image turns get longer ttft grace and capped fleet`() {
        assertEquals(
            ChatOrchestrator.IMAGE_TTFT_TIMEOUT_MS,
            ChatOrchestrator.streamTtftTimeoutMs(hasInlineData = true)
        )
        assertEquals(
            ChatOrchestrator.TTFT_TIMEOUT_MS,
            ChatOrchestrator.streamTtftTimeoutMs(hasInlineData = false)
        )

        val imageFleet = ChatOrchestrator.streamModelFleet(
            model = "gemini-3.5-flash",
            hasInlineData = true
        )
        assertEquals(ChatOrchestrator.IMAGE_MODEL_ATTEMPT_LIMIT, imageFleet.size)
        assertEquals("gemini-3.5-flash", imageFleet.first())
        assertTrue(
            ChatOrchestrator.streamModelFleet(
                model = "gemini-3.5-flash",
                hasInlineData = false
            ).size > imageFleet.size
        )
    }

    @Test
    fun `image fleet drops 3-7-flash even as the seed model`() {
        // gemini-3.7-flash 503s on vision input, so it must never appear in
        // an image-turn fleet — even if a session persisted it as the seed
        // model from a prior text turn.
        val imageFleet = ChatOrchestrator.streamModelFleet(
            model = "gemini-3.7-flash",
            hasInlineData = true
        )
        assertTrue("gemini-3.7-flash" !in imageFleet)

        val textFleet = ChatOrchestrator.streamModelFleet(
            model = "gemini-3.7-flash",
            hasInlineData = false
        )
        assertTrue("gemini-3.7-flash" in textFleet)
    }

    @Test
    fun `image reasoning skips parallel fast reply`() {
        val intent = ChatIntent.Reasoning("Describe this image")

        assertTrue(ChatOrchestrator.shouldStartFastReply(intent, imageStored = null))
        assertEquals(false, ChatOrchestrator.shouldStartFastReply(intent, "file:///image.jpg"))
    }

    @Test
    fun `blocking fallback cap is wider for image turns`() {
        assertEquals(
            ChatOrchestrator.IMAGE_BLOCKING_FALLBACK_TIMEOUT_MS,
            ChatOrchestrator.blockingFallbackTimeoutMs(hasInlineData = true)
        )
        assertEquals(
            ChatOrchestrator.BLOCKING_FALLBACK_TIMEOUT_MS,
            ChatOrchestrator.blockingFallbackTimeoutMs(hasInlineData = false)
        )
        assertTrue(
            ChatOrchestrator.IMAGE_BLOCKING_FALLBACK_TIMEOUT_MS >
                ChatOrchestrator.BLOCKING_FALLBACK_TIMEOUT_MS
        )
    }

    @Test
    fun `chatErrorKeyFor classifies causes consistently`() {
        assertEquals(
            "chat_error_offline",
            ChatOrchestrator.chatErrorKeyFor(java.net.UnknownHostException("no dns"))
        )
        assertEquals(
            "chat_error_timeout",
            ChatOrchestrator.chatErrorKeyFor(java.net.SocketTimeoutException("timeout"))
        )
        assertEquals(
            "chat_error_busy",
            ChatOrchestrator.chatErrorKeyFor(IllegalStateException("429 quota exceeded"))
        )
        assertEquals(
            "chat_error_generic",
            ChatOrchestrator.chatErrorKeyFor(IllegalStateException("some other failure"))
        )
        assertEquals("chat_error_generic", ChatOrchestrator.chatErrorKeyFor(null))
    }

}
