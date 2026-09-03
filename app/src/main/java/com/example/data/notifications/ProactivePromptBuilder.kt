package com.example.data.notifications

import android.content.Context
import com.example.BuildConfig
import com.example.data.health.ShDailyActivity
import com.example.data.local.ChatDatabase
import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import com.example.data.local.MealCategoryFromTime
import com.example.data.local.TargetSnapshot
import com.example.data.remote.Content
import com.example.data.remote.GeminiRequest
import com.example.data.remote.GeminiRetrofitClient
import com.example.data.remote.GenerationConfig
import com.example.data.remote.HABIT_PROTOCOL
import com.example.data.remote.Part
import com.example.data.remote.ThinkingConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bloco E of the proactive-notification plan
 * (`/Users/i548178/.claude/plans/estou-a-pensar-em-twinkly-key.md`).
 *
 * One-shot prompt builder used by [ProactiveNudgeWorker] (Bloco G) to ask
 * Gemini Flash for the text that becomes BOTH the body of the OS
 * notification AND the first assistant bubble in the chat session the user
 * opens by tapping it. The prompt is built from the same `ChatContextBuilder`
 * snapshot the interactive chat uses so the wording is grounded in the
 * user's real numbers — but the system instruction strictly forbids the
 * `[QUERY_DATA]` / `[LOG_MEAL_PROPOSAL]` / `[HABIT_PROPOSAL]` / etc. protocol
 * tags because there is no user turn after this that could honour them.
 *
 * Design constraints (from the plan, Onda 1):
 * - No `ChatOrchestrator`, no streaming, no Pro escalation — Flash only.
 * - `temperature=0.6`, `maxOutputTokens=256`, `thinkingBudget=0` for speed
 *   and to keep the result short enough for `BigTextStyle`.
 * - Errors are swallowed and surface as `null`; the worker retries.
 * - This file MUST NOT insert messages or sessions — that is Bloco G's job.
 */
internal object ProactivePromptBuilder {

    /** [text] is the generated notification body; [model] is the Gemini
     *  model id that actually produced it (whichever tier in [MODEL_CHAIN]
     *  answered first), so the worker can log/surface which LLM was used —
     *  see [ProactiveNudgeWorker]. */
    data class Result(val text: String, val model: String)

    /** Fallback chain mirroring [com.example.data.remote.GeminiRepository.generateContentWithFallback]
     *  (`GeminiRepository.kt:341`). Each model has its own free-tier RPM
     *  bucket, so on a 429 we walk the chain instead of giving up — this is
     *  the same trade-off the interactive chat already makes. See memory
     *  `gemini-fallback-must-cover-stream-and-block`. Distinct() guards
     *  against the first entry duplicating later ones. */
    private val MODEL_CHAIN = listOf(
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-preview",
        "gemini-3.1-pro-preview",
        "gemini-flash-latest"
    )

    /**
     * Builds the prompt for [trigger], calls Gemini Flash, and returns the
     * trimmed first-candidate text. Returns `null` if the API key is missing,
     * the call fails, or the model produced no text — the caller (Bloco G)
     * treats `null` as a retry signal.
     */
    suspend fun buildAndCall(
        ctx: Context,
        db: ChatDatabase,
        trigger: Trigger,
        language: String,
        userName: String,
        shStepsByDate: Map<LocalDate, Long>,
        shDailyActivity: Map<LocalDate, ShDailyActivity>
    ): Result? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") return null

