package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleRouterTest {

    private val policy = EnsemblePolicy.DEFAULT

    private fun evidence(
        provider: String,
        name: String,
        identification: Double = 0.95,
        portion: Double = 0.90,
        specificity: Double = 0.90,
        completeness: Double = 0.95,
        consistency: Double = 0.95,
    ) = FoodAnalysisEvidence(
        provider = provider,
        result = FoodAnalysisResult(name = name),
        latencyMs = 100L,
        confidence = ConfidenceProfile(
            identification = identification,
            portion = portion,
            specificity = specificity,
            completeness = completeness,
            consistency = consistency,
        ),
    )

    @Test
    fun `a strong result is accepted without spending a second call`() {
        val decision = EnsembleRouter.gate(evidence("qwen-groq", "lentil curry"), policy)
        assertTrue(decision is RoutingDecision.Accept)
    }

    @Test
    fun `low identification confidence triggers a challenger`() {
        val decision = EnsembleRouter.gate(
            evidence("qwen-groq", "lentil curry", identification = 0.50), policy
        )
        assertTrue(decision is RoutingDecision.Challenge)
        assertTrue((decision as RoutingDecision.Challenge).reason.contains("identification"))
    }

    @Test
    fun `over-specific naming alone is enough to trigger a challenger`() {
        val decision = EnsembleRouter.gate(
            evidence("qwen-groq", "Dal Makhani", specificity = 0.30), policy
        )
        assertTrue(decision is RoutingDecision.Challenge)
        assertTrue((decision as RoutingDecision.Challenge).reason.contains("specificity"))
    }

    @Test
    fun `agreement on the canonical concept avoids paid adjudication`() {
        val bundle = AnalysisEvidenceBundle(
            primary = evidence("qwen-groq", "yellow lentil curry"),
            challengers = listOf(evidence("gemini", "lentil curry")),
            disagreements = FoodLabelCanonicalizer.compare(
                FoodAnalysisResult(name = "yellow lentil curry"),
                FoodAnalysisResult(name = "lentil curry"),
            ),
        )
        assertTrue(EnsembleRouter.fuse(bundle, policy) is RoutingDecision.Accept)
    }

    @Test
    fun `the confidence profile observed live on a real duck-vs-pork photo escalates`() {
        // Values captured from a real Qwen/Groq call on a meal photo whose
        // stored analysis said "pork" while Qwen answered "duck". Qwen was
        // honest about its uncertainty (identification 0.7), so the gate must
        // escalate rather than silently overwrite the identity.
        val decision = EnsembleRouter.gate(
            evidence(
                provider = "qwen-groq",
                name = "Roasted Duck with Rice and Vegetables",
                identification = 0.70,
                portion = 0.70,
                specificity = 0.60,
                completeness = 0.90,
                consistency = 0.95,
            ),
            policy,
        )
        assertTrue(decision is RoutingDecision.Challenge)
        assertTrue((decision as RoutingDecision.Challenge).reason.contains("identification"))
    }

    @Test
    fun `when two providers disagree with nothing else to separate them the challenger wins`() {
        // The challenger only ran because the primary failed the gate, so
        // deferring to the primary here would restore the answer we distrusted.
        val primary = evidence("qwen-groq", "roasted duck", identification = 0.70)
        val challenger = evidence("gemini", "roasted pork", identification = 0.70)
        val bundle = AnalysisEvidenceBundle(primary = primary, challengers = listOf(challenger))

        val safest = EnsembleRouter.selectSafest(
            bundle, escalationOrder = listOf("qwen-groq", "gemini")
        )
        assertEquals("gemini", safest.provider)
    }

    @Test
    fun `material disagreement escalates to adjudication`() {
        val bundle = AnalysisEvidenceBundle(
            primary = evidence("qwen-groq", "chicken breast"),
            challengers = listOf(evidence("gemini", "chicken thigh")),
            disagreements = FoodLabelCanonicalizer.compare(
                FoodAnalysisResult(name = "chicken breast"),
                FoodAnalysisResult(name = "chicken thigh"),
            ),
        )
        assertTrue(EnsembleRouter.fuse(bundle, policy) is RoutingDecision.Adjudicate)
    }

    @Test
    fun `adjudication can be disabled by policy to cap cost`() {
        val bundle = AnalysisEvidenceBundle(
            primary = evidence("qwen-groq", "chicken breast"),
            challengers = listOf(evidence("gemini", "chicken thigh")),
            disagreements = FoodLabelCanonicalizer.compare(
                FoodAnalysisResult(name = "chicken breast"),
                FoodAnalysisResult(name = "chicken thigh"),
            ),
        )
        val thrifty = policy.copy(adjudicateOnMaterialDisagreement = false)
        assertTrue(EnsembleRouter.fuse(bundle, thrifty) is RoutingDecision.Accept)
    }

    @Test
    fun `highest confidence does not automatically win against cross-model agreement`() {
        // The scenario from the design brief: a lone confident "chicken breast"
        // must not beat two models agreeing on "chicken thigh".
        val bundle = AnalysisEvidenceBundle(
            primary = evidence("qwen-groq", "chicken breast", identification = 0.96),
            challengers = listOf(
                evidence("gemini", "chicken thigh", identification = 0.91),
                evidence("minimax", "chicken thigh", identification = 0.89),
            ),
        )
        val safest = EnsembleRouter.selectSafest(bundle)
        assertEquals("chicken thigh", safest.result.name)
    }

    @Test
    fun `selecting the safest result is deterministic for identical candidates`() {
        val bundle = AnalysisEvidenceBundle(
            primary = evidence("qwen-groq", "rice"),
            challengers = listOf(evidence("gemini", "rice")),
        )
        val first = EnsembleRouter.selectSafest(bundle)
        val second = EnsembleRouter.selectSafest(bundle)
        assertEquals(first.provider, second.provider)
    }

    @Test
    fun `a lone primary is returned unchanged as the safest choice`() {
        val only = evidence("qwen-groq", "rice")
        val bundle = AnalysisEvidenceBundle(primary = only)
        assertEquals(only, EnsembleRouter.selectSafest(bundle))
    }

    @Test
    fun `no challenger means nothing to fuse`() {
        val bundle = AnalysisEvidenceBundle(primary = evidence("qwen-groq", "rice"))
        assertTrue(EnsembleRouter.fuse(bundle, policy) is RoutingDecision.Accept)
    }

    @Test
    fun `the shipping default preserves current production behaviour`() {
        assertEquals(EnsembleMode.GEMINI_ONLY, EnsemblePolicy.DEFAULT.mode)
        assertEquals(1, EnsemblePolicy.DEFAULT.maxChallengers)
    }
}
