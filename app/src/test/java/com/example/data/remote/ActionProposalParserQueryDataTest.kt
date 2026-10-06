package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the `[QUERY_DATA]` salvage chain — especially the 2026-10-06
 * empty-bubble fix: when the model emits the CANONICAL tags but a payload
 * the DTO cannot satisfy (invented `window` key, missing dates), the parser
 * must still recover the window from the raw payload so the follow-up runs
 * and the bubble is not silently emptied.
 *
 * Regression: session 175, msg 892 (build 426, runinfra:qwen3-8-27b) —
 * `[QUERY_DATA]{"type":"meals","window":"2026-09-28 → 2026-10-04","reason":
 * "..." }[/QUERY_DATA]`. Pre-fix: canonical regex matched → `queryHandled`
 * → Moshi DTO incomplete → `return@forEach` → tag stripped from the visible
 * text AND no follow-up → completely empty bubble ("parou").
 */
class ActionProposalParserQueryDataTest {

    @Test
    fun `invented window key is salvaged from ISO dates`() {
        // The exact shape Qwen3.8-27B produced on 2026-10-06.
        val raw = """
            Preciso dos dados da semana passada para comparar.

            [QUERY_DATA]{"type":"meals","window":"2026-09-28 → 2026-10-04","reason":"preciso dos dados reais da semana anterior"}[/QUERY_DATA]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        val qdr = parsed.queryDataRequest
        assertNotNull("window-key payload must be salvaged", qdr)
        assertEquals("meals", qdr!!.type)
        assertEquals("2026-09-28", qdr.fromDate)
        assertEquals("2026-10-04", qdr.toDate)
        assertFalse("protocol tag must not leak to the screen",
            parsed.cleanedText.contains("QUERY_DATA"))
        assertTrue("the prose around the tag survives",
            parsed.cleanedText.contains("Preciso dos dados da semana passada"))
    }

    @Test
    fun `canonical complete json still parses directly`() {
        val raw = """
            Deixa-me ver os teus números.
            [QUERY_DATA]{"type":"meals","fromDate":"2026-06-01","toDate":"2026-06-16"}[/QUERY_DATA]
        """.trimIndent()
        val qdr = ActionProposalParser.parse(raw).queryDataRequest
        assertNotNull(qdr)
        assertEquals("meals", qdr!!.type)
        assertEquals("2026-06-01", qdr.fromDate)
        assertEquals("2026-06-16", qdr.toDate)
    }

    @Test
    fun `snake_case alias keys are normalised`() {
        val raw = """
            [QUERY_DATA]{"type":"meals","start_date":"2026-06-01","end_date":"2026-06-16"}[/QUERY_DATA]
        """.trimIndent()
        val qdr = ActionProposalParser.parse(raw).queryDataRequest
        assertNotNull("alias keys must survive normaliseJson", qdr)
        assertEquals("2026-06-01", qdr!!.fromDate)
        assertEquals("2026-06-16", qdr.toDate)
    }

    @Test
    fun `free-form text in canonical tags salvages dates and infers type`() {
        val raw = """
            Quero rever as tuas refeições de 2026-06-01 a 2026-06-07.
            [QUERY_DATA]as refeições de 2026-06-01 até 2026-06-07[/QUERY_DATA]
        """.trimIndent()
        val qdr = ActionProposalParser.parse(raw).queryDataRequest
        assertNotNull("free-form canonical payload must be salvaged", qdr)
        assertEquals("meals", qdr!!.type)
        assertEquals("2026-06-01", qdr.fromDate)
        assertEquals("2026-06-07", qdr.toDate)
    }

    @Test
    fun `missing dates in canonical tags leaves no request and keeps tag stripped`() {
        // No ISO dates anywhere → salvage impossible; the turn degrades to
        // prose-only (the tag is still swept from the screen — never raw
        // protocol in the bubble).
        val raw = """
            Deixa-me ver os teus números.
            [QUERY_DATA]{"type":"meals","window":"a semana passada"}[/QUERY_DATA]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertNull("no dates → no request", parsed.queryDataRequest)
        assertFalse("tag must still be swept", parsed.cleanedText.contains("QUERY_DATA"))
        assertTrue("prose survives", parsed.cleanedText.contains("Deixa-me ver os teus números"))
    }

    @Test
    fun `window key with activity keyword infers activity`() {
        val raw = """
            Quero comparar a tua atividade dos últimos dias.
            [QUERY_DATA]{"type":"activity","window":"2026-09-29 → 2026-10-03"}[/QUERY_DATA]
        """.trimIndent()
        val qdr = ActionProposalParser.parse(raw).queryDataRequest
        assertNotNull(qdr)
        assertEquals("activity", qdr!!.type)
        assertEquals("2026-09-29", qdr.fromDate)
        assertEquals("2026-10-03", qdr.toDate)
    }
}