        return try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)

            // SCOPED snapshot — proactive notifications are NEVER allowed to
            // cite data outside the trigger's natural window. A "Daily Summary"
            // that quotes 27k steps over the last week is exactly the kind of
            // confusion the user called out (2026-06-22 screenshot). The full
            // `ChatContextBuilder.build` would inject Month-to-date / Sliding
            // 30-day / End-of-month projection, none of which belongs in a
            // single-hour nudge. So we build a tighter block here, per trigger.
            //
            // For MealMissing we want a 3-day same-category baseline so the
            // model can say "média dos últimos 3 dias: 540 kcal ao almoço".
            // Other triggers stay on "today" only.
            val threeDaysAgoStartMs = today.minusDays(2)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val tomorrowStartMs2 = today.plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val recentFoods = db.foodDao.getFoodsBetween(threeDaysAgoStartMs, tomorrowStartMs2)
            val latestTarget = db.targetSnapshotDao.latest()

            val dataBlock = buildScopedSnapshot(
                trigger = trigger,
                today = today,
                zone = zone,
                recentFoods = recentFoods,
                latestTarget = latestTarget,
                shStepsByDate = shStepsByDate,
                shDailyActivity = shDailyActivity,
            )

            val triggerInstruction = buildTriggerInstruction(trigger, language)

            val systemInstruction = baseSystemInstruction(userName) +
                "\n\nYou are now sending a PROACTIVE notification to the user — there is NO user turn before this. " +
                "Write a single self-contained message that will be shown both as a phone notification body AND as the " +
                "first assistant bubble in a chat that opens when the user taps the notification. Keep it warm, " +
                "concrete, and grounded in the live data snapshot below.\n\n" +
                "HARD CONSTRAINTS for THIS reply only (override anything in the protocol section above):\n" +
                "- Do NOT emit any protocol tags whatsoever — no [QUERY_DATA], no [LOG_MEAL_PROPOSAL], no " +
                "[LOG_ACTIVITY_PROPOSAL], no [HABIT_PROPOSAL], no [INSIGHT_PROPOSAL], no [GENERATE_REPORT_PROPOSAL]. " +
                "Plain prose only.\n" +
                "- Do NOT ask the user a clarifying question; you have no follow-up turn here.\n" +
                "- HARD LIMIT: 1 short sentence by default, never more than 2. " +
                "Maximum 15 words across the whole reply. Punctuated with periods only. " +
                "No filler, no encouragement, no rhetorical adjectives — just the fact " +
                "the trigger asks for.\n" +
                "- Write in language code: $language.\n\n" +
                triggerInstruction +
                dataBlock

            val request = GeminiRequest(
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOf(Part(text = "Send the proactive notification now."))
                    )
                ),
                systemInstruction = Content(parts = listOf(Part(text = systemInstruction))),
                generationConfig = GenerationConfig(
                    temperature = 0.4f,
                    maxOutputTokens = 96,
                    thinkingConfig = ThinkingConfig(thinkingBudget = 0)
                )
            )

            val response = callWithFallback(apiKey, request)
            val maxSentences = when (trigger) {
                is Trigger.ActivitySpike -> 1
                else -> 2
            }
            response?.let { (rawText, model) ->
                stripQuestionsAndImperatives(rawText, maxSentences = maxSentences)
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Result(text = it, model = model) }
            }
        } catch (t: Throwable) {
            // Worker retries on null; never crash the worker process.
            android.util.Log.w(
                "ProactivePromptBuilder",
                "buildAndCall failed: ${t.javaClass.simpleName}: ${t.message}"
            )
            null
        }
    }

    /** Walk [MODEL_CHAIN] until one returns non-empty text. On 429 in the
     *  current model: retry once after 1s (transient burst), then drop to the
     *  next model. On any other API error or empty response: drop to the next
     *  model immediately. Returns `null` if every model failed — the worker
     *  treats that as a retry signal and re-tries the whole chain on the next
     *  schedule. Mirrors `GeminiRepository.generateContentWithFallback` shape
     *  but uses 2 attempts per model (the chat uses 3 on the seed model;
     *  proactive nudges can wait for the next cycle so we don't burn quota). */
    private suspend fun callWithFallback(
        apiKey: String,
        request: GeminiRequest
    ): Pair<String, String>? {
        for (model in MODEL_CHAIN) {
            for (attempt in 1..2) {
                try {
                    val response = GeminiRetrofitClient.service.generateContent(
                        model = model,
                        apiKey = apiKey,
                        request = request
                    )
                    val responseError = response.error
                    if (responseError != null) {
                        val is429 = responseError.code == 429 ||
                            responseError.message?.contains("429") == true
                        if (is429 && attempt < 2) {
                            kotlinx.coroutines.delay(1000L)
                            continue
                        }
                        // 429 exhausted on this model OR any non-429 error:
                        // drop to the next model.
                        android.util.Log.i(
                            "ProactivePromptBuilder",
                            "$model error (${responseError.code}) — trying next model"
                        )
                        break
                    }
                    val text = response.candidates
                        ?.firstOrNull()
                        ?.content
                        ?.parts
                        ?.firstOrNull()
                        ?.text
                    if (text.isNullOrBlank()) {
                        android.util.Log.i(
                            "ProactivePromptBuilder",
                            "$model returned empty — trying next model"
                        )
                        break
                    }
                    return text to model
                } catch (e: Exception) {
                    val is429 = (e is retrofit2.HttpException && e.code() == 429) ||
                        e.message?.contains("429") == true
                    if (is429 && attempt < 2) {
                        kotlinx.coroutines.delay(1000L)
                        continue
                    }
                    android.util.Log.i(
                        "ProactivePromptBuilder",
                        "$model threw ${e.javaClass.simpleName} — trying next model"
                    )
                    break
                }
            }
        }
        return null
    }

    /**
     * Coaching prefix + [HABIT_PROTOCOL] that mirrors the default
     * `systemInstruction` built by [com.example.data.remote.GeminiRepository.createNewSession]
     * (see `GeminiRepository.kt`, the `if (systemInstruction.isBlank())` branch).
     *
     * 2026-06-28 — Both the chat path and this proactive path now share
     * [com.example.data.chat.ChatToneRules.personaFor]; the single source
     * of truth lives there. Keeping a tiny wrapper here is intentional:
     * the proactive path layers extra hard-constraints on top (no protocol
     * tags, no follow-up question, single-sentence cap) which the chat
     * path doesn't want.
     */
    fun baseSystemInstruction(userName: String): String =
        HABIT_PROTOCOL + com.example.data.chat.ChatToneRules.personaFor(userName)

    private fun buildTriggerInstruction(trigger: Trigger, language: String): String {
        // Telegraph-style: bullet-of-facts, NOT a paragraph. Each sentence
        // states one concrete observation in plain everyday words. NO formal
        // register ("reflete já sete entradas", "saldo calórico permite
        // flexibilidade nutricional") — the user rejected that academic
        // tone explicitly (2026-06-22). Vocabulary level: SMS, not report.
        val styleHeader = "STYLE: Telegraph style. Two short factual sentences in $language. " +
            "Max 12 words EACH. Plain everyday vocabulary, like an SMS. " +
            "Report concrete observations only — never describe them in abstract terms. " +
            "NO questions, NO imperatives, NO greetings, NO sign-off, NO emoji. " +
            "NO formal register: AVOID words like 'reflete', 'permite flexibilidade', " +
            "'mantendo-se dentro dos limites', 'consumo diário', 'objetivo metabólico'. " +
            "Forbidden tone phrases: 'em falta', 'devias', 'queres', 'avisa-me', 'considera'. " +
            "ONLY cite numbers that appear in the snapshot below. Never cite weekly, monthly, " +
            "or 'last 7 days' aggregates — those are NOT in scope for proactive notifications. " +
            "Do NOT repeat the numbers already shown in the header above. " +
            "Just state facts, like 'Última refeição foi às 15h31.' / 'Janela do almoço fechou.'\n\n"
        val triggerLine = when (trigger) {
            is Trigger.MealMissing ->
                "TRIGGER — MEAL MISSING: Report two short facts about the " +
                    "${trigger.category.name.lowercase()} window. " +
                    "Examples of acceptable facts: time the window closed, time of the previous meal, " +
                    "average kcal at this point of day over last 3 days. " +
                    "Do NOT speculate about why the meal wasn't logged. Do NOT suggest foods."
            is Trigger.ActivitySpike ->
                "TRIGGER — ACTIVITY SPIKE: Output EXACTLY ONE short factual sentence " +
                    "stating the delta. The title above the body already says " +
                    "'Atividade excelente!' — so DO NOT repeat celebration words. " +
                    "Required output shape: '+${trigger.deltaKcal} kcal acima de ontem.' " +
                    "or equivalent literal restatement. No second sentence. " +
                    "No clock hour. No 'pico'. No 'ritmo'. No 'continua assim'. " +
                    "No commentary, no encouragement — just the delta fact."
            is Trigger.MacrosAtRisk ->
                "TRIGGER — MACROS AT RISK: Report two short factual observations. " +
                    "Examples: 'Faltam X g de proteína para o target.' / " +
                    "'A média dos últimos 3 dias atinge Y g a esta hora.' " +
                    "Do NOT suggest specific foods. Do NOT use urgency words."
            is Trigger.DailySummary ->
                "TRIGGER — DAILY SUMMARY: Two short factual sentences recapping the day. " +
                    "First: meals logged + total kcal. Second: steps + active kcal. " +
                    "Do NOT add tomorrow tips. Do NOT add congratulations."
        }
        return styleHeader + triggerLine
    }

    // ─── Scoped data snapshot per trigger ────────────────────────────────────

    /**
     * Replaces [com.example.data.remote.ChatContextBuilder.build] for the
     * proactive worker. The chat snapshot is intentionally wide (today,
     * last 7 days, month-to-date, sliding 30d, projection); a proactive
     * notification must NOT cite anything outside its trigger's natural
     * window — the user explicitly called out a "Resumo do dia" that quoted
     * weekly steps (2026-06-22 screenshot). So we hand the model only what
     * it's allowed to mention.
     *
     * Each trigger gets exactly the data it needs:
     *  - MealMissing:  today's macros so far + average over the last 3 days
     *                  for the SAME meal category (so the model can say
     *                  "média do almoço dos últimos 3 dias: 540 kcal").
     *  - ActivitySpike: today's active kcal + this hour's surge delta
     *                  (already in the Trigger object).
     *  - MacrosAtRisk: today's macros so far + targets.
     *  - DailySummary: today's macros so far + targets + today's steps/active kcal.
     *                  EXPLICITLY no weekly data.
     */
    private fun buildScopedSnapshot(
        trigger: Trigger,
        today: LocalDate,
        zone: ZoneId,
        recentFoods: List<FoodEntry>,
        latestTarget: TargetSnapshot?,
        shStepsByDate: Map<LocalDate, Long>,
        shDailyActivity: Map<LocalDate, ShDailyActivity>,
    ): String {
        val isoDate = DateTimeFormatter.ISO_LOCAL_DATE.format(today)
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStartMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayFoods = recentFoods.filter { it.timestamp in todayStartMs until tomorrowStartMs }
        val todayKcal = todayFoods.sumOf { it.calories }
        val todayProtein = todayFoods.sumOf { it.protein }.toInt()

        val sb = StringBuilder()
        sb.append("\n\n### Data snapshot for this trigger only (date=$isoDate)\n")
        sb.append("ONLY cite numbers from this snapshot. Do NOT invent weekly / monthly / projected values.\n\n")

        if (latestTarget != null) {
            sb.append("Today's targets — kcal=${latestTarget.targetKcal}, ")
                .append("protein=${latestTarget.targetProtein.toInt()}g\n")
        }
        sb.append("Today consumed — $todayKcal kcal, protein ${todayProtein}g, ")
            .append("${todayFoods.size} meals logged\n")

        when (trigger) {
            is Trigger.MealMissing -> {
                // Same-category baseline over the previous 2 days. We avoid
                // calling it "last 7 days" because the user shouldn't see
                // weekly data in a meal-missing nudge.
                val cat = trigger.category
                val yesterdayStartMs = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val twoDaysAgoStartMs = today.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                val baselineFoods = recentFoods
                    .filter { it.timestamp in twoDaysAgoStartMs until todayStartMs }
                    .filter { MealCategoryFromTime.classify(it.timestamp, it.mealCategory) == cat }
                val baselineDays = baselineFoods
                    .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                    .distinct().size.coerceAtLeast(1)
                val baselineKcalAvg = if (baselineFoods.isEmpty()) 0 else baselineFoods.sumOf { it.calories } / baselineDays
                val mostRecentForCategory = recentFoods
                    .filter { MealCategoryFromTime.classify(it.timestamp, it.mealCategory) == cat }
                    .maxByOrNull { it.timestamp }
                sb.append("Last ${cat.name.lowercase()} baseline (previous 2 days): ")
                    .append(if (baselineFoods.isEmpty()) "no data" else "$baselineKcalAvg kcal/day on average")
                    .append('\n')
                if (mostRecentForCategory != null) {
                    val ts = Instant.ofEpochMilli(mostRecentForCategory.timestamp)
                        .atZone(zone).toLocalDateTime()
                    sb.append("Most recent ${cat.name.lowercase()} entry: $ts · ")
                        .append(mostRecentForCategory.name).append(" · ")
                        .append(mostRecentForCategory.calories).append(" kcal\n")
                }
                // Yesterday at the same hour (rough — pick the meal whose time-of-day
                // matches): give one prior reference if any.
                val yesterdaySameMeal = recentFoods
                    .filter { it.timestamp in yesterdayStartMs until todayStartMs }
                    .filter { MealCategoryFromTime.classify(it.timestamp, it.mealCategory) == cat }
                    .sortedByDescending { it.timestamp }
                    .firstOrNull()
                if (yesterdaySameMeal != null) {
                    sb.append("Yesterday's ${cat.name.lowercase()}: ${yesterdaySameMeal.name} · ")
                        .append(yesterdaySameMeal.calories).append(" kcal\n")
                }
            }
            is Trigger.ActivitySpike -> {
                val activeKcalToday = (shDailyActivity[today]?.activeCalories ?: 0f).toInt()
                val stepsToday = shStepsByDate[today] ?: 0L
                sb.append("Today's activity so far — $stepsToday steps, $activeKcalToday active kcal\n")
                sb.append("This hour's surge vs same-hour yesterday: +${trigger.deltaKcal} active kcal\n")
            }
            is Trigger.MacrosAtRisk -> {
                if (latestTarget != null) {
                    val kcalLeft = (latestTarget.targetKcal - todayKcal).coerceAtLeast(0)
                    val proteinLeft = (latestTarget.targetProtein.toInt() - todayProtein).coerceAtLeast(0)
                    sb.append("Remaining to target — kcal $kcalLeft, protein ${proteinLeft}g\n")
                }
                sb.append("Hour: 18+ (post-dinner window)\n")
            }
            is Trigger.DailySummary -> {
                val stepsToday = shStepsByDate[today] ?: 0L
                val activeKcalToday = (shDailyActivity[today]?.activeCalories ?: 0f).toInt()
                if (latestTarget != null) {
                    val kcalDiff = todayKcal - latestTarget.targetKcal
                    val proteinDiff = todayProtein - latestTarget.targetProtein.toInt()
                    val kcalVerdict = when {
                        kcalDiff < -100 -> "below target by ${-kcalDiff} kcal"
                        kcalDiff > 100 -> "above target by ${kcalDiff} kcal"
                        else -> "on target"
                    }
                    val proteinVerdict = when {
                        proteinDiff < -10 -> "below target by ${-proteinDiff}g"
                        proteinDiff > 10 -> "above target by ${proteinDiff}g"
                        else -> "on target"
                    }
                    sb.append("Today vs target — kcal: $kcalVerdict; protein: $proteinVerdict\n")
                }
                sb.append("Today's activity — $stepsToday steps, $activeKcalToday active kcal\n")
                // CRITICAL: do not include weekly aggregates here. The trigger
                // is "summary of THE DAY" — anything beyond today is off-scope
                // and the model has burned trust by quoting it in the past.
            }
        }

        return sb.toString()
    }

    // ─── Defensive output filter ─────────────────────────────────────────────

    /** Strip any sentence that the LLM let slip in spite of the "NO questions /
     *  NO imperatives" rules in [buildTriggerInstruction]. With temperature 0.6
     *  the model still ignores style constraints in 5-10% of runs — this is
     *  the deterministic net so the user never sees "Hugo, almoçaste?".
     *
     *  2026-06-28 — the per-sentence filter is now delegated to
     *  [com.example.data.chat.ChatToneRules.clean] (single source of truth
     *  with the chat path). The 30-word budget cap that's unique to the
     *  proactive-notification format stays here, because the chat path
     *  intentionally allows longer replies. */
    internal fun stripQuestionsAndImperatives(raw: String, maxSentences: Int = 2): String {
        val sieved = com.example.data.chat.ChatToneRules.clean(raw, maxSentences = maxSentences)
        // Hard cap: at most 30 words across [maxSentences] sentences. If the
        // first sentence alone already exceeds 30 words, just keep it as-is —
        // better a slightly long single sentence than total dropout.
        val sentences = sieved.split(Regex("(?<=[.!?])\\s+"))
        val capped = mutableListOf<String>()
        var wordBudget = 30
        for (s in sentences) {
            val wc = s.trim().split(Regex("\\s+")).size
            if (capped.isEmpty()) {
                capped.add(s)
                wordBudget -= wc
            } else if (wc <= wordBudget) {
                capped.add(s)
                wordBudget -= wc
            }
        }
        return capped.joinToString(" ").trim()
    }

    // ─── Visual header (Kotlin-side, no LLM) ─────────────────────────────────

    /** Build the 2- to 3-line ASCII-progress header that is prepended to the
     *  LLM text in [ProactiveNudgeWorker]. The header is deterministic, costs
     *  zero tokens, and is guaranteed-exact regardless of model hallucination.
     *
     *  Shape (MealMissing / MacrosAtRisk):
     *  ```
     *  🍽️ Almoço em falta
     *  █████░░░░░ 30% kcal · 1341/1900
     *  ████░░░░░░ 47% prot · 62/130g
     *  ```
     *
     *  ActivitySpike emits steps/active-kcal lines; DailySummary emits the
     *  closing-of-day kcal+protein bars.
     *
     *  [bodyKcal] / [bodyProtein] are the numbers already used by the trigger
     *  evaluator and the prompt — passed in rather than recomputed so the
     *  header and the LLM see identical numbers.
     */
    fun buildVisualHeader(
        trigger: Trigger,
        language: String,
        bodyKcal: Int,
        targetKcal: Int,
        bodyProteinG: Int,
        targetProteinG: Int,
        stepsToday: Long = 0L,
        activeKcalToday: Int = 0
    ): String {
        // Notification title (NotificationCompat.setContentTitle, set in
        // ProactiveNudgeWorker.postNotification) already shows the trigger
        // class ("Refeições de hoje" / "Resumo do dia" / "Macros em risco" /
        // "Atividade desta semana"). Repeating the same label inside the
        // header reads as duplicate noise (user flagged 2026-06-22). So only
        // MealMissing keeps an extra label here — and only because it
        // narrows the title's "Refeições de hoje" to the specific window
        // (e.g. "Almoço sem registo"). The other 3 triggers go straight to
        // the bars.
        return when (trigger) {
            is Trigger.MealMissing -> {
                val label = mealMissingLabel(trigger.category, language)
                buildString {
                    append("🍴 ").append(label).append('\n')
                    append(macroLine("kcal", bodyKcal, targetKcal, suffix = ""))
                    append('\n')
                    append(macroLine("prot", bodyProteinG, targetProteinG, suffix = "g"))
                }
            }
            is Trigger.ActivitySpike -> {
                buildString {
                    append(macroLine("kcal activos", activeKcalToday, /*soft target*/ 500, suffix = ""))
                    append('\n')
                    append(macroLine("passos", stepsToday.toInt(), /*soft target*/ 6000, suffix = ""))
                }
            }
            is Trigger.MacrosAtRisk -> {
                buildString {
                    append(macroLine("kcal", bodyKcal, targetKcal, suffix = ""))
                    append('\n')
                    append(macroLine("prot", bodyProteinG, targetProteinG, suffix = "g"))
                }
            }
            is Trigger.DailySummary -> {
                buildString {
                    append(macroLine("kcal", bodyKcal, targetKcal, suffix = ""))
                    append('\n')
                    append(macroLine("prot", bodyProteinG, targetProteinG, suffix = "g"))
                }
            }
        }
    }

    /** 10-cell ASCII progress bar: "█████░░░░░ 47% kcal · 62/130". Saturates
     *  at 100% so a meal logged above target still shows a full bar without
     *  overflowing into a 12-char line that breaks notification wrapping. */
    private fun macroLine(label: String, current: Int, target: Int, suffix: String): String {
        if (target <= 0) return "—" // no target → can't draw a ratio
        val pct = ((current.toDouble() / target.toDouble()) * 100.0)
            .coerceIn(0.0, 999.0)
            .toInt()
        val cells = ((current.toDouble() / target.toDouble()) * 10.0)
            .coerceIn(0.0, 10.0)
            .toInt()
        val bar = "█".repeat(cells) + "░".repeat(10 - cells)
        return "$bar $pct% $label · $current/$target$suffix"
    }

    private fun mealMissingLabel(cat: com.example.data.local.MealCategory, language: String): String {
        val isPt = language.startsWith("pt", ignoreCase = true)
        // Tom: "sem registo" descreve um facto neutro. "em falta" lê-se como
        // pressão (alguém te falhou com algo) — foi explicitamente rejeitado
        // pelo Hugo (sessão 2026-06-22).
        return when (cat) {
            com.example.data.local.MealCategory.BREAKFAST ->
                if (isPt) "Pequeno-almoço sem registo" else "Breakfast — no log"
            com.example.data.local.MealCategory.LUNCH ->
                if (isPt) "Almoço sem registo" else "Lunch — no log"
            com.example.data.local.MealCategory.DINNER ->
                if (isPt) "Jantar sem registo" else "Dinner — no log"
            else -> if (isPt) "Refeição sem registo" else "Meal — no log"
        }
    }

    private fun macrosAtRiskLabel(language: String): String =
        if (language.startsWith("pt", ignoreCase = true)) "Macros do dia" else "Daily macros"

    private fun dailySummaryLabel(language: String): String =
        if (language.startsWith("pt", ignoreCase = true)) "Resumo do dia" else "Daily summary"
}
