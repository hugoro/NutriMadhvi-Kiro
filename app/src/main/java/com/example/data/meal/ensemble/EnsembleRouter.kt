package com.example.data.meal.ensemble

/** Which providers run, and in what role. Defaults preserve production behaviour. */
enum class EnsembleMode {
    /** Current shipping behaviour: Gemini streaming only, no ensemble. */
    GEMINI_ONLY,

    /** Qwen/Groq only — benchmarking mode. */
    QWEN_ONLY,

    /** Qwen primary, Gemini as fallback on failure/low confidence. */
    QWEN_THEN_GEMINI,

    /** Full pipeline: Qwen primary → selective Gemini challenger → Qwen adjudication. */
    QWEN_ENSEMBLE,

    /**
     * Bitdeer Qwen3-VL-32B as primary, Gemini as fallback on failure / low
     * confidence. The ensemble gate and challenger machinery applies normally —
     * when Bitdeer clears the gate the result is accepted; otherwise Gemini runs
     * as challenger.
     *
     * Phase 9 of the multimodel plan. Default remains [GEMINI_ONLY] until
     * Bitdeer is validated on real photos.
     */
    BITDEER_THEN_GEMINI,
}

/**
 * Tunable gate thresholds. The starting values come from the implementation
 * plan and are explicitly *uncalibrated* — they exist to be adjusted against
 * real benchmark images, which is why they are configuration rather than
 * constants buried in the router.
 */
data class EnsemblePolicy(
    val mode: EnsembleMode = EnsembleMode.GEMINI_ONLY,
    val identificationThreshold: Double = 0.85,
    val portionThreshold: Double = 0.70,
    val completenessThreshold: Double = 0.85,
    val consistencyThreshold: Double = 0.85,
    /** Below this, a specific dish name is considered unjustified and the
     *  result is challenged even if identification confidence is high. */
    val specificityThreshold: Double = 0.55,
    val maxChallengers: Int = 1,
    /** Adjudication costs a third paid call; only run it when the challenger
     *  materially disagrees. */
    val adjudicateOnMaterialDisagreement: Boolean = true,
) {
    companion object {
        /** Production default until the ensemble passes benchmark acceptance. */
        val DEFAULT = EnsemblePolicy()
    }
}

/** What the router decided to do next, and why. */
sealed interface RoutingDecision {
    /** The current result is good enough; stop and enrich it. */
    data class Accept(val reason: String) : RoutingDecision

    /** Run one challenger provider to get a second opinion. */
    data class Challenge(val reason: String) : RoutingDecision

    /** Providers materially disagree; ask the adjudicator to look at the image. */
    data class Adjudicate(val reason: String) : RoutingDecision

    /** Nothing usable was produced; the caller falls back to its error path. */
    data class Fail(val reason: String) : RoutingDecision
}

/**
 * Pure decision logic for the ensemble.
 *
 * Deliberately contains no I/O, no coroutines and no provider references, so
 * every routing rule is unit-testable without spending a single API token —
 * which matters because every escalation this class authorises costs real
 * money on the user's account.
 */
object EnsembleRouter {

    /**
     * Stage 2 — accept/gate. Decides whether [evidence] can be accepted as-is
     * or needs a second opinion.
     */
    fun gate(evidence: FoodAnalysisEvidence, policy: EnsemblePolicy): RoutingDecision {
        val c = evidence.confidence
        val failures = buildList {
            if (c.identification < policy.identificationThreshold) add("identification")
            if (c.portion < policy.portionThreshold) add("portion")
            if (c.completeness < policy.completenessThreshold) add("completeness")
            if (c.consistency < policy.consistencyThreshold) add("consistency")
            // Over-specification is an accuracy risk even when the model is
            // confident: "Dal Makhani" asserts more than the pixels support.
            if (c.specificity < policy.specificityThreshold) add("specificity")
        }
        return if (failures.isEmpty()) {
            RoutingDecision.Accept("gate passed for ${evidence.provider}")
        } else {
            RoutingDecision.Challenge("gate failed on ${failures.joinToString("+")}")
        }
    }

    /**
     * Stage 4 — evidence comparison. Given the primary and its challengers,
     * decide whether consensus is good enough or adjudication is required.
     */
    fun fuse(bundle: AnalysisEvidenceBundle, policy: EnsemblePolicy): RoutingDecision {
        if (bundle.challengers.isEmpty()) {
            return RoutingDecision.Accept("no challenger ran")
        }
        if (!bundle.hasMaterialDisagreement) {
            return RoutingDecision.Accept("challenger agrees on the canonical concept")
        }
        if (!policy.adjudicateOnMaterialDisagreement) {
            return RoutingDecision.Accept("adjudication disabled by policy")
        }
        val fields = bundle.disagreements.filter { it.material }.joinToString("+") { it.field }
        return RoutingDecision.Adjudicate("material disagreement on $fields")
    }

    /**
     * Stage 5 fallback — when adjudication is unavailable or fails, pick the
     * safest validated result we already have rather than corrupting state.
     *
     * "Safest" deliberately does **not** mean "highest self-reported
     * confidence": a model claiming 0.96 on `chicken breast` must not beat two
     * models agreeing on `chicken thigh`. We rank by cross-model agreement
     * first, then by the derived (not self-reported) quality signals, and use
     * specificity as a tie-breaker so the less over-committed label wins.
     *
     * [escalationOrder] breaks the final tie by trust rather than alphabetically.
     * This matters in two-provider mode: a challenger is only ever consulted
     * *because* the primary failed the confidence gate, so when the two
     * disagree and nothing else separates them, deferring to the primary would
     * hand the answer back to the model we had already judged unreliable.
     */
    fun selectSafest(
        bundle: AnalysisEvidenceBundle,
        escalationOrder: List<String> = emptyList(),
    ): FoodAnalysisEvidence {
        val all = listOf(bundle.primary) + bundle.challengers
        if (all.size == 1) return bundle.primary

        // Agreement: how many *other* providers share this canonical concept.
        fun agreementCount(candidate: FoodAnalysisEvidence): Int =
            all.count { other ->
                other !== candidate &&
                    FoodLabelCanonicalizer.canonicalLabel(other.result.name) ==
                    FoodLabelCanonicalizer.canonicalLabel(candidate.result.name)
            }

        // Later in the escalation order == more trusted for this decision.
        fun trust(candidate: FoodAnalysisEvidence): Int =
            escalationOrder.indexOf(candidate.provider)

        return all.maxWith(
            compareBy<FoodAnalysisEvidence> { agreementCount(it) }
                .thenBy { it.confidence.consistency + it.confidence.completeness }
                .thenBy { it.confidence.specificity }
                .thenBy { trust(it) }
                // Deterministic tie-break so the same inputs always give the
                // same answer (important for reproducible benchmarking).
                .thenBy { it.provider },
        )
    }
}
