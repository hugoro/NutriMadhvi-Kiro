package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null,
    /** 2026-06-22 (plan ADENDO 2 — Fix 9) — Explicit Gemini context cache
     *  reference. When non-null, this names a previously-created
     *  `cachedContents/{id}` resource that holds the long stable prefix
     *  (HABIT_PROTOCOL + template + nameDirective). The server treats it
     *  as if those bytes were prepended to `systemInstruction` — but we
     *  pay neither token cost nor transit time for them. Moshi omits this
     *  field when null, so all existing call sites stay backward-
     *  compatible (the chat orchestrator is the only consumer that will
     *  populate it; food/activity analysis paths leave it null). */
    @Json(name = "cachedContent") val cachedContent: String? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    @Json(name = "role") val role: String? = null, // "user" or "model" (.env/API uses "user" and "model")
    @Json(name = "parts") val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: InlineData? = null,
    /** 2026-10-06 (Frente Thinking) — `true` when this part is extended
     *  thinking output (emitted while `includeThoughts` is on) instead of
     *  final answer text. Null on parts that carry no flag. */
    @Json(name = "thought") val thought: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String // Base64 representation
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "topP") val topP: Float? = null,
    @Json(name = "topK") val topK: Int? = null,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null,
    @Json(name = "responseMimeType") val responseMimeType: String? = null,
    /** 2026-06-22 — Gemini 2.5+ Flash models run extended thinking by
     *  default which adds 30–40s of pre-stream latency on long chat
     *  replies. The chat tab passes a populated [ThinkingConfig] here to
     *  cap that budget; all other call sites leave it null (default API
     *  behaviour). Moshi omits the field when null. */
    @Json(name = "thinkingConfig") val thinkingConfig: ThinkingConfig? = null
)

/**
 * Controls extended-thinking budget on Gemini 2.5+ Flash/Pro models.
 *
 * Two fields, both nullable, both omitted by Moshi when null:
 *   - `thinking_level`: documented in the public Interactions docs as
 *     `"none" | "low" | "medium" | "high"`. Preferred for newer SDKs.
 *   - `thinkingBudget`: older numeric form (token budget; 0 = disabled).
 *     Some endpoints accept this and not `thinking_level`, others the
 *     reverse — sending both is harmless because unknown fields are
 *     ignored by the REST endpoint.
 *
 * Plan: /Users/i548178/.claude/plans/preciso-que-vejas-isto-federated-quail.md
 * — ADENDO 2026-06-22 (Fix 7).
 */
@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    @Json(name = "thinking_level") val thinkingLevel: String? = null,
    @Json(name = "thinkingBudget") val thinkingBudget: Int? = null,
    /** 2026-10-06 (Frente Thinking) — when true, the stream interleaves the
     *  model's extended-thinking parts (flagged `thought: true`) BEFORE the
     *  answer text, so the UI can render live reasoning. Verified live on
     *  gemini-3.5-flash 2026-10-06 (SSE `includeThoughts=true`): thought
     *  parts stream first (~1.1k thought tokens), then the answer.
     *  Omitted when null — older call sites are unaffected. */
    @Json(name = "includeThoughts") val includeThoughts: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<Candidate>? = null,
    @Json(name = "error") val error: GeminiError? = null,
    /** 2026-07-06 (Frente G) — token accounting emitted by Gemini in
     *  BOTH streaming (final SSE chunk) and non-streaming responses.
     *  Absent on intermediate SSE chunks; the client picks up the last
     *  non-null value. Absent entirely on some very early free-tier
     *  endpoints — the log line falls back to "tokens=n/a" in that
     *  case. */
    @Json(name = "usageMetadata") val usageMetadata: UsageMetadata? = null
)

