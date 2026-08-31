package com.example.data.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientClusterBuilderTest {

    @Test
    fun `median of odd list returns middle`() {
        assertEquals(200.0, IngredientClusterBuilder.median(listOf(100.0, 200.0, 300.0)))
    }

    @Test
    fun `median of even list averages middle two`() {
        assertEquals(250.0, IngredientClusterBuilder.median(listOf(100.0, 200.0, 300.0, 400.0)))
    }

    @Test
    fun `median ignores order`() {
        assertEquals(200.0, IngredientClusterBuilder.median(listOf(300.0, 100.0, 200.0)))
    }

    @Test
    fun `median of empty is null`() {
        assertNull(IngredientClusterBuilder.median(emptyList()))
    }

    @Test
    fun `median is robust to a single outlier`() {
        // Three good samples ~270, one bad outlier from a mis-estimated weight.
        // Mean would be skewed; median stays near the true value.
        val samples = listOf(270.0, 272.0, 268.0, 900.0)
        val m = IngredientClusterBuilder.median(samples)!!
        assertTrue("median $m should stay near 270, not be dragged by 900", m in 260.0..280.0)
    }

    @Test
    fun `normalize strips accents and lowercases`() {
        assertEquals("pao de massa mae", IngredientClusterBuilder.normalize("Pão de Massa Mãe"))
        assertEquals("azeite virgem extra", IngredientClusterBuilder.normalize("Azeite Virgem Extra"))
    }

    @Test
    fun `normalize collapses whitespace`() {
        assertEquals("cafe com leite", IngredientClusterBuilder.normalize("  Café   com  leite "))
    }
}
