package com.example.data.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic safety validator tests (v40 review item 2): shape-correct but
 * UNSAFE LLM tactics must be rejected and fall back to the deterministic
 * list. Pure JVM.
 */
class TacticSafetyValidatorTest {

    private val cutPriorities = listOf(
        BodyPlanEngine.ZonePriority("waist", BodyPlanEngine.Direction.REDUCE, 2.4f, BodyPlanEngine.Tissue.FAT_DOMINANT),
        BodyPlanEngine.ZonePriority("belly", BodyPlanEngine.Direction.REDUCE, 1.9f, BodyPlanEngine.Tissue.FAT_DOMINANT),
        BodyPlanEngine.ZonePriority("shoulder", BodyPlanEngine.Direction.GROW, -1.2f, BodyPlanEngine.Tissue.MUSCLE_DOMINANT),
    )

    private fun t(category: String, text: String, target: String? = null,
                  windowDays: Int = 28, zone: String? = null) =
        TacticSafetyValidator.TacticCandidate(category, text, target, windowDays, zone)

    // ── Acceptance ───────────────────────────────────────────────────────────

    @Test
    fun `safe block passes and yields cleaned json`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(
                t("protein", "Proteína em 1.8 g/kg por dia"),
                t("calories", "Défice calórico de 15%", "1500 kcal"),
                t("zone", "Progressivo no ombro para hipertrofia", zone = "shoulder"),
                t("steps", "Manter 8000 passos/dia"),
            ),
            cutPriorities,
            weightKg = 70f,
            hasCompositionData = true,
        )
        assertNotNull("safe block must not be rejected: ${verdict.reasons}", verdict.safeJson)
        assertTrue(verdict.safeJson!!.contains("\"category\":\"protein\""))
        assertTrue(verdict.safeJson!!.contains("\"zone\":\"shoulder\""))
        assertTrue(verdict.safeJson!!.startsWith("[") && verdict.safeJson!!.endsWith("]"))
    }

    @Test
    fun `deficit at bound is accepted`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("calories", "Défice de 20% durante 8 semanas", windowDays = 56)),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNotNull("20% deficit is inside the bound: ${verdict.reasons}", verdict.safeJson)
    }

    // ── Missing / malformed / count ─────────────────────────────────────────

    @Test
    fun `null block rejected`() {
        val verdict = TacticSafetyValidator.validate(null, cutPriorities, 70f, true)
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.isNotEmpty())
    }

    @Test
    fun `empty block rejected`() {
        val verdict = TacticSafetyValidator.validate(emptyList(), cutPriorities, 70f, true)
        assertNull(verdict.safeJson)
    }

    @Test
    fun `too many tactics rejected`() {
        val many = (1..7).map { t("steps", "passo $it") }
        val verdict = TacticSafetyValidator.validate(many, cutPriorities, 70f, true)
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.first().contains("tactic count"))
    }

    @Test
    fun `unknown category rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("cardio", "correr todos os dias")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("unknown category"))
    }

    // ── Zone semantics (the review's core ask) ───────────────────────────────

    @Test
    fun `reduce advice for muscle-dominant zone rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("zone", "Reduzir o volume no ombro", zone = "shoulder")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull("REDUCE on muscle-dominant shoulder must be rejected: ${verdict.safeJson}", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("muscle-dominant"))
    }

    @Test
    fun `grow advice for fat-dominant zone rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("zone", "Aumentar a cintura para ganho de massa", zone = "waist")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("fat-dominant"))
    }

    @Test
    fun `unknown zone key rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("zone", "Reducir el cuello", zone = "neck")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("unknown zone"))
    }

    @Test
    fun `zone tactic without zone key rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("zone", "Trabalhar o core para reduzir")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("zone key"))
    }

    @Test
    fun `direction conflicting with persisted priority rejected`() {
        // Persisted priority holds belly at HOLD; LLM tries to REDUCE it
        // (REDUCE on a fat-dominant zone is tissue-allowed, so this exercises
        // the persisted-priority conflict check specifically).
        val verdict = TacticSafetyValidator.validate(
            listOf(t("zone", "Reduzir a barriga", zone = "belly")),
            listOf(
                BodyPlanEngine.ZonePriority("belly", BodyPlanEngine.Direction.HOLD, 0.4f, BodyPlanEngine.Tissue.FAT_DOMINANT),
            ),
            weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("conflicts with persisted"))
    }

    @Test
    fun `zone tactic agreeing with priority accepted`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(
                t("zone", "Défice moderado e core para reduzir a cintura", zone = "waist"),
                t("zone", "Hipertrofia de ombros com progressão", zone = "shoulder"),
            ),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNotNull("agreeing zone tactics must pass: ${verdict.reasons}", verdict.safeJson)
    }

    @Test
    fun `directional zone advice without composition data rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("zone", "Reduzir a barriga", zone = "belly")),
            emptyList(), weightKg = null, hasCompositionData = false,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("composition data"))
    }

    // ── Protein bounds ───────────────────────────────────────────────────────

    @Test
    fun `protein g per kg above max rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("protein", "Proteína em 3.0 g/kg")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("g/kg outside"))
    }

    @Test
    fun `protein g per kg below min rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("protein", "Proteína em 1.2 g/kg")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("g/kg outside"))
    }

    @Test
    fun `protein g per day grounded against anchored weight`() {
        // 70 kg → conservative 112..154 g/day
        val ok = TacticSafetyValidator.validate(
            listOf(t("protein", "130 g por dia")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNotNull(ok.safeJson)

        val tooHigh = TacticSafetyValidator.validate(
            listOf(t("protein", "220 g por dia")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(tooHigh.safeJson)
        assertTrue(tooHigh.reasons.joinToString().contains("g/day outside"))

        val ungrounded = TacticSafetyValidator.validate(
            listOf(t("protein", "150 g por dia")),
            cutPriorities, weightKg = null, hasCompositionData = false,
        )
        assertNull(ungrounded.safeJson)
        assertTrue(ungrounded.reasons.joinToString().contains("ungrounded"))
    }

    // ── Calorie bounds ───────────────────────────────────────────────────────

    @Test
    fun `calorie deficit outside 10 to 20 percent rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("calories", "Défice calórico de 40%")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("deficit 40.0% outside"))
    }

    @Test
    fun `calorie surplus outside 5 to 10 percent rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("calories", "Superávit de 25% para crescer")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("surplus 25.0% outside"))
    }

    @Test
    fun `pct without deficit or surplus context rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("calories", "Reduzir as calorias em 15%")),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("no deficit/surplus context"))
    }

    // ── Generic bounds ───────────────────────────────────────────────────────

    @Test
    fun `window days outside 7 to 120 rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("steps", "8000 passos", windowDays = 3)),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("windowDays"))
    }

    @Test
    fun `oversized text rejected`() {
        val verdict = TacticSafetyValidator.validate(
            listOf(t("steps", "x".repeat(250))),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("chars"))
    }

    @Test
    fun `one violation rejects the whole block not just the tactic`() {
        // First tactic safe, second unsafe → nothing may be persisted from
        // the LLM (the caller stores the deterministic fallback instead).
        val verdict = TacticSafetyValidator.validate(
            listOf(
                t("protein", "Proteína em 1.8 g/kg por dia"),
                t("calories", "Défice calórico de 40%"),
            ),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNull(verdict.safeJson)
        assertEquals(1, verdict.reasons.size)
    }
}
