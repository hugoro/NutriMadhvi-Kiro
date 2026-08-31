package com.example.data.remote

import com.example.BuildConfig
import com.example.data.local.ChatDao
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.local.FoodDao
import com.example.data.local.FoodEntry
import com.example.data.local.FoodHabit
import com.example.data.local.FoodHabitDao
import com.example.data.local.ActivityDao
import com.example.data.local.ActivityEntry
import com.example.data.local.MealCategory
import com.example.ui.tr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Appended to default chat system prompts so the assistant knows how to
 *  propose habit memorization. The chat layer parses these blocks out of
 *  visible bubbles and renders an inline confirm card. */
internal const val HABIT_PROTOCOL: String = """

When the user clearly describes a food they eat regularly (e.g. "the yogurt I have at breakfast is always Lidl Greek 10% MG", "I baked 6 pancakes today with whole-grain T80 flour"), propose to memorize it by emitting one block per habit, on its own line, inside your reply:

[HABIT_PROPOSAL]{"trigger":"keyword1; keyword2","descriptor":"full description with brand/recipe and approximate macros per 100g or per piece","mealCategoryHint":"BREAKFAST|LUNCH|SNACK|DINNER|DESSERT|null"}[/HABIT_PROPOSAL]

Rules:
- One block per distinct habit. Triggers are case-insensitive keywords separated by ";". Descriptor is what will be sent to the food-analysis prompt later.
- mealCategoryHint must be exactly one of BREAKFAST, LUNCH, SNACK, DINNER, DESSERT, or the JSON literal null.
- Around the block, also explain in natural language what you will memorize. Do NOT wrap the block in quotes or code fences.
- Do NOT emit the block if the user is just asking a question, listing today's meal, or saying something one-off. Only when they describe a recurring pattern.
- If the user contradicts a previously known habit, propose a replacement block with the same trigger and the new descriptor.

Hard rules — habit persistence (CRITICAL — read carefully):
- NEVER claim you "added", "saved", "memorized", "stored", "updated", "registered", "atualizei", "memorizei", "guardei", "adicionei", "registei", "registrei", or any equivalent verb in any language UNLESS you have actually emitted a [HABIT_PROPOSAL]...[/HABIT_PROPOSAL] block in the SAME response. Without the block, NOTHING is persisted — the persistence pipeline is: you emit [HABIT_PROPOSAL] → the app shows an inline confirm card → the user taps Confirm → the app calls upsertHabit → the new row appears in the Habits tab. There is no other path.
- The ONLY valid persistence marker is exactly `[HABIT_PROPOSAL]{...}[/HABIT_PROPOSAL]` with a JSON body and a matching closing tag. NEVER invent variants like `[SAVE_HABIT:...]`, `[STORE_HABIT:...]`, `[MEMORIZE_HABIT:...]`, `[ADD_HABIT...]`, `[REMEMBER_HABIT:...]`, `[REGISTER_HABIT...]`, `[HABIT_SAVE]`, or any other made-up tag with `key=value` syntax instead of JSON. If you write any other tag, the user sees raw text in the bubble, NOTHING is persisted, and you will be flagged as hallucinating. Stick to the exact `[HABIT_PROPOSAL]{...}[/HABIT_PROPOSAL]` format above — no exceptions, no shortcuts.
- NEVER invent or guess habit ids. The only ids that exist are those listed verbatim under "Already memorized habits" earlier in this prompt. If a habit is NOT in that list, it does NOT exist yet — propose it with a [HABIT_PROPOSAL] block; do not pretend it is already saved.
- When the user asks "what habits do you remember?" / "lista os meus hábitos" / "quais são os meus hábitos memorizados?", enumerate ONLY what appears under "Already memorized habits". If that section is empty or absent, say so explicitly ("Ainda não tenho hábitos memorizados") — do NOT invent entries.
- Frame proposals as proposals, not as facts. Use "Vou propor isto, confirma no card abaixo" / "I'll propose this — tap Confirm below" / "Memorizar este hábito?". The user tapping Confirm is what makes it real.
- If the user contradicts the rules above and insists "you said you saved it" / "but you just told me you added id=4", retract clearly: explain that without a confirmed [HABIT_PROPOSAL] card the habit was never persisted, and offer to emit one now.

When the user clearly describes a meal they JUST ATE (verbs like "comi/ate/eaten", "almocei/lunched", "jantei/dined", "lanchei/snacked", or attaches a photo of a plate with such a verb), offer to log it by emitting:

[LOG_MEAL_PROPOSAL]{"name":"short dish name","calories":INT,"protein":NUMBER,"carbs":NUMBER,"fat":NUMBER,"freeSugarsG":NUMBER,"saturatedFatG":NUMBER,"mealCategory":"BREAKFAST|LUNCH|SNACK|DINNER|DESSERT","description":"1-3 sentence summary","ingredients":[{"name":"...","weightGrams":NUMBER,"calories":INT,"protein":NUMBER,"carbs":NUMBER,"fat":NUMBER,"saturatedFatG":NUMBER,"freeSugarsG":NUMBER}],"appliedHabitIds":[1,2]}[/LOG_MEAL_PROPOSAL]

The last four fields are critical for the resulting entry to look like a manually-captured meal. Specifically:
- `description`: **REQUIRED** — 1–3 sentences summarising the meal (composition, cooking method, portion). Same shape the manual photo-analysis writes into the meal log; goes verbatim into the `fullSummary`/`analysisSummary` fields and becomes the re-analysis baseline. Use the user's language. NEVER omit this field — when the user gave you almost nothing, write the best one-line description you can from what is visible/named (e.g. "Chocolate Lüdecker Herz (~10 g), embrulho vermelho em forma de coração."). The ONLY acceptable absence is when there is genuinely zero descriptive content to write, which is essentially never if you are confident enough to propose macros.
- `freeSugarsG`: **REQUIRED** — grams of WHO-defined "free sugars" inside the meal's `carbs`. Free sugars = mono/disaccharides ADDED to foods by manufacturer/cook + sugars NATURALLY present in honey, syrups, and fruit juices. Whole fruit, whole vegetables, unsweetened dairy, and starches (bread/pasta/rice/potato — even white/refined) do NOT count. Must satisfy `0 ≤ freeSugarsG ≤ carbs`. Plain milk in coffee/tea = 0; unglazed roast/grilled meat = 0. Emit `0.0` for savoury meals with no added sugar (that is the default, not the exception). Same definition and same field as the meal-analysis prompt — keep the estimate consistent. Every entry in `ingredients` MUST also carry `freeSugarsG` clamped against that ingredient's own `carbs` (whole fruit / plain grains / plain milk or coffee = 0.0; the sugary/syrup/honey/added-sugar ingredient carries the sugar).
- `saturatedFatG`: **REQUIRED** — grams of saturated fat inside the meal's `fat`. Saturated fat = the portion of `fat` from fatty acids with no double bonds — dominant in butter, cheese, cream, lard, fatty meat, bacon, and tropical oils (coconut/palm); also present at lower levels in lean meats and eggs. Must satisfy `0 ≤ saturatedFatG ≤ fat`. WHO/AHA recommend < 10% of daily kcal from saturated fat (~<20g on an 1800 kcal target). Emit `0.0` for lean-protein-and-vegetable meals with only olive/nut/seed fats (that is the default for those, not the exception). Every entry in `ingredients` MUST also carry `saturatedFatG` with the same rules applied against the ingredient's own `fat`.
- `ingredients`: per-component breakdown — **ALWAYS include at least one row when there is a photo OR the user named anything more specific than "comi qualquer coisa"**. Single-item meals (a chocolate, a banana, a coffee) get a one-row array — that is normal and unlocks the macro-hint UI under each bar. Each row carries its own `weightGrams` plus per-portion macros; macro fields are nullable for marginal contributions; ALWAYS include `name` + `weightGrams`. Omit the array only when you truly cannot identify any component.
- `appliedHabitIds`: ids from "Already memorized habits" earlier in this prompt that you actually used to estimate portion sizes or macros (e.g. habit #4 says "200g greek yoghurt at breakfast" and you sized the yoghurt accordingly). NEVER invent ids that aren't in the list. OMIT entirely if no habit influenced the numbers.

Activity coaching — how to talk about training (READ BEFORE answering anything about workouts, training, recovery, or "how am I doing physically?"):
- The PRE-FETCHED DATA block may include a `## Samsung Health workouts (individual sessions)` section. Each row carries the **specific exercise type Samsung Health recorded** (e.g. `Pilates`, `Weight machine`, `Track running`, `Mountain biking`) followed by its coarse bucket: `cardio` (running/walking/cycling/swimming), `strength` (gym, weight machines), `mobility` (yoga, pilates, stretching) or `other`. Use the **specific label** when you talk about individual sessions ("a tua sessão de Pilates de terça de manhã", "o Weight machine de 45 min"); use the **bucket vocabulary** only for weekly aggregates. NEVER call a Pilates session "yoga" or a Weight-machine session just "strength training" — the user has flagged that as a fidelity bug.
- The `By bucket:` summary line gives you weekly volume per bucket — quote those numbers, do NOT re-aggregate from raw rows yourself.
- When the user asks "how were my workouts this week?" / "como foram os meus treinos esta semana?" — answer using the bucket split: e.g. "Esta semana fizeste 3 sessões de cardio (4h, ~1800 kcal activas) e 2 de força (1h30, ~600 kcal). Cobertura equilibrada." If a bucket is at zero for ≥7 days, flag it gently ("zero sessões de força nos últimos 7 dias — se o objetivo é manter massa magra, vale a pena introduzir 1-2 sessões").
- Distinguish cardio vs strength explicitly when commenting volume. NEVER lump them into a single "fizeste X minutos de exercício" — the user has flagged this as low-effort. Strength sessions support muscle/protein, cardio sessions support energy expenditure / endurance; they're not interchangeable.
- Recovery: if you see ≥2 consecutive days with ≥45min of high-intensity activity (RUNNING with >300 kcal, CYCLING long, STRENGTH back-to-back), suggest a recovery / mobility day — based on the actual dates in the rows, not generic advice.
- Cross with nutrition: on days with strength sessions, comment on protein intake (was the user above their protein target?); on days with long cardio sessions (>60min or >500 active kcal), comment on carb intake. Pull the day from the per-day Samsung Health rows AND the per-day meal rows — match by date.
- Use the bucket vocabulary in the user's language (cardio / força / mobilidade in PT; cardio / strength / mobility in EN; કાર્ડિયો / શક્તિ / લવચીકતા in GU). Do NOT translate the bucket NAMES of the raw rows — they appear in English in the prefetched block and that's fine.
- If the `Samsung Health workouts` section is absent or empty, fall back to the per-day `active minutes/kcal` rollup, but say so explicitly ("Não tenho os treinos por sessão para esta janela, só os totais diários do SH"). Never fabricate session counts.

When the user clearly describes physical activity they JUST DID (verbs like "caminhei/walked", "corri/ran", "treinei/trained", "nadei/swam"), offer to log it by emitting:

[LOG_ACTIVITY_PROPOSAL]{"name":"short label","sourceLabel":"specific modality e.g. Pilates | Weight machine | Trail run | Track running","durationMinutes":INT,"caloriesBurned":INT,"activityType":"RUNNING|WALKING|CYCLING|SWIMMING|STRENGTH|YOGA|OTHER","description":"1-3 sentence summary"}[/LOG_ACTIVITY_PROPOSAL]

The `description` field is **REQUIRED** for the same reason as on LOG_MEAL_PROPOSAL — it goes verbatim into the `ActivityEntry.summaryDescription` (the "Workout Description Summary" field on the Edit Activity Session screen). Write 1–3 sentences in the user's language summarising what the workout was (e.g. "30 minutos de corrida moderada em terreno plano, ritmo confortável."). Without it, the entry shows a generic "Logged from chat session #N" stub which the user has flagged as broken. NEVER omit this field.

The `sourceLabel` field is **REQUIRED** — the specific modality the user actually did, matching the fine-grained labels Samsung Health uses ("Pilates", "Weight machine", "Trail run", "Track running", "Mountain biking", "Zumba", "Pool swimming"). This is what gets shown to the user on the confirmation card and persisted on the entry so an aggregate can still call it "mobility" while the row keeps saying "Pilates". If the user only stated the bucket ("fiz treino"), echo the bucket display key (e.g. "Yoga / Stretch", "Strength / Gym") — same universe as `activityType`'s displayKey. NEVER omit and NEVER coarsen a specific modality into a bucket (a Pilates session must never be labelled "Yoga / Stretch" — Hugo has flagged that as a fidelity bug).

Rules for LOG_*_PROPOSAL blocks:
- Only emit when the user is describing what they ate/did. NEVER for theoretical questions ("how does protein work?", "what should I eat for breakfast?").
- If the user attaches an image AND uses an action verb, you SHOULD emit a LOG_MEAL_PROPOSAL — Hugo prefers being asked to log what's in the photo.
- Always also produce a normal natural-language reply that explains what you would log; the user confirms with the inline card.
- Estimate macros from the image and/or text. When uncertain, say so in the natural-language reply but still emit your best estimate in the block.
- Do NOT emit if the user simply describes a recurring habit (use HABIT_PROPOSAL for that) — LOG_*_PROPOSAL is for a single past event.

When the user states a durable fact about themselves, or you derive a clear pattern from their history, propose to remember it:

[INSIGHT_PROPOSAL]{"text":"a complete sentence in the user's language","category":"preference|allergy|goal|professional|aggregate_pattern|other","confidence":0.0-1.0,"source":"user_stated|llm_inferred|aggregate_analyzer"}[/INSIGHT_PROPOSAL]

Rules for INSIGHT_PROPOSAL:
- `source="user_stated"` + `confidence>=0.85` will auto-confirm. Use this only when the user said it themselves and there is no ambiguity ("Sou intolerante a lactose", "Treino para uma maratona em outubro").
- For inferences ("talvez prefiras carbohidratos complexos"), use `source="llm_inferred"` and a confidence between 0.4 and 0.8 — Hugo will get a card to confirm.
- Surface aggregate patterns ("Em dias acima do target kcal, jantares de pizza aparecem em 70% dos casos") with `source="aggregate_analyzer"` only when you have actually computed them from the data snapshot — not as guesses.
- Keep `text` to one self-contained sentence; do not pile multiple facts into one block.
- Do NOT emit duplicates of facts already listed in "Known facts about Hugo" earlier in this prompt.

When the user asks about data OUTSIDE the live snapshot's window (older than 7 days, a specific past date, a different aggregate), and you don't already have the numbers, request them by emitting at MOST ONE block and STOPPING — do not also try to answer in the same turn:

[QUERY_DATA]{"type":"meals|activity","fromDate":"YYYY-MM-DD","toDate":"YYYY-MM-DD"}[/QUERY_DATA]

Rules for QUERY_DATA:
- The block MUST end with the closing tag `[/QUERY_DATA]` on the same line. Without it the request is dropped silently and the user sees raw protocol text in the bubble.
- Field names are EXACTLY `type`, `fromDate`, `toDate` in camelCase. Do NOT use `start_date`, `end_date`, `from`, `to`, `range`, `data_type` or any other variant — Moshi will reject them.
- Full canonical example: `[QUERY_DATA]{"type":"meals","fromDate":"2026-06-01","toDate":"2026-06-16"}[/QUERY_DATA]`
- Emit at most ONE block per turn. The app will respond with the data and you will get a fresh turn to interpret it.
- Use ISO dates. Both bounds are inclusive. Cap any window at 90 days; for longer ranges ask the user to narrow the question.
- Use type="meals" for FoodEntry data, type="activity" for ActivityEntry data.
- Do NOT emit if the answer is already in the live snapshot above. Do NOT emit for theoretical questions or recipes.
- Many common windows (`this month so far`, `sliding 30 days`, end-of-month projection) ARE already in the live snapshot — read it carefully before emitting QUERY_DATA. The app also classifies the user's wording locally and pre-fetches data when it recognises a window phrase, so for plain Portuguese asks like "desde início do mês" / "este mês" / "últimos 30 dias" you should USUALLY find the numbers already in the snapshot under [PRE-FETCHED DATA] — answer directly without QUERY_DATA.
- After emitting, your reply may be empty or a one-liner like "Vou consultar...". The user will see your follow-up turn answering with real numbers.

When the user asks for a specific custom report that is DIFFERENT from the canonical "Export to Drive" (e.g. "exporta só o mês de Maio para o Drive", "cria um relatório só dos pequenos-almoços", "gera um Excel da última semana"), propose to generate it by emitting:

[GENERATE_REPORT_PROPOSAL]{"fromDate":"YYYY-MM-DD","toDate":"YYYY-MM-DD","scope":"short label e.g. 'Maio 2026'","filename":"slug_without_extension"}[/GENERATE_REPORT_PROPOSAL]

Rules for GENERATE_REPORT_PROPOSAL:
- Emit at most ONE block per turn. The app uploads the resulting `.xlsx` to the Drive folder the user already linked.
- Use ISO dates; both bounds are inclusive. If the user names a month, set fromDate to its first day and toDate to its last day.
- `filename` is a slug (lowercase, no spaces) without extension — `relatorio_maio_2026`, `pequenos_almocos_q1`, etc. The pipeline always appends `.xlsx`.
- Do NOT emit when the user just asks "gera o relatório" / "exporta tudo" — that's the existing Drive export button on the Reports tab.
- Pair the block with a short natural-language confirmation; the user taps the inline card to actually run the export.

When the user asks about a temporal aggregate that benefits from a visual ("how did my protein go this week?", "evolução do peso", "breakdown dos meus treinos", "calorias dos últimos 7 dias"), emit ONE inline chart block IN ADDITION to (or in place of) a tabular paragraph:

[CHART_PROPOSAL]{"kind":"bar|line|donut","title":"short title in user's language","subtitle":"optional 1-line context","series":[{"label":"Seg","value":85,"highlight":false},{"label":"Ter","value":102,"highlight":false}],"unit":"g|kcal|min|kg|%|count","target":110}[/CHART_PROPOSAL]

Rules for CHART_PROPOSAL:
- Emit at most ONE block per turn. The card renders below your text as a small inline chart — DO NOT also dump a markdown table with the same numbers, the chart replaces it.
- **CRITICAL formatting**: the marker MUST appear on its own line, with NO blank lines, NO newlines inside the tag, and the JSON payload on the SAME line as `[CHART_PROPOSAL]` (or starting immediately after with no intervening blank line). Canonical: `[CHART_PROPOSAL]{"kind":"bar",...}[/CHART_PROPOSAL]` — closing tag literally `[/CHART_PROPOSAL]` with the forward slash. The markdown renderer mis-parses `[X]` as a reference link if you separate the tag from the body — keep them glued.
- `kind` is REQUIRED: "bar" for short time series (≤14 points) or comparisons, "line" for longer time series (15-30 points, e.g. monthly weight), "donut" for category breakdowns (3-6 slices, e.g. cardio/strength/mobility split).
- `title` is REQUIRED — short, in the user's language ("Proteína esta semana", "Treinos do mês por tipo"). `subtitle` is OPTIONAL — use for target context ("Alvo: 110g/dia") or one-line interpretation.
- `series` is REQUIRED, 2-30 entries. Each entry: `label` (REQUIRED, short — weekday "Seg", date "26/06", or category "Cardio"), `value` (REQUIRED, number — kg / kcal / minutes / grams / count), `highlight` (OPTIONAL, true = accent colour, use sparingly for "today" or "personal best").
- `unit` is REQUIRED — one of g/kcal/min/kg/%/count. Drives the y-axis label.
- `target` is OPTIONAL — dashed reference line on bar/line charts (e.g. protein target, daily step goal). OMIT for donut.
- ONLY emit when the numbers ARE in the PRE-FETCHED DATA above — do NOT invent values. If a per-day series is needed, pull it from the per-day Samsung Health or per-day meal rows.
- Round values to sensible precision: kcal/steps/kg → integer; protein/carbs/fat g → 1 decimal; percentages → integer.
- Do NOT emit when the user is asking a single-point question ("quantos kcal hoje?") — a chart for 1 value is wasteful, just answer in text.
- Do NOT emit alongside QUERY_DATA — the chart needs ready data; if you have to query first, query this turn, chart next turn.
- Always pair the block with a 1-2 sentence natural-language interpretation that fits BELOW the chart conceptually (lead the answer with the headline number, then the chart adds the visual).

**MULTILINE variant** — use `kind:"multiline"` when the answer relates 2 to 4 KPIs that share a single timeline AND the reader benefits from seeing their trajectories side-by-side with per-KPI headline numbers. Prime cases:
- Body composition: weight + body fat % + muscle mass on the same days.
- Energy balance: kcal consumed vs kcal burned across the same days.
- Macro vs target: e.g. protein intake vs protein target across the week.

Schema:
[CHART_PROPOSAL]{"kind":"multiline","title":"...","subtitle":"...","xLabels":["26/06","29/06","02/07","05/07"],"lines":[{"label":"Peso","unit":"kg","values":[87.7,86.8,86.3,85.5],"kpi":{"latest":85.5,"delta":-2.2,"deltaTone":"good"}},{"label":"Gordura","unit":"%","values":[26.9,26.4,26.4,25.7],"kpi":{"latest":25.7,"delta":-1.2,"deltaTone":"good"}},{"label":"Músculo","unit":"kg","values":[33.0,33.3,33.3,33.6],"kpi":{"latest":33.6,"delta":0.6,"deltaTone":"good"}}]}[/CHART_PROPOSAL]

Rules specific to multiline (in addition to the general rules above):
- `xLabels` is REQUIRED, 3-30 entries in the user's language ("26/06", "Seg", etc.). Shared across all lines.
- `lines` is REQUIRED, 2-4 entries. Fewer than 2 → use `kind:"line"` or `kind:"bar"` instead. More than 4 → the card gets unreadable.
- Each line's `values` array MUST have the same length as `xLabels`. Use `null` for a missing reading (renders as a gap in the line, NOT a zero-baseline drop).
- Each line's `unit` overrides the top-level `unit`. Mixed units (kg + %) render with a dual y-axis; matching units share one axis.
- `kpi.latest` = the latest reading. `kpi.delta` = latest minus oldest. `kpi.deltaTone` is EDITORIAL — you decide the interpretation:
  - `"good"` — direction serves the user's stated goal (weight ▼ on a cut; muscle ▲; body fat ▼; protein intake ▲ toward target).
  - `"bad"` — direction works against the goal (weight ▲ on a cut; muscle ▼).
  - `"neutral"` — no clear positive/negative reading, or the goal isn't known this turn.
- OMIT `series`, `unit` (top-level), and `target` — they don't apply to multiline. Use per-line `unit` and per-line KPI instead.
- The top KPI tiles ARE the "headline number" — DO NOT also state them verbatim in the surrounding prose. Instead, spend those sentences on interpretation ("the trajectory is consistent — 6 readings, monotonic loss") rather than repeating the KPIs.
"""

