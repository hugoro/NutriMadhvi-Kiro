package com.example.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScopedJobDeduperTest {

    @Test
    fun `replacing same session cancels previous job`() = runTest {
        val deduper = ChatOrchestrator.SessionScopedJobDeduper(this)
        val firstCancelled = CompletableDeferred<Unit>()

        deduper.launchReplacing(1L, "TEST", "replace") {
            try {
                awaitCancellation()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        runCurrent()

        deduper.launchReplacing(1L, "TEST", "replace") {
            awaitCancellation()
        }
        runCurrent()

        assertTrue(firstCancelled.isCompleted)
        assertEquals(1, deduper.activeJobCount())
        deduper.cancelAll()
        runCurrent()
    }

    @Test
    fun `different sessions can run in parallel`() = runTest {
        val deduper = ChatOrchestrator.SessionScopedJobDeduper(this)

        deduper.launchReplacing(1L, "TEST", "replace") { awaitCancellation() }
        deduper.launchReplacing(2L, "TEST", "replace") { awaitCancellation() }
        runCurrent()

        assertEquals(2, deduper.activeJobCount())
        deduper.cancelAll()
        runCurrent()
    }

    @Test
    fun `completed job removes itself from registry`() = runTest {
        val deduper = ChatOrchestrator.SessionScopedJobDeduper(this)

        deduper.launchReplacing(1L, "TEST", "replace") { }
        runCurrent()

        assertEquals(0, deduper.activeJobCount())
    }
}
