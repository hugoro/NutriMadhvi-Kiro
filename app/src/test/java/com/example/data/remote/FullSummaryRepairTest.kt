package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FullSummaryRepair] — deterministic gram-weight repair of
 * model-emitted `fullSummary`.
 *
 * The live regression case (food_entries 586, gemini-3.5-flash, 2026-10-05):
 * the model returned a one-line restatement of the dish name with NO gram
 * weights, while `ingredientsJson` carried correct per-ingredient
 * weightGrams (churros 30g, calda 20g, iogurte 50g, amêndoas 3g). The
 * edit screen then showed "Meia dose de churros com calda de chocolate e
 * iogurte grego aromatizado, finalizada com amêndoas laminadas." — no
 * grams, exactly what the user reported.
 */
class FullSummaryRepairTest {

    private val churrosJson = """
        [
          {"name":"Churros","weightGrams":30.0,"calories":114,"protein":1.5,"carbs":18.0,"fat":3.0},
          {"name":"Calda de chocolate","weightGrams":20.0,"calories":60,"protein":0.5,"carbs":8.0,"fat":2.5},
          {"name":"Iogurte grego aromatizado","weightGrams":50.0,"calories":55,"protein":3.0,"carbs":5.0,"fat":1.5},
          {"name":"Amêndoas laminadas","weightGrams":3.0,"calories":15,"protein":0.5,"carbs":0.7,"fat":1.1}
        ]
    """.trimIndent()

    // ── Live regression case ─────────────────────────────────────────────────

    @Test
    fun `one-liner without grams is rebuilt from ingredientsJson`() {
        val broken = "Meia dose de churros com calda de chocolate e iogurte grego aromatizado, finalizada com amêndoas laminadas."
        val repaired = FullSummaryRepair.repair(broken, churrosJson, "pt-PT")!!

        val lines = repaired.split("\n")
        assertEquals(2, lines.size)
        // Line 1 keeps the model's prose + appends the total (30+20+50+3=103)
        assertTrue("line1: ${lines[0]}", lines[0].startsWith("Meia dose de churros com calda de chocolate e iogurte grego aromatizado, finalizada com amêndoas laminadas"))
        assertTrue("line1: ${lines[0]}", lines[0].endsWith("(~103g)"))
        // Line 2 is the deterministic ingredient line with per-item weights
        assertEquals(
            "Ingredientes visíveis: Churros (~30g), Calda de chocolate (~20g), Iogurte grego aromatizado (~50g), Amêndoas laminadas (~3g)",
            lines[1]
        )
    }

    // ── Non-regression: compliant summaries pass through unchanged ──────────

    @Test
    fun `compliant 3-line summary from gemini-3-6-flash is untouched`() {
        val compliant = "Café com leite Vly e mini sandes de salmão fumado (~240 g total).\n" +
            "Ingredientes visíveis: Pão de massa mãe (~20g), salmão fumado (~15g), creme de caju light (~5g), tomate (~15g), café solúvel com bebida Vly Barista (~200g).\n" +
            "Assumido: Bebida de ervilha Vly Barista conforme padrão habitual; confiança alta."
        assertEquals(compliant, FullSummaryRepair.repair(compliant, churrosJson, "pt-PT"))
    }

    @Test
    fun `compliant summary is untouched even when ingredientsJson is null`() {
        val compliant = "Tigela de iogurte grego (~250 g) com granola e mirtilos.\nIngredientes visíveis: iogurte grego (~200g), granola (~40g).\nAssumido: iogurte natural; confiança alta."
        assertEquals(compliant, FullSummaryRepair.repair(compliant, null, "pt-PT"))
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `blank summary is returned as-is`() {
        assertEquals("", FullSummaryRepair.repair("", churrosJson, "pt-PT"))
        assertNull(FullSummaryRepair.repair(null, churrosJson, "pt-PT"))
    }

    @Test
    fun `one-liner with no usable ingredients is left unchanged`() {
        // No ingredientsJson → nothing to repair from; keep the prose.
        val broken = "Sopa de legumes com sementes de abóbora e girassol"
        assertEquals(broken, FullSummaryRepair.repair(broken, null, "pt-PT"))
        // ingredients present but all zero/invalid weights → same.
        val zeroWeights = """[{"name":"Mistério","weightGrams":0.0}]"""
        assertEquals(broken, FullSummaryRepair.repair(broken, zeroWeights, "pt-PT"))
    }

    @Test
    fun `malformed ingredientsJson degrades to no-op`() {
        val broken = "Sopa de legumes"
        assertEquals(broken, FullSummaryRepair.repair(broken, "{not valid json", "pt-PT"))
    }

    @Test
    fun `escaped literal backslash-n from Moshi is normalised before repair`() {
        // The model (or Moshi) left "\\n" as two characters; sanitizeAiNewlines
        // turns them into real newlines, and the compliant check must then
        // pass on the ingredient line. Input here contains the two-character
        // sequence (Kotlin "\\n"), NOT a real newline.
        val escapedInput = "Pastel e café (~240 g).\\nIngredientes visíveis: pastel (~60g), café (~180g)."
        val repaired = FullSummaryRepair.repair(escapedInput, null, "pt-PT")!!
        val lines = repaired.split("\n")
        assertEquals(2, lines.size)
        assertEquals("Pastel e café (~240 g).", lines[0])
        assertEquals("Ingredientes visíveis: pastel (~60g), café (~180g).", lines[1])
    }

    @Test
    fun `assumptions line present on last line is preserved as line 3`() {
        val twoLines = "Koftas de carne com cuscuz e Coca-Cola Zero\n" +
            "Assumido: sem molho adicionado; confiança média — porção difícil de estimar."
        val repaired = FullSummaryRepair.repair(twoLines, churrosJson, "pt-PT")!!
        val lines = repaired.split("\n")
        assertEquals(3, lines.size)
        assertTrue("line1: ${lines[0]}", lines[0].endsWith("(~103g)"))
        assertTrue("line2: ${lines[1]}", lines[1].startsWith("Ingredientes visíveis:"))
        assertEquals("Assumido: sem molho adicionado; confiança média — porção difícil de estimar.", lines[2])
    }

    @Test
    fun `english labels used for non-Portuguese languages`() {
        val broken = "Chicken soup"
        val repaired = FullSummaryRepair.repair(broken, churrosJson, "en")!!
        val lines = repaired.split("\n")
        assertTrue("line2: ${lines[1]}", lines[1].startsWith("Visible ingredients:"))
    }

    @Test
    fun `decimal weights keep one decimal place`() {
        val half = """[{"name":"Arroz","weightGrams":152.5}]"""
        val repaired = FullSummaryRepair.repair("Arroz", half, "pt-PT")!!
        assertTrue("repaired: $repaired", repaired.contains("Arroz (~152.5g)"))
        val lines = repaired.split("\n")
        assertTrue("line1: ${lines[0]}", lines[0].endsWith("(~152.5g)"))
    }
}
