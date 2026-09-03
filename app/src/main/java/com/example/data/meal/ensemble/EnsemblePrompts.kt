package com.example.data.meal.ensemble

/**
 * Prompt fragments shared by the ensemble providers.
 *
 * The base meal-analysis prompt lives in
 * `GeminiRepository.buildFoodAnalysisPromptCore` and is reused verbatim so
 * both providers answer in the same schema the app already parses. The
 * additions below encode the ensemble-specific behaviour from the plan:
 * canonical naming, portion/identity separation, and independent confidence
 * values that the router (not the model) gets to act on.
 */
internal object EnsemblePrompts {

    /**
     * Appended to the shared core prompt. Keeps the output schema unchanged —
     * the extra confidence fields are optional, so a provider that ignores
     * them still parses cleanly and simply gets heuristically scored instead.
     */
    const val CANONICAL_NAMING_RULES: String = """

        NAMING DISCIPLINE (ensemble mode):
          - Identify each visible component separately rather than naming only the dominant item.
          - Prefer a generic, canonical food name whenever the visual evidence does not justify a specific regional or branded dish name. Say "yellow lentil curry", "bread roll", "milk tea" unless the photo genuinely supports "Dal Makhani", "Naan", "Masala Chai".
          - Only distinguish varieties or cuts (breast vs thigh, loin vs belly) when the difference is visually supported. If it is not, name the base food.
          - Estimate the portion independently of the identity: being unsure what a dish is called must not change the gram estimate, and vice versa.
          - Nutrient values you emit are provisional. A deterministic nutrition database is authoritative downstream, so never inflate confidence to defend your own numbers.

        CONFIDENCE REPORTING (optional fields, omit rather than guess):
          - "identificationConfidence": 0.0-1.0, that the food identity is right.
          - "portionConfidence": 0.0-1.0, that the quantity is right.
          - "specificityConfidence": 0.0-1.0, that a specific dish name is justified over a generic one.
    """

    /**
     * Adjudication prompt. The adjudicator re-examines the original image and
     * treats the competing structured answers strictly as evidence.
     */
    fun adjudicationInstructions(
        primaryProvider: String,
        primaryJson: String,
        challengerProvider: String,
        challengerJson: String,
        disagreements: List<AnalysisDisagreement>,
    ): String {
        val disagreementLines = disagreements.joinToString("\n") { d ->
            "  - ${d.field}: \"${d.left}\" (${primaryProvider}) vs \"${d.right}\" ($challengerProvider)" +
                if (d.material) " [MATERIAL]" else ""
        }
        return """

        ADJUDICATION TASK:
        Two vision models analysed the SAME photo above and disagree. Their answers are EVIDENCE, NOT GROUND TRUTH — inspect the image yourself and decide.

        $primaryProvider proposed:
        $primaryJson

        $challengerProvider proposed:
        $challengerJson

        Disagreements:
        $disagreementLines

        Rules:
          - Look at the image first; use the two answers only to know what to check.
          - Where the models agree, that agreement is strong evidence — do not overturn it without a clear visual reason.
          - Penalise unjustified specificity: if neither model can visually justify a regional dish name, return the generic name.
          - Treat identity uncertainty and portion uncertainty separately; you may accept one model's food name and the other's portion.
          - Do not invent final nutrition facts to win the argument — a nutrition database recomputes them downstream.
          - Reply with the SAME JSON schema, nothing else.
        """
    }
}
