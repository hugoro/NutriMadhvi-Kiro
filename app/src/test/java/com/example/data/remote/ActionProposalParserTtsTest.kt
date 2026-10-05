package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the `[TTS_SUMMARY]` wire contract (2026-10-05, chat TTS mode):
 * the model emits a spoken script at the end of analytic replies; the
 * parser extracts it into [ParsedActionMessage.ttsSummary] AND strips it
 * from the visible text; the TTS path later runs the script through
 * [com.example.data.voice.TtsController.stripMarkdown].
 *
 * The script is plain prose (no JSON) — the only "parsing" is marker
 * extraction plus the defensive orphan sweeps.
 */
class ActionProposalParserTtsTest {

    @Test
    fun `ttsSummary is extracted and stripped from cleanedText`() {
        val raw = """
            ### Resumo
            Défice consistente esta semana.

            ### Próximas ações
            - Mais proteína.

            [TTS_SUMMARY]Em resumo: a semana correu em défice. O que os dados mostram: a proteína foi o ponto fraco. Para as próximas 24 horas: fecha as 130 gramas de proteína.[/TTS_SUMMARY]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertNotNull("ttsSummary must be present", parsed.ttsSummary)
        assertEquals(
            "Em resumo: a semana correu em défice. O que os dados mostram: a proteína foi o ponto fraco. Para as próximas 24 horas: fecha as 130 gramas de proteína.",
            parsed.ttsSummary
        )
        assertFalse("script must not leak to the screen", parsed.cleanedText.contains("TTS_SUMMARY"))
        assertTrue("visible reply survives", parsed.cleanedText.contains("Défice consistente esta semana"))
    }

    @Test
    fun `missing block yields null ttsSummary and untouched text`() {
        val raw = """
            Resposta curta, sem análise.
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertNull(parsed.ttsSummary)
        assertEquals("Resposta curta, sem análise.", parsed.cleanedText)
    }

    @Test
    fun `multi-line script survives verbatim`() {
        val raw = """
            Texto visível.

            [TTS_SUMMARY]Em resumo: primeira frase do guião.
            O que os dados mostram: segunda frase.
            O que está a correr bem e o que precisa de ser corrigido: terceira.
            Para as próximas 24 horas: quarta e última.[/TTS_SUMMARY]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertNotNull(parsed.ttsSummary)
        val lines = parsed.ttsSummary!!.split("\n")
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("Em resumo:"))
        assertTrue(lines[3].startsWith("Para as próximas 24 horas:"))
        assertFalse(parsed.cleanedText.contains("TTS_SUMMARY"))
    }

    @Test
    fun `orphan open tag is swept from visible text`() {
        val raw = "Texto visível.\n[TTS_SUMMARY]guião sem fecho que o modelo truncou"
        val parsed = ActionProposalParser.parse(raw)
        // The script body never reaches TTS (no closing tag = no match); the
        // orphan sweep must still keep the marker off the screen.
        assertNull(parsed.ttsSummary)
        assertFalse(parsed.cleanedText.contains("TTS_SUMMARY"))
    }

    @Test
    fun `orphan close tag is swept from visible text`() {
        val raw = "Texto visível.\nalgum texto solto [/TTS_SUMMARY]"
        val parsed = ActionProposalParser.parse(raw)
        assertNull(parsed.ttsSummary)
        assertFalse(parsed.cleanedText.contains("TTS_SUMMARY"))
    }

    @Test
    fun `ttsSummary coexists with a chart proposal`() {
        val raw = """
            Análise da semana.

            [CHART_PROPOSAL]{"kind":"bar","title":"kcal — 7 dias","series":[{"label":"d1","value":1500,"highlight":false},{"label":"d2","value":1600,"highlight":true}],"unit":"kcal","target":1900}[/CHART_PROPOSAL]

            [TTS_SUMMARY]Em resumo: défice consistente. O que os dados mostram: proteína abaixo do alvo. Para as próximas 24 horas: subir a proteína.[/TTS_SUMMARY]
        """.trimIndent()
        val parsed = ActionProposalParser.parse(raw)
        assertEquals(1, parsed.chartProposals.size)
        assertEquals("kcal — 7 dias", parsed.chartProposals.first().title)
        assertNotNull(parsed.ttsSummary)
        assertFalse(parsed.cleanedText.contains("CHART_PROPOSAL"))
        assertFalse(parsed.cleanedText.contains("TTS_SUMMARY"))
    }

    @Test
    fun `script runs through stripMarkdown without protocol residue`() {
        // End-to-end sanity: whatever the model writes inside the block, the
        // TTS cleaner must leave plain prose (the block itself is never sent
        // to TTS with its markers, but the script may carry light formatting
        // the model slipped in).
        val raw = "Visível.\n[TTS_SUMMARY]Em resumo: **défice** consistente. 1635 kcal/dia. ~110 mil passos.[/TTS_SUMMARY]"
        val parsed = ActionProposalParser.parse(raw)
        assertNotNull(parsed.ttsSummary)
        val script = parsed.ttsSummary!!
        // TtsController is in the same module (internal is visible from tests).
        val spoken = com.example.data.voice.TtsController.stripMarkdown(script)
        assertFalse(spoken.contains("**"))
        assertTrue(spoken.contains("défice"))
        assertFalse("protocol must never reach the TTS engine", spoken.contains("TTS_SUMMARY"))
    }
}
