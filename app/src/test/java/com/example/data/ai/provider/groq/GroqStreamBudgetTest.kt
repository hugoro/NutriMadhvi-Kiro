package com.example.data.ai.provider.groq

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 (plan §11, §12) — chat-scoped Groq 429 memory. Deterministic:
 * the clock is passed in, never read from the wall.
 */
class GroqStreamBudgetTest {

    private val model = "qwen/qwen3.8-27b"

    @Before fun clear() = GroqStreamBudget.reset()
    @After fun cleanup() = GroqStreamBudget.reset()

    @Test
    fun unknown_model_is_optimistically_usable() {
        assertTrue(GroqStreamBudget.isUsable(model, nowMs = 0L))
        assertEquals(0L, GroqStreamBudget.millisUntilUsable(model, nowMs = 0L))
    }

    @Test
    fun record429_blocks_for_cooldown_then_clears() {
        val t0 = 10_000L
        GroqStreamBudget.record429(model, nowMs = t0)

        // Still inside the window → blocked.
        assertFalse(GroqStreamBudget.isUsable(model, nowMs = t0 + 1))
        assertEquals(
            GroqStreamBudget.DEFAULT_COOLDOWN_MS - 1,
            GroqStreamBudget.millisUntilUsable(model, nowMs = t0 + 1),
        )

        // Exactly at expiry → usable again.
        assertTrue(GroqStreamBudget.isUsable(model, nowMs = t0 + GroqStreamBudget.DEFAULT_COOLDOWN_MS))
    }

    @Test
    fun isUsable_self_clears_expired_entry() {
        val t0 = 5_000L
        GroqStreamBudget.record429(model, nowMs = t0, cooldownMs = 1_000L)
        // Probe past expiry — should return usable AND drop the entry.
        assertTrue(GroqStreamBudget.isUsable(model, nowMs = t0 + 2_000L))
        // millisUntilUsable now behaves as unknown (0), confirming removal.
        assertEquals(0L, GroqStreamBudget.millisUntilUsable(model, nowMs = t0 + 2_000L))
    }

    @Test
    fun blank_model_is_ignored() {
        GroqStreamBudget.record429("", nowMs = 1_000L)
        assertTrue(GroqStreamBudget.isUsable("", nowMs = 1_000L))
    }

    @Test
    fun per_model_isolation() {
        val a = "qwen/qwen3.8-27b"
        val b = "llama-3.3-70b"
        GroqStreamBudget.record429(a, nowMs = 1_000L)
        assertFalse(GroqStreamBudget.isUsable(a, nowMs = 1_500L))
        assertTrue(GroqStreamBudget.isUsable(b, nowMs = 1_500L))
    }

    @Test
    fun custom_cooldown_is_honoured() {
        val t0 = 0L
        GroqStreamBudget.record429(model, nowMs = t0, cooldownMs = 5_000L)
        assertFalse(GroqStreamBudget.isUsable(model, nowMs = 4_999L))
        assertTrue(GroqStreamBudget.isUsable(model, nowMs = 5_000L))
    }
}
