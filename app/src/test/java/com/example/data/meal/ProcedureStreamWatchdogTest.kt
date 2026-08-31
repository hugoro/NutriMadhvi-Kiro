package com.example.data.meal

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcedureStreamWatchdogTest {

    @Test
    fun `ttft timeout fires before first partial`() {
        val started = 1_000L
        val watchdog = MealAnalysisOrchestrator.ProcedureStreamWatchdog(startedAtMs = started)

        assertNull(
            watchdog.timeoutReason(
                nowMs = started + MealAnalysisOrchestrator.STREAM_TTFT_TIMEOUT_MS - 1L
            )
        )
        assertEquals(
            MealAnalysisOrchestrator.ProcedureStreamWatchdog.REASON_TTFT,
            watchdog.timeoutReason(
                nowMs = started + MealAnalysisOrchestrator.STREAM_TTFT_TIMEOUT_MS
            )
        )
    }

    @Test
    fun `idle timeout fires only after first partial`() {
        val started = 5_000L
        val firstPartial = 7_000L
        val watchdog = MealAnalysisOrchestrator.ProcedureStreamWatchdog(
            startedAtMs = started,
            lastPartialAtMs = firstPartial,
        )

        assertNull(
            watchdog.timeoutReason(
                nowMs = firstPartial + MealAnalysisOrchestrator.STREAM_IDLE_TIMEOUT_MS - 1L
            )
        )
        assertEquals(
            MealAnalysisOrchestrator.ProcedureStreamWatchdog.REASON_IDLE,
            watchdog.timeoutReason(
                nowMs = firstPartial + MealAnalysisOrchestrator.STREAM_IDLE_TIMEOUT_MS
            )
        )
    }

    @Test
    fun `idle timeout suppresses ttft once first partial exists`() {
        val started = 100L
        val firstPartial = started + 50L
        val watchdog = MealAnalysisOrchestrator.ProcedureStreamWatchdog(
            startedAtMs = started,
            lastPartialAtMs = firstPartial,
        )

        assertNull(
            watchdog.timeoutReason(
                nowMs = started + MealAnalysisOrchestrator.STREAM_TTFT_TIMEOUT_MS + 1L,
                idleTimeoutMs = MealAnalysisOrchestrator.STREAM_IDLE_TIMEOUT_MS * 2,
            )
        )
    }

    @Test
    fun `real cancellation is not treated as watchdog timeout`() {
        val cancel = CancellationException("user cancelled")

        val normalized = MealAnalysisOrchestrator.ProcedureStreamWatchdog.normalizeWatchdogThrowable(cancel, watchdogReason = null)

        assertNull(normalized)
    }

    @Test
    fun `watchdog cancellation keeps explicit reason`() {
        val cancel = CancellationException("job cancelled")

        val normalized = MealAnalysisOrchestrator.ProcedureStreamWatchdog.normalizeWatchdogThrowable(
            cause = cancel,
            watchdogReason = MealAnalysisOrchestrator.ProcedureStreamWatchdog.REASON_IDLE,
        )

        assertTrue(normalized is CancellationException)
        assertEquals(MealAnalysisOrchestrator.ProcedureStreamWatchdog.REASON_IDLE, normalized?.message)
        assertSame(cancel, normalized?.cause)
    }

}
