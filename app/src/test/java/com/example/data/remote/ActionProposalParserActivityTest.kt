package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the [LOG_ACTIVITY_PROPOSAL] wire → DTO → domain contract for the new
 * `sourceLabel` field. See `GeminiRepository.HABIT_PROTOCOL` for the grammar
 * the model is instructed to emit.
 *
 * Only the activity path is covered; meal/habit/query/insight paths are
 * verified indirectly by higher-level chat integration tests when they exist.
 */
class ActionProposalParserActivityTest {

    @Test fun `sourceLabel present is parsed into proposal`() {
        val raw = """
            Vou registar isto:
            [LOG_ACTIVITY_PROPOSAL]{"name":"Pilates","sourceLabel":"Pilates","durationMinutes":30,"caloriesBurned":150,"activityType":"YOGA","description":"30 min de pilates de manhã."}[/LOG_ACTIVITY_PROPOSAL]
            Confirma abaixo.
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertEquals(1, parsed.activityProposals.size)
        val p = parsed.activityProposals.first()
        assertEquals("Pilates", p.name)
        assertEquals("Pilates", p.sourceLabel)
        assertEquals("YOGA", p.activityTypeName)
        assertEquals(30, p.durationMinutes)
        assertEquals(150, p.caloriesBurned)
        assertNotNull(p.description)
    }

    @Test fun `sourceLabel absent keeps the field null but proposal parses`() {
        // Grace period for older prompts / model omissions — even though the
        // grammar says REQUIRED, a missing field must not drop the proposal
        // entirely (that would silently swallow the user's confirmed workout).
        val raw = """
            [LOG_ACTIVITY_PROPOSAL]{"name":"Run","durationMinutes":45,"caloriesBurned":400,"activityType":"RUNNING","description":"..."}[/LOG_ACTIVITY_PROPOSAL]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertEquals(1, parsed.activityProposals.size)
        assertNull(parsed.activityProposals.first().sourceLabel)
    }

    @Test fun `blank sourceLabel is normalised to null`() {
        val raw = """
            [LOG_ACTIVITY_PROPOSAL]{"name":"Run","sourceLabel":"","durationMinutes":45,"caloriesBurned":400,"activityType":"RUNNING","description":"..."}[/LOG_ACTIVITY_PROPOSAL]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertNull(parsed.activityProposals.first().sourceLabel)
    }

    @Test fun `sourceLabel preserves the specific modality (Weight machine)`() {
        // Regression guard: the chat should propose "Weight machine" for a
        // gym session, not just the bucket "STRENGTH".
        val raw = """
            [LOG_ACTIVITY_PROPOSAL]{"name":"Ginásio","sourceLabel":"Weight machine","durationMinutes":60,"caloriesBurned":300,"activityType":"STRENGTH","description":"..."}[/LOG_ACTIVITY_PROPOSAL]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        val p = parsed.activityProposals.first()
        assertEquals("Weight machine", p.sourceLabel)
        assertEquals("STRENGTH", p.activityTypeName)
    }
}
