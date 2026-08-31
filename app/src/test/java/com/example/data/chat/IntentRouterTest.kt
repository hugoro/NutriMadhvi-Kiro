package com.example.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Minimal coverage (~10 cases) for the patterns Hugo's screenshots showed —
 * mostly Portuguese (his primary language). The point is to lock down the
 * "desde início do mês" / "entre DD-DD" wording that produced the marker leak.
 *
 * If a regression slips through here, the salvage parser still cleans the
 * bubble, but we lose the local-fast path and pay a Gemini round-trip.
 */
class IntentRouterTest {

    private val today = LocalDate.of(2026, 6, 17)  // matches the screenshots

    @Test
    fun `desde inicio do mes is a DataQuery from first of month to today`() {
        val intent = IntentRouter.classify(
            "Tendo em conta o que se passou desde o início do mês relativamente à minha nutrição e atividade, qual é o impacto?",
            today
        )
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(LocalDate.of(2026, 6, 1), q.window.from)
        assertEquals(today, q.window.to)
        // "nutrição e atividade" → BOTH
        assertEquals(DataType.BOTH, q.type)
    }

    @Test
    fun `este mes maps to the same window`() {
        val intent = IntentRouter.classify("Como está a minha dieta este mês?", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(LocalDate.of(2026, 6, 1), q.window.from)
        assertEquals(today, q.window.to)
        assertEquals(DataType.MEALS, q.type)
    }

    @Test
    fun `entre datas iso explicit window`() {
        val intent = IntentRouter.classify("Quantas calorias entre 2026-06-01 e 2026-06-16?", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(LocalDate.of(2026, 6, 1), q.window.from)
        assertEquals(LocalDate.of(2026, 6, 16), q.window.to)
        assertEquals(DataType.MEALS, q.type)
    }

    @Test
    fun `ultimos 30 dias`() {
        val intent = IntentRouter.classify("Como foram os últimos 30 dias de treino?", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(today.minusDays(29), q.window.from)
        assertEquals(today, q.window.to)
        assertEquals(DataType.ACTIVITY, q.type)
    }

    @Test
    fun `esta semana monday to today`() {
        // 2026-06-17 é uma quarta-feira → Monday = 2026-06-15
        val intent = IntentRouter.classify("Quantas calorias comi esta semana?", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(LocalDate.of(2026, 6, 15), q.window.from)
        assertEquals(today, q.window.to)
    }

    @Test
    fun `ontem is single day window`() {
        val intent = IntentRouter.classify("Como foi a minha alimentação ontem?", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(today.minusDays(1), q.window.from)
        assertEquals(today.minusDays(1), q.window.to)
    }

    @Test
    fun `desde iso date until today`() {
        val intent = IntentRouter.classify("Mostra-me os jantares desde 2026-05-15", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(LocalDate.of(2026, 5, 15), q.window.from)
        assertEquals(today, q.window.to)
        assertEquals(DataType.MEALS, q.type)
    }

    @Test
    fun `theoretical question is Reasoning not DataQuery`() {
        val intent = IntentRouter.classify("Como funciona a digestão dos hidratos?", today)
        assertTrue(intent is ChatIntent.Reasoning)
    }

    @Test
    fun `greeting is Reasoning`() {
        val intent = IntentRouter.classify("Olá, tudo bem?", today)
        assertTrue(intent is ChatIntent.Reasoning)
    }

    @Test
    fun `90 day cap clamps wide window`() {
        // "este ano" em 2026-06-17 = 168 dias > 90
        val intent = IntentRouter.classify("Como foi a minha alimentação este ano?", today)
        assertTrue(intent is ChatIntent.DataQuery)
        val q = intent as ChatIntent.DataQuery
        assertEquals(90, q.window.days)
        assertEquals(today, q.window.to)
    }

    @Test
    fun `data type detection is independent of window`() {
        assertEquals(DataType.MEALS, IntentRouter.detectDataType("quantas calorias?"))
        assertEquals(DataType.ACTIVITY, IntentRouter.detectDataType("quantos passos hoje?"))
        assertEquals(DataType.BOTH, IntentRouter.detectDataType("calorias e passos"))
        assertNull(IntentRouter.detectDataType("olá tudo bem?"))
    }
}