@JsonClass(generateAdapter = true)
data class UsageMetadata(
    @Json(name = "promptTokenCount") val promptTokenCount: Int? = null,
    @Json(name = "candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @Json(name = "totalTokenCount") val totalTokenCount: Int? = null,
    /** Number of tokens served from the Gemini context cache (when
     *  `cachedContent` was passed with the request). A cache hit here
     *  proves the cachedContent path is actually saving bytes on the
     *  wire — worth logging alongside `cache=hit`. */
    @Json(name = "cachedContentTokenCount") val cachedContentTokenCount: Int? = null,
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @Json(name = "content") val content: Content? = null,
    @Json(name = "finishReason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiError(
    @Json(name = "code") val code: Int? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "status") val status: String? = null
)

@JsonClass(generateAdapter = true)
data class FoodAnalysisResult(
    @Json(name = "name") val name: String? = null,
    @Json(name = "calories") val calories: Int? = null,
    @Json(name = "protein") val protein: Double? = null,
    @Json(name = "carbs") val carbs: Double? = null,
    @Json(name = "fat") val fat: Double? = null,
    /** Grams of WHO-defined "free sugars" inside [carbs]: mono/disaccharides
     *  added by manufacturer/cook + sugars naturally present in honey, syrups,
     *  and fruit juices. Frutose from intact whole fruit does NOT count.
     *  Powers the split-color carbs bar on the dashboard and the "Açúcar
     *  livre" line on the edit-meal screen. Nullable so older prompts / older
     *  cached results parse cleanly — the UI treats null as "unknown" and
     *  renders the legacy single-color bar. */
    @Json(name = "freeSugarsG") val freeSugarsG: Double? = null,
    /** Grams of saturated fat inside [fat]: the fraction from fatty acids with no
     *  double bonds — dominant in butter, cheese, cream, lard, fatty meat, bacon,
     *  and tropical oils (coconut/palm). WHO/AHA target: keep daily saturated fat
     *  below 10% of daily kcal. Powers the split-color fat bar on the dashboard
     *  (yellow-500 primary + yellow-200 saturated tail) and the two-row split on
     *  the Análise → Alimentação card. Nullable so older prompts / cached results
     *  parse cleanly — the UI treats null as "unknown" and renders the legacy
     *  single-color yellow bar. */
    @Json(name = "saturatedFatG") val saturatedFatG: Double? = null,
    @Json(name = "analysisSummary") val analysisSummary: String? = null,
    @Json(name = "fullSummary") val fullSummary: String? = null,
    @Json(name = "mealCategory") val mealCategory: String? = null,
    /** IDs of FoodHabit rows the model claims to have applied. Echoed back from
     *  the prompt so the UI can show which habits informed the macros. Not
     *  serialized to the on-disk JSON cache; populated post-call. */
    @Json(name = "appliedHabitIds") val appliedHabitIds: List<Long>? = null,
    /** Per-ingredient breakdown of the meal. One entry per identifiable
     *  component, with `weightGrams` being the actual portion (not per-100g)
     *  and the macro fields being absolute values for that portion. The sum
     *  across ingredients should approximately match the meal totals above —
     *  small drift (≤5%) is tolerated because the LLM rounds independently
     *  per row. Used by the edit-meal screen to render hints like
     *  "(10g manteiga; 20g queijo)" under the Fats macro bar. */
    @Json(name = "ingredients") val ingredients: List<IngredientDto>? = null,
    /** LLM self-rated confidence in the macro estimate. One of `low`, `medium`,
     *  `high` (English literals — translated for display in the user's
     *  language via [com.example.ui.I18n.tr]). The UI renders a coloured chip
     *  above the description (red/amber/green). Not persisted to Room in v1
     *  — only surfaced from the most recent analysis result. */
    @Json(name = "confidence") val confidence: String? = null,
    /** Which Gemini model produced this result. Not in the LLM JSON — stamped
     *  by [com.example.data.meal.MealAnalysisOrchestrator] after the parse so
     *  the UI / logs can tell flash vs. pro apart. `@Json(ignore = true)` keeps
     *  Moshi from trying to read/write it.  */
    @Json(ignore = true) @Transient val analysisModel: String? = null,
    /** True iff this result is the product of an automatic flash→pro escalation
     *  triggered by low/medium confidence. Drives the small "Pro" badge next to
     *  the [ConfidenceChip] in the edit-meal screen. */
    @Json(ignore = true) @Transient val escalated: Boolean = false,
    /** True when [shouldEscalate] fired BUT the Pro call failed (network,
     *  quota, parse). The Flash result is kept (so the user still sees
     *  numbers), and the VM surfaces a one-shot snackbar so the user knows
     *  the refinement was attempted but unavailable. Mutually exclusive
     *  with [escalated] — only one of them can be true on a single
     *  result. */
    @Json(ignore = true) @Transient val proAttempted: Boolean = false
)

/** One identifiable component of a meal with its portion weight and per-portion
 *  macros. All macro fields nullable to keep the schema lenient if the model
 *  omits a value for a marginal contribution; the UI threshold filter handles
 *  noise downstream. */
@JsonClass(generateAdapter = true)
data class IngredientDto(
    @Json(name = "name") val name: String,
    @Json(name = "weightGrams") val weightGrams: Double,
    @Json(name = "calories") val calories: Int? = null,
    @Json(name = "protein") val protein: Double? = null,
    @Json(name = "carbs") val carbs: Double? = null,
    @Json(name = "fat") val fat: Double? = null,
    /** Grams of saturated fat inside this ingredient's [fat]. Same WHO/AHA
     *  definition as [FoodAnalysisResult.saturatedFatG] but at the ingredient
     *  level so the macro-breakdown popup can surface real per-source rows
     *  (manteiga, queijo, bacon) in the "Fontes de gordura saturada" table.
     *  Invariant: `0 ≤ saturatedFatG ≤ fat`. Nullable so legacy `ingredientsJson`
     *  blobs (pre-v26) parse cleanly — the popup falls back to a meal-level
     *  "—" row per food when the field is absent, mirroring how the free-sugars
     *  table degrades today. */
    @Json(name = "saturatedFatG") val saturatedFatG: Double? = null,
    /** Grams of WHO "free sugars" inside this ingredient's [carbs]. Same
     *  definition as [FoodAnalysisResult.freeSugarsG] but at the ingredient
     *  level so the carbs-breakdown popup ("Fontes de açúcar livre") can
     *  attribute free sugars to the real source (pastel, doce de gila,
     *  refrigerante, mel) instead of guessing via the name heuristic
     *  [com.example.ui.components.topFreeSugarSourceHint].
     *  Invariant: `0 ≤ freeSugarsG ≤ carbs`. Nullable so legacy
     *  `ingredientsJson` blobs (pre this field) parse cleanly — the popup
     *  falls back to the meal-level heuristic-labelled row when absent. */
    @Json(name = "freeSugarsG") val freeSugarsG: Double? = null,
    /** English ingredient name emitted by the LLM alongside the local-language
     *  [name], used for USDA/Open Food Facts lookup. Nullable so legacy blobs
     *  parse cleanly — the lookup service falls back to [name] when absent. */
    @Json(name = "englishName") val englishName: String? = null,
    /** Nutrition data source after lookup enrichment. Set by
     *  [com.example.data.nutrition.NutritionLookupService] — never by the LLM.
     *  "insa" | "usda:ID" | "off:ID" | "user" | "gemini". Null until enriched. */
    @Json(ignore = true) @Transient val sourceTag: String? = null,
)

@JsonClass(generateAdapter = true)
data class ExerciseDto(
    @Json(name = "name") val name: String,
    @Json(name = "durationMinutes") val durationMinutes: Int,
    @Json(name = "caloriesBurned") val caloriesBurned: Int,
    @Json(name = "activityType") val activityType: String,
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class ActivityAnalysisResult(
    @Json(name = "name") val name: String? = null,
    @Json(name = "durationMinutes") val durationMinutes: Int? = null,
    @Json(name = "caloriesBurned") val caloriesBurned: Int? = null,
    @Json(name = "analysisSummary") val analysisSummary: String? = null,
    @Json(name = "fullSummary") val fullSummary: String? = null,
    @Json(name = "exercises") val exercises: List<ExerciseDto>? = null
)

/**
 * The food-analysis and reanalysis prompts ask the model to separate the 3
 * lines of `fullSummary` with the JSON escape sequence `\n`. Gemini regularly
 * mis-types this as `/n` (forward slash + n) which is *valid JSON* (a forward
 * slash in a string is not an escape) and therefore lands in the DB verbatim,
 * showing up as literal "/n" inside the edit-meal description.
 *
 * This sanitizer is the defensive patch:
 *   - `/n` → real newline
 *   - `\\n` (a literal backslash followed by n that Moshi did NOT unescape,
 *     i.e. the model double-escaped it as `\\n` in the JSON source) → newline
 *
 * Applied on every write that comes from the model, and on read in the edit
 * screen to retroactively heal entries persisted before this fix.
 */
internal fun String.sanitizeAiNewlines(): String =
    replace("\\n", "\n")  // double-escaped \\n that Moshi left as \n
        .replace("/n", "\n")  // common Gemini typo

internal fun String?.sanitizeAiNewlinesOrNull(): String? = this?.sanitizeAiNewlines()
