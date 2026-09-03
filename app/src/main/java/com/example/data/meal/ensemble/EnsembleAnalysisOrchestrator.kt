package com.example.data.meal.ensemble

import android.util.Log

/**
 * Executes the confidence-gated ensemble.
 *
 * Responsibility boundary: this class decides *which model answer to trust*.
 * It deliberately does not enrich nutrition, touch Room, or emit UI events —
 * the caller feeds the winning [FoodAnalysisEvidence] into the existing
 * `enrichResult()` → `NutritionLookupService` → `MacroPlausibilityGate`
 * pipeline exactly as before.
 *
 * Cost discipline: every stage past the first is a paid API call on the
 * user's own account, so escalation is strictly selective — one challenger
 * only when the gate fails, and adjudication only on *material*
 * disagreement. The pure decision rules live in [EnsembleRouter] so they can
 * be exercised in unit tests without spending anything.
 */
class EnsembleAnalysisOrchestrator(
    private val primary: FoodVisionProvider,
    private val challenger: FoodVisionProvider,
    private val adjudicator: FoodVisionProvider? = null,
    private val policy: EnsemblePolicy = EnsemblePolicy.DEFAULT,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {

    /**
     * Providers ordered by escalation, least to most trusted. Used only to
     * break ties in [EnsembleRouter.selectSafest]: the challenger runs because
     * the primary was not trusted, so it wins an otherwise-even contest.
     */
    private val escalationOrder: List<String> = listOf(primary.id, challenger.id)

    /**
     * Runs the ensemble for one meal image.
     *
     * Returns null only when nothing usable was produced at all; the caller
     * then falls back to its existing error/salvage path, leaving user state
     * untouched.
     */
    suspend fun analyze(request: FoodVisionRequest): EnsembleResult? {
        val attempts = mutableListOf<FoodVisionOutcome.Failure>()

        // Stage 1 — primary.
        val primaryEvidence = when (val outcome = runProvider(primary, request, attempts)) {
            is FoodVisionOutcome.Success -> outcome.evidence
            else -> null
        }

        if (primaryEvidence == null) {
            // Primary failed outright: go straight to the challenger rather
            // than failing the whole analysis. This is the "Qwen fails ->
            // Gemini directly" rule.
            logger("primary ${primary.id} failed; falling back to ${challenger.id}")
            val fallback = runProvider(challenger, request, attempts)
            return (fallback as? FoodVisionOutcome.Success)?.let {
                EnsembleResult(
                    evidence = it.evidence,
                    bundle = AnalysisEvidenceBundle(primary = it.evidence),
                    decisionPath = listOf("primary_failed", "challenger_accepted"),
                    failures = attempts.toList(),
                )
            } ?: run {
                logger("all providers failed (${attempts.joinToString { "${it.provider}:${it.kind}" }})")
                null
            }
        }

        // Stage 2 — accept gate.
        val gate = EnsembleRouter.gate(primaryEvidence, policy)
        if (gate is RoutingDecision.Accept) {
            logger("accepted ${primary.id} (${gate.reason}) in ${primaryEvidence.latencyMs}ms")
            return EnsembleResult(
                evidence = primaryEvidence,
                bundle = AnalysisEvidenceBundle(primary = primaryEvidence),
                decisionPath = listOf("gate_accept"),
                failures = attempts.toList(),
            )
        }
        logger("gate failed for ${primary.id}: ${(gate as RoutingDecision.Challenge).reason}")

        // Stage 3 — one challenger.
        if (policy.maxChallengers < 1) {
            return EnsembleResult(
                evidence = primaryEvidence,
                bundle = AnalysisEvidenceBundle(primary = primaryEvidence),
                decisionPath = listOf("gate_failed", "challengers_disabled"),
                failures = attempts.toList(),
            )
        }

        val challengerEvidence = when (val outcome = runProvider(challenger, request, attempts)) {
            is FoodVisionOutcome.Success -> outcome.evidence
            else -> null
        }
        if (challengerEvidence == null) {
            // Challenger unavailable: keep the primary's answer. The user still
            // gets usable numbers rather than an error.
            logger("challenger ${challenger.id} unavailable; keeping ${primary.id}")
            return EnsembleResult(
                evidence = primaryEvidence,
                bundle = AnalysisEvidenceBundle(primary = primaryEvidence),
                decisionPath = listOf("gate_failed", "challenger_failed", "kept_primary"),
                failures = attempts.toList(),
            )
        }

        // Stage 4 — evidence fusion.
        val disagreements = FoodLabelCanonicalizer.compare(
            primaryEvidence.result, challengerEvidence.result
        )
        val bundle = AnalysisEvidenceBundle(
            primary = primaryEvidence,
            challengers = listOf(challengerEvidence),
            disagreements = disagreements,
        )

        when (val fused = EnsembleRouter.fuse(bundle, policy)) {
            is RoutingDecision.Accept -> {
                // Consensus (or policy) says stop. Prefer the safest of the two
                // rather than blindly keeping the primary, since the gate had
                // already flagged the primary as weak.
                val winner = EnsembleRouter.selectSafest(bundle, escalationOrder)
                logger("consensus accept via ${winner.provider} (${fused.reason})")
                return EnsembleResult(
                    evidence = winner,
                    bundle = bundle,
                    decisionPath = listOf("gate_failed", "challenger_ran", "consensus_accept"),
                    failures = attempts.toList(),
                )
            }

            is RoutingDecision.Adjudicate -> {
                logger("adjudicating: ${fused.reason}")
                val judged = runAdjudication(request, bundle, attempts)
                if (judged != null) {
                    return EnsembleResult(
                        evidence = judged,
                        bundle = bundle,
                        decisionPath = listOf("gate_failed", "challenger_ran", "adjudicated"),
                        failures = attempts.toList(),
                    )
                }
                // Adjudicator failed — never corrupt state, fall back to the
                // safest already-validated answer we hold.
                val safest = EnsembleRouter.selectSafest(bundle, escalationOrder)
                logger("adjudication failed; falling back to safest=${safest.provider}")
                return EnsembleResult(
                    evidence = safest,
                    bundle = bundle,
                    decisionPath = listOf("gate_failed", "challenger_ran", "adjudication_failed", "safest"),
                    failures = attempts.toList(),
                )
            }

            else -> {
                val safest = EnsembleRouter.selectSafest(bundle, escalationOrder)
                return EnsembleResult(
                    evidence = safest,
                    bundle = bundle,
                    decisionPath = listOf("gate_failed", "challenger_ran", "safest"),
                    failures = attempts.toList(),
                )
            }
        }
    }

    private suspend fun runAdjudication(
        request: FoodVisionRequest,
        bundle: AnalysisEvidenceBundle,
        attempts: MutableList<FoodVisionOutcome.Failure>,
    ): FoodAnalysisEvidence? {
        val judge = adjudicator ?: return null
        if (!judge.isAvailable()) return null
        // The adjudicator must see the ORIGINAL image, not just the two
        // structured answers — otherwise it is only refereeing text.
        if (!request.hasImage) return null

        val challengerEvidence = bundle.challengers.firstOrNull() ?: return null
        val adjudicationHint = EnsemblePrompts.adjudicationInstructions(
            primaryProvider = bundle.primary.provider,
            primaryJson = summarize(bundle.primary),
            challengerProvider = challengerEvidence.provider,
            challengerJson = summarize(challengerEvidence),
            disagreements = bundle.disagreements,
        )

        val judgedRequest = request.copy(
            promptHint = listOfNotNull(request.promptHint, adjudicationHint)
                .joinToString("\n")
        )
        return (runProvider(judge, judgedRequest, attempts) as? FoodVisionOutcome.Success)?.evidence
    }

    /** Compact, PII-free rendering of a candidate answer for the adjudicator. */
    private fun summarize(evidence: FoodAnalysisEvidence): String {
        val r = evidence.result
        val ingredients = r.ingredients.orEmpty().joinToString(", ") {
            "${it.name} ${"%.0f".format(it.weightGrams)}g"
        }
        return """{"name":"${r.name.orEmpty()}","calories":${r.calories ?: 0},""" +
            """"ingredients":"$ingredients"}"""
    }

    private suspend fun runProvider(
        provider: FoodVisionProvider,
        request: FoodVisionRequest,
        attempts: MutableList<FoodVisionOutcome.Failure>,
    ): FoodVisionOutcome {
        if (!provider.isAvailable()) {
            val failure = FoodVisionOutcome.Failure(
                provider = provider.id,
                kind = FoodVisionErrorKind.NOT_CONFIGURED,
                message = "provider not configured",
            )
            attempts += failure
            return failure
        }
        val outcome = provider.analyze(request)
        if (outcome is FoodVisionOutcome.Failure) attempts += outcome
        return outcome
    }

    private companion object {
        const val TAG = "MealEnsemble"
    }
}

/**
 * Outcome of one ensemble run. [evidence] is the answer to feed into the
 * existing nutrition pipeline; the rest is runtime-only diagnostic context
 * that is never persisted.
 */
data class EnsembleResult(
    val evidence: FoodAnalysisEvidence,
    val bundle: AnalysisEvidenceBundle,
    val decisionPath: List<String>,
    val failures: List<FoodVisionOutcome.Failure> = emptyList(),
) {
    val providerCallCount: Int get() = 1 + bundle.challengers.size
}
