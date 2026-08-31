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

}
