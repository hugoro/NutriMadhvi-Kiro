package com.example.data.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `mixed block keeps the safe tactic and only replaces the rejected one`() {
        // v42 (2026-10) granularity: the old behaviour rejected the WHOLE
        // block on a single bad tactic, discarding the safe ones. Now the
        // validator returns the safe subset (safeJson) plus the per-tactic
        // rejections; the caller swaps only the rejected categories for the
        // deterministic equivalent.
        val verdict = TacticSafetyValidator.validate(
            listOf(
                t("protein", "Proteína em 1.8 g/kg por dia"),
                t("calories", "Défice calórico de 40%"),
            ),
            cutPriorities, weightKg = 70f, hasCompositionData = true,
        )
        assertNotNull("the safe protein tactic must survive: ${verdict.reasons}", verdict.safeJson)
        assertTrue(verdict.safeJson!!.contains("\"category\":\"protein\""))
        assertFalse("the unsafe 40% tactic must not be in the safe subset", verdict.safeJson!!.contains("40%"))
        assertEquals("exactly one per-tactic rejection", 1, verdict.reasons.size)
    }

    // ── v41 adversarial: EVERY numeric health prescription is bounded ───────

    /** Runs one tactic through the validator and returns the verdict. */
    private fun validateOne(category: String, text: String, target: String? = null,
                            zone: String? = null) =
        TacticSafetyValidator.validate(
            listOf(t(category, text, target, 28, zone)),
            cutPriorities, weightKg = 70f, hasCompositionData = true)

    @Test
    fun `absolute 500 kcal day rejected - starve level without bounded semantics`() {
        val verdict = validateOne("calories", "Comer apenas 500 kcal por dia")
        assertNull("500 kcal/day is below the absolute intake floor", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("kcal"))
    }

    @Test
    fun `absolute calorie deficit outside moderate band rejected`() {
        // 800 kcal/day deficit exceeds the conservative 250..600 band.
        val verdict = validateOne("calories", "Défice de 800 kcal por dia")
        assertNull(verdict.safeJson)
    }

    @Test
    fun `moderate absolute deficit accepted`() {
        val verdict = validateOne("calories", "Défice de 500 kcal por dia")
        assertNotNull("500 kcal/day is a moderate deficit: ${verdict.reasons}", verdict.safeJson)
    }

    @Test
    fun `extreme daily steps rejected`() {
        val verdict = validateOne("steps", "Caminhar 1500000 passos por dia")
        assertNull("1,500,000 steps/day is unbounded", verdict.safeJson)
    }

    @Test
    fun `thousands separated steps obfuscation rejected`() {
        // pt-BR thousands separators must NOT hide the magnitude.
        val verdict = validateOne("steps", "Caminhar 1.500.000 passos por dia")
        assertNull("1.500.000 steps/day must be decoded as 1500000", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("1500000.0"))
    }

    @Test
    fun `sleep hours outside safe band rejected`() {
        assertNull("0 hours of sleep", validateOne("sleep", "Durma 0 horas por noite").safeJson)
        assertNull("24 hours of sleep", validateOne("sleep", "Durma 24 horas por noite").safeJson)
        assertNull("3 hours of sleep", validateOne("sleep", "Durma 3 horas por noite").safeJson)
        assertNotNull("8 hours of sleep is fine", validateOne("sleep", "Durma 8 horas por noite").safeJson)
    }

    @Test
    fun `overtraining frequency rejected`() {
        val verdict = validateOne("strength", "Treinar 10 vezes por semana")
        assertNull("10 sessions/week exceeds 5", verdict.safeJson)
    }

    @Test
    fun `excessive sets rejected`() {
        // "10x" hits the sets×reps volume branch — the sets half fails.
        val verdict = validateOne("strength", "Fazer 10x10 de supino")
        assertNull("10 sets exceeds the bound", verdict.safeJson)
    }

    @Test
    fun `volume reps half rejected`() {
        val verdict = validateOne("strength", "Fazer 4x30 de agachamento")
        assertNull("30 reps exceeds the bound", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("reps 30"))
    }

    @Test
    fun `intensity pct1rm out of band rejected`() {
        assertNull("95% of 1RM is maximal loading",
            validateOne("strength", "Supino a 95% do seu 1RM").safeJson)
        assertNotNull("70% of 1RM is routine intensity",
            validateOne("strength", "Supino a 70% do seu 1RM").safeJson)
    }

    @Test
    fun `contradictory reduce and grow direction rejected`() {
        val verdict = validateOne("zone", "Reduzir a gordura e aumentar a massa da cintura", zone = "waist")
        assertNull("reduce+grow in one tactic is a contradiction, never a HOLD", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("contradictory"))
    }

    @Test
    fun `unrecognized numeric prescription fails closed`() {
        val verdict = validateOne("strength", "Elevar 37,5 kg no supino")
        assertNull("37,5 kg is an unrecognized numeric prescription", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("unrecognized"))
    }

    @Test
    fun `protein without bounded figure rejected`() {
        val verdict = validateOne("protein", "Aumentar a proteína")
        assertNull("no grams figure at all", verdict.safeJson)
    }

    // ── v42 (2026-10): false-positive fixes — intervals, frequency, rounding ─
    // Real Pixel evidence (checkpoint #1): the validator rejected (a) a safe
    // sleep interval "7–8 horas/noite" (halves scanned as separate numbers),
    // (b) "2 sessões/semana" (unrecognized frequency context), and (c) a
    // protein value 2 g below the computed floor (LLM rounding). All three
    // must pass, while genuinely unsafe variants stay rejected (fail closed).

    @Test
    fun `safe sleep interval with en-dash accepted`() {
        val verdict = validateOne("sleep", "Durma 7–8 horas por noite")
        assertNotNull("7–8 h/night is inside the sleep band: ${verdict.reasons}", verdict.safeJson)
    }

    @Test
    fun `safe sleep interval with hyphen and word accepted`() {
        assertNotNull(validateOne("sleep", "Durma 7-8 horas por noite").safeJson)
        assertNotNull(validateOne("sleep", "Durma 7 a 8 horas por noite").safeJson)
    }

    @Test
    fun `unsafe sleep interval rejected`() {
        val verdict = validateOne("sleep", "Durma 11–12 horas por noite")
        assertNull("12 h exceeds the sleep band", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("interval"))
    }

    @Test
    fun `session frequency accepted in pt and en`() {
        assertNotNull("2 sessões/semana", validateOne("strength", "Treinar 2 sessões/semana").safeJson)
        assertNotNull("3 sessions per week", validateOne("strength", "Train 3 sessions per week").safeJson)
        assertNotNull("2–3 sessões/semana range", validateOne("strength", "Treinar 2–3 sessões/semana").safeJson)
    }

    @Test
    fun `session frequency above five rejected`() {
        val verdict = validateOne("strength", "Treinar 6 sessões/semana")
        assertNull("6 sessions/week exceeds 5", verdict.safeJson)
    }

    @Test
    fun `protein rounded near the computed lower bound accepted`() {
        // 82.5 kg → conservative 132..181 g/day; the LLM rounds 132→130.
        // Rounding tolerance only — never a relaxation of the clinical band.
        val verdict = TacticSafetyValidator.validate(
            listOf(t("protein", "130 g/dia")),
            cutPriorities, weightKg = 82.5f, hasCompositionData = true,
        )
        assertNotNull("130 g/day is a rounding of the 132 g lower bound: ${verdict.reasons}", verdict.safeJson)
    }

    @Test
    fun `protein materially below or above the band still rejected`() {
        val low = TacticSafetyValidator.validate(
            listOf(t("protein", "100 g/dia")),
            cutPriorities, weightKg = 82.5f, hasCompositionData = true,
        )
        assertNull("100 g/day is well below the 132 g lower bound", low.safeJson)
        val high = TacticSafetyValidator.validate(
            listOf(t("protein", "200 g/dia")),
            cutPriorities, weightKg = 82.5f, hasCompositionData = true,
        )
        assertNull("200 g/day is well above the 181 g upper bound", high.safeJson)
    }

    @Test
    fun `interval in unrecognized unit context fails closed`() {
        val verdict = validateOne("strength", "Elevar 37–39 kg no supino")
        assertNull("a kg interval is not a recognized prescription", verdict.safeJson)
        assertTrue(verdict.reasons.joinToString().contains("interval"))
    }
}
