package com.example.data.remote

import com.example.data.local.MealCategory
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * Phase D: extends the [HabitProposalParser] family with action-style
 * proposals the assistant can emit alongside (or instead of) habit
 * proposals.
 *
 * Grammar (added to the assistant's system prompt by GeminiRepository):
 *
 *   [LOG_MEAL_PROPOSAL]{
 *     "name": "...",
 *     "calories": 420,
 *     "protein": 18, "carbs": 50, "fat": 14,
 *     "mealCategory": "LUNCH"            // optional
 *   }[/LOG_MEAL_PROPOSAL]
 *
 *   [LOG_ACTIVITY_PROPOSAL]{
 *     "name": "...",
 *     "durationMinutes": 30,
 *     "caloriesBurned": 220,
 *     "activityType": "RUNNING",         // optional
 *     "description": "..."               // optional narrative summary
 *   }[/LOG_ACTIVITY_PROPOSAL]
 *
 * The parser tolerates malformed JSON the same way [HabitProposalParser]
 * does — markers are always stripped from the visible text even when the
 * payload is unusable, so the protocol never leaks to the user.
 */

data class LogMealProposal(
    val name: String,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val mealCategory: MealCategory?,
    /** Optional narrative summary the LLM produces when it has enough detail
     *  to describe the meal (e.g. a photo is in the conversation). Mirrors
     *  the `fullSummary` produced by the manual capture flow so that
     *  accepting a chat-proposal lands the same payload in `FoodEntry` —
     *  see the equivalence plan dated 2026-06-22.
     *
     *  When null/blank the chat-confirm path falls back to a generic
     *  "Logged from chat session #N" stub (legacy behaviour). */
    val description: String? = null,
    /** Optional per-ingredient breakdown. When the LLM provides this, the
     *  chat-confirm path serializes it via `encodeIngredients` and stores
     *  the JSON in `FoodEntry.ingredientsJson` so the edit-meal screen can
     *  render the per-macro ingredient hints under each bar.
     *  Null/empty = no breakdown (legacy single-row behaviour). */
    val ingredients: List<IngredientDto>? = null,
    /** Optional list of `FoodHabit` ids the LLM considered while sizing
     *  this meal (e.g. a habit "Hugo eats 200g of greek yoghurt at
     *  breakfast" influenced the protein estimate). Surfaces the same
     *  "Aplicado: …" badge as the manual flow. */
    val appliedHabitIds: List<Long>? = null,
    /** Grams of WHO free sugars inside `carbs`, when the LLM emitted it in
     *  the LOG_MEAL_PROPOSAL block. Propagated verbatim into the resulting
     *  `FoodEntry.freeSugarsG`; null falls back to the legacy single-color
     *  carbs rendering for this row. */
    val freeSugarsG: Double? = null,
    /** Grams of saturated fat inside `fat`, when the LLM emitted it in the
     *  LOG_MEAL_PROPOSAL block. Propagated verbatim into the resulting
     *  `FoodEntry.saturatedFatG`; null falls back to the legacy single-color
     *  yellow fat rendering for this row. */
    val saturatedFatG: Double? = null
)

data class LogActivityProposal(
    val name: String,
    val durationMinutes: Int,
    val caloriesBurned: Int,
    val activityTypeName: String?,
    /** Optional narrative summary the LLM produces when it has enough detail
     *  to describe the workout (duration breakdown, intensity, sub-exercises
     *  etc). Mirrors [LogMealProposal.description] so that accepting a
     *  chat-proposal lands the same payload in `ActivityEntry` as the manual
     *  capture flow — see the equivalence plan (Hugo 2026-06-23).
     *
     *  When null/blank the chat-confirm path falls back to a generic
     *  "Logged from chat session #N" stub (legacy behaviour). */
    val description: String? = null,
    /** Fine-grained modality label the LLM captured from the user's turn — e.g.
     *  "Pilates", "Weight machine", "Trail run". Mirrors `ShWorkout.sourceLabel`
     *  so that a chat-confirmed activity persists the specific modality the user
     *  described, not just the coarse `activityTypeName` bucket. UI cards read
     *  this in preference to the bucket display. Nullable — the LLM MAY omit
     *  when the user only stated a bucket ("fiz treino"). */
    val sourceLabel: String? = null
)

data class InsightProposal(
    val text: String,
    val category: String,
    val confidence: Double,
    val source: String
) {
    /** True when the proposal qualifies for silent auto-confirm: the user said
     *  it themselves AND the model is confident enough. Anything else surfaces
     *  as a confirmation card. */
    val autoConfirms: Boolean
        get() = source.equals("user_stated", ignoreCase = true) && confidence >= 0.85
}

/** Phase F: a single on-demand DB query the assistant has requested. */
data class QueryDataRequest(
    val type: String,        // "meals" | "activity"
    val fromDate: String,    // ISO YYYY-MM-DD
    val toDate: String
)

/**
 * Phase G (reformulado): the assistant asks to generate an ad-hoc report and
 * drop it in the Drive folder the user already linked. The card lets the user
 * confirm before the file is written. Reuses the existing
 * [com.example.data.export.NutritionXlsxExporter] + SAF/Drive plumbing.
 *
 * `scope` is informational — the model can pass any short label (e.g. "May
 * meals", "weekly summary") and we forward it into the filename so multiple
 * reports don't overwrite each other.
 */
data class GenerateReportProposal(
    /** ISO YYYY-MM-DD inclusive. */
    val fromDate: String,
    /** ISO YYYY-MM-DD inclusive. */
    val toDate: String,
    /** Free-form label for the user-visible filename. Empty = "custom". */
    val scope: String,
    /** Suggested filename without extension. The pipeline always writes `.xlsx`. */
    val filenameStem: String
)

/**
 * Phase 3 (chat-redesign 2026-06-28): an inline chart the assistant emits to
 * visualise a small temporal aggregate next to its natural-language reply.
 * No persistence — chart cards are informational; they have no Confirm/Discard
 * buttons and disappear with the session like any other bubble content.
 */
data class ChartProposal(
    /** "bar" | "line" | "donut" | "multiline". Anything else is rejected by the parser. */
    val kind: String,
    val title: String,
    /** Optional one-liner under the title — target context or interpretation. */
    val subtitle: String?,
    /** 2-30 entries; each carries a label + value + optional highlight flag.
     *  Empty for `kind == "multiline"` — data lives in [lines] instead. */
    val series: List<ChartPoint>,
    /** "g" / "kcal" / "min" / "kg" / "%" / "count". Drives the y-axis label.
     *  For multiline: this is the DEFAULT/primary unit. Individual lines may
     *  override via [ChartLine.unit] when they mix units (e.g. kg + %). */
    val unit: String,
    /** Optional reference value drawn as a dashed line on bar/line charts. */
    val target: Double?,
    /** Phase 4 (2026-07-05): 2-4 overlaid time-series with KPI header.
     *  Non-null only when [kind] == "multiline". Use this shape when the
     *  reply relates 2+ KPIs on a shared timeline (weight/fat/muscle,
     *  kcal-in vs kcal-out, macro vs target, etc.) — a single card with
     *  KPI tiles above the overlaid lines reads much more clearly than
     *  N separate charts. */
    val lines: List<ChartLine>? = null,
    /** For multiline: shared x-axis labels. All lines have values in the
     *  same order; missing points are null in [ChartLine.values]. */
    val xLabels: List<String>? = null
)

data class ChartPoint(
    val label: String,
    val value: Double,
    val highlight: Boolean
)

/**
 * One series in a [ChartProposal] with `kind == "multiline"`. Each line
 * carries its own KPI tile drawn above the chart so the user gets both
 * the latest reading + delta AND the trajectory in one glance.
 */
data class ChartLine(
    /** Line label (also used in the KPI tile). E.g. "Peso", "Gordura", "Músculo". */
    val label: String,
    /** Optional unit override — e.g. body-fat is "%" while weight/muscle are "kg".
     *  When null, the parent [ChartProposal.unit] is used. */
    val unit: String?,
    /** One value per x-label. Nulls draw gaps. Length matches xLabels. */
    val values: List<Double?>,
    /** Optional KPI tile shown above this line. Missing → derive from values
     *  (latest = last non-null, delta = latest − oldest non-null). */
    val kpi: ChartKpi?,
    /** Optional unit for the KPI tile when it differs from the plotted line's
     *  [unit]. Body composition plots muscle/water as kg (mass over time) but
     *  the KPI reports the change in percentage points ("pp"), because absolute
     *  kg moves with total weight and would flag muscle red on a weight cut even
     *  when muscle % rose. Null → tile uses [unit]. */
    val kpiUnit: String? = null,
    /** Optional alternate series the KPI tile reads instead of [values] — used
     *  when the tile reports a different quantity than the plotted line (e.g.
     *  line plots muscle kg, KPI shows muscle % and its pp delta). Same length
     *  and null-alignment as [values]. Null → KPI derives from [values]. */
    val kpiValues: List<Double?>? = null,
    /** Optional per-segment health tone for the scrollable body-composition
     *  strip. Index i = tone of the segment ARRIVING at point i (so [0] is
     *  unused / "neutral"). Values: "good" | "bad" | "neutral". When present,
     *  the strip colours each segment and dot by direction-of-change
     *  (fat down = good, muscle up = good, …) instead of the flat metric
     *  palette. Null → palette colouring (AI [CHART_PROPOSAL] charts). */
    val segmentTones: List<String>? = null,
    /** Optional per-point ESTIMATED overlay for the body-composition strip.
     *  Index i non-null = a virtual point to draw at i even though [values][i]
     *  is null (metric not measured that day). Extrapolated from the trajectory
     *  of the following real readings, and rendered DASHED + HOLLOW so it reads
     *  as "estimated, not measured" — it never feeds KPI, delta, or tone (those
     *  still ignore the null in [values]). Same length as [values]. Null → no
     *  estimates (AI charts, or series with no leading gap). */
    val estimatedValues: List<Double?>? = null
)

/**
 * KPI tile for a [ChartLine]. `deltaTone` lets the LLM signal editorial
 * interpretation — losing 2 kg is `"good"` on a cut, `"bad"` on a bulk,
 * so the app can't infer it from the numbers alone.
 */
data class ChartKpi(
    val latest: Double,
    val delta: Double?,
    /** "good" | "bad" | "neutral". Drives the KPI tile color accent. */
    val deltaTone: String
)

/** All action proposals extracted from a single assistant reply. */
data class ParsedActionMessage(
    /** Reply with every proposal marker stripped. */
    val cleanedText: String,
    val habitProposals: List<HabitProposal>,
    val mealProposals: List<LogMealProposal>,
    val activityProposals: List<LogActivityProposal>,
    val insightProposals: List<InsightProposal>,
    /** At most one query per turn — protocol-enforced. */
    val queryDataRequest: QueryDataRequest?,
    /** Phase G: at most one report-generation proposal per turn. */
    val reportProposals: List<GenerateReportProposal>,
    /** Phase 3 (2026-06-28): at most one inline chart per turn. */
    val chartProposals: List<ChartProposal>,
    /** TTS mode (2026-10-05): the spoken script the model emits for analytic
     *  replies, wrapped in `[TTS_SUMMARY]…[/TTS_SUMMARY]`. Written for the EAR
     *  (radio-briefing register, spoken transitions, rounded numbers, no
     *  acronyms) — deliberately NOT a markdown-stripped copy of the visible
     *  reply. When non-null the TTS path prefers it over [cleanedText]; when
     *  null the TTS falls back to the visible text (pre-existing behaviour,
     *  so older persisted messages keep working). Stripped from [cleanedText]
     *  so it never leaks to the screen. */
    val ttsSummary: String? = null
)

@JsonClass(generateAdapter = true)
internal data class LogMealDto(
    val name: String? = null,
    val calories: Int? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    /** Grams of WHO-defined "free sugars" inside `carbs`. Same field the
     *  meal-analysis prompt returns; nullable at the wire level for
     *  back-compat with older prompt drafts that never emitted it. */
    val freeSugarsG: Double? = null,
    /** Grams of saturated fat inside `fat`. Same field the meal-analysis
     *  prompt returns; nullable for back-compat. */
    val saturatedFatG: Double? = null,
    val mealCategory: String? = null,
    /** Optional narrative summary (1–3 sentences). The LLM is asked to fill
     *  this whenever there is enough context to write a real description —
     *  same content as `fullSummary` in the manual capture flow. */
    val description: String? = null,
    /** Optional per-ingredient breakdown. Same schema the analyzer prompt
     *  uses for `MealAnalysisResult.ingredients`. */
    val ingredients: List<IngredientDto>? = null,
    /** Optional ids of `FoodHabit` rows the model leveraged for sizing. */
    val appliedHabitIds: List<Long>? = null
)

@JsonClass(generateAdapter = true)
internal data class LogActivityDto(
    val name: String? = null,
    val durationMinutes: Int? = null,
    val caloriesBurned: Int? = null,
    val activityType: String? = null,
    val description: String? = null,
    val sourceLabel: String? = null
)

@JsonClass(generateAdapter = true)
internal data class InsightProposalDto(
    val text: String? = null,
    val category: String? = null,
    val confidence: Double? = null,
    val source: String? = null
)

@JsonClass(generateAdapter = true)
internal data class QueryDataDto(
    val type: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null
)

/**
 * Salvage helpers for the "[QUERY_DATA] ..." family. Gemini periodically
 * emits malformed variants — missing the `[/QUERY_DATA]` closing tag,
 * snake_case field names (`start_date`/`end_date`), or just two dates with
 * no JSON wrapper at all. Without salvage every one of these leaks to the
 * chat bubble as raw protocol. Same playbook as `HabitProposalParser`'s
 * SAVE_HABIT salvage — see [[parser-salvage-over-warning]] in user memory.
 */
private object QueryDataSalvage {
    /** Regex used when the canonical `[QUERY_DATA]…[/QUERY_DATA]` regex did
     *  not match. Captures everything until the next `[` or a blank line. */
    val ORPHAN_REGEX = Regex(
        """\[QUERY_DATA\]\s*([^\[\n]+(?:\n(?!\s*\n)[^\[\n]+)*)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    /** Last-resort sweep: nukes any leftover `[QUERY_DATA]` opening tag and
     *  the few characters that follow it before the next `[`. Must run AFTER
     *  every salvage path so it never eats a payload we are still parsing. */
    val SWEEP_REGEX = Regex("""\[QUERY_DATA\][^\[]*""")

    /** Sweep for the four other proposal markers when the canonical regex
     *  failed (e.g. closing tag missing). Cleans visible text only — does not
     *  re-inject anything into the proposal lists, that's already done. */
    val OTHER_MARKERS_SWEEP = Regex(
        """\[(?:LOG_MEAL_PROPOSAL|LOG_ACTIVITY_PROPOSAL|INSIGHT_PROPOSAL|GENERATE_REPORT_PROPOSAL|CHART_PROPOSAL|TTS_SUMMARY)\][^\[]*"""
    )

    /** Defensive: closing tags that survived after their opening was eaten by
     *  the markdown renderer (halilibo treats `[CHART_PROPOSAL]` as a
     *  reference-style link anchor and may strip it from the visible string).
     *  Without this the closing tag itself leaks to the bubble. Runs AFTER
     *  the CHART salvage pass so we never strip a closing tag we still need
     *  to match against. `\??` tolerates the `[/?NAME]` variant the model
     *  occasionally emits. */
    val ORPHAN_CLOSE_SWEEP = Regex(
        """\[/\??(?:LOG_MEAL_PROPOSAL|LOG_ACTIVITY_PROPOSAL|INSIGHT_PROPOSAL|GENERATE_REPORT_PROPOSAL|CHART_PROPOSAL|QUERY_DATA|HABIT_PROPOSAL|TTS_SUMMARY)\]"""
    )

    /** Two-or-more ISO dates anywhere in the orphan payload. */
    val ISO_DATE_REGEX = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")

    /** Normalise the JSON payload's keys to the canonical camelCase shape so
     *  Moshi accepts `start_date`/`end_date`/`from`/`to`/`data_type`/`category`/`kind`
     *  variants the model is fond of inventing. Operates on the JSON text
     *  before it reaches the adapter — Moshi does not natively support
     *  multiple aliases per field. */
    fun normaliseJson(payload: String): String {
        var s = payload
        // Replace ANY of the alias names appearing as JSON keys.
        s = s.replace(Regex(""""(start_date|startDate|from|start)"\s*:""", RegexOption.IGNORE_CASE)) { """"fromDate":""" }
        s = s.replace(Regex(""""(end_date|endDate|to|end)"\s*:""", RegexOption.IGNORE_CASE)) { """"toDate":""" }
        s = s.replace(Regex(""""(data_type|category|kind)"\s*:""", RegexOption.IGNORE_CASE)) { """"type":""" }
        return s
    }

    /** Heuristic: pick "meals" or "activity" from keywords in the user's
     *  surrounding text. Falls back to "meals" because that is by far the
     *  more common ask in this app. */
    fun inferType(rawAssistantText: String): String {
        val lower = rawAssistantText.lowercase()
        val mealsHits = MEAL_KEYWORDS.count { lower.contains(it) }
        val actHits = ACTIVITY_KEYWORDS.count { lower.contains(it) }
        return if (actHits > mealsHits) "activity" else "meals"
    }

    private val MEAL_KEYWORDS = listOf(
        // pt
        "calori", "kcal", "comi", "comida", "comer", "refeição", "refeicao",
        "jantar", "almoço", "almoco", "pequeno-almoço", "pequeno almoço",
        "lanche", "ementa", "dieta", "macros", "proteína", "proteina",
        "hidratos", "carbo", "gordura",
        // en
        "calorie", "ate ", " eat ", "meal", "breakfast", "lunch", "dinner",
        "snack", "macro", "protein", "carb", "fat ", "diet"
    )
    private val ACTIVITY_KEYWORDS = listOf(
        // pt
        "treino", "treinei", "corri", "caminhei", "atividade", "actividade",
        "passos", "passo", " km ", "minuto", "minutos", "exercício", "exercicio",
        // en
        "workout", "trained", "ran ", "walked", "activity", "steps", " km",
        "exercise"
    )
}

@JsonClass(generateAdapter = true)
internal data class GenerateReportDto(
    val fromDate: String? = null,
    val toDate: String? = null,
    val scope: String? = null,
    val filename: String? = null
)

@JsonClass(generateAdapter = true)
internal data class ChartPointDto(
    val label: String? = null,
    val value: Double? = null,
    val highlight: Boolean? = null
)

@JsonClass(generateAdapter = true)
internal data class ChartKpiDto(
    val latest: Double? = null,
    val delta: Double? = null,
    val deltaTone: String? = null
)

@JsonClass(generateAdapter = true)
internal data class ChartLineDto(
    val label: String? = null,
    val unit: String? = null,
    /** Nullable Doubles so the LLM can emit gaps for missing readings. */
    val values: List<Double?>? = null,
    val kpi: ChartKpiDto? = null
)

@JsonClass(generateAdapter = true)
internal data class ChartProposalDto(
    val kind: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val series: List<ChartPointDto>? = null,
    val unit: String? = null,
    val target: Double? = null,
    /** Phase 4 multiline (2026-07-05): overlaid KPI + line card. */
    val xLabels: List<String>? = null,
    val lines: List<ChartLineDto>? = null
)

object ActionProposalParser {

    private val MEAL_REGEX = Regex(
        """\[LOG_MEAL_PROPOSAL\](.*?)\[/LOG_MEAL_PROPOSAL\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val ACTIVITY_REGEX = Regex(
        """\[LOG_ACTIVITY_PROPOSAL\](.*?)\[/LOG_ACTIVITY_PROPOSAL\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val INSIGHT_REGEX = Regex(
        """\[INSIGHT_PROPOSAL\](.*?)\[/INSIGHT_PROPOSAL\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val QUERY_DATA_REGEX = Regex(
        """\[QUERY_DATA\](.*?)\[/QUERY_DATA\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val REPORT_REGEX = Regex(
        """\[GENERATE_REPORT_PROPOSAL\](.*?)\[/GENERATE_REPORT_PROPOSAL\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val CHART_REGEX = Regex(
        """\[CHART_PROPOSAL\](.*?)\[/CHART_PROPOSAL\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    /** TTS mode (2026-10-05): plain-text spoken script — NOT JSON. The model
     *  emits it ONCE per analytic reply, wrapped like the other proposals. */
    private val TTS_SUMMARY_REGEX = Regex(
        """\[TTS_SUMMARY\](.*?)\[/TTS_SUMMARY\]""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    /** Salvage path A — opener survived but the closer never arrived (LLM
     *  truncated mid-JSON). The body capture is balanced-brace aware so
     *  series arrays of `{label,value,highlight}` items don't break it. */
    private val CHART_ORPHAN_OPEN_REGEX = Regex(
        """\[CHART_PROPOSAL\]\s*(\{)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    /** Salvage path B — the markdown renderer ate the `[CHART_PROPOSAL]`
     *  opener (treated it as a `[reference-style link]` anchor) but the
     *  closer `[/CHART_PROPOSAL]` survived. We locate the closing tag and
     *  walk backwards to find the matching opening `{` via balanced-brace
     *  scan; see [extractChartObjectBefore]. */
    private val CHART_CLOSE_TAG_REGEX = Regex("""\[/CHART_PROPOSAL\]""")
    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val mealAdapter by lazy { moshi.adapter(LogMealDto::class.java) }
    private val activityAdapter by lazy { moshi.adapter(LogActivityDto::class.java) }
    private val insightAdapter by lazy { moshi.adapter(InsightProposalDto::class.java) }
    private val queryAdapter by lazy { moshi.adapter(QueryDataDto::class.java) }
    private val reportAdapter by lazy { moshi.adapter(GenerateReportDto::class.java) }
    private val chartAdapter by lazy { moshi.adapter(ChartProposalDto::class.java) }

    /**
     * Parse [raw] and extract every action proposal. Habit proposals are
     * delegated to [HabitProposalParser] and merged in. The returned
     * [cleanedText] has all three marker families stripped.
     */
    fun parse(raw: String): ParsedActionMessage {
        // Run the legacy habit parser first — it strips its own markers and
        // gives us the habit proposals.
        val habitParsed = HabitProposalParser.parse(raw)
        var working = habitParsed.cleanedText

        val meals = mutableListOf<LogMealProposal>()
        MEAL_REGEX.findAll(working).forEach { m ->
            val payload = m.groupValues[1].trim()
            val dto = runCatching { mealAdapter.fromJson(payload) }.getOrNull() ?: return@forEach
            val name = dto.name?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            val cat = dto.mealCategory
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let { runCatching { MealCategory.valueOf(it.uppercase()) }.getOrNull() }
            meals += LogMealProposal(
                name = name,
                calories = dto.calories ?: 0,
                protein = dto.protein ?: 0.0,
                carbs = dto.carbs ?: 0.0,
                fat = dto.fat ?: 0.0,
                mealCategory = cat,
                description = dto.description?.trim()?.takeIf { it.isNotBlank() },
                ingredients = dto.ingredients?.takeIf { it.isNotEmpty() },
                appliedHabitIds = dto.appliedHabitIds?.takeIf { it.isNotEmpty() },
                // Clamp to [0, carbs] here (parser layer) so downstream never
                // has to worry about a malformed LLM emission producing a
                // segment wider than the total on the dashboard bar.
                freeSugarsG = dto.freeSugarsG
                    ?.coerceAtMost(dto.carbs ?: 0.0)
                    ?.coerceAtLeast(0.0),
                // Same clamp for saturatedFatG against `fat`.
                saturatedFatG = dto.saturatedFatG
                    ?.coerceAtMost(dto.fat ?: 0.0)
                    ?.coerceAtLeast(0.0)
            )
        }
        working = MEAL_REGEX.replace(working, "")

        val activities = mutableListOf<LogActivityProposal>()
        ACTIVITY_REGEX.findAll(working).forEach { m ->
            val payload = m.groupValues[1].trim()
            val dto = runCatching { activityAdapter.fromJson(payload) }.getOrNull() ?: return@forEach
            val name = dto.name?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            activities += LogActivityProposal(
                name = name,
                durationMinutes = dto.durationMinutes ?: 0,
                caloriesBurned = dto.caloriesBurned ?: 0,
                activityTypeName = dto.activityType?.trim(),
                description = dto.description?.trim()?.takeIf { it.isNotBlank() },
                sourceLabel = dto.sourceLabel?.trim()?.takeIf { it.isNotBlank() }
            )
        }
        working = ACTIVITY_REGEX.replace(working, "")

        val insights = mutableListOf<InsightProposal>()
        INSIGHT_REGEX.findAll(working).forEach { m ->
            val payload = m.groupValues[1].trim()
            val dto = runCatching { insightAdapter.fromJson(payload) }.getOrNull() ?: return@forEach
            val text = dto.text?.trim().orEmpty()
            val category = dto.category?.trim()?.lowercase().orEmpty()
            val source = dto.source?.trim()?.lowercase().orEmpty()
            val confidence = dto.confidence ?: 0.5
            if (text.isBlank() || category.isBlank() || source.isBlank()) return@forEach
            insights += InsightProposal(
                text = text,
                category = category,
                confidence = confidence.coerceIn(0.0, 1.0),
                source = source
            )
        }
        working = INSIGHT_REGEX.replace(working, "")

        // Phase F: at most ONE QUERY_DATA per turn.
        //
        // 3-pass salvage chain (added 2026-06-18 for the marker-leak bug Hugo
        // hit on screen — see [[parser-salvage-over-warning]]):
        //   1. Canonical: `[QUERY_DATA]{...}[/QUERY_DATA]` → Moshi → DTO.
        //   2. Orphan:    `[QUERY_DATA] …` (no closing tag, optional JSON) →
        //                 normalise alias keys → Moshi; if that still fails,
        //                 sniff two ISO dates and infer `type` from keywords.
        //   3. Sweep:     unconditionally remove any `[QUERY_DATA]` opener
        //                 still in `working`, plus a defensive sweep of the
        //                 four other marker families' orphan opens.
        var queryRequest: QueryDataRequest? = null
        var queryHandled = false
        QUERY_DATA_REGEX.findAll(working).take(1).forEach { m ->
            val payload = m.groupValues[1].trim()
            queryHandled = true
            val normalised = QueryDataSalvage.normaliseJson(payload)
            val dto = runCatching { queryAdapter.fromJson(normalised) }.getOrNull() ?: return@forEach
            val type = dto.type?.trim()?.lowercase().orEmpty()
            val from = dto.fromDate?.trim().orEmpty()
            val to = dto.toDate?.trim().orEmpty()
            if (type !in setOf("meals", "activity")) return@forEach
            if (from.isBlank() || to.isBlank()) return@forEach
            queryRequest = QueryDataRequest(type, from, to)
        }
        working = QUERY_DATA_REGEX.replace(working, "")

        // Pass 2 — orphan recovery. Only runs when the canonical regex did
        // NOT match anything (closing tag missing).
        if (!queryHandled) {
            QueryDataSalvage.ORPHAN_REGEX.findAll(working).take(1).forEach { m ->
                val payload = m.groupValues[1].trim()
                if (payload.isEmpty()) return@forEach
                // Try Moshi on a normalised JSON variant first.
                val asJson = if (payload.startsWith("{")) {
                    runCatching { queryAdapter.fromJson(QueryDataSalvage.normaliseJson(payload)) }.getOrNull()
                } else null
                val from: String?
                val to: String?
                val type: String?
                if (asJson != null && !asJson.fromDate.isNullOrBlank() && !asJson.toDate.isNullOrBlank()) {
                    from = asJson.fromDate.trim()
                    to = asJson.toDate.trim()
                    type = asJson.type?.trim()?.lowercase()?.takeIf { it in setOf("meals", "activity") }
                        ?: QueryDataSalvage.inferType(raw)
                } else {
                    // Free-form: sniff two ISO dates anywhere in the payload.
                    val dates = QueryDataSalvage.ISO_DATE_REGEX.findAll(payload)
                        .map { it.value }.toList()
                    if (dates.size < 2) return@forEach
                    from = dates[0]
                    to = dates[1]
                    type = QueryDataSalvage.inferType(raw)
                }
                if (from.isNullOrBlank() || to.isNullOrBlank() || type.isNullOrBlank()) return@forEach
                queryRequest = QueryDataRequest(type, from, to)
                android.util.Log.w(
                    "CHAT_PROTOCOL",
                    "salvaged QUERY_DATA from orphan: type=$type from=$from to=$to (rawLen=${payload.length})"
                )
            }
        }

        // Pass 3 — sweep guard. Whether or not salvage worked, leftover opens
        // get nuked from the visible text so the bubble is always clean.
        val beforeSweep = working
        working = QueryDataSalvage.SWEEP_REGEX.replace(working, "")
        if (working != beforeSweep) {
            android.util.Log.w(
                "CHAT_PROTOCOL",
                "stripped orphan [QUERY_DATA] opener (handled=$queryHandled, salvaged=${queryRequest != null})"
            )
        }
        // Defensive cleanup of orphan opener tags is deferred to AFTER all
        // typed parsers have run so the CHART/REPORT salvage paths get a
        // chance to see their tags. Done at the bottom of this function.

        // Phase G: at most ONE GENERATE_REPORT_PROPOSAL per turn so a
        // single user prompt never spawns multiple drive uploads. The model
        // is instructed in HABIT_PROTOCOL to only emit this when the user
        // explicitly asks for a report that doesn't fit the canonical Drive
        // export.
        val reports = mutableListOf<GenerateReportProposal>()
        REPORT_REGEX.findAll(working).take(1).forEach { m ->
            val payload = m.groupValues[1].trim()
            val dto = runCatching { reportAdapter.fromJson(payload) }.getOrNull() ?: return@forEach
            val from = dto.fromDate?.trim().orEmpty()
            val to = dto.toDate?.trim().orEmpty()
            if (from.isBlank() || to.isBlank()) return@forEach
            // Sanity-check ISO dates so the VM doesn't crash later. We don't
            // verify ordering here; the VM clamps to the available data.
            val isoOk = Regex("""\d{4}-\d{2}-\d{2}""")
            if (!isoOk.matches(from) || !isoOk.matches(to)) return@forEach
            val scope = dto.scope?.trim().orEmpty()
            val stem = dto.filename?.trim().orEmpty()
            reports += GenerateReportProposal(from, to, scope, stem)
        }
        working = REPORT_REGEX.replace(working, "")

        // Phase 3 (chat-redesign 2026-06-28): at most ONE CHART_PROPOSAL per
        // turn. Informational — no persistence, no Confirm/Discard. Three-pass
        // funnel mirroring the QUERY_DATA salvage in [[parser-salvage-over-warning]]:
        //   1. Canonical `[CHART_PROPOSAL]{...}[/CHART_PROPOSAL]`.
        //   2. Orphan-open `[CHART_PROPOSAL]{...` (no closer) — walk braces.
        //   3. Orphan-close `{...}[/CHART_PROPOSAL]` (opener eaten by the
        //      markdown renderer's reference-link parser) — walk braces back
        //      from the closing tag.
        // Whatever survives gets the same defensive sweeps (OTHER_MARKERS_SWEEP
        // + ORPHAN_CLOSE_SWEEP further down) so the bubble is always clean.
        val charts = mutableListOf<ChartProposal>()
        var chartHandled = false
        CHART_REGEX.findAll(working).take(1).forEach { m ->
            val payload = m.groupValues[1].trim()
            val proposal = tryBuildChart(payload) ?: return@forEach
            charts += proposal
            chartHandled = true
            android.util.Log.i(
                "CHAT_PROTOCOL",
                "parsed CHART_PROPOSAL kind=${proposal.kind} points=${proposal.series.size} unit=${proposal.unit} target=${proposal.target}"
            )
        }
        working = CHART_REGEX.replace(working, "")

        if (!chartHandled) {
            // Pass 2 — orphan-open. Find the opening tag, walk the JSON braces
            // forward from there to find the matching `}`.
            CHART_ORPHAN_OPEN_REGEX.find(working)?.let { m ->
                val braceStart = m.range.last  // position of the `{`
                val json = extractBalancedObject(working, braceStart)
                if (json != null) {
                    val proposal = tryBuildChart(json)
                    if (proposal != null) {
                        charts += proposal
                        chartHandled = true
                        // Strip "[CHART_PROPOSAL] + json" from the visible text.
                        val cutEnd = (braceStart + json.length).coerceAtMost(working.length)
                        working = working.removeRange(m.range.first, cutEnd)
                        android.util.Log.w(
                            "CHAT_PROTOCOL",
                            "salvaged CHART_PROPOSAL via orphan-open (closer missing); kind=${proposal.kind}"
                        )
                    }
                }
            }
        }
        if (!chartHandled) {
            // Pass 3 — orphan-close. The renderer ate the opener; walk braces
            // backwards from the closing tag to find the matching `{`.
            CHART_CLOSE_TAG_REGEX.find(working)?.let { m ->
                val closeStart = m.range.first
                val json = extractBalancedObjectBefore(working, closeStart)
                if (json != null) {
                    val proposal = tryBuildChart(json)
                    if (proposal != null) {
                        charts += proposal
                        chartHandled = true
                        val jsonStart = closeStart - json.length
                        // Allow a few whitespace chars between json and tag.
                        val cutStart = working.substring(jsonStart.coerceAtLeast(0), closeStart)
                            .let { jsonStart + it.indexOf("{") }
                            .coerceAtLeast(0)
                        working = working.removeRange(cutStart, m.range.last + 1)
                        android.util.Log.w(
                            "CHAT_PROTOCOL",
                            "salvaged CHART_PROPOSAL via orphan-close (opener eaten by markdown); kind=${proposal.kind}"
                        )
                    }
                }
            }
        }

        // TTS mode (2026-10-05): the spoken script. Plain text — no JSON,
        // no Moshi. At most ONE block per turn (the prompt enforces it; we
        // take the first). Extracted BEFORE the deferred sweeps below so a
        // stray `[/TTS_SUMMARY]` further along still gets cleaned up. The
        // visible reply keeps the rest of the text; the script is only ever
        // consumed by the TTS path (ChatViewModel → TtsController).
        var ttsSummary: String? = null
        TTS_SUMMARY_REGEX.find(working)?.let { m ->
            val body = m.groupValues[1].trim()
            if (body.isNotBlank()) {
                ttsSummary = body
                android.util.Log.i(
                    "CHAT_PROTOCOL",
                    "parsed TTS_SUMMARY chars=${body.length}"
                )
            }
        }
        working = TTS_SUMMARY_REGEX.replace(working, "")

        // Deferred sweep — runs AFTER all typed parsers (including CHART
        // salvage paths above) so they got their shot at the markers first.
        // Anything still wearing a marker shape after this point is debris.
        val beforeOther = working
        working = QueryDataSalvage.OTHER_MARKERS_SWEEP.replace(working, "")
        if (working != beforeOther) {
            android.util.Log.w(
                "CHAT_PROTOCOL",
                "stripped orphan proposal marker(s)"
            )
        }
        // Trailing closing tags whose openers were eaten by the markdown
        // renderer (halilibo `[X]` → reference-link parsing). These survive
        // even when the salvage above succeeded (we strip only the JSON +
        // closer that we matched, not stray `[/X]` further along).
        val beforeOrphanClose = working
        working = QueryDataSalvage.ORPHAN_CLOSE_SWEEP.replace(working, "")
        if (working != beforeOrphanClose) {
            android.util.Log.w(
                "CHAT_PROTOCOL",
                "stripped orphan closing tag(s)"
            )
        }

        val cleaned = working
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        // Diagnostic logging for the chat-redesign 2026-06-12 habit fix.
        // Surfaces every successful block detection so logcat (`adb logcat | grep HABIT`)
        // can show whether a hallucinated "I saved your habit" reply was
        // accompanied by a real block or not.
        if (habitParsed.proposals.isNotEmpty()) {
            android.util.Log.i(
                "HABIT",
                "ActionProposalParser: parsed ${habitParsed.proposals.size} habit proposal(s) " +
                    "from assistant response (rawLen=${raw.length})"
            )
        }

        return ParsedActionMessage(
            cleanedText = cleaned,
            habitProposals = habitParsed.proposals,
            mealProposals = meals,
            activityProposals = activities,
            insightProposals = insights,
            queryDataRequest = queryRequest,
            reportProposals = reports,
            chartProposals = charts,
            ttsSummary = ttsSummary
        )
    }

    // ------------------------------------------------------------------------
    // CHART_PROPOSAL helpers — kept inside the object so callers can stay on
    // a single import.
    // ------------------------------------------------------------------------

    /** Run a candidate JSON [payload] through the chart adapter + validation
     *  rules. Returns null on any failure (Moshi exception, missing fields,
     *  bad enum). Mirrors the validation block that used to live inline in
     *  the canonical pass — pulled out so salvage paths share the same
     *  ruleset and the same error log surface. */
    private fun tryBuildChart(payload: String): ChartProposal? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return null
        val dto = runCatching { chartAdapter.fromJson(trimmed) }.getOrNull() ?: return null
        val kind = dto.kind?.trim()?.lowercase().orEmpty()
        if (kind !in setOf("bar", "line", "donut", "multiline")) return null
        val title = dto.title?.trim().orEmpty()
        if (title.isBlank()) return null
        val unit = dto.unit?.trim().orEmpty()
        // For multiline the top-level unit is optional (each line carries its own).
        if (kind != "multiline" && unit.isBlank()) return null

        if (kind == "multiline") {
            val xLabels = dto.xLabels.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
            if (xLabels.size < 3 || xLabels.size > 30) return null

            val lines = dto.lines.orEmpty().mapNotNull { ld ->
                val lineLabel = ld.label?.trim().orEmpty()
                if (lineLabel.isBlank()) return@mapNotNull null
                // Sanitize the values array to exactly xLabels.size entries;
                // pad short arrays with null, trim long ones. This is defensive
                // — the LLM sometimes emits N-1 values when it forgets the
                // first day's zero baseline.
                val raw = ld.values.orEmpty()
                val values = List(xLabels.size) { i ->
                    raw.getOrNull(i)?.takeIf { it.isFinite() }
                }
                // A line needs at least 2 finite readings to plot a trend.
                if (values.count { it != null } < 2) return@mapNotNull null
                val lineUnit = ld.unit?.trim()?.takeIf { it.isNotBlank() }
                    ?: unit.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                // KPI: honor the LLM's version if present + valid, otherwise
                // derive latest/delta from the values. `deltaTone` MUST come
                // from the LLM (editorial call — the app can't tell if losing
                // muscle is good or bad without context); fall back to
                // "neutral" if it's missing/invalid.
                val kpi: ChartKpi = ld.kpi.let { k ->
                    val latestFinite = k?.latest?.takeIf { it.isFinite() }
                        ?: values.lastOrNull { it != null }
                        ?: return@mapNotNull null
                    val deltaFinite = k?.delta?.takeIf { it.isFinite() }
                        ?: run {
                            val first = values.firstOrNull { it != null }
                            if (first != null) latestFinite - first else null
                        }
                    val tone = k?.deltaTone?.trim()?.lowercase()
                        ?.takeIf { it in setOf("good", "bad", "neutral") }
                        ?: "neutral"
                    ChartKpi(latest = latestFinite, delta = deltaFinite, deltaTone = tone)
                }

                ChartLine(label = lineLabel, unit = lineUnit, values = values, kpi = kpi)
            }
            if (lines.size < 2 || lines.size > 4) return null

            return ChartProposal(
                kind = kind,
                title = title,
                subtitle = dto.subtitle?.trim()?.takeIf { it.isNotBlank() },
                series = emptyList(),
                // Primary unit for the chart axis when all lines share it,
                // else the first line's unit — the renderer decides.
                unit = unit.ifBlank { lines.first().unit ?: "" },
                target = null,
                lines = lines,
                xLabels = xLabels
            )
        }

        // Legacy path: bar / line / donut with flat series[].
        val pts = dto.series.orEmpty().mapNotNull { p ->
            val label = p.label?.trim().orEmpty()
            val value = p.value
            if (label.isBlank() || value == null || !value.isFinite()) null
            else ChartPoint(label = label, value = value, highlight = p.highlight == true)
        }
        if (pts.size < 2 || pts.size > 30) return null
        val target = dto.target?.takeIf { it.isFinite() && kind != "donut" }
        return ChartProposal(
            kind = kind,
            title = title,
            subtitle = dto.subtitle?.trim()?.takeIf { it.isNotBlank() },
            series = pts,
            unit = unit,
            target = target
        )
    }

    /** Walk braces forward from [openIdx] (must be the position of an opening
     *  `{`) and return the substring `{ ... }` that closes the outer object.
     *  Tracks string literals + escapes so braces inside quoted strings don't
     *  confuse the counter. Returns null when the string ends before the
     *  outer `{` finds its matching `}` (truncated JSON). */
    private fun extractBalancedObject(s: String, openIdx: Int): String? {
        if (openIdx < 0 || openIdx >= s.length || s[openIdx] != '{') return null
        var depth = 0
        var i = openIdx
        var inString = false
        var escape = false
        while (i < s.length) {
            val c = s[i]
            if (escape) { escape = false; i++; continue }
            if (c == '\\') { escape = true; i++; continue }
            if (c == '"') { inString = !inString; i++; continue }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return s.substring(openIdx, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** Counterpart of [extractBalancedObject] for the orphan-close salvage:
     *  walk backwards from just-before [closeIdx] (the start of the closing
     *  tag) to find the matching outer `{`. Returns the substring `{ ... }`
     *  inclusive, or null when no balanced object is found. */
    private fun extractBalancedObjectBefore(s: String, closeIdx: Int): String? {
        if (closeIdx <= 0) return null
        // Locate the last `}` before closeIdx, skipping whitespace.
        var lastBrace = -1
        for (i in (closeIdx - 1) downTo 0) {
            val c = s[i]
            if (c == '}') { lastBrace = i; break }
            if (!c.isWhitespace()) return null   // junk between json and tag
        }
        if (lastBrace < 0) return null
        // Walk backwards balanced. We don't track strings going backwards
        // (the LLM-emitted JSON is well-formed enough in practice — this
        // salvage only fires when the marker tags fail, not the JSON itself);
        // a stray `{` inside a string would mis-match, but that has never
        // appeared in the chart payloads we've seen.
        var depth = 0
        for (i in lastBrace downTo 0) {
            val c = s[i]
            if (c == '}') depth++
            else if (c == '{') {
                depth--
                if (depth == 0) return s.substring(i, lastBrace + 1)
            }
        }
        return null
    }
}
