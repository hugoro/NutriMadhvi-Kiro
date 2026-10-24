package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F1 (performance): the pt/gu/enExplicit maps used by [tr] were rebuilt on
 * EVERY call (628 call sites, many inside lazy-list row composition). They
 * are now hoisted to top-level vals built once. These tests pin the exact
 * mapping surface so the hoist is provably behaviour-identical: every key of
 * every hoisted map must resolve to the same value through `tr()` for every
 * language that consults it, and the fallback chain (pt→enExplicit→key,
 * gu→enExplicit→key, other→enExplicit→key) must be unchanged.
 */
class I18nTrHoistTest {

    @Test
    fun `pt map is identical - every key resolves through tr for pt variants`() {
        for ((key, value) in PT_MAP) {
            assertEquals("pt[$key]", value, tr(key, "pt"))
            assertEquals("pt-PT[$key]", value, tr(key, "pt-PT"))
            assertEquals("empty-lang[$key]", value, tr(key, ""))
        }
    }

    @Test
    fun `gu map is identical - every key resolves through tr for gu`() {
        for ((key, value) in GU_MAP) {
            assertEquals("gu[$key]", value, tr(key, "gu"))
        }
    }

    @Test
    fun `en explicit map is identical - every key resolves through tr for en and unknown langs`() {
        for ((key, value) in EN_EXPLICIT_MAP) {
            assertEquals("en[$key]", value, tr(key, "en"))
            assertEquals("fr-fallback[$key]", value, tr(key, "fr"))
        }
    }

    @Test
    fun `maps are non-trivial in size`() {
        // Guards against an accidental empty hoist (a silent total i18n loss).
        // Real sizes (2026-10-22): PT 657, GU ~600, EN_EXPLICIT ~150 after
        // duplicate-key collapse; keep thresholds safely below those.
        assertTrue(PT_MAP.size > 600, "PT_MAP too small: ${PT_MAP.size}")
        assertTrue(GU_MAP.size > 500, "GU_MAP too small: ${GU_MAP.size}")
        assertTrue(EN_EXPLICIT_MAP.size > 100, "EN_EXPLICIT_MAP too small: ${EN_EXPLICIT_MAP.size}")
    }

    @Test
    fun `fallback chain unchanged - unknown key returns the key for every lang`() {
        for (lang in listOf("pt", "pt-PT", "", "gu", "en", "fr", "xx")) {
            assertEquals("SomeUnknownKeyXYZ", tr("SomeUnknownKeyXYZ", lang))
        }
    }

    @Test
    fun `fallback chain - no known key ever resolves to the raw identifier`() {
        // As of 2026-10-22 every enExplicit key also has a PT and a GU
        // entry, so the pt→enExplicit and gu→enExplicit legs are currently
        // unreachable for known keys — the `else` leg (other languages) is
        // the live fallback. Pin the invariant that matters: for every
        // known key, every language resolves to a real copy, never the raw
        // key.
        for ((key, value) in EN_EXPLICIT_MAP) {
            assertEquals("fr[$key]", value, tr(key, "fr"))
            val ptRes = tr(key, "pt")
            assertTrue(
                ptRes == PT_MAP[key] || ptRes == value,
                "pt[$key] resolved to unexpected $ptRes"
            )
            val guRes = tr(key, "gu")
            assertTrue(
                guRes == GU_MAP[key] || guRes == value,
                "gu[$key] resolved to unexpected $guRes"
            )
        }
        // Unknown keys still fall through to themselves, unchanged.
        assertEquals("brand_new_key_xyz", tr("brand_new_key_xyz", "pt"))
        assertEquals("brand_new_key_xyz", tr("brand_new_key_xyz", "en"))
    }

    @Test
    fun `sample known strings`() {
        assertEquals("Painel", tr("Dashboard", "pt"))
        assertEquals("Painel", tr("Dashboard", "pt-PT"))
        assertEquals("ડેશબોર્ડ", tr("Dashboard", "gu"))
        assertEquals("Dashboard", tr("Dashboard", "en"))
        assertEquals("Pergunte sobre a sua dieta/nutrição...", tr("Ask about your diet/nutrition...", "pt"))
        assertEquals("Ver demonstração", tr("demo_view", "pt"))
    }

    @Test
    fun `microbench tr throughput informational`() {
        // Printed, not asserted: the report compares these numbers before
        // (maps rebuilt per call) vs after (single hoisted instance).
        val n = 200_000
        var acc = 0
        val start = System.nanoTime()
        repeat(n) { i ->
            acc += tr("Macronutrients", "pt").length
            acc += tr("demo_view", if (i % 2 == 0) "gu" else "en").length
            acc += tr("SomeMissingKey", "pt").length
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("I18N MICROBENCH: ${n} tr() calls in ${ms} ms (acc=$acc)")
    }

    private fun assertTrue(cond: Boolean, msg: String) {
        org.junit.Assert.assertTrue(msg, cond)
    }
}