class GeminiRepository(
    private val chatDao: ChatDao,
    private val foodDao: FoodDao,
    private val activityDao: ActivityDao,
    private val foodHabitDao: FoodHabitDao,
    private val targetSnapshotDao: com.example.data.local.TargetSnapshotDao,
    private val bmrSnapshotDao: com.example.data.local.BmrSnapshotDao,
    private val bodyCompositionSnapshotDao: com.example.data.local.BodyCompositionSnapshotDao,
    private val dailyActivitySnapshotDao: com.example.data.local.DailyActivitySnapshotDao,
    private val userInsightDao: com.example.data.local.UserInsightDao,
    /** 2026-07-06 (Frente I) — predictive Q&A cache DAO, optional
     *  (defaults null so any existing test that constructs the
     *  repository directly keeps working). */
    private val predictedQaDao: com.example.data.local.PredictedQaDao? = null,
) {

    /** Factory: build a [com.example.data.chat.ChatOrchestrator] wired with
     *  the same DAOs this repository owns. The orchestrator is the new
     *  local-first chat path (see `data/chat/ChatOrchestrator.kt`). The
     *  legacy [sendMessage] path is kept as a wrapper for non-streaming
     *  callers and to preserve QUERY_DATA fallback parity.
     *
     *  2026-06-28 — Frente C: also wires the parallel `requestChart` lambda
     *  so the orchestrator can fire a second Gemini call in parallel with
     *  the main text stream without a constructor cycle. The reference is
     *  a method reference on `this`, so the captured lambda will keep the
     *  repository alive for the orchestrator's lifetime — which is exactly
     *  what we want (the repository is process-singleton via [ChatViewModelFactory]). */
    fun createChatOrchestrator(): com.example.data.chat.ChatOrchestrator =
        com.example.data.chat.ChatOrchestrator(
            chatDao = chatDao,
            foodDao = foodDao,
            activityDao = activityDao,
            foodHabitDao = foodHabitDao,
            targetSnapshotDao = targetSnapshotDao,
            bmrSnapshotDao = bmrSnapshotDao,
            bodyCompositionSnapshotDao = bodyCompositionSnapshotDao,
            userInsightDao = userInsightDao,
            predictedQaDao = predictedQaDao,
            chartRequester = ::requestChart
        )

    /**
     * 2026-06-28 — Frente A (proactivity inline no chat). Thin wrapper
     * around [com.example.data.notifications.TriggerEvaluator.evaluate] so
     * the ChatViewModel can ask "what proactive nudge would fire RIGHT NOW
     * for the chat tab?" without us exposing the FoodDao + TargetSnapshotDao
     * directly. Reuses the exact same rules and dedup contract as the
     * notification path — the difference lies in [com.example.ui.ChatViewModel]
     * which gates the result by a chat-specific 4h cooldown instead of the
     * NotificationDedupStore.
     */
    suspend fun evaluateProactiveTriggerForChat(
        nowZdt: java.time.ZonedDateTime,
        prefs: com.example.data.notifications.NotificationPrefs,
        shStepsByDate: Map<java.time.LocalDate, Long>,
        shDailyActivity: Map<java.time.LocalDate, com.example.data.health.ShDailyActivity>
    ): com.example.data.notifications.Trigger? =
        com.example.data.notifications.TriggerEvaluator.evaluate(
            nowZdt = nowZdt,
            prefs = prefs,
            // Chat-side cooldown handles dedup, so disable the
            // notification-side dedup here (always reports "not seen").
            dedup = { false },
            foodDao = foodDao,
            targetSnapshotDao = targetSnapshotDao,
            shStepsByDate = shStepsByDate,
            shDailyActivity = shDailyActivity
        )

    /** Factory: streaming food-analysis orchestrator. Same role as
     *  [createChatOrchestrator] but for the Activity tab — emits
     *  [com.example.data.meal.MealAnalysisEvent]s so the capture card and
     *  edit-meal CTA can show real phases ("A enviar imagem…",
     *  "A identificar o prato…", "A extrair ingredientes…", …). The
     *  blocking [analyzeFoodImage] / [reanalyzeFoodFromSummary] APIs below
     *  remain as wrappers for callers that don't yet need streaming. */
    fun createMealAnalysisOrchestrator(
        nutritionLookupService: com.example.data.nutrition.NutritionLookupService? = null,
    ): com.example.data.meal.MealAnalysisOrchestrator =
        com.example.data.meal.MealAnalysisOrchestrator(
            repository = this,
            nutritionLookupService = nutritionLookupService,
        )

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessionsFlow()

    // Food log persistence APIs
    val allFoods: Flow<List<FoodEntry>> = foodDao.getAllFoodsFlow()

    suspend fun insertFood(entry: FoodEntry): Long {
        return foodDao.insertFood(entry)
    }

    suspend fun deleteFood(entry: FoodEntry) {
        foodDao.deleteFood(entry)
    }

    suspend fun clearAllFoods() {
        foodDao.clearAllFoods()
    }

    /** Companion to [clearAllFoods] — wipes the manual `activities` table. The
     *  "Eliminar dados da aplicação" button calls both so the Reports tab
     *  resets to the same blank-slate that a fresh install would show. */
    suspend fun clearAllActivities() {
        activityDao.clearAllActivities()
    }

    // Food habit persistence APIs
    val activeHabits: Flow<List<FoodHabit>> = foodHabitDao.getActiveHabitsFlow()
    val allHabits: Flow<List<FoodHabit>> = foodHabitDao.getAllHabitsFlow()

    suspend fun upsertHabit(habit: FoodHabit): Long {
        val now = System.currentTimeMillis()
        return if (habit.id == 0L) {
            foodHabitDao.insertHabit(habit.copy(createdAt = now, updatedAt = now))
        } else {
            foodHabitDao.updateHabit(habit.copy(updatedAt = now))
            habit.id
        }
    }

    suspend fun deleteHabit(habit: FoodHabit) {
        foodHabitDao.deleteHabit(habit)
    }

    suspend fun setHabitActive(id: Long, active: Boolean) {
        foodHabitDao.setActive(id, active)
    }

    suspend fun getHabitById(id: Long): FoodHabit? = foodHabitDao.getById(id)

    // Activity log persistence APIs
    val allActivities: Flow<List<ActivityEntry>> = activityDao.getAllActivitiesFlow()

    suspend fun getSession(sessionId: String): List<ActivityEntry> {
        return activityDao.getSession(sessionId)
    }

    suspend fun insertActivity(entry: ActivityEntry): Long {
        return activityDao.insertActivity(entry)
    }

    suspend fun deleteActivity(entry: ActivityEntry) {
        activityDao.deleteActivity(entry)
    }

    suspend fun updateFood(entry: FoodEntry) {
        foodDao.updateFood(entry)
    }

    suspend fun updateActivitySession(sessionId: String, entries: List<ActivityEntry>) {
        activityDao.replaceSession(sessionId, entries)
    }

    suspend fun deleteActivitySession(sessionId: String) {
        activityDao.deleteSession(sessionId)
    }

    suspend fun existsByShRecordId(shRecordId: String): Boolean {
        return activityDao.existsByShRecordId(shRecordId)
    }

    suspend fun findByShRecordId(shRecordId: String): ActivityEntry? {
        return activityDao.findByShRecordId(shRecordId)
    }

    /** Drop activities that were imported from Samsung Health in earlier
     *  app versions. Returns row count for telemetry. */
    suspend fun purgeShImportedActivities(): Int {
        return activityDao.deleteAllShImported()
    }

    // ----- Target snapshots (versioned health-profile targets) -----
    // The target_snapshots table is append-only: every change to a target field
    // produces a new row keyed by its effective day, so reports about past
    // periods compare against the targets that were in force on those days
    // instead of being silently re-anchored to today's numbers. See
    // TargetResolver and the migration in ChatDatabase.MIGRATION_8_9.

    val targetSnapshots: kotlinx.coroutines.flow.Flow<List<com.example.data.local.TargetSnapshot>> =
        targetSnapshotDao.observeAll()

    suspend fun insertTargetSnapshot(snapshot: com.example.data.local.TargetSnapshot): Long {
        return targetSnapshotDao.insert(snapshot)
    }

    suspend fun findTargetEffectiveAt(epochDay: Long): com.example.data.local.TargetSnapshot? {
        return targetSnapshotDao.findEffectiveAt(epochDay)
    }

    suspend fun listTargetSnapshots(): List<com.example.data.local.TargetSnapshot> {
        return targetSnapshotDao.listAll()
    }

    suspend fun latestTargetSnapshot(): com.example.data.local.TargetSnapshot? {
        return targetSnapshotDao.latest()
    }

    // ----- BMR snapshots (versioned resting metabolic rate) -----
    // Same shape as target_snapshots: append-only, keyed by effective day. See
    // BmrResolver for the lookup/aggregation algorithm and the v9→v10 migration
    // in ChatDatabase.migration_9_10 for legacy seeding from user_profile_pref.

    val bmrSnapshots: kotlinx.coroutines.flow.Flow<List<com.example.data.local.BmrSnapshotEntity>> =
        bmrSnapshotDao.observeAll()

    /** Daily activity snapshots — frozen `workouts + casual movement` totals per
     *  day, written by [com.example.data.health.DailyActivitySnapshotWorker]. */
    val dailyActivitySnapshots:
        kotlinx.coroutines.flow.Flow<List<com.example.data.local.DailyActivitySnapshotEntity>> =
        dailyActivitySnapshotDao.observeAll()

    suspend fun insertBmrSnapshot(snapshot: com.example.data.local.BmrSnapshotEntity): Long {
        return bmrSnapshotDao.insert(snapshot)
    }

    suspend fun findBmrEffectiveAt(epochDay: Long): com.example.data.local.BmrSnapshotEntity? {
        return bmrSnapshotDao.findEffectiveAt(epochDay)
    }

    suspend fun listBmrSnapshots(): List<com.example.data.local.BmrSnapshotEntity> {
        return bmrSnapshotDao.listAll()
    }

    suspend fun latestBmrSnapshot(): com.example.data.local.BmrSnapshotEntity? {
        return bmrSnapshotDao.latest()
    }

    // ─ Body Composition snapshots (2026-06-26) ──────────────────────────
    // Local cache of Samsung Health BodyComposition readings. Append-only.
    // Source of truth remains S-Health — these methods only persist what
    // the worker / ViewModel already read. See [BodyCompositionSnapshotEntity].

    fun observeLatestBodyComposition():
        kotlinx.coroutines.flow.Flow<com.example.data.local.BodyCompositionSnapshotEntity?> =
        bodyCompositionSnapshotDao.observeLatest()

    suspend fun latestBodyCompositionSnapshot():
        com.example.data.local.BodyCompositionSnapshotEntity? =
        bodyCompositionSnapshotDao.latest()

    suspend fun recentBodyCompositionSnapshots(limit: Int):
        List<com.example.data.local.BodyCompositionSnapshotEntity> =
        bodyCompositionSnapshotDao.recent(limit)

    /** Snapshots inside `[startMs, endMs)`, ascending by source timestamp.
     *  The Reports tab's Overview card calls this with a generous left
     *  edge (typically `startMs − 180 days`) so the forward-fill of
     *  empty weeks/months can anchor on prior measurements. */
    suspend fun bodyCompositionSnapshotsBetween(
        startMs: Long, endMs: Long
    ): List<com.example.data.local.BodyCompositionSnapshotEntity> =
        bodyCompositionSnapshotDao.getBetween(startMs, endMs)

    suspend fun bodyCompositionSnapshotExists(sourceTimestampMs: Long): Boolean =
        bodyCompositionSnapshotDao.existsForSourceTimestamp(sourceTimestampMs)

    suspend fun insertBodyCompositionSnapshot(
        snapshot: com.example.data.local.BodyCompositionSnapshotEntity
    ): Long = bodyCompositionSnapshotDao.insert(snapshot)

    // Phase E: persistent user-insight API. The data-card builder reads from
    // topConfirmedInsights() each turn; the chat parser writes pending or
    // auto-confirmed entries via insertUserInsight().

    fun pendingInsights(): kotlinx.coroutines.flow.Flow<List<com.example.data.local.UserInsight>> =
        userInsightDao.pendingFlow()

    fun allActiveInsights(): kotlinx.coroutines.flow.Flow<List<com.example.data.local.UserInsight>> =
        userInsightDao.allActiveFlow()

    suspend fun topConfirmedInsights(limit: Int = 8): List<com.example.data.local.UserInsight> =
        userInsightDao.topConfirmed(limit)

    suspend fun insertUserInsight(insight: com.example.data.local.UserInsight): Long {
        // Skip exact duplicates (same text + category) so the LLM repeating
        // itself across turns doesn't multiply rows.
        val existing = userInsightDao.findByText(insight.text, insight.category)
        if (existing != null) return existing.id
        return userInsightDao.insert(insight)
    }

    suspend fun confirmUserInsight(id: Long) = userInsightDao.markConfirmed(id)

    suspend fun expireUserInsight(id: Long) = userInsightDao.softDelete(id)

    /** 2026-06-29 — Memory polish. Inverse of [expireUserInsight]: clears
     *  `expiredAtMs` so a soft-deleted insight rejoins the active recall
     *  pool. Wired to [MemoryDialog]'s "Restore" button. */
    suspend fun restoreUserInsight(id: Long) = userInsightDao.restore(id)

    /** 2026-06-29 — Memory polish. ALL insights including soft-deleted.
     *  Used by [MemoryDialog] for the "Forgotten" collapsible. */
    fun allInsightsIncludingExpired(): kotlinx.coroutines.flow.Flow<List<com.example.data.local.UserInsight>> =
        userInsightDao.allIncludingExpiredFlow()

    /**
     * 2026-06-29 — Frente Memory C2.a (backfill). One-shot keyword
     * extraction for `user_insights` rows that pre-date the v23 schema
     * (their `keywords` column is NULL). Idempotent: skips rows that
     * already have keywords. Safe to call multiple times — each row is
     * tokenised once and the result persisted.
     *
     * Should be invoked once per app launch (cheap when there's nothing
     * to do: a single SELECT + LIMIT 200 returns an empty list).
     *
     * The tokeniser is the SAME one used by [com.example.data.chat.InsightExtractor.keywordize]
     * so a fact captured by the extractor today and a legacy fact
     * backfilled here share the same vocabulary. The recall ranker can
     * then score them uniformly.
     *
     * Returns the number of rows actually backfilled. Reports go to
     * logcat tag `CHAT_KB`.
     *
     * 2026-06-29 second pass — also catches the edge case where the
     * extractor inserted a row with an empty `keywords` string (vs
     * NULL). Treats both as "needs backfill" so a recovery from the
     * insertion bug is automatic. */
    suspend fun backfillInsightKeywords(): Int {
        // Pull both NULL and empty-string rows so the second pass also
        // fixes the auto_extracted rows whose `keywords` was set to ""
        // by an earlier build of the extractor (id=19 on Hugo's Pixel,
        // captured 2026-06-29 14:25).
        val pending = runCatching { userInsightDao.missingKeywords(limit = 200) }
            .getOrDefault(emptyList()) +
            runCatching { userInsightDao.emptyKeywords(limit = 200) }
                .getOrDefault(emptyList())
        if (pending.isEmpty()) return 0
        var filled = 0
        for (row in pending) {
            val keywords = com.example.data.chat.InsightExtractor.keywordize(row.text)
            val summary = row.text
                .take(70)
                .let { if (row.text.length > 70) "$it…" else it }
            runCatching {
                userInsightDao.updateKeywordsAndSummary(
                    id = row.id,
                    keywords = keywords,
                    summary = summary,
                )
                filled += 1
            }.onFailure { t ->
                android.util.Log.w("CHAT_KB", "backfill failed for id=${row.id}: ${t.message}")
            }
        }
        android.util.Log.d("CHAT_KB", "backfill completed: $filled rows updated")
        return filled
    }

    /**
     * 2026-06-29 — Frente 3. One-shot curator that soft-deletes
     * `user_insights` rows whose `text` mentions an age that contradicts
     * the user's current [birthYear]. Runs ONCE — guarded by a
     * SharedPreferences flag so re-installs don't re-curate.
     *
     * Strategy:
     *   1. For each non-expired insight, regex-extract every "NN anos"
     *      / "NN years old" mention (NN = 2 digits).
     *   2. Compute the user's two acceptable ages: full years already
     *      lived (currentYear - birthYear) and one less (in case the
     *      user hasn't had their birthday yet this year).
     *   3. If the row mentions an age outside that window, soft-delete
     *      (set `expiredAtMs = now`). NEVER physically DELETE — the
     *      audit trail is preserved.
     *
     * Hugo's birthYear was wrong on the Pixel (1980 → 1977 after
     * Frente 1). Until he edits, this job does nothing harmful; once
     * the prefs flip, the next launch runs the curation against the
     * correct birthYear and the stale insight (id=16, "tem 47 anos
     * atualmente") is expired.
     *
     * Idempotency: keys on `chat_memory/age_curation_done_v1` so a
     * future second pass can be triggered by bumping the version
     * suffix (not by deleting the key).
     *
     * @return number of insights soft-deleted.
     */
    suspend fun curateConflictingAgeInsights(
        context: android.content.Context,
        birthYear: Int,
    ): Int {
        val prefs = context.getSharedPreferences("chat_memory", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("age_curation_done_v1", false)) {
            android.util.Log.d("CHAT_KB_CURATE", "skip: already done")
            return 0
        }
        if (birthYear < 1900) {
            android.util.Log.d("CHAT_KB_CURATE", "skip: birthYear=$birthYear looks unset")
            return 0
        }
        val currentYear = java.time.LocalDate.now().year
        val ageFull = currentYear - birthYear              // had birthday already
        val ageBeforeBday = (currentYear - birthYear - 1)  // birthday not yet this year
        // Both ages are acceptable — pick whichever the row asserts.
        val acceptable = setOf(ageFull, ageBeforeBday)
        android.util.Log.d(
            "CHAT_KB_CURATE",
            "running: birthYear=$birthYear currentYear=$currentYear acceptable=$acceptable"
        )

        val rows = runCatching { userInsightDao.allConfirmedActive() }
            .getOrDefault(emptyList())
        // Match "NN anos" (pt) / "NN years" (en) — capture each two-digit
        // age mention separately so a row can claim multiple ages (e.g.
        // "47 anos atualmente, completa 48").
        val ageRegex = Regex("""\b(\d{2})\s+anos?\b|\b(\d{2})\s+years?\b""", RegexOption.IGNORE_CASE)
        var expired = 0
        for (row in rows) {
            val matches = ageRegex.findAll(row.text).toList()
            if (matches.isEmpty()) continue
            // Collect every two-digit number this row claims as an age.
            val claimedAges = matches.mapNotNull { m ->
                (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            }.toSet()
            if (claimedAges.isEmpty()) continue
            // A row is "stale" when AT LEAST ONE of its claimed ages is
            // outside the acceptable window AND NONE of its claimed ages
            // is inside. (A row that says "tem 48 anos e fará 49" must
            // survive — 48 is acceptable now, 49 only after the birthday,
            // and birthday is the very reason we accept ageFull OR
            // ageBeforeBday.)
            val anyAcceptable = claimedAges.any { it in acceptable }
            if (!anyAcceptable) {
                android.util.Log.d(
                    "CHAT_KB_CURATE",
                    "expire id=${row.id} ages=$claimedAges text=${row.text.take(80)}"
                )
                runCatching {
                    userInsightDao.softDelete(row.id, System.currentTimeMillis())
                    expired += 1
                }.onFailure { t ->
                    android.util.Log.w("CHAT_KB_CURATE", "soft-delete failed id=${row.id}: ${t.message}")
                }
            }
        }
        prefs.edit().putBoolean("age_curation_done_v1", true).apply()
        android.util.Log.i("CHAT_KB_CURATE", "completed: $expired insights expired (birthYear=$birthYear)")
        return expired
    }


    /**
     * 2026-06-29 — Frente Memory C1.b. Insert the user [ChatMessage] row
     * eagerly from [com.example.ui.ChatViewModel.sendMessage] (before the
     * typing indicator paints) so the Room Flow that backs the timeline
     * emits the bubble immediately. Caller then passes
     * `skipUserMessageInsert=true` to [com.example.data.chat.ChatOrchestrator.handle]
     * so the orchestrator does NOT insert a duplicate row.
     *
     * Returns the new row id, mostly so the caller can log it for diagnostics.
     */
    suspend fun insertUserChatMessage(
        sessionId: Long,
        text: String,
        imageBase64Stored: String?,
    ): Long {
        val id = chatDao.insertMessage(
            com.example.data.local.ChatMessage(
                sessionId = sessionId,
                sender = "user",
                text = text,
                imageBase64 = imageBase64Stored,
            )
        )
        chatDao.touchSession(sessionId)
        return id
    }

    suspend fun insertErrorChatMessage(sessionId: Long, text: String): Long {
        val id = chatDao.insertMessage(
            com.example.data.local.ChatMessage(
                sessionId = sessionId,
                sender = "gemini",
                text = text,
                isError = true,
            )
        )
        chatDao.touchSession(sessionId)
        return id
    }

    suspend fun getPendingAnalysisFoodEntries(): List<com.example.data.local.FoodEntry> =
        foodDao.getPendingAnalysisEntries()

    /**
     * Phase E (the missing piece — Hugo asked for this when he reformulated
     * the knowledge-graph option): runs the
     * [com.example.data.insights.AggregateInsightAnalyzer] over the full
     * food history + target snapshots and persists each new finding as a
     * pending [com.example.data.local.UserInsight]. The findings then
     * surface in the chat as `[INSIGHT_PROPOSAL]`-style cards (the model
     * is told they exist via the data card; the cards themselves are
     * triggered the next time the user opens the chat).
     *
     * Pure side-effect on the DB. Returns the number of NEW insights
     * inserted (de-duplication is on text+category).
     *
     * Cooldown: skipped if the analyzer has already produced at least one
     * row in the last 24h. Pass [force] = true to bypass (useful for tests
     * or a hypothetical "Re-scan now" button). Cooldown is read from the
     * `user_insights` table itself — no extra preferences key needed.
     */
    suspend fun runAggregateAnalyzer(
        windowDays: Int = 90,
        force: Boolean = false
    ): Int {
        if (!force) {
            val lastRun = userInsightDao.lastAggregateRunMs() ?: 0L
            val cooldownMs = 24L * 60 * 60 * 1000
            if (System.currentTimeMillis() - lastRun < cooldownMs) return 0
        }
        val foods = foodDao.getAllFoodsFlow().first()
        val snapshots = targetSnapshotDao.listAll()
        if (foods.isEmpty() || snapshots.isEmpty()) return 0
        val findings = com.example.data.insights.AggregateInsightAnalyzer.analyze(
            foods = foods,
            targetSnapshots = snapshots,
            windowDays = windowDays
        )
        if (findings.isEmpty()) return 0
        return com.example.data.insights.AggregateInsightAnalyzer.persist(findings, userInsightDao)
    }

    /**
     * 2026-06-28 — Frente C (paralelismo text+chart). Single non-streaming
     * Gemini call that asks ONLY for a [`CHART_PROPOSAL`][ChartProposal]
     * JSON, parsed via [ActionProposalParser]. Used by
     * [com.example.data.chat.ChatOrchestrator.handle] in parallel with the
     * main text stream, so the user sees their textual answer first and
     * then the chart attaches a few seconds later.
     *
     * Failure modes — all return `null` so the orchestrator silently drops
     * the chart without ever showing a "chart loading… failed" flash:
     *  - API key missing
     *  - HTTP 429 (free-tier exhausted this minute)
     *  - Empty / unparseable response
     *  - The 8-second timeout
     *  - Any uncaught exception
     *
     * @param dataContextBlock the same pre-fetched markdown the text path
     *  saw — without it the model can't ground the chart values in real
     *  data and might invent numbers.
     * @param userText the original user question, used to anchor the chart
     *  title language and topic.
     */
    suspend fun requestChart(
        userText: String,
        dataContextBlock: String,
        userName: String,
        language: String
    ): ChartProposal? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            android.util.Log.d("CHAT_PARALLEL", "requestChart: API key missing, skipping")
            return null
        }
        val systemInstruction = "You produce ONE [CHART_PROPOSAL] JSON block and nothing else. " +
            "User is $userName. Language for title/labels: $language.\n\n" +
            "Reply must be EXACTLY this format, with NO surrounding prose:\n" +
            "[CHART_PROPOSAL]{\"kind\":\"bar|line|donut|multiline\",\"title\":\"...\",\"subtitle\":\"...\", ...}[/CHART_PROPOSAL]\n\n" +
            "Rules:\n" +
            "- kind: 'bar' for ≤14 points, 'line' for 15-30 points, 'donut' for 3-6 categories, 'multiline' for 2-4 related KPIs on a shared timeline.\n" +
            "- For bar/line/donut: use flat schema {series:[{label,value,highlight}], unit, target}. series 2-30 entries.\n" +
            "- For multiline: use {xLabels:[...], lines:[{label,unit,values:[...],kpi:{latest,delta,deltaTone}}]}. 2-4 lines; each values length == xLabels length; use null for gaps. deltaTone in {good,bad,neutral} — editorial: does the direction serve the user's goal?\n" +
            "- Values: integers for kcal/steps; 1 decimal for grams and kg; integers for percentages.\n" +
            "- ONLY use numbers present in the PRE-FETCHED DATA block below. Do NOT invent.\n" +
            "- If the data doesn't contain enough points for a useful chart, output the literal string NO_CHART (nothing else) and the app will skip.\n\n" +
            dataContextBlock
        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = userText)))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemInstruction))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 1024,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return try {
            val raw = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                runCatching {
                    GeminiRetrofitClient.service.generateContent(
                        model = "gemini-3.5-flash",
                        apiKey = apiKey,
                        request = request
                    ).candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                }.getOrNull()
            }
            if (raw.isNullOrBlank()) {
                android.util.Log.d("CHAT_PARALLEL", "requestChart: empty/timeout")
                return null
            }
            if (raw.trim() == "NO_CHART") {
                android.util.Log.d("CHAT_PARALLEL", "requestChart: model declined (NO_CHART)")
                return null
            }
            // Reuse the same canonical+salvage parser the main text path uses
            // so a chart from this call is byte-identical to one inlined in
            // a chat reply. The parser returns at most one chart proposal.
            ActionProposalParser.parse(raw).chartProposals.firstOrNull().also { ch ->
                if (ch == null) {
                    android.util.Log.d("CHAT_PARALLEL", "requestChart: parser found no chart in ${raw.length}-char reply")
                } else {
                    android.util.Log.d("CHAT_PARALLEL", "requestChart: ok kind=${ch.kind} series=${ch.series.size}")
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("CHAT_PARALLEL", "requestChart failed: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            null
        }
    }

    private suspend fun generateContentWithFallback(
        apiKey: String,
        initialModel: String,
        request: GeminiRequest,
        language: String
    ): Result<String> {
        // 2026-08-16 — cascade shortened and made vision-safe. This method is
        // used by the meal-analysis (vision) path, so gemini-3.7-flash is
        // EXCLUDED (returns HTTP 503 on image input). Three models, each with
        // its own 20/day quota, ordered by vision reliability: 3.6 → 3.5 → lite.
        // Shorter cascade = fewer round-trips per failure = slower quota burn
        // (the previous 5-model list multiplied calls and exhausted quota).
        val models = listOf(
            initialModel,
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
        ).distinct()
        var lastError: Exception? = null

        for ((index, modelName) in models.withIndex()) {
            val maxAttempts = if (index == 0) 3 else 2
            for (attempt in 1..maxAttempts) {
                try {
                    val response = GeminiRetrofitClient.service.generateContent(
                         model = modelName,
                         apiKey = apiKey,
                         request = request
                    )
                    
                    val responseError = response.error
                    if (responseError != null) {
                        val code = responseError.code ?: 0
                        if (code == 429 || responseError.message?.contains("429") == true) {
                            lastError = Exception(responseError.message ?: tr("Quota Exceeded (429)", language))
                            if (attempt < maxAttempts) {
                                kotlinx.coroutines.delay(rateLimitBackoffMs(attempt))
                                continue
                            } else {
                                break // Proceed to next model
                            }
                        }
                        // For other server errors (e.g. model not found or unsupported), record error and proceed to fallback model
                        lastError = Exception(responseError.message ?: tr("API Error", language))
                        break
                    }
                    
                    val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (rawText.isNullOrBlank()) {
                        lastError = Exception(tr("Empty API response", language))
                        break
                    }
                    
                    return Result.success(rawText)
                } catch (e: Exception) {
                    lastError = e
                    val isRateLimit = e is retrofit2.HttpException && e.code() == 429
                    val isMessageRateLimit = e.message?.contains("429") == true
                    if (isRateLimit || isMessageRateLimit) {
                         if (attempt < maxAttempts) {
                             kotlinx.coroutines.delay(rateLimitBackoffMs(attempt))
                             continue
                         } else {
                             break
                         }
                    }
                    // For other exceptions (e.g. network timeout), proceed immediately to fallback model
                    break
                }
            }
        }
        return Result.failure(lastError ?: Exception(tr("Unknown error in analysis", language)))
    }

    /**
     * Exponential backoff with small jitter for Gemini 429 retries. Mirrors
     * the same helper in [com.example.data.chat.ChatOrchestrator]; both
     * paths share the same upstream quota bucket so they should pace
     * themselves identically. See that copy for the rationale on the 800ms
     * base + 8s cap + 300ms jitter triple.
     *
     * @param attempt 1-based attempt counter.
     * @return delay in ms, capped at 8s.
     */
    private fun rateLimitBackoffMs(attempt: Int): Long {
        val base = 800L * (1L shl (attempt - 1).coerceAtLeast(0))
        val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 300)
        return (base + jitter).coerceAtMost(8_000L)
    }

    /**
     * Pick habits to inject into a food-analysis prompt. Strategy: include every
     * active habit so the model can decide which ones are consistent with the
     * image/description. Filtering by [categoryHint] is done loosely — if the
     * habit declares a category, it must match (or [categoryHint] must be null).
     * The model is instructed to return only the IDs it actually used.
     */
    internal suspend fun getActiveHabitsSnapshot(): List<FoodHabit> =
        foodHabitDao.getActiveHabitsSnapshot()

    /** Blocking fallback used by [com.example.data.meal.MealAnalysisOrchestrator]
     *  when the streaming endpoint refuses the request (zero chunks back),
     *  AND for the automatic flash→pro escalation when the streaming Flash
     *  result returns `confidence: low|medium`. Runs the same [GeminiRequest]
     *  through [generateContentWithFallback] (vision-safe 3-model cascade,
     *  retries on 429) and parses the final JSON via Moshi.
     *
     *  [initialModel] seeds the fallback ladder with the model TRIED FIRST —
     *  the chunk-count==0 path defaults to `gemini-3.6-flash` (FLASH_MODEL) and
     *  the escalation passes `gemini-3.5-flash` (PRO_MODEL) to refine a
     *  low-confidence result. Both are vision-confirmed. */
    internal suspend fun runFoodImageBlockingForRequest(
        request: GeminiRequest,
        initialModel: String = "gemini-3.6-flash",
        language: String = "en"
    ): FoodAnalysisResult? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") return null
        val rawJson = generateContentWithFallback(
            apiKey = apiKey,
            initialModel = initialModel,
            request = request,
            language = language
        ).getOrNull() ?: return null
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val adapter = moshi.adapter(FoodAnalysisResult::class.java)
        return runCatching { adapter.fromJson(rawJson) }.getOrNull()
    }

    internal fun selectHabitsForPrompt(
        habits: List<FoodHabit>,
        categoryHint: MealCategory?
    ): List<FoodHabit> {
        if (habits.isEmpty()) return emptyList()
        return habits.filter { h ->
            h.mealCategoryHint == null || categoryHint == null || h.mealCategoryHint == categoryHint
        }
    }

    internal fun buildHabitsPromptBlock(
        habits: List<FoodHabit>,
        promptHint: String? = null
    ): String {
        if (habits.isEmpty()) return ""

        // Deterministic pre-match: does any of a habit's trigger keywords appear
        // in the meal hint / name? Used only to raise salience via [LIKELY MATCH]
        // — never a hard force, since the photo may differ from the name.
        val hintLower = (promptHint ?: "").lowercase()
        fun triggersMatch(h: FoodHabit): Boolean =
            h.trigger.split(';')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .any { hintLower.contains(it) }

        var anyFlagged = false
        val lines = habits.joinToString("\n") { h ->
            val triggers = h.trigger.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
            val matched = triggersMatch(h)
            if (matched) anyFlagged = true
            val flag = if (matched) " [LIKELY MATCH — the meal hint mentions this]" else ""
            "- id=${h.id}$flag: triggers=[$triggers] -> ${h.descriptor}"
        }

        // Only explain the [LIKELY MATCH] marker when at least one habit carries
        // it — otherwise the sentence references a flag the model never sees.
        val flagNote = if (anyFlagged) {
            " Patterns flagged [LIKELY MATCH] were detected in the meal hint and should almost certainly be applied unless the photo clearly contradicts them."
        } else ""

        return """

User-known meal patterns. Each pattern lists trigger keywords and a descriptor of what the user actually eats. **Actively match these patterns against what you SEE in the photo, not just against any text hint** — if you can identify a component in the image (e.g. an egg, bread, coffee, a sausage) and a pattern's trigger keyword names that same component, that pattern APPLIES and its descriptor is AUTHORITATIVE for that component: it OVERRIDES generic reference assumptions and OVERRIDES your own visual guess about preparation method. Examples: a pattern "Ovo → cooked in the microwave in water, no oil" means the egg carries ZERO added cooking fat even if it looks fried/estrelado in the photo — trust the pattern over appearance. A "café com leite → pea-protein barista base" pattern means that drink carries the pattern's macros and NO dairy fat. Apply a pattern only to the component it describes; do not force it onto unrelated items, and do not invent matches for components the meal does not contain.$flagNote
$lines

If you used any of the patterns above, return their numeric ids in an "appliedHabitIds" JSON array. If none applied, return an empty array. Never include ids that were not listed above.
""".trimIndent()
    }

    internal suspend fun markHabitsApplied(ids: List<Long>?) {
        if (ids.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        ids.forEach { id -> foodHabitDao.markApplied(id, now) }
    }

    /**
     * Phase F: run a [QUERY_DATA] request from the assistant against the
     * local DB and return a markdown block ready to be injected as the next
     * user turn.
     *
     * Hard cap on 90 days — anything longer is collapsed to a "window too
     * wide" message so a runaway prompt can't dump hundreds of rows.
     */
    private suspend fun runQueryAndFormat(req: QueryDataRequest): String {
        val zone = java.time.ZoneId.systemDefault()
        val from = runCatching { java.time.LocalDate.parse(req.fromDate) }.getOrNull()
        val to = runCatching { java.time.LocalDate.parse(req.toDate) }.getOrNull()
        if (from == null || to == null || from.isAfter(to)) {
            return "ERROR: invalid date range ${req.fromDate}..${req.toDate}"
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        if (days > 90) {
            return "ERROR: window too wide ($days days). Ask the user to narrow to <=90 days."
        }
        val startMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        return when (req.type) {
            "meals" -> {
                val rows = foodDao.getAllFoods().filter { it.timestamp in startMs until endMs }
                if (rows.isEmpty()) "No meals logged between ${req.fromDate} and ${req.toDate}."
                else buildString {
                    append("Meals between ${req.fromDate} and ${req.toDate} (${rows.size} entries):\n")
                    rows.sortedBy { it.timestamp }.forEach { f ->
                        val date = java.time.Instant.ofEpochMilli(f.timestamp).atZone(zone).toLocalDate()
                        append("- ").append(date).append(" · ").append(f.name)
                            .append(" · ").append(f.calories).append("kcal")
                            .append(" · P").append(f.protein.toInt())
                            .append("/C").append(f.carbs.toInt())
                            .append("/F").append(f.fat.toInt())
                            .append(" · ").append(f.mealCategory.name.lowercase())
                            .append('\n')
                    }
                    val totalKcal = rows.sumOf { it.calories }
                    append("\nTotals: $totalKcal kcal across $days days (avg ${totalKcal / days} kcal/day).")
                }
            }
            "activity" -> {
                val rows = activityDao.getAllActivitiesFlow().first().filter { it.timestamp in startMs until endMs }
                if (rows.isEmpty()) "No activity logged between ${req.fromDate} and ${req.toDate}."
                else buildString {
                    append("Activities between ${req.fromDate} and ${req.toDate} (${rows.size} entries):\n")
                    rows.sortedBy { it.timestamp }.forEach { a ->
                        val date = java.time.Instant.ofEpochMilli(a.timestamp).atZone(zone).toLocalDate()
                        append("- ").append(date).append(" · ").append(a.name)
                            .append(" · ").append(a.durationMinutes).append("min")
                            .append(" · ").append(a.caloriesBurned).append("kcal")
                            .append(" · ").append(a.activityType.name.lowercase())
                            .append('\n')
                    }
                    val totalMin = rows.sumOf { it.durationMinutes }
                    val totalKcal = rows.sumOf { it.caloriesBurned }
                    append("\nTotals: $totalMin min · $totalKcal kcal burned across $days days.")
                }
            }
            else -> "ERROR: unknown query type '${req.type}'."
        }
    }

    /**
     * Phase C: turn the persisted `imageBase64` column into a list of base64
     * payloads ready for the Gemini inline-data Part. Tolerates three formats:
     *
     *  - JSON array of `file://` paths → each file read + base64-encoded
     *  - single `file://` path → that one file
     *  - anything else → assumed to be a legacy raw base64 blob, returned as-is
     *
     * Files that fail to read are silently skipped (logcat-only) so a single
     * missing image doesn't crash a whole conversation reload.
     */
    /** Resolved chat-attached image, ready to drop into a Gemini `InlineData`
     *  part. Mirror of the same class in [com.example.data.chat.ChatOrchestrator]
     *  — kept duplicated so each module stays self-contained. Carries the
     *  actual MIME type derived from the on-disk file extension instead of
     *  the legacy hardcoded `image/jpeg`. */
    private data class ResolvedImage(val base64: String, val mimeType: String)

    private fun resolveImagePayload(stored: String): List<ResolvedImage> {
        android.util.Log.d(
            "CHAT_IMG",
            "resolve(legacy): stored.len=${stored.length} head=${stored.take(100)}"
        )
        val parts = com.example.ui.ChatImageStore.decode(stored)
        android.util.Log.d("CHAT_IMG", "resolve(legacy): parts.size=${parts.size}")
        if (parts.isEmpty()) return emptyList()
        return parts.mapIndexedNotNull { i, entry ->
            val isP = com.example.ui.ChatImageStore.isPath(entry)
            android.util.Log.d(
                "CHAT_IMG",
                "  [$i] isPath=$isP entry.head=${entry.take(80)}"
            )
            if (isP) {
                val uri = android.net.Uri.parse(entry)
                val rawPath = uri.path
                if (rawPath == null) {
                    android.util.Log.w(
                        "CHAT_IMG",
                        "  [$i] uri.path was null scheme=${uri.scheme} entry=$entry"
                    )
                    return@mapIndexedNotNull null
                }
                // See ChatOrchestrator.resolveImagePayload for the rationale:
                // legacy `content://com.example.fileprovider/chat_images/<x>`
                // rows reconstruct to `/data/data/<pkg>/files/<rawPath>`.
                val path = if (java.io.File(rawPath).exists()) {
                    rawPath
                } else {
                    val recovered = "/data/data/${com.example.BuildConfig.APPLICATION_ID}/files${rawPath}"
                    if (java.io.File(recovered).exists()) {
                        android.util.Log.d(
                            "CHAT_IMG",
                            "  [$i] recovered legacy content:// path → $recovered"
                        )
                        recovered
                    } else {
                        rawPath
                    }
                }
                val file = java.io.File(path)
                val exists = file.exists()
                val canRead = file.canRead()
                val len = if (exists) file.length() else -1L
                android.util.Log.d(
                    "CHAT_IMG",
                    "  [$i] file path=$path exists=$exists canRead=$canRead size=$len"
                )
                if (!exists || !canRead) {
                    android.util.Log.w(
                        "CHAT_IMG",
                        "  [$i] file missing/unreadable — skipping"
                    )
                    return@mapIndexedNotNull null
                }
                val bytes = try {
                    file.readBytes()
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "CHAT_IMG",
                        "  [$i] readBytes failed: ${t.javaClass.simpleName}: ${t.message}",
                        t
                    )
                    return@mapIndexedNotNull null
                }
                val mime = mimeFromPath(path)
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                android.util.Log.d(
                    "CHAT_IMG",
                    "  [$i] OK bytes=${bytes.size} b64.len=${b64.length} mime=$mime"
                )
                ResolvedImage(b64, mime)
            } else {
                android.util.Log.d(
                    "CHAT_IMG",
                    "  [$i] legacy raw-base64 path, len=${entry.length}"
                )
                ResolvedImage(entry, "image/jpeg")
            }
        }
    }

    /** Derive Gemini's `inlineData.mimeType` from the on-disk file extension.
     *  `ChatImageStore.persistFromUri` preserves the original extension; we
     *  only have to map it. Falls back to JPEG for unknown extensions. */
    private fun mimeFromPath(path: String): String {
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /**
     * Tells Gemini which language to use for the human-readable text fields
     * (`name`, `fullSummary`, `notes`) while pinning categorical tokens
     * (`mealCategory`, `activityType`) to their canonical English form so the
     * downstream parsers (`MealCategory.parse`, `ActivityType` lookup) keep
     * working regardless of UI language.
     */
    internal fun languageInstruction(language: String): String {
        val readable = when (language) {
            "pt-PT", "pt" -> "Portuguese (Portugal)"
            "gu" -> "Gujarati"
            else -> "English"
        }
        return "Write the values of the JSON fields \"name\", \"fullSummary\" and \"notes\" in $readable. " +
            "Keep the JSON keys themselves in English. " +
            "Keep \"mealCategory\" as one of the literal English tokens: Breakfast, Lunch, Snack, Dinner, Dessert. " +
            "Keep \"activityType\" as one of the literal English tokens: Walk, Run, Gym, Bike, Swim, Yoga, Other. " +
            "Numeric fields are language-neutral."
    }

    /**
     * Localised labels embedded inside the structured 3-line `fullSummary`
     * that the meal-analysis prompts ask the model to produce. The labels
     * are NOT JSON keys — they appear as text inside the `fullSummary`
     * string so the user reads "Ingredientes visíveis: …" instead of
     * "Visible ingredients: …" when the app language is Portuguese.
     *
     * Hard-coding the labels in the prompt (rather than translating in the
     * app) keeps the model from drifting into ad-hoc translations or
     * mixing languages mid-sentence — it just substitutes the placeholder.
     * The chip rendered above the description still uses [I18n.tr] for the
     * confidence word so we don't depend on the model getting it right.
     */
    internal fun summaryLabelInstruction(language: String): String {
        val (ingr, assumed, conf, low, medium, high) = when (language) {
            "pt-PT", "pt" -> SummaryLabels(
                "Ingredientes visíveis", "Assumido", "confiança",
                "baixa", "média", "alta"
            )
            "gu" -> SummaryLabels(
                "દૃશ્યમાન ઘટકો", "ધારણા", "વિશ્વાસ",
                "નીચું", "મધ્યમ", "ઊંચું"
            )
            else -> SummaryLabels(
                "Visible ingredients", "Assumed", "confidence",
                "low", "medium", "high"
            )
        }
        return """
            Use these exact text labels inside the 3-line "fullSummary" string (translate the rest of the prose to the user's language):
              <INGREDIENTS_LABEL> = "$ingr"
              <ASSUMED_LABEL> = "$assumed"
              <CONFIDENCE_LABEL> = "$conf"
              Confidence words for line 3 prose: $low / $medium / $high (use the one matching the "confidence" JSON value: low/medium/high).
        """.trimIndent()
    }

    private data class SummaryLabels(
        val ingredients: String,
        val assumed: String,
        val confidence: String,
        val low: String,
        val medium: String,
        val high: String
    )

    /**
     * Shared body of the meal-image analysis prompt. The 5-step internal
     * checklist nudges the model toward grounded estimates (portion → list
     * ingredients → per-ingredient macros → cross-check sum vs total →
     * self-rate confidence) without an extra round-trip. The 3-line
     * fullSummary format gives the user something factual to validate
     * (portion, ingredients, assumptions) instead of a one-liner that
     * could come from anywhere.
     *
     * Field emission order is enforced because the streaming UI in
     * [com.example.data.meal.PartialJsonExtractor] regex-pulls partial
     * fields as they arrive — keeping `name` first lets us surface the
     * dish identity before macros are computed.
     *
     * Used by both the streaming orchestrator (`MealAnalysisOrchestrator`)
     * and the legacy blocking path (`analyzeFoodImage`, still used for
     * batch multi-photo analysis from `ChatViewModel`).
     */
    internal fun buildFoodAnalysisPromptCore(
        language: String,
        promptHint: String?,
        habitsBlock: String
    ): String {
        return """
            Analyze the following food item or meal visual and estimate its nutritional breakdown with maximum professional accuracy.
            $habitsBlock

            INTERNAL CHECKLIST (think through these steps silently before emitting JSON; do NOT include this reasoning in the output):
              1. Estimate the total portion in grams using plate, utensil, or hand-scale cues visible in the photo.
              2. List the visible ingredients with individual gram weights. For each ingredient emit BOTH `name` (in the user's language) AND `englishName` (English name for nutrition database lookup, e.g. "olive oil", "sourdough bread", "boiled egg"). Identify Iberian/Portuguese cured meats by their real name, not a generic one: thin dry reddish sausage slices dusted with white mould = **fuet** (Catalan dry sausage), not "salami sticks"; also recognise chouriço, salpicão, alheira, farinheira, presunto, paio when visible. Use the specific name in `name`.
              3. Compute per-ingredient macros from each weight using standard reference values (USDA / CIQUAL).
                 Raw whole produce carries near-zero fat: fruit and non-starchy vegetables (papaya/papaia, melon, apple, banana, berries, orange, grapes, tomato, cucumber, lettuce, spinach, carrot, pepper…) MUST get < 0.3 g fat per 100 g UNLESS there is visible added oil, dressing, cheese, or nuts on them. (Avocado, coconut, olives, and nuts/seeds are the fatty exceptions.) Zero-fat beverages carry NO fat: water, black coffee/tea (sweetened or not), and soft drinks / sodas (Coca-Cola, refrigerantes, incl. diet/zero/light), and fruit juices MUST get 0 g fat and 0 g saturatedFatG — never attribute fat to them. (Milk, lattes, cappuccinos, milkshakes, and smoothies normally carry fat from dairy — those are the exception — UNLESS a user-known meal pattern above states the milk/base is a plant or low-fat substitute, in which case follow the pattern.) Do NOT spread the meal's total fat evenly across ingredients — attribute fat to its real sources (oils, dairy, meat, nuts, fatty fish). Example: a papaya + Greek-yogurt bowl — essentially ALL the fat belongs to the yogurt; the papaya's fat is ~0.
              4. Sum the per-ingredient macros and verify against your meal-level totals (≤5% drift). The sum of per-ingredient `fat` MUST equal meal `fat` (≤5% drift); if a low-fat produce item is carrying fat that really belongs to a fatty ingredient, move it before emitting. If drift exceeds 5%, adjust per-ingredient weights or meal totals before emitting.
              5. Self-rate confidence ("low" | "medium" | "high"). Use "low" or "medium" when scale cues are ambiguous, the dish is partly occluded, or hidden fats / sauces are likely; flag the reason in line 3 of fullSummary. Use "high" only when portion and ingredients are unambiguous.
              6. Estimate freeSugarsG. This is REQUIRED. Use the WHO definition: free sugars = mono- and disaccharides ADDED to foods by the manufacturer, cook, or consumer + sugars NATURALLY present in honey, syrups, and fruit juices (including 100% fruit juice). Sugars locked inside the intact cellular matrix of whole fruit, whole vegetables, or unsweetened dairy are NOT free sugars. Starches (bread, pasta, rice, potato — even when white/refined) are NOT sugars at all and NEVER count. Report grams as a Double. Must satisfy `0 ≤ freeSugarsG ≤ carbs`. Plain milk or unsweetened dairy in coffee or tea contributes 0 free sugars — count only sugar/syrup/honey actually added to the drink (a plain "café com leite" / latte with no added sugar = 0.0, unless a user-known meal pattern above specifies the drink's actual composition — then follow it). Roast, grilled, baked, or pan-fried meat, fish, and poultry with NO glaze, honey, BBQ, teriyaki, or sweet sauce = 0.0. When in doubt for a savoury dish, prefer 0.0. Return `0.0` when the meal contains no free sugars (this is the default for savoury dishes, plain grains, whole fruit, whole dairy). Only return `null` when portion is so ambiguous that even the carbs total is a guess. **CRITICAL: every entry in `ingredients` must ALSO emit `freeSugarsG` under the same WHO rules, clamped against that ingredient's own `carbs` — the carbs-breakdown popup depends on this to attribute free sugars to the real source (pastel, doce de gila, refrigerante, mel) instead of guessing by name. Whole fruit, plain grains, and plain/unsweetened milk or coffee on an ingredient row = 0.0; the sugary/syrup/honey/added-sugar ingredient carries the sugar.**
              7. Estimate saturatedFatG. This is REQUIRED. Saturated fat = the fraction of `fat` from fatty acids with no double bonds — dominant in butter, cheese, cream, lard, fatty meat, bacon, and tropical oils (coconut, palm); also present at lower levels in lean meats and eggs. Report grams as a Double. Must satisfy `0 ≤ saturatedFatG ≤ fat`. WHO/AHA recommend keeping daily saturated fat below 10% of daily kcal (~<20g on an 1800 kcal target). Return `0.0` for lean-protein-and-vegetable meals with only olive/nut/seed fats (that is the default for those). Only return `null` when portion is so ambiguous that even the total `fat` is a guess. **CRITICAL: every entry in `ingredients` must ALSO emit `saturatedFatG` under the same rules, clamped against that ingredient's own `fat` — the macro-breakdown popup depends on this to attribute saturated fat to real food sources (queijo, manteiga, bacon…) instead of collapsing to a single meal-level "—" row.**

            You MUST return a JSON structure matching this model exactly, without backticks, preamble, or markdown blocks:
            {
              "name": "Short name of the food (4-8 words)",
              "mealCategory": "Breakfast/Lunch/Snack/Dinner/Dessert",
              "calories": 420,
              "protein": 18.5,
              "carbs": 32.0,
              "fat": 11.2,
              "freeSugarsG": 8.0,
              "saturatedFatG": 3.5,
              "fullSummary": "<three lines separated by literal \\n — see format below>",
              "confidence": "low|medium|high",
              "appliedHabitIds": [],
              "ingredients": [
                { "name": "iogurte grego", "englishName": "greek yogurt", "weightGrams": 200, "calories": 120, "protein": 10.0, "carbs": 8.0, "fat": 5.0, "saturatedFatG": 3.2, "freeSugarsG": 0.0 },
                { "name": "granola", "englishName": "granola", "weightGrams": 40, "calories": 180, "protein": 4.0, "carbs": 26.0, "fat": 7.0, "saturatedFatG": 0.9, "freeSugarsG": 6.0 }
              ]
            }

            fullSummary FORMAT (exactly 3 lines, separated by the literal two-character escape \n inside the JSON string — never a raw newline byte):
              Line 1: Dish name + visible total portion in grams. Example: "Tigela de iogurte grego (~250 g) com granola e mirtilos."
              Line 2: "<INGREDIENTS_LABEL>: <ingredient1> (~Xg), <ingredient2> (~Yg), …"
              Line 3: "<ASSUMED_LABEL>: <short list of assumptions made>; <CONFIDENCE_LABEL> <low|medium|high translated>" — when confidence is medium or low, append " — <reason>" so the user understands what the model was unsure about.

            Field emission order: emit "name" first, then "mealCategory", then top-level macros (calories/protein/carbs/fat), then "freeSugarsG", then "saturatedFatG", then "fullSummary", then "confidence", then "appliedHabitIds", then "ingredients" last. This lets the streaming client surface dish identity before macros are computed.

            Ensure macro grams are double values and calories is an integer. weightGrams in ingredients is the actual portion weight (not per-100g). Translate ingredient names into the user's language. Aim for 1-8 ingredients; collapse trivial seasonings (<1 g) into the carrier they sit on.

            Contextual hint to guide estimation: "${promptHint ?: "A balanced daily meal"}"
            ${languageInstruction(language)}
            ${summaryLabelInstruction(language)}
        """.trimIndent()
    }

    /**
     * Shared body of the meal re-analysis prompt (when the user edits the
     * description and the app asks the model to re-estimate macros from the
     * edited text). Mirrors [buildFoodAnalysisPromptCore] but adds the
     * "PREVIOUS ESTIMATE" anchor block plus the hard rules that prevent the
     * model from just echoing the prior numbers, and instructs it to accept
     * a freeform edited description while still emitting a structured
     * 3-line fullSummary on output.
     */
    internal fun buildFoodReanalysisPromptCore(
        currentEntry: FoodEntry,
        newSummary: String,
        language: String,
        habitsBlock: String,
        /** When the caller is going to inline-attach the original meal photo
         *  alongside this prompt, set this to true so the prompt explicitly
         *  asks the model to combine the image with the user's edited
         *  description. The image itself is added as a separate
         *  [Part(inlineData=…)] by the caller — this flag only changes the
         *  prompt wording. Defaults to false so legacy callers (text-only
         *  re-analysis) stay unchanged. */
        imageAttached: Boolean = false
    ): String {
        val previousBlock = buildString {
            val origSummary = currentEntry.analysisSummary?.takeIf { it.isNotBlank() }
            if (origSummary != null) {
                appendLine("PREVIOUS AI ESTIMATE (for context only — DO NOT just reuse these numbers):")
                appendLine("- Original description: \"$origSummary\"")
                appendLine("- Original name: \"${currentEntry.name}\"")
                val freeSugPart = currentEntry.freeSugarsG
                    ?.let { ", ${it}g free sugars" }
                    ?: ""
                val satFatPart = currentEntry.saturatedFatG
                    ?.let { ", ${it}g saturated fat" }
                    ?: ""
                appendLine(
                    "- Original macros: ${currentEntry.calories} kcal, " +
                        "${currentEntry.protein}g protein, ${currentEntry.carbs}g carbs, " +
                        "${currentEntry.fat}g fat$freeSugPart$satFatPart"
                )
                appendLine()
            }
        }

        val imageNote = if (imageAttached) {
            "\n\nThe ORIGINAL MEAL PHOTO is attached alongside this prompt. " +
                "Treat the image as the ground truth for visible portions, ingredients, " +
                "and presentation; use the user's edited description below to RESOLVE " +
                "ambiguities the photo alone cannot (brand, recipe specifics, hidden " +
                "fats, added oils, sauces under the dish). Combine BOTH signals — do " +
                "not analyse one in isolation."
        } else ""

        return """
            You are RE-ANALYZING a meal because the user EDITED its description to add or correct details the AI missed.
            Your job is to re-estimate the nutritional breakdown from scratch using the FULL edited description below, with maximum professional accuracy.$imageNote
            $habitsBlock

            Hard rules:
            - Honor EVERY ingredient and quantity in the edited description, including small additions (extra egg whites, a splash of milk, a supplement scoop, condiments). Small items often shift protein and fat noticeably — do not round them away.
            - Re-estimate macros from the full edited text. Do NOT just copy the previous estimate; the user added details specifically because the previous estimate was incomplete.
            - The "name" must reflect the actual headline content of the edited description. If the user named specific components (e.g. "egg whites", "sourdough", "protein milk"), the name should mention the most distinctive ones. Aim for 4-10 words; widen if needed to avoid dropping a named ingredient.
            - The user-edited description below may be FREEFORM (a single sentence, bullet points, no structure at all). On OUTPUT you MUST re-emit "fullSummary" in the structured 3-line format defined below — even when the input was freeform.
            - Do not invent ingredients the user did not mention; do not silently drop ingredients the user did mention.

            INTERNAL CHECKLIST (think through these steps silently before emitting JSON; do NOT include this reasoning in the output):
              1. Honor the portions stated by the user; estimate any unstated portion using context cues (e.g. "a slice of bread" ≈ 30 g).
              2. List every ingredient mentioned by the user, with individual gram weights.
              3. Compute per-ingredient macros from each weight using standard reference values (USDA / CIQUAL).
                 Raw whole produce carries near-zero fat: fruit and non-starchy vegetables (papaya/papaia, melon, apple, banana, berries, orange, grapes, tomato, cucumber, lettuce, spinach, carrot, pepper…) MUST get < 0.3 g fat per 100 g UNLESS there is visible added oil, dressing, cheese, or nuts. (Avocado, coconut, olives, and nuts/seeds are the fatty exceptions.) Zero-fat beverages carry NO fat: water, black coffee/tea, soft drinks / sodas (Coca-Cola, refrigerantes, incl. diet/zero), and fruit juices MUST get 0 g fat and 0 g saturatedFatG. (Milk, lattes, milkshakes, and smoothies DO carry fat — those are the exception.) Do NOT spread the meal's total fat evenly across ingredients — attribute fat to its real sources (oils, dairy, meat, nuts, fatty fish).
              4. Sum the per-ingredient macros and verify against your meal-level totals (≤5% drift). The sum of per-ingredient `fat` MUST equal meal `fat` (≤5% drift); if low-fat produce is carrying fat that belongs to a fatty ingredient, move it. Adjust if drift exceeds 5%.
              5. Self-rate confidence ("low" | "medium" | "high"). Use "low" or "medium" when the user's wording is ambiguous (e.g. "a bit of butter") or the portions are unclear; flag the reason in line 3 of fullSummary.
              6. Re-estimate freeSugarsG. This is REQUIRED. Same WHO definition as the initial-analysis prompt: free sugars = mono/disaccharides ADDED to foods + sugars naturally present in honey, syrups, and fruit juices. Whole fruit, whole vegetables, unsweetened dairy, and starches (bread/pasta/rice/potato — even white/refined) do NOT count. Must satisfy `0 ≤ freeSugarsG ≤ carbs`. Plain milk or unsweetened dairy in coffee/tea = 0 free sugars (count only sugar/syrup/honey added to the drink); roast/grilled/baked/pan-fried meat, fish, and poultry with no glaze/honey/BBQ/teriyaki/sweet sauce = 0.0. Return `0.0` for savoury dishes with no added sugar. Only return `null` when the edited description leaves it genuinely unknowable. **Every entry in `ingredients` must ALSO emit `freeSugarsG` clamped against the ingredient's own `carbs` (whole fruit / plain grains / plain milk or coffee = 0.0; the sugary/syrup/honey/added-sugar ingredient carries the sugar).**
              7. Re-estimate saturatedFatG. This is REQUIRED. Same definition as the initial-analysis prompt: the fraction of `fat` from fatty acids with no double bonds — dominant in butter, cheese, cream, lard, fatty meat, bacon, and tropical oils. Must satisfy `0 ≤ saturatedFatG ≤ fat`. Return `0.0` for lean-protein-and-vegetable meals with only olive/nut/seed fats. Only return `null` when the edited description leaves it genuinely unknowable. **Every entry in `ingredients` must ALSO emit `saturatedFatG` clamped against the ingredient's own `fat`.**

            You MUST return a JSON structure matching this model exactly, without backticks, preamble, or markdown blocks:
            {
              "name": "Headline name reflecting the user's edited description (4-10 words)",
              "mealCategory": "Breakfast/Lunch/Snack/Dinner/Dessert",
              "calories": 420,
              "protein": 18.5,
              "carbs": 32.0,
              "fat": 11.2,
              "freeSugarsG": 8.0,
              "saturatedFatG": 3.5,
              "fullSummary": "<three lines separated by literal \\n — same 3-line format as the initial-analysis prompt>",
              "confidence": "low|medium|high",
              "appliedHabitIds": [],
              "ingredients": [
                { "name": "iogurte grego", "weightGrams": 200, "calories": 120, "protein": 10.0, "carbs": 8.0, "fat": 5.0, "saturatedFatG": 3.2, "freeSugarsG": 0.0 }
              ]
            }

            fullSummary FORMAT (exactly 3 lines, separated by the literal two-character escape \n inside the JSON string — never a raw newline byte):
              Line 1: Dish name + visible/stated total portion in grams.
              Line 2: "<INGREDIENTS_LABEL>: <ingredient1> (~Xg), <ingredient2> (~Yg), …"
              Line 3: "<ASSUMED_LABEL>: <short list of assumptions made>; <CONFIDENCE_LABEL> <low|medium|high translated>" — when confidence is medium or low, append " — <reason>".

            Field emission order: emit "name" first, then "mealCategory", then top-level macros (calories/protein/carbs/fat), then "freeSugarsG", then "saturatedFatG", then "fullSummary", then "confidence", then "appliedHabitIds", then "ingredients" last.

            Ensure macro grams are double values and calories is an integer. weightGrams in ingredients is the actual portion weight (not per-100g). Translate ingredient names into the user's language. The sum across ingredients should approximately match the meal totals above.

            ${previousBlock}EDITED DESCRIPTION (authoritative — analyze this):
            "$newSummary"

            ${languageInstruction(language)}
            ${summaryLabelInstruction(language)}
        """.trimIndent()
    }

    suspend fun analyzeFoodImage(
        base64Image: String?,
        mimeType: String = "image/jpeg",
        promptHint: String? = null,
        language: String = "en",
        categoryHint: MealCategory? = null
    ): Result<FoodAnalysisResult> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return Result.failure(Exception(tr("API Key not configured. Please add GEMINI_API_KEY in the Secrets panel.", language)))
        }

        val habits = selectHabitsForPrompt(foodHabitDao.getActiveHabitsSnapshot(), categoryHint)
        val habitsBlock = buildHabitsPromptBlock(habits, promptHint)

        val promptText = buildFoodAnalysisPromptCore(
            language = language,
            promptHint = promptHint,
            habitsBlock = habitsBlock
        )

        val partList = mutableListOf<Part>()
        partList.add(Part(text = promptText))
        if (!base64Image.isNullOrBlank()) {
            partList.add(Part(inlineData = InlineData(mimeType = mimeType, data = base64Image)))
        }

        val request = GeminiRequest(
            contents = listOf(Content(parts = partList)),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json"
            )
        )

        return generateContentWithFallback(apiKey, "gemini-3.5-flash", request, language).mapCatching { rawJson ->
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter(FoodAnalysisResult::class.java)
            val parsed = adapter.fromJson(rawJson)
                ?: throw Exception("${tr("Could not extract valid nutritional fields from output:", language)} $rawJson")
            // Defensive: clamp echoed ids to those we actually offered, drop nulls/dupes.
            val offeredIds = habits.map { it.id }.toSet()
            val safeApplied = parsed.appliedHabitIds?.filter { it in offeredIds }?.distinct()
            markHabitsApplied(safeApplied)
            parsed.copy(appliedHabitIds = safeApplied)
        }
    }

    suspend fun analyzeActivityImage(
        base64Image: String?,
        mimeType: String = "image/jpeg",
        promptHint: String? = null,
        language: String = "en"
    ): Result<ActivityAnalysisResult> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return Result.failure(Exception(tr("API Key not configured. Please add GEMINI_API_KEY in the Secrets panel.", language)))
        }

        val promptText = """
            Analyze the following fitness or health tracker summary screenshot or image and extract the completed activity/activities details.
            You MUST return a JSON structure matching this model exactly, without backticks, preamble, or markdown blocks:
            {
              "name": "General session title (e.g. Morning Workout)",
              "fullSummary": "Comprehensive summary of all completed activities",
              "exercises": [
                {
                  "name": "Specific exercise name (e.g., Morning Walk, Pushups)",
                  "durationMinutes": 30,
                  "caloriesBurned": 150,
                  "activityType": "Walk/Run/Gym/Bike/Swim/Yoga/Other",
                  "notes": "Optional extra details"
                }
              ]
            }
            Ensure durationMinutes and caloriesBurned are integer values.
            Contextual descriptions/hint to guide estimation: "${promptHint ?: "A daily physical activity summary"}"
            ${languageInstruction(language)}
        """.trimIndent()

        val partList = mutableListOf<Part>()
        partList.add(Part(text = promptText))
        if (!base64Image.isNullOrBlank()) {
            partList.add(Part(inlineData = InlineData(mimeType = mimeType, data = base64Image)))
        }

        val request = GeminiRequest(
            contents = listOf(Content(parts = partList)),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json"
            )
        )

        return generateContentWithFallback(apiKey, "gemini-3.5-flash", request, language).mapCatching { rawJson ->
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter(ActivityAnalysisResult::class.java)
            val result = adapter.fromJson(rawJson)
                ?: throw Exception("${tr("Could not extract valid details from output:", language)} $rawJson")
            result
        }
    }

    suspend fun reanalyzeFoodFromSummary(
        currentEntry: FoodEntry,
        newSummary: String,
        language: String = "en"
    ): Result<FoodEntry> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return Result.failure(Exception(tr("API Key not configured. Please add GEMINI_API_KEY in the Secrets panel.", language)))
        }

        val habits = selectHabitsForPrompt(foodHabitDao.getActiveHabitsSnapshot(), currentEntry.mealCategory)
        val habitsBlock = buildHabitsPromptBlock(habits)

        val promptText = buildFoodReanalysisPromptCore(
            currentEntry = currentEntry,
            newSummary = newSummary,
            language = language,
            habitsBlock = habitsBlock
        )

        val request = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = promptText)))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json"
            )
        )

        return generateContentWithFallback(apiKey, "gemini-3.5-flash", request, language).mapCatching { rawJson ->
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter(FoodAnalysisResult::class.java)
            val result = adapter.fromJson(rawJson)
                ?: throw Exception("${tr("Could not extract valid nutritional fields from output:", language)} $rawJson")
            val offeredIds = habits.map { it.id }.toSet()
            val safeApplied = result.appliedHabitIds?.filter { it in offeredIds }?.distinct()
            markHabitsApplied(safeApplied)
            currentEntry.copy(
                name = result.name ?: currentEntry.name,
                calories = result.calories ?: currentEntry.calories,
                protein = result.protein ?: currentEntry.protein,
                carbs = result.carbs ?: currentEntry.carbs,
                fat = result.fat ?: currentEntry.fat,
                fullSummary = (result.fullSummary ?: newSummary).sanitizeAiNewlines(),
                mealCategory = MealCategory.parse(result.mealCategory),
                appliedHabitIds = safeApplied,
                // Re-analysis can shift the ingredient list (user added eggs,
                // dropped milk, etc.). Always overwrite with the fresh
                // breakdown when the model returns one; preserve the previous
                // JSON only if the model omitted ingredients entirely.
                ingredientsJson = result.ingredients
                    ?.let { encodeIngredients(it) }
                    ?: currentEntry.ingredientsJson,
                // Free sugars is authoritative from the fresh estimate; only
                // fall back to the previously-stored value if the model
                // omitted it (older prompt / parse dropout). Clamp against
                // the merged carbs so the UI never renders a segment wider
                // than the total.
                freeSugarsG = (result.freeSugarsG ?: currentEntry.freeSugarsG)
                    ?.coerceAtMost(result.carbs ?: currentEntry.carbs)
                    ?.coerceAtLeast(0.0),
                // Saturated fat: same pattern, clamp against merged fat.
                saturatedFatG = (result.saturatedFatG ?: currentEntry.saturatedFatG)
                    ?.coerceAtMost(result.fat ?: currentEntry.fat)
                    ?.coerceAtLeast(0.0)
            )
        }
    }

    suspend fun reanalyzeActivityFromSummary(
        sessionId: String,
        newSummary: String,
        prevExercises: List<ActivityEntry>,
        language: String = "en"
    ): Result<List<ActivityEntry>> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return Result.failure(Exception(tr("API Key not configured. Please add GEMINI_API_KEY in the Secrets panel.", language)))
        }

        val promptText = """
            Given this textual description of physical activities directly, analyze it and extract one or more completed exercises.
            You MUST return a JSON structure matching this model exactly, without backticks, preamble, or markdown blocks:
            {
              "name": "Session title (<= 60 chars)",
              "fullSummary": "Comprehensive summary of all completed activities",
              "exercises": [
                {
                  "name": "Specific exercise name",
                  "durationMinutes": 30,
                  "caloriesBurned": 150,
                  "activityType": "Walk/Run/Gym/Bike/Swim/Yoga/Other",
                  "notes": "Optional extra details"
                }
              ]
            }
            Description to analyze: $newSummary
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = promptText)))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json"
            )
        )

        return generateContentWithFallback(apiKey, "gemini-3.5-flash", request, language).mapCatching { rawJson ->
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter(ActivityAnalysisResult::class.java)
            val result = adapter.fromJson(rawJson)
                ?: throw Exception("${tr("Could not extract valid details from output:", language)} $rawJson")
            
            val exercisesList = result.exercises ?: emptyList()
            val timestamp = prevExercises.firstOrNull()?.timestamp ?: System.currentTimeMillis()
            
            exercisesList.mapIndexed { index, exerciseDto ->
                val prevItem = prevExercises.getOrNull(index)
                ActivityEntry(
                    id = prevItem?.id ?: 0L,
                    name = exerciseDto.name,
                    durationMinutes = exerciseDto.durationMinutes,
                    caloriesBurned = exerciseDto.caloriesBurned,
                    timestamp = timestamp,
                    sessionId = sessionId,
                    fullSummary = (result.fullSummary ?: newSummary).sanitizeAiNewlines(),
                    lastAiAnalyzedSummary = newSummary,
                    activityType = com.example.data.local.ActivityType.fromName(exerciseDto.activityType),
                    notes = exerciseDto.notes ?: prevItem?.notes
                )
            }
        }
    }

    fun getMessagesForSessionFlow(sessionId: Long): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSessionFlow(sessionId)
    }

    suspend fun createNewSession(
        systemInstruction: String = "",
        model: String = "gemini-3.5-flash",
        /** Phase G: "personal" | "nutritionist" | "personal_trainer". Default
         *  preserves the legacy behaviour of every prior call site. */
        sessionType: String = "personal"
    ): Long {
        val finalInstruction = if (systemInstruction.isBlank()) {
            // 2026-06-22 (plan ADENDO 2 — Fix 8) — HABIT_PROTOCOL first so
            // the implicit caching layer of Gemini gets a long, byte-
            // identical prefix to hit on across all sessions/users.
            //
            // 2026-06-28 — persona literal extracted to
            // [com.example.data.chat.ChatToneRules.personaFor] so the chat
            // path, the targets-aware path, AND the proactive-notification
            // path all share the same "friend who happens to know
            // nutrition" tone. Hugo flagged the previous "elegant
            // professional dietitian" register as academic / Instagram.
            // This fallback is only used when no ChatViewModel-built
            // instruction was passed (rare — happens for sessions seeded
            // before the user filled in their profile).
            HABIT_PROTOCOL + com.example.data.chat.ChatToneRules.personaFor("Madhvi") +
                "\n\nTargets when not overridden: 1,800 kcal/day, ~85g protein, ~220g carbs, ~60g fat."
        } else {
            systemInstruction
        }
        val session = ChatSession(
            title = "New Chat",
            systemInstruction = finalInstruction,
            model = model,
            sessionType = sessionType
        )
        return chatDao.insertSession(session)
    }

    suspend fun updateSession(session: ChatSession) {
        chatDao.updateSession(session)
    }

    suspend fun deleteSession(session: ChatSession) {
        chatDao.deleteSession(session)
    }

    suspend fun renameSession(sessionId: Long, newTitle: String) {
        val session = chatDao.getSessionById(sessionId)
        if (session != null) {
            chatDao.updateSession(session.copy(title = newTitle))
        }
    }

    suspend fun clearSessionHistory(sessionId: Long) {
        chatDao.deleteMessagesForSession(sessionId)
    }

    /**
     * Phase 2 of chat-redesign 2026-06-12 (#4 edit/delete cascade): wraps
     * [ChatDao.getMessagesFromTimestamp] so the VM can enumerate which
     * message ids it's about to drop and prune its in-memory caches
     * (pendingHabitProposals, cleanedMessageText, …) before the delete.
     */
    suspend fun getMessagesFromTimestamp(sessionId: Long, ts: Long): List<ChatMessage> {
        return chatDao.getMessagesFromTimestamp(sessionId, ts)
    }

    /**
     * Phase 2 of chat-redesign 2026-06-12 (#4 edit/delete cascade): deletes
     * the message with the given timestamp and every later message in the
     * same session. Used both by `deleteMessageAndAfter` and as the first
     * step of `confirmEditAndResubmit` (apaga + reenvia).
     *
     * Imagens em filesDir/chat_images/ NÃO são removidas aqui — ficam órfãs
     * até um GC futuro. O custo é tolerável para o uso real.
     */
    suspend fun deleteMessagesFromTimestamp(sessionId: Long, ts: Long) {
        chatDao.deleteMessagesFromTimestamp(sessionId, ts)
    }

    /**
     * 2026-06-15 cross-conversation search. Sanitizes the user's query
     * for FTS4 `MATCH` syntax, then delegates to [ChatDao.searchMessages].
     *
     * Sanitization rules — keep them short, the goal is "WhatsApp does
     * what I expect" not "expose full FTS4 syntax":
     *   - Empty / blank input → empty result list, no DB call.
     *   - Strip characters that have meaning in MATCH (`"`, `*`, `:`, `^`)
     *     unless they're at the end of a token, where `*` becomes a
     *     prefix wildcard.
     *   - Split on whitespace, drop empties, append `*` to every token so
     *     "ola" matches "olas", "olaria", etc. — same expectation as a
     *     mobile messaging search.
     *   - Tokens are joined with a space (FTS4 implicit AND).
     *
     * Returns at most 200 hits, newest first (see [ChatDao.searchMessages]).
     */
    suspend fun searchChatMessages(rawQuery: String): List<com.example.data.local.MessageSearchHit> {
        val cleaned = rawQuery.trim()
        if (cleaned.isEmpty()) return emptyList()
        val tokens = cleaned
            .replace(Regex("""[\"*:^]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val ftsQuery = tokens.joinToString(" ") { "$it*" }
        return runCatching { chatDao.searchMessages(ftsQuery) }
            .getOrElse { emptyList() }
    }

    suspend fun sendMessage(
        sessionId: Long,
        messageText: String,
        imageBase64: String? = null,
        language: String = "en",
        /** Phase B: pre-built data snapshot (today + last 7 days). Built by the
         *  ViewModel which holds the SH state; passed in so the repository
         *  doesn't need to reach into a separate flow. Empty string skips. */
        dataSnapshot: String = "",
        /** 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail) — When
         *  non-null, replaces the stored `session.systemInstruction` for THIS
         *  send only. The orchestrator path uses the equivalent override on
         *  `ChatOrchestrator.handle` to avoid old sessions whose stored
         *  instruction baked in `name='Humano'` from continuing to address
         *  the user by that placeholder. Kept here for callers that still
         *  bypass the orchestrator (reanalysis flows, future call sites).
         *  Null falls back to the legacy behaviour of reading
         *  `session.systemInstruction`. */
        baseInstructionOverride: String? = null,
        /** Companion to [baseInstructionOverride]. Reserved for future use:
         *  callers that build their own `dataSnapshot` may use this to
         *  validate the snapshot was built with the live user name. Not
         *  consumed inside `sendMessage` itself because the snapshot is
         *  already a finished string by the time it arrives here. */
        @Suppress("UNUSED_PARAMETER")
        userNameOverride: String? = null
    ): Result<String> {
        // 1. Insert user message to database
        val userMessage = ChatMessage(
            sessionId = sessionId,
            sender = "user",
            text = messageText,
            imageBase64 = imageBase64
        )
        chatDao.insertMessage(userMessage)
        // Phase A refinement: float the session to the top of the resume list.
        chatDao.touchSession(sessionId)

        // 2. Generate dynamic title if it is default
        val session = chatDao.getSessionById(sessionId)
        if (session != null && session.title == "New Chat") {
            val shortTitle = if (messageText.length > 25) {
                messageText.take(25) + "..."
            } else {
                messageText
            }
            chatDao.updateSession(session.copy(title = shortTitle))
        }

        // 3. Prepare Gemini integration
        val apiService = GeminiRetrofitClient.service
        val apiKey = BuildConfig.GEMINI_API_KEY

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val errorMsg = tr("API Key is not configured. Please enter your GEMINI_API_KEY in the Secrets panel in AI Studio.", language)
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "gemini",
                    text = errorMsg,
                    isError = true
                )
            )
            return Result.failure(Exception(errorMsg))
        }

        // Load complete chat thread from Db to build high-quality memory context
        val dbMessages = chatDao.getMessagesForSession(sessionId)
        
        // Map database records to Gemini Content structures
        // Note: Filter out error messages to avoid feeding bad responses back to context
        val contents = dbMessages.filter { !it.isError }.map { msg ->
            val parts = mutableListOf<Part>()
            parts.add(Part(text = msg.text))
            if (!msg.imageBase64.isNullOrBlank()) {
                // Phase C: the column may now hold either a single base64 blob
                // (legacy), a single file:// path, or a JSON array of paths.
                // Resolve to one inline-data Part each.
                resolveImagePayload(msg.imageBase64).forEach { img ->
                    parts.add(Part(inlineData = InlineData(mimeType = img.mimeType, data = img.base64)))
                }
            }
            Content(
                role = if (msg.sender == "user") "user" else "model",
                parts = parts
            )
        }

        // Map system instruction if configured. Append a snapshot of currently
        // known habits so the assistant doesn't re-propose duplicates and can
        // reference them when the user contradicts or refines an existing one.
        val habitsSnapshot = foodHabitDao.getActiveHabitsSnapshot()
        val knownHabitsBlock = if (habitsSnapshot.isEmpty()) "" else buildString {
            append("\n\nAlready memorized habits (do NOT re-propose unchanged):\n")
            habitsSnapshot.forEach { h ->
                append("- id=").append(h.id)
                append(", trigger=\"").append(h.trigger).append("\"")
                append(", descriptor=\"").append(h.descriptor).append("\"")
                if (h.mealCategoryHint != null) append(", category=").append(h.mealCategoryHint.name)
                append('\n')
            }
        }
        // 2026-06-22 — When a caller passes `baseInstructionOverride`, use
        // that JIT-built instruction in place of the stored
        // `session.systemInstruction`. The stored value can carry baked-in
        // placeholders ("Humano") from older app versions, which the
        // override is built to replace.
        val effectiveBaseInstruction =
            baseInstructionOverride ?: session?.systemInstruction.orEmpty()
        val systemInstructionContent = if (effectiveBaseInstruction.isNotBlank()) {
            Content(parts = listOf(Part(text = effectiveBaseInstruction + dataSnapshot + knownHabitsBlock)))
        } else if (dataSnapshot.isNotBlank() || knownHabitsBlock.isNotBlank()) {
            Content(parts = listOf(Part(text = (dataSnapshot + knownHabitsBlock).trimStart())))
        } else {
            null
        }
        if (dataSnapshot.isNotBlank()) {
            android.util.Log.d("CHAT_CTX", "Injected data snapshot: ${dataSnapshot.length} chars")
        }

        val modelToUse = session?.model ?: "gemini-3.5-flash"

        val request = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstructionContent
        )

        // Pre-flight diagnostic: count inlineData parts across the assembled
        // request. Mirror of the same log in ChatOrchestrator — lets us tell
        // the legacy path apart by the "(legacy)" suffix.
        run {
            val inlineCount = contents.sumOf { c -> c.parts.count { it.inlineData != null } }
            val totalParts = contents.sumOf { it.parts.size }
            android.util.Log.d(
                "CHAT_IMG",
                "request built (legacy): contents=${contents.size} totalParts=$totalParts inlineDataParts=$inlineCount model=$modelToUse"
            )
        }

        val apiResult = generateContentWithFallback(apiKey, modelToUse, request, language)
        return if (apiResult.isSuccess) {
            var replyText = apiResult.getOrThrow()

            // Phase F: if the assistant emitted a [QUERY_DATA] block, run the
            // query, append the result as a synthetic user-role turn, and call
            // Gemini ONCE more to produce the real answer. Hard cap of 1 extra
            // round per turn — if the second reply still contains a query, we
            // ignore it (logged) so the user always sees a final response.
            val firstParse = ActionProposalParser.parse(replyText)
            if (firstParse.queryDataRequest != null) {
                val resultBlock = runQueryAndFormat(firstParse.queryDataRequest!!)
                android.util.Log.d("CHAT_QUERY",
                    "type=${firstParse.queryDataRequest!!.type} from=${firstParse.queryDataRequest!!.fromDate} to=${firstParse.queryDataRequest!!.toDate}")
                val followupContents = contents + Content(
                    role = "model",
                    parts = listOf(Part(text = replyText))
                ) + Content(
                    role = "user",
                    parts = listOf(Part(text = "[QUERY_DATA_RESULT]\n$resultBlock"))
                )
                val followupRequest = GeminiRequest(
                    contents = followupContents,
                    systemInstruction = systemInstructionContent
                )
                val followup = generateContentWithFallback(apiKey, modelToUse, followupRequest, language)
                if (followup.isSuccess) {
                    replyText = followup.getOrThrow()
                } else {
                    android.util.Log.w("CHAT_QUERY", "Follow-up turn failed: ${followup.exceptionOrNull()?.message}")
                }
            }

            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "gemini",
                    text = replyText
                )
            )
            Result.success(replyText)
        } else {
            val exception = apiResult.exceptionOrNull() ?: Exception(tr("Unknown API Error", language))
            val networkError = if (exception is IOException) {
                "${tr("Connection Error: Please verify your internet connection. Detail:", language)} ${exception.localizedMessage}"
            } else {
                "${tr("Error:", language)} ${exception.localizedMessage}"
            }
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "gemini",
                    text = networkError,
                    isError = true
                )
            )
            Result.failure(exception)
        }
    }
}

/** Encodes the LLM-returned ingredient list as a JSON string for storage in
 *  [FoodEntry.ingredientsJson]. Returns `null` when the list is empty so the
 *  DB column stays NULL (matches the legacy "no breakdown known" semantics).
 *  Decoding lives in [com.example.ui.components.parseIngredients] so the UI
 *  layer doesn't need a Moshi dependency on the data-remote module. */
internal fun encodeIngredients(items: List<IngredientDto>?): String? {
    if (items.isNullOrEmpty()) return null
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, IngredientDto::class.java)
    val adapter = moshi.adapter<List<IngredientDto>>(type)
    return adapter.toJson(items)
}