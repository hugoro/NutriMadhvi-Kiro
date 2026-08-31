package com.example.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionScopedDeferredRegistryTest {

    @Test
    fun `same session reuses in flight deferred`() = runTest {
        val registry = ChatOrchestrator.SessionScopedDeferredRegistry<String>(this)
        val gate = CompletableDeferred<String>()
        var starts = 0

        val first = registry.getOrStart(1L, "TEST", "reuse") {
            starts += 1
            gate.await()
        }
        val second = registry.getOrStart(1L, "TEST", "reuse") {
            starts += 1
            "second"
        }

        assertSame(first, second)
        // The winner is started with CoroutineStart.LAZY, so its producer body
        // only runs once the dispatcher is pumped. Pump before asserting the
        // start count. The loser was never started, so `starts` must be 1.
        runCurrent()
        assertEquals(1, starts)
        assertEquals(1, registry.activeDeferredCount())

        gate.complete("done")
        runCurrent()
        assertEquals(0, registry.activeDeferredCount())
    }

    @Test
    fun `different sessions get independent deferreds`() = runTest {
        val registry = ChatOrchestrator.SessionScopedDeferredRegistry<String>(this)
        val gate1 = CompletableDeferred<String>()
        val gate2 = CompletableDeferred<String>()

        val first = registry.getOrStart(1L, "TEST", "reuse") { gate1.await() }
        val second = registry.getOrStart(2L, "TEST", "reuse") { gate2.await() }

        assertEquals(2, registry.activeDeferredCount())
        gate1.complete("one")
        gate2.complete("two")
        runCurrent()
        assertEquals("one", first.await())
        assertEquals("two", second.await())
        assertEquals(0, registry.activeDeferredCount())
    }
}
