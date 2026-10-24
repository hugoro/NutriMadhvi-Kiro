package com.example.data.performance

import com.example.ui.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v43 (2026-10) — vertical proof for the local curated training-demo
 * catalog:
 *  - LOCAL, curated, STABLE IDs (the AI may only pick from the allowlist,
 *    never invent exercises/URLs/loads),
 *  - ≥3 safe cards covering strength / activity / recovery,
 *  - every card: local illustration, name+region, sets-reps or duration,
 *    short cue, progression, curated video (https, allowlisted host),
 *  - copy never promises spot fat loss and never references a camera,
 *  - the prompt builder offers ONLY the curated demo IDs,
 *  - the safety validator fails closed on unknown demoId and on any URL
 *    in the tactic text.
 *
 * Pure JVM — no Android runtime needed (illustrations are referenced by
 * resource NAME here; the UI maps name → R.drawable).
 */
class TrainingDemoCatalogTest {

    private val langs = listOf("pt", "en", "gu")
    private val fields = listOf("name", "prescription", "cue", "progression")

    // ── Catalog shape ───────────────────────────────────────────────────────

    @Test
    fun catalog_has_at_least_one_safe_card_per_kind() {
        val all = TrainingDemoCatalog.ALL
        assertTrue("need >= 3 cards", all.size >= 3)
        assertTrue("need a STRENGTH card",
            all.any { it.kind == TrainingDemoCatalog.DemoKind.STRENGTH })
        assertTrue("need an ACTIVITY card",
            all.any { it.kind == TrainingDemoCatalog.DemoKind.ACTIVITY })
        assertTrue("need a RECOVERY card",
            all.any { it.kind == TrainingDemoCatalog.DemoKind.RECOVERY })
    }

