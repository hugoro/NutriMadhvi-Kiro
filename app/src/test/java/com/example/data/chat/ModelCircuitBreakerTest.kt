package com.example.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCircuitBreakerTest {

    @Test
    fun `opens after failure threshold and skips during cooldown`() {
        var now = 1_000L
        val breaker = ModelCircuitBreaker(nowMs = { now })

        breaker.recordFailure("flash")
        assertFalse(breaker.isOpen("flash"))

        breaker.recordFailure("flash")
        assertTrue(breaker.isOpen("flash"))

        now += ModelCircuitBreaker.OPEN_DURATION_MS - 1L
        assertTrue(breaker.isOpen("flash"))
    }

    @Test
    fun `half opens after cooldown elapses`() {
        var now = 10_000L
        val breaker = ModelCircuitBreaker(nowMs = { now })

        breaker.recordFailure("flash")
        breaker.recordFailure("flash")
        assertTrue(breaker.isOpen("flash"))

        now += ModelCircuitBreaker.OPEN_DURATION_MS
        assertFalse(breaker.isOpen("flash"))
        assertFalse(breaker.isOpen("flash"))
    }

    @Test
    fun `success closes breaker and clears failure history`() {
        var now = 20_000L
        val breaker = ModelCircuitBreaker(nowMs = { now })

        breaker.recordFailure("flash")
        breaker.recordFailure("flash")
        assertTrue(breaker.isOpen("flash"))

        breaker.recordSuccess("flash")
        assertFalse(breaker.isOpen("flash"))

        breaker.recordFailure("flash")
        assertFalse(breaker.isOpen("flash"))
    }
}
