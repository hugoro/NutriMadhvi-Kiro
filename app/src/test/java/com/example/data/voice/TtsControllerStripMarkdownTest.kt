package com.example.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-05 (chat TTS mode, camada 2) — locks the ear-safety fixes in
 * [TtsController.stripMarkdown]. Each case mirrors text that actually
 * appeared in persisted chat messages (meal plans, weekly reviews) and the
 * way the TTS engine misread it: letters spelled out, acronyms, dot-grouped
 * thousands, code-like identifiers.
 *
 * `stripMarkdown` is `internal` in the same module, so unit tests in
 * `com.example.data.voice` can call it directly (mirrors how
 * ActionProposalParserTtsTest drives it end-to-end).
 */
class TtsControllerStripMarkdownTest {

    private fun tts(input: String): String = TtsController.stripMarkdown(input)

    @Test
    fun `compact macros with spaces are expanded, not spelled letter by letter`() {
        val out = tts("Opção A: ovos, chouriço (309 kcal; P24 / C14 / F17)")
        assertFalse("letters must not survive", Regex("P\\s?\\d+\\s*/\\s*C\\s?\\d+").containsMatchIn(out))
        assertTrue(out.contains("proteína 24"))
        assertTrue(out.contains("carbos 14"))
        assertTrue(out.contains("gordura 17"))
    }

    @Test
    fun `compact macros without spaces keep working`() {
        val out = tts("(555 kcal; P37/C48/F24)")
        assertTrue(out.contains("proteína 37"))
        assertTrue(out.contains("carbos 48"))
        assertTrue(out.contains("gordura 24"))
    }

    @Test
    fun `dot-grouped thousands lose the separator`() {
        val out = tts("110.724 passos e 6.835 kcal ativas")
        assertFalse("dot group must be gone", out.contains("110.724"))
        assertFalse(out.contains("6.835"))
        assertTrue(out.contains("110724"))
        assertTrue(out.contains("6835"))
    }

    @Test
    fun `space-grouped thousands lose the separator`() {
        val out = tts("1 058 kcal no dia")
        assertFalse(out.contains("1 058"))
        assertTrue(out.contains("1058"))
    }

    @Test
    fun `decimals and ordinary numbers are untouched`() {
        val out = tts("média de 101.3 g/dia contra alvo de 130 g e 82,8 kg")
        // "101.3" is a decimal (1 digit after the dot) — must NOT be grouped
        assertFalse(out.contains("1013"))
        // pt-PT comma decimal untouched
        assertTrue(out.contains("82,8"))
        assertTrue(out.contains("130"))
    }

    @Test
    fun `acronyms are expanded to spoken Portuguese`() {
        val out = tts("o SH regista TMB e HRV; a BIA tem ruído, prefere DEXA")
        assertFalse(out.contains("SH"))
        assertFalse(out.contains("TMB"))
        assertFalse(out.contains("HRV"))
        assertFalse(out.contains("BIA"))
        assertFalse(out.contains("DEXA"))
        assertTrue(out.contains("relógio"))
        assertTrue(out.contains("metabolismo basal"))
        assertTrue(out.contains("variabilidade da frequência cardíaca"))
        assertTrue(out.contains("bioimpedância"))
        assertTrue(out.contains("densitometria"))
    }

    @Test
    fun `SH never matches inside a longer word`() {
        val out = tts("o SH mostrou 82 kg")
        assertTrue(out.contains("relógio"))
        // sanity: a word containing SH is untouched
        val out2 = tts("o shambhala não é medido")
        assertTrue(out2.contains("shambhala"))
    }

    @Test
    fun `english session labels are translated`() {
        val out = tts("8 sessões de Running e 2 de Weight machine, com fast pacing walk")
        assertFalse(out.contains("Weight machine"))
        assertFalse(out.contains("fast pacing walk"))
        assertTrue(out.contains("máquina de pesos"))
        assertTrue(out.contains("caminhada rápida"))
        assertTrue(out.contains("corrida"))
    }

    @Test
    fun `snake_case code identifiers are removed from prose`() {
        val out = tts("Mantiveste a tua aggregate_pattern de alta performance")
        assertFalse(out.contains("aggregate_pattern"))
        assertFalse("generic snake_case must go", Regex("\\w+_\\w+").containsMatchIn(out))
        assertTrue(out.contains("padrão agregado"))
    }

    @Test
    fun `percentage over a slash pair is spoken naturally`() {
        val out = tts("não há nova leitura de %gordura/músculo desde 20/08")
        assertFalse(out.contains("%gordura"))
        assertFalse(out.contains("gordura/músculo"))
        assertTrue(out.contains("por cento de gordura e músculo"))
    }

    @Test
    fun `a TTS_SUMMARY script passes through as clean prose`() {
        // The exact shape the model is instructed to emit (2026-10-05).
        val script = """
            Em resumo: a semana correu em défice consistente, com 1635 kcal/dia em média.
            O que os dados mostram: a constância no movimento foi o ponto forte, com 8 sessões de Running; a proteína ficou a 101.3 g/dia contra o alvo de 130 g.
            O que está a correr bem e o que precisa de ser corrigido: os blocos matinais de cardio estão a rodar bem, mas a proteína desce nos dias longos.
            Para as próximas 24 horas: fecha as 130 g de proteína no pequeno-almoço e no almoço, e troca a corrida por recuperação ativa.
        """.trimIndent()
        val out = tts(script)
        assertFalse(out.contains("Running"))
        assertTrue("1-decimal survives (not a thousands group)", out.contains("101.3"))
        assertTrue(out.contains("corrida"))
        assertTrue("spoken transitions survive", out.startsWith("Em resumo:"))
        assertTrue(out.contains("Para as próximas 24 horas:"))
    }

    @Test
    fun `legacy behaviour is preserved for plain prose`() {
        val out = tts("Hoje tens 1900 kcal de alvo, com ~130g de proteína e 16h30 de sono.")
        assertTrue(out.contains("quilocalorias"))
        assertTrue(out.contains("gramas"))
        // the ~ expansion from the legacy rules
        assertTrue(out.contains("aproximadamente"))
    }
}
