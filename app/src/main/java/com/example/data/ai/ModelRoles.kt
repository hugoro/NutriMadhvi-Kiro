package com.example.data.ai

/**
 * Role-based model routing for in-app LLM work (v39).
 *
 * The principle: **routine structured work goes to the least costly capable
 * model**. These constants are the single place that maps a *role* to a
 * concrete model, so the rest of the codebase never hard-codes model names.
 *
 *  - [ROUTINE_EXTRACTION_MODEL] — cheap structured extraction/reporting
 *    (JSON blocks, short coaching text). Primary for body-plan tactics +
 *    checkpoint reports.
 *  - [ROUTINE_FALLBACK_MODEL] — compatibility fallback when the primary is
 *    missing, 503, or quota-exhausted. Same API surface, slightly higher
 *    capability/cost.
 *  - [HIGH_REASONING_ESCALATION_MODEL] — reserved interface for a future
 *    high-reasoning escalation model. Deliberately **null and unwired**:
 *    nothing in any runtime path reads a non-null value today. A future
 *    escalation feature (complex plan reasoning, multi-checkpoint analysis)
 *    should add its own quality tests before binding a model here.
 *
 * Deliberately NOT present: gemini-3.8-flash (no in-repo quality-test
 * justification) and any Gemini 4 / Argon model (not in routine paths).
 */
object ModelRoles {

    /** Primary for routine structured work (flash-lite tier). */
    const val ROUTINE_EXTRACTION_MODEL = "gemini-3.5-flash-lite"

    /** Compatibility fallback for routine structured work (flash tier). */
    const val ROUTINE_FALLBACK_MODEL = "gemini-3.6-flash"

    /** Reserved for future high-reasoning escalation. NOT wired to any
     *  runtime path — see object KDoc. A `val` (not `const`) because a
     *  nullable type can't be a Kotlin constant. */
    val HIGH_REASONING_ESCALATION_MODEL: String? = null

    /** The routine chain, in call order: cheapest capable first. */
    val ROUTINE_CHAIN: List<String> = listOf(
        ROUTINE_EXTRACTION_MODEL,
        ROUTINE_FALLBACK_MODEL,
    )
}