    @Test
    fun catalog_ids_are_stable_unique_snake_case() {
        val ids = TrainingDemoCatalog.ALL.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue("id '$id' not stable snake_case",
                Regex("^[a-z][a-z0-9_]{2,40}$").matches(id))
            assertTrue("id '$id' not in the allowed set",
                TrainingDemoCatalog.ALLOWED_DEMO_IDS.contains(id))
        }
        assertEquals("allowlist must equal the catalog ids",
            ids.toSet(), TrainingDemoCatalog.ALLOWED_DEMO_IDS)
    }

    @Test
    fun every_entry_has_local_illustration_and_curated_https_video() {
        for (d in TrainingDemoCatalog.ALL) {
            assertTrue("illustration name blank for ${d.id}", d.illustrationName.isNotBlank())
            assertTrue("illustration name malformed: ${d.illustrationName}",
                d.illustrationName.matches(Regex("^demo_[a-z0-9_]+$")))
            val u = java.net.URI(d.videoUrl)
            assertEquals("video must be https (${d.id})", "https", u.scheme)
            assertTrue("video host not allowlisted (${d.id}): ${u.host}",
                u.host in setOf("www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"))
        }
    }

    @Test
    fun lookup_by_id_roundtrips_and_rejects_unknown() {
        for (d in TrainingDemoCatalog.ALL) {
            assertEquals(d, TrainingDemoCatalog.byId(d.id))
        }
        assertEquals(null, TrainingDemoCatalog.byId("nope_not_real"))
    }

    // ── Localized copy (PT / EN / GU) ───────────────────────────────────────

    @Test
    fun localized_copy_exists_for_all_fields_and_languages() {
        for (d in TrainingDemoCatalog.ALL) {
            for (f in fields) {
                val key = "demo_${d.id}_$f"
                for (lang in langs) {
                    val v = tr(key, lang)
                    assertNotEquals("missing i18n: $key ($lang)", key, v)
                    assertTrue("blank i18n: $key ($lang)", v.isNotBlank())
                }
                // PT must differ from EN (a real PT translation, not a fall-through),
                // and GU must contain actual Gujarati script.
                assertNotEquals("PT copy not distinct from EN: $key",
                    tr(key, "en"), tr(key, "pt"))
                assertTrue("GU copy lacks Gujarati script: $key",
                    tr(key, "gu").any { it in '\u0A80'..'\u0AFF' })
            }
        }
    }

    @Test
    fun copy_never_promises_spot_fat_loss_and_never_mentions_camera() {
        val banned = listOf(
            "perder gordura local", "queimar gordura local", "gordura localizada",
            "reduzir gordura local", "eliminar gordura", "spot reduction",
            "melt", "melting", "burn fat", "fat loss",
            "c\u00e2mara", "camera", "webcam", "webcam", "fotoc\u00e2mara",
        )
        for (d in TrainingDemoCatalog.ALL) {
            for (f in fields) {
                for (lang in langs) {
                    val v = tr("demo_${d.id}_$f", lang).lowercase()
                    for (b in banned.distinct()) {
                        assertFalse("$b found in ${d.id}/$f/$lang → '$v'", v.contains(b))
                    }
                }
            }
        }
    }

    // ── Prompt: the AI may only pick curated IDs ────────────────────────────

    @Test
    fun prompt_offers_only_curated_demo_ids_and_forbids_inventing() {
        val p = BodyPlanPromptBuilder.buildTacticsPrompt(
            phase = null,
            priorities = emptyList(),
            goalText = null,
            latestComp = null,
            name = "X",
            lang = "pt",
        )
        for (id in TrainingDemoCatalog.ALLOWED_DEMO_IDS) {
            assertTrue("prompt missing curated id $id", p.contains(id))
        }
        assertTrue("prompt must document the demoId field", p.contains("demoId"))
        assertFalse("old blanket ban contradicts curated demos",
            p.contains("Sem links, sem v\u00eddeos"))
        assertTrue("prompt must forbid inventing exercises/URLs/loads",
            p.lowercase().contains("nunca inventes") || p.lowercase().contains("never invent"))
    }

    // ── Validator: demoId allowlist (fail closed) ───────────────────────────

    private fun validStrengthTactic(demoId: String?) =
        com.example.data.performance.TacticSafetyValidator.TacticCandidate(
            category = "strength",
            text = "Faz agachamentos com o corpo todo e boa postura",
            target = "3 s\u00e9ries de 12 repeti\u00e7\u00f5es",
            windowDays = 28,
            zone = null,
            demoId = demoId,
        )

    @Test
    fun tactic_with_valid_demo_id_is_accepted_and_serialized() {
        val card = TrainingDemoCatalog.ALL.first { it.kind == TrainingDemoCatalog.DemoKind.STRENGTH }
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(validStrengthTactic(card.id)),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertNull("blockReason: ${verdict.blockReason}", verdict.blockReason)
        assertEquals("safe tactics: ${verdict.rejected}", 1, verdict.safe.size)
        assertEquals(card.id, verdict.safe.first().demoId)
        assertTrue("cleaned json must carry demoId: ${verdict.safeJson}",
            verdict.safeJson!!.contains("\"demoId\":\"${card.id}\""))
    }

    @Test
    fun tactic_with_unknown_demo_id_is_rejected_fail_closed() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(validStrengthTactic("burpee_super_x")),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertTrue("unknown demoId must not survive", verdict.safe.isEmpty())
        assertEquals(1, verdict.rejected.size)
        assertTrue("reasons: ${verdict.rejected[0].reasons}",
            verdict.rejected[0].reasons.any { it.contains("demoId") })
    }

    @Test
    fun tactic_without_demo_id_still_accepted_and_json_has_no_demo_id() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(validStrengthTactic(null)),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertEquals(1, verdict.safe.size)
        assertFalse("no demoId key expected: ${verdict.safeJson}",
            verdict.safeJson!!.contains("demoId"))
    }

    @Test
    fun tactic_text_with_url_is_rejected() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(
                TacticSafetyValidator.TacticCandidate(
                    category = "steps",
                    text = "Caminha todos os dias e segue o v\u00eddeo em https://youtu.be/xyz",
                    target = "30 minutos",
                    windowDays = 28,
                    zone = null,
                ),
            ),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertTrue("URL in text must not survive", verdict.safe.isEmpty())
        assertTrue("reasons: ${verdict.rejected[0].reasons}",
            verdict.rejected[0].reasons.any { it.contains("demoId") || it.contains("URL") })
    }

    // ── v43.1: demoId must be COMPATIBLE with the tactic ────────────────────
    // Category fit + region fit. A lower-body demo on an upper-body strength
    // tactic is misleading guidance → fail closed.

    @Test
    fun upper_body_strength_tactic_with_lower_body_demo_is_rejected() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(
                TacticSafetyValidator.TacticCandidate(
                    category = "strength",
                    text = "Realiza treinos de for\u00e7a focados nos membros superiores para estimular o crescimento",
                    target = "3 sess\u00f5es/semana",
                    windowDays = 28,
                    zone = null,
                    demoId = "squat_bodyweight",
                ),
            ),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertTrue("upper-body strength + squat demo must not survive: ${verdict.rejected}",
            verdict.safe.isEmpty())
        assertTrue("reasons: ${verdict.rejected[0].reasons}",
            verdict.rejected[0].reasons.any { it.contains("demoId") })
    }

    @Test
    fun zone_tactic_upper_body_zone_with_lower_body_demo_is_rejected() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(
                TacticSafetyValidator.TacticCandidate(
                    category = "zone",
                    text = "Aumenta o volume e a intensidade nos exerc\u00edcios espec\u00edficos para o desenvolvimento dos ombros",
                    target = "AUMENTAR",
                    windowDays = 28,
                    zone = "shoulder",
                    demoId = "squat_bodyweight",
                ),
            ),
            priorities = listOf(
                BodyPlanEngine.ZonePriority(
                    "shoulder", BodyPlanEngine.Direction.GROW, -27.2f,
                    BodyPlanEngine.Tissue.MUSCLE_DOMINANT,
                ),
            ),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertTrue("shoulder zone + squat demo must not survive: ${verdict.rejected}",
            verdict.safe.isEmpty())
        assertTrue("reasons: ${verdict.rejected[0].reasons}",
            verdict.rejected[0].reasons.any { it.contains("demoId") })
    }

    @Test
    fun steps_tactic_with_strength_demo_is_rejected_category_mismatch() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(
                TacticSafetyValidator.TacticCandidate(
                    category = "steps",
                    text = "Acumula passos di\u00e1rios para garantir o gasto energ\u00e9tico base",
                    target = "8000 passos",
                    windowDays = 28,
                    zone = null,
                    demoId = "squat_bodyweight",
                ),
            ),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertTrue("steps + squat demo must not survive: ${verdict.rejected}",
            verdict.safe.isEmpty())
        assertTrue("reasons: ${verdict.rejected[0].reasons}",
            verdict.rejected[0].reasons.any { it.contains("demoId") })
    }

    @Test
    fun recovery_demo_on_sleep_tactic_is_accepted() {
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(
                TacticSafetyValidator.TacticCandidate(
                    category = "sleep",
                    text = "Dorme o suficiente para otimizar a recupera\u00e7\u00e3o f\u00edsica",
                    target = "7\u20138 horas por noite",
                    windowDays = 28,
                    zone = null,
                    demoId = "hip_flexor_stretch",
                ),
            ),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertEquals("recovery demo should fit a sleep tactic: ${verdict.rejected}",
            1, verdict.safe.size)
    }

    @Test
    fun lower_body_strength_tactic_with_lower_body_demo_still_accepted() {
        // regression guard: the intended pairing must keep working
        val verdict = TacticSafetyValidator.validate(
            candidates = listOf(validStrengthTactic("squat_bodyweight")),
            priorities = emptyList(),
            weightKg = 80f,
            hasCompositionData = true,
        )
        assertEquals("intended pairing must survive: ${verdict.rejected}", 1, verdict.safe.size)
    }

    @Test
    fun catalog_declares_compatible_categories_for_every_entry() {
        for (d in TrainingDemoCatalog.ALL) {
            assertTrue("missing compatibleCategories for ${d.id}",
                d.compatibleCategories.isNotEmpty())
            for (c in d.compatibleCategories) {
                assertTrue("unknown category for ${d.id}: $c",
                    c in TacticSafetyValidator.ALLOWED_CATEGORIES)
            }
        }
        assertTrue(TrainingDemoCatalog.byId("squat_bodyweight")!!
            .compatibleCategories.contains("strength"))
        assertTrue(TrainingDemoCatalog.byId("brisk_walk")!!
            .compatibleCategories.contains("steps"))
        assertTrue(TrainingDemoCatalog.byId("hip_flexor_stretch")!!
            .compatibleCategories.contains("sleep"))
    }

    // ── v43.2: runtime URL guard (openDemoVideo must not launch anything
    //    that is not https + allowlisted host — fail closed) ────────────────

    @Test
    fun curated_youtube_urls_pass_the_runtime_guard() {
        // every catalog URL must pass — the guard and the catalog agree
        for (d in TrainingDemoCatalog.ALL) {
            assertTrue("curated URL rejected: ${d.videoUrl}",
                TrainingDemoCatalog.isSafeDemoUrl(d.videoUrl))
        }
        assertTrue(TrainingDemoCatalog.isSafeDemoUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(TrainingDemoCatalog.isSafeDemoUrl("https://youtu.be/abc"))
        assertTrue(TrainingDemoCatalog.isSafeDemoUrl("https://m.youtube.com/watch?v=abc"))
        assertTrue(TrainingDemoCatalog.isSafeDemoUrl("https://youtube.com/watch?v=abc"))
    }

    @Test
    fun http_urls_are_rejected_by_the_runtime_guard() {
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("http://www.youtube.com/watch?v=abc"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("HTTP://WWW.YOUTUBE.COM/watch?v=abc"))
    }

    @Test
    fun non_allowlisted_hosts_are_rejected_by_the_runtime_guard() {
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("https://evil.example.com/watch?v=abc"))
        // lookalike domain must not match the allowlist
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("https://youtube.com.evil.com/watch?v=abc"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("https://www.youtube.com.evil.com/watch?v=abc"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("https://notyoutube.com/watch?v=abc"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("https://youtube.com@evil.com/watch?v=abc"))
    }

    @Test
    fun malformed_and_non_http_urls_are_rejected_by_the_runtime_guard() {
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl(""))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("not a url"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("https://"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("javascript:alert(1)"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("file:///data/local/tmp/x.html"))
        assertFalse(TrainingDemoCatalog.isSafeDemoUrl("intent://youtube#Intent;end"))
    }
}
