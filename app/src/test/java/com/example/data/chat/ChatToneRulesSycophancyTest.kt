package com.example.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the anti-sycophancy fix (2026-07-10). Hugo's feedback: in a running
 * chat conversation the assistant kept opening with "tens toda a razão, peço
 * desculpa…", which reads as servile. [ChatToneRules.stripLeadingSycophancy]
 * must peel that opener WITHOUT eating the substance that follows.
 */
class ChatToneRulesSycophancyTest {

    @Test
    fun `leading agreement opener is stripped, substance kept`() {
        val out = ChatToneRules.stripLeadingSycophancy(
            "Tens toda a razão, o valor correto é 12g de proteína."
        )
        assertEquals("O valor correto é 12g de proteína.", out)
    }

    @Test
    fun `leading apology is stripped`() {
        val out = ChatToneRules.stripLeadingSycophancy(
            "Peço desculpa — o total do dia é 1820 kcal."
        )
        assertEquals("O total do dia é 1820 kcal.", out)
    }

    @Test
    fun `stacked openers are all peeled`() {
        val out = ChatToneRules.stripLeadingSycophancy(
            "Sim, tens razão. Peço desculpa. O jantar ficou por logar."
        )
        assertEquals("O jantar ficou por logar.", out)
    }

    @Test
    fun `english opener is stripped`() {
        val out = ChatToneRules.stripLeadingSycophancy(
            "You're absolutely right, the target is 135g."
        )
        assertEquals("The target is 135g.", out)
    }

    @Test
    fun `mid-sentence use is NOT stripped`() {
        // "desculpa" used substantively, not as a leading preamble.
        val input = "Não tens de pedir desculpa por comer um bolo."
        assertEquals(input, ChatToneRules.stripLeadingSycophancy(input))
    }

    @Test
    fun `normal reply is untouched`() {
        val input = "Faltam 40g de proteína para o alvo, vai ter de ser ao jantar."
        assertEquals(input, ChatToneRules.stripLeadingSycophancy(input))
    }

    @Test
    fun `opener with nothing after it keeps original`() {
        // Dropout guard — never return empty.
        val input = "Peço desculpa."
        val out = ChatToneRules.stripLeadingSycophancy(input)
        assertTrue(out.isNotBlank())
    }

    @Test
    fun `clean pipeline removes opener end to end`() {
        val out = ChatToneRules.clean("Tens toda a razão, o défice hoje é 300 kcal.")
        assertTrue("must not keep the agreement opener", !out.lowercase().contains("tens toda a razão"))
        assertTrue("must keep the fact", out.contains("300 kcal"))
    }
}
