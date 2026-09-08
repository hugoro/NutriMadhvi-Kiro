package com.example.data.chat

/**
 * Phase 8 — pure heuristic classifier: maps (user text, effectiveIntent window)
 * → [ToolPlan] that tells [NutritionToolRunner] which [NutritionTools] functions
 * to run on the rows already in hand.
 *
 * Zero LLM work — just keyword matching, same discipline as [IntentRouter].
 * Conservative on purpose: only signal a tool when the turn clearly asks for
 * that computation. A false negative (no block injected) gracefully degrades to
 * today's behaviour; a false positive wastes string work but never persists
 * anything or changes the control flow.
 *
 * Keywords cover pt-PT, pt-BR, and en. Gujarati transliteration limited to the
 * highest-frequency terms (same scope as [IntentRouter.MEAL_KEYWORDS]).
 */
object ToolPlanner {

    enum class ToolKind { CALORIES, MACROS, WEIGHT, ACTIVITY }

    /**
     * [kinds] is empty → [NutritionToolRunner.run] returns "" and nothing
     * is appended to the system prompt this turn.
     */
    data class ToolPlan(val kinds: Set<ToolKind>, val window: DateWindow)

    fun plan(text: String, intent: ChatIntent.DataQuery): ToolPlan {
        val lower = text.lowercase()
        val kinds = mutableSetOf<ToolKind>()

        // CALORIES — caloric budget / target questions
        if (CALORIE_KEYWORDS.any { lower.contains(it) }) kinds += ToolKind.CALORIES

        // MACROS — explicit macro breakdown questions
        if (MACRO_KEYWORDS.any { lower.contains(it) }) kinds += ToolKind.MACROS

        // WEIGHT — body weight / composition trend questions
        if (WEIGHT_KEYWORDS.any { lower.contains(it) }) kinds += ToolKind.WEIGHT

        // ACTIVITY — movement / workout questions (reuse IntentRouter's verdict)
        val dataType = intent.type
        if (dataType == DataType.ACTIVITY || dataType == DataType.BOTH) {
            kinds += ToolKind.ACTIVITY
        }

        // For a MEALS-only intent that also mentions meal keywords, ensure we
        // always pair CALORIES with MACROS when the question is about "what did
        // I eat" — the user expects a nutritional breakdown, not raw kcal alone.
        // Only trigger when the text doesn't already explicitly request macros
        // (to avoid forcing a macro block on a pure "am I over my target?" ask).
        if (dataType == DataType.MEALS && kinds.contains(ToolKind.CALORIES) &&
            !kinds.contains(ToolKind.MACROS) &&
            BROAD_MEAL_KEYWORDS.any { lower.contains(it) }
        ) {
            kinds += ToolKind.MACROS
        }

        return ToolPlan(kinds = kinds, window = intent.window)
    }

    // --- keyword sets ---------------------------------------------------------

    // Budget / target / surplus / deficit language (pt + en + gu-translit).
    private val CALORIE_KEYWORDS = listOf(
        // pt
        "target", "meta ", "objetivo calor", "défice", "defice", "déficit", "deficit",
        "superav", "superávit", "saldo calor", "excesso calor",
        "dentro do objetivo", "dentro da meta", "fora do objetivo",
        "quantas calori", "total calori", "calori por dia",
        "estou bem nas calori", "como estou nas calori",
        // en
        "calorie target", "calorie goal", "calorie budget", "over budget",
        "under budget", "calorie deficit", "calorie surplus",
        "how many calories", "total calories", "calories per day",
        "on track", "calories today", "calories this week",
        // gu-translit
        "calorino target", "calorino lakshya"
    )

    // Explicit macro / nutrient breakdown language (pt + en).
    // NOTE: "calori" words are intentionally NOT here so they don't trigger
    // MACROS alone — the pair-up rule above handles the combined case.
    private val MACRO_KEYWORDS = listOf(
        // pt
        "proteína", "proteinas", "proteínas", "proteina",
        "hidratos", "carboidrat", "carboidratos", "carbo ",
        "gordura ", "gorduras ", "lípid", "lipid",
        "macros ", "macro ",
        "distribuição", "distribuicao", "nutriente", "nutrientes",
        "quanto proteína", "quanta proteina", "quanta gordura",
        // en
        "protein", "carbs", "carbohydrate", "fat grams", "macros",
        "macro breakdown", "macro split", "nutrient", "protein intake",
        "how much protein", "how many carbs",
        // gu-translit
        "protein kem", "macro vibhajan"
    )

    // Weight / body composition trend language (pt + en).
    private val WEIGHT_KEYWORDS = listOf(
        // pt
        "perdi peso", "ganhei peso", "evolução peso", "evolucao peso",
        "tendência peso", "tendencia peso",
        "quanto peso", "quantos kg", "variação peso", "variacao peso",
        "gordura corporal", "massa muscular", "massa magra",
        "composição corporal", "composicao corporal",
        "emagreci", "engordei", "estou a emagrecer", "estou a engordar",
        "peso esta semana", "peso este mês", "peso este mes",
        "balança", "balanca",
        // en
        "weight trend", "weight loss", "weight gain", "body weight",
        "lost weight", "gained weight", "how much weight",
        "body fat", "muscle mass", "lean mass", "lean body",
        "body composition", "weighed", "scale",
        "am i losing", "am i gaining",
        // gu-translit
        "vajan", "fat percentage"
    )

    // Broad "what did I eat" terms — triggers MACROS alongside CALORIES when
    // the intent is already MEALS (to give the full nutritional picture).
    private val BROAD_MEAL_KEYWORDS = listOf(
        "comi", "comer", "refeição", "refeicao", "alimentação", "alimentacao",
        "comida", "jantar", "almoço", "almoco", "pequeno", "lanche",
        "ate ", "eaten", "meal", "food", "breakfast", "lunch", "dinner", "snack",
        "o que comi", "o que eu comi", "what did i eat", "what i ate"
    )
}
