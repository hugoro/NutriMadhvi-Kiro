package com.example.data.remote

import com.example.data.local.ActivityEntry
import com.example.data.local.BmrSnapshotEntity
import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.local.PerformanceSnapshotEntity
import com.example.data.local.TargetSnapshot
import com.example.data.local.UserInsight
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the per-turn data snapshot ("data card") that gets injected into the
 * Gemini system instruction so the assistant can answer with real numbers
 * instead of generic advice.
 *
 * Phase B: this is the simplest hybrid that buys most of the quality jump —
 * a fixed ~500-token block per turn covering today + last 7 days. For older
 * windows the assistant has to emit `[QUERY_DATA]` (Phase F) to get more.
 *
 * Caller is the ViewModel/Repository that already has the data in memory.
 * The builder itself does not query DAOs — it takes the inputs explicitly so
 * unit-testing is trivial and so we don't double-query the DB on every turn.
 */
internal object ChatContextBuilder {

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val DAY_HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /**
     * Returns the markdown block to append to the system instruction. May be
     * an empty string when the user has logged literally nothing yet — in
     * that case there's no data to ground on and the original prompt stands.
     *
     * @param today Reference date — caller passes LocalDate.now() in production;
     *              tests pass a fixed value.
     * @param zone  Timezone used to bucket epoch-ms timestamps into days.
     * @param foods Full food log (the builder filters/sorts internally).
     * @param activities Full activity log (filtered internally).
     * @param latestTarget Currently-active target snapshot (kcal/macros).
     * @param latestBmr  Currently-active BMR snapshot.
     * @param stepsByDate Samsung Health daily step counts. Empty map when SH
     *                    is offline; the section is omitted in that case.
     * @param caloriesBurnedByDate SH-derived calories burned per day. Empty
     *                             when offline.
     */
    fun build(
        today: LocalDate,
        zone: ZoneId,
        foods: List<FoodEntry>,
        activities: List<ActivityEntry>,
        latestTarget: TargetSnapshot?,
        latestBmr: BmrSnapshotEntity?,
        stepsByDate: Map<LocalDate, Long>,
        caloriesBurnedByDate: Map<LocalDate, Int>,
        /** Phase E: top-N confirmed UserInsights to surface so the assistant
         *  speaks back what it has learned. Empty list omits the section. */
        confirmedInsights: List<UserInsight> = emptyList(),
        /** The current user's display name, threaded from the orchestrator
         *  through `userNameOverride`. Used in the "Known facts about <name>"
         *  section so the canonical snapshot doesn't contradict the system
         *  prompt's name directive. Default `"the user"` keeps a sensible
         *  fallback when no name is available. */
        userName: String = "the user",
        /** Recent body-composition snapshots (newest first). Surfaces
         *  weight / body fat % / muscle mass / BMI history to the LLM so
         *  it can answer questions like "How has my weight changed?" or
         *  reason about lean-mass trends. Empty list omits the section.
         *  Caller decides the depth — a typical value is 5–10 entries. */
        bodyCompositionHistory: List<BodyCompositionSnapshotEntity> = emptyList(),
        /** Recent performance snapshots (newest first). Surfaces silhouette
         *  width ratios + coach report excerpt so the assistant can link body
         *  composition trends to visual assessment. Empty list omits section. */
        recentPerformanceSnapshots: List<PerformanceSnapshotEntity> = emptyList(),
    ): String {
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sevenDaysAgoStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()

        val todayFoods = foods.filter { it.timestamp in todayStart until tomorrowStart }
        val last7Foods = foods.filter { it.timestamp in sevenDaysAgoStart until tomorrowStart }
        val last7Activities = activities.filter { it.timestamp in sevenDaysAgoStart until tomorrowStart }

        val todayKcal = todayFoods.sumOf { it.calories }
        val todayProtein = todayFoods.sumOf { it.protein }
        val todayCarbs = todayFoods.sumOf { it.carbs }
        val todayFat = todayFoods.sumOf { it.fat }

        val last7DayCount = if (last7Foods.isEmpty()) 7 else
            last7Foods.map { dayOf(it.timestamp, zone) }.distinct().size.coerceAtLeast(1)
        val last7AvgKcal = if (last7Foods.isEmpty()) 0 else (last7Foods.sumOf { it.calories } / last7DayCount)
        val last7AvgProtein = if (last7Foods.isEmpty()) 0.0 else (last7Foods.sumOf { it.protein } / last7DayCount)

        val last7Steps = if (stepsByDate.isEmpty()) 0L else stepsByDate
            .filterKeys { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) }
            .values.sum()
        val last7Burned = if (caloriesBurnedByDate.isEmpty()) 0 else caloriesBurnedByDate
            .filterKeys { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) }
            .values.sum()
        val last7Workouts = last7Activities.size

        val sb = StringBuilder()
        sb.append("\n\n### Live data snapshot (date=").append(ISO_DATE.format(today)).append(")\n")
        sb.append("Reference only. These numbers are provided every turn so you can answer accurately — do NOT recite them back unprompted. ")
        sb.append("Use them when the user asks about today / this week, or when the totals changed since you last mentioned them. ")
        sb.append("Do not open a reply by restating today's consumption/targets the user has already seen this conversation. ")
        sb.append("If the user asks about a different window, emit a [QUERY_DATA] block.\n\n")

        // Targets + BMR
        if (latestTarget != null) {
            sb.append("**Today's prescribed targets** — kcal=").append(latestTarget.targetKcal)
                .append(", protein=").append(latestTarget.targetProtein.toInt()).append("g")
                .append(", carbs=").append(latestTarget.targetCarbs.toInt()).append("g")
                .append(", fat=").append(latestTarget.targetFat.toInt()).append("g\n")
        }
        if (latestBmr != null) {
            sb.append("**Latest BMR/TMB** — ").append(latestBmr.valueKcal).append(" kcal/day")
                .append(" (effective ").append(ISO_DATE.format(LocalDate.ofEpochDay(latestBmr.effectiveDateEpochDay))).append(")\n")
        }

        // Body composition history. The most recent entry feeds the "current"
        // line; if there are older entries we also surface a Δ from oldest →
        // newest so the LLM can reason about trends without re-deriving them.
        // Massa magra is computed inline (peso × (1 − bf/100)) — matches the
        // UI's derivation rule (see NutritionTableBody).
        if (bodyCompositionHistory.isNotEmpty()) {
            val latest = bodyCompositionHistory.first()
            sb.append("**Latest body composition** (")
            latest.sourceTimestampMs?.let {
                sb.append(ISO_DATE.format(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()))
            } ?: sb.append("date unknown")
            sb.append(") — weight ").append(String.format("%.1f", latest.weightKg)).append(" kg")
            latest.bodyFatPct?.let { sb.append(", body fat ").append(String.format("%.1f", it)).append("%") }
            latest.muscleMassKg?.let { sb.append(", muscle ").append(String.format("%.1f", it)).append(" kg") }
            if (latest.bodyFatPct != null) {
                val fatFree = latest.weightKg * (1f - latest.bodyFatPct / 100f)
                sb.append(", lean mass (derived) ").append(String.format("%.1f", fatFree)).append(" kg")
            }
            latest.totalBodyWaterKg?.let { sb.append(", water ").append(String.format("%.1f", it)).append(" kg") }
            latest.bmiValue?.let { sb.append(", BMI ").append(String.format("%.1f", it)) }
            sb.append('\n')

            if (bodyCompositionHistory.size >= 2) {
                val oldest = bodyCompositionHistory.last()
                val deltaKg = latest.weightKg - oldest.weightKg
                val sign = if (deltaKg >= 0) "+" else ""
                sb.append("**Body composition trend** — ")
                    .append("weight ").append(sign).append(String.format("%.1f", deltaKg)).append(" kg")
                if (latest.bodyFatPct != null && oldest.bodyFatPct != null) {
                    val deltaBf = latest.bodyFatPct - oldest.bodyFatPct
                    val bfSign = if (deltaBf >= 0) "+" else ""
                    sb.append(", body fat ").append(bfSign).append(String.format("%.1f", deltaBf)).append(" pp")
                }
                if (latest.muscleMassKg != null && oldest.muscleMassKg != null) {
                    val deltaM = latest.muscleMassKg - oldest.muscleMassKg
                    val mSign = if (deltaM >= 0) "+" else ""
                    sb.append(", muscle ").append(mSign).append(String.format("%.1f", deltaM)).append(" kg")
                }
                sb.append(" since ")
                oldest.sourceTimestampMs?.let {
                    sb.append(ISO_DATE.format(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()))
                } ?: sb.append("first reading")
                sb.append(" (").append(bodyCompositionHistory.size).append(" readings on file)\n")

                // Full series — one row per DAY (dedup by date, keep latest of the
                // day). Without this the LLM only has the two endpoints and any
                // CHART_PROPOSAL it emits will be a 2-bar chart hiding the shape
                // of the trajectory (session 102 msg 551, 2026-07-05: 6 real
                // readings collapsed into a 2-bar bar chart). Cap at 30 rows to
                // match ActionProposalParser.tryBuildChart's own upper bound.
                if (bodyCompositionHistory.size >= 3) {
                    // bodyCompositionHistory is newest-first (DAO ORDER BY sourceTimestampMs DESC),
                    // so we iterate reverse to get chronological order.
                    val byDay = linkedMapOf<LocalDate, BodyCompositionSnapshotEntity>()
                    for (r in bodyCompositionHistory.reversed()) {
                        val ts = r.sourceTimestampMs ?: continue
                        val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
                        // Overwrite: later reading on the same day wins.
                        byDay[day] = r
                    }
                    val rows = byDay.entries.toList().takeLast(30)
                    if (rows.size >= 3) {
                        sb.append("**Body composition series** (one row per day, newest last)\n")
                        for ((day, r) in rows) {
                            sb.append("- ").append(ISO_DATE.format(day))
                                .append(": weight ").append(String.format("%.1f", r.weightKg)).append(" kg")
                            r.bodyFatPct?.let { sb.append(", body fat ").append(String.format("%.1f", it)).append("%") }
                            r.muscleMassKg?.let { sb.append(", muscle ").append(String.format("%.1f", it)).append(" kg") }
                            sb.append('\n')
                        }
                    }
                }
            }
        }

        // Today
        sb.append("**Today consumed** — ").append(todayKcal).append(" kcal")
            .append(", protein ").append(formatGrams(todayProtein))
            .append(", carbs ").append(formatGrams(todayCarbs))
            .append(", fat ").append(formatGrams(todayFat))
            .append(" (").append(todayFoods.size).append(" entries)\n")

        // Last 7 days summary
        sb.append("**Last 7 days avg/day** — ").append(last7AvgKcal).append(" kcal")
            .append(", protein ").append(formatGrams(last7AvgProtein))
            .append(" (over ").append(last7DayCount).append(" days with logs)\n")
        if (stepsByDate.isNotEmpty() || caloriesBurnedByDate.isNotEmpty()) {
            sb.append("**Last 7 days activity** — ").append(last7Steps).append(" steps total")
                .append(", ").append(last7Burned).append(" kcal burned")
                .append(", ").append(last7Workouts).append(" logged workouts\n")
        }

        // Recent meals (last 5). Ingredients are surfaced per meal so the LLM
        // can reason about carb quality (fast vs slow, added sugar vs whole
        // food) — see [ChatToneRules.personaFor]'s "Sobre hidratos" rule.
        // Aggregate `carbs` alone hides the fast/slow distinction; adding a
        // one-line ingredient list ("aveia 60g, banana 120g") is what lets
        // the assistant tell "chá com aveia" apart from "sumo com bolachas".
        // Legacy entries without an ingredients breakdown fall back cleanly
        // to the previous name-only rendering.
        if (todayFoods.isNotEmpty() || last7Foods.isNotEmpty()) {
            val recent = (todayFoods + last7Foods).distinctBy { it.id }.sortedByDescending { it.timestamp }.take(5)
            sb.append("**Recent meals** (most recent first):\n")
            recent.forEach { f ->
                val ts = DAY_HOUR.format(Instant.ofEpochMilli(f.timestamp).atZone(zone))
                sb.append("- ").append(ts).append(" · ").append(f.name)
                    .append(" · ").append(f.calories).append("kcal")
                    .append(" · ").append(f.mealCategory.name.lowercase())
                val ingredients = decodeIngredientsForContext(f.ingredientsJson)
                if (ingredients.isNotEmpty()) {
                    sb.append(" · ingredients: ")
                    ingredients.joinTo(sb, separator = ", ") { ing ->
                        val g = ing.weightGrams
                        // Whole-gram output when the LLM gave us a whole number
                        // ("banana 120g"); 1 decimal only when it didn't. Keeps
                        // the line compact for prompt-caching.
                        val gStr = if (g % 1.0 == 0.0) "${g.toInt()}g" else "${(g * 10).toInt() / 10.0}g"
                        "${ing.name} $gStr"
                    }
                }
                // Free-sugars breakdown — surfaces only when the LLM populated
                // it. Costs ~6-8 tokens per meal. See ChatToneRules "Sobre
                // hidratos" for how the assistant is told to use it.
                val fs = f.freeSugarsG
                if (fs != null && f.carbs > 0.0) {
                    val clamped = fs.coerceIn(0.0, f.carbs)
                    val fsStr = if (clamped % 1.0 == 0.0) "${clamped.toInt()}" else String.format("%.1f", clamped)
                    val totalStr = if (f.carbs % 1.0 == 0.0) "${f.carbs.toInt()}" else String.format("%.1f", f.carbs)
                    sb.append(" · free sugars: ").append(fsStr).append("g/").append(totalStr).append("g")
                }
                // Saturated-fat breakdown — same pattern as free-sugars above.
                // Powers the split fat bar and the two-row Análise → Alimentação
                // split. Costs ~6-8 tokens per meal.
                val sat = f.saturatedFatG
                if (sat != null && f.fat > 0.0) {
                    val clampedSat = sat.coerceIn(0.0, f.fat)
                    val satStr = if (clampedSat % 1.0 == 0.0) "${clampedSat.toInt()}" else String.format("%.1f", clampedSat)
                    val fatTotalStr = if (f.fat % 1.0 == 0.0) "${f.fat.toInt()}" else String.format("%.1f", f.fat)
                    sb.append(" · sat fat: ").append(satStr).append("g/").append(fatTotalStr).append("g")
                }
                sb.append('\n')
            }
        }

        // Recent activities (last 3)
        if (last7Activities.isNotEmpty()) {
            sb.append("**Recent activities**:\n")
            last7Activities.sortedByDescending { it.timestamp }.take(3).forEach { a ->
                val ts = DAY_HOUR.format(Instant.ofEpochMilli(a.timestamp).atZone(zone))
                sb.append("- ").append(ts).append(" · ").append(a.name)
                    .append(" · ").append(a.durationMinutes).append("min")
                    .append(" · ").append(a.caloriesBurned).append("kcal\n")
            }
        }

        // Phase E: known facts (preferences, allergies, goals, professional
        // recommendations, derived patterns). Surface up to 8.
        if (confirmedInsights.isNotEmpty()) {
            sb.append("**Known facts about $userName** (do NOT contradict; you may build on them):\n")
            confirmedInsights.take(8).forEach { i ->
                sb.append("- [").append(i.category).append("] ").append(i.text).append('\n')
            }
        }

        // -----------------------------------------------------------------
        // 2026-06-18: month-so-far + sliding-30d + end-of-month projection.
        // Added to handle the kind of question Hugo screenshotted ("desde
        // início do mês ... se continuar assim ... qual é impacto") without
        // forcing a [QUERY_DATA] round-trip. Wider windows already in the
        // snapshot mean the LLM rarely needs to ask for more.
        // -----------------------------------------------------------------
        val monthStartDate = today.withDayOfMonth(1)
        val monthStartMs = monthStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val thirtyDaysAgoStartMs = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()

        val monthFoods = foods.filter { it.timestamp in monthStartMs until tomorrowStart }
        val last30Foods = foods.filter { it.timestamp in thirtyDaysAgoStartMs until tomorrowStart }
        val last30Activities = activities.filter { it.timestamp in thirtyDaysAgoStartMs until tomorrowStart }

        val monthDays = (today.dayOfMonth)  // 1..31, days elapsed including today
        val monthDaysWithLogs = monthFoods.map { dayOf(it.timestamp, zone) }.distinct().size
        val monthKcal = monthFoods.sumOf { it.calories }
        val monthAvgKcal = if (monthDays > 0) monthKcal / monthDays else 0
        val monthAvgKcalOnLoggedDays = if (monthDaysWithLogs > 0) monthKcal / monthDaysWithLogs else 0

        val last30DaysWithLogs = last30Foods.map { dayOf(it.timestamp, zone) }.distinct().size
        val last30Kcal = last30Foods.sumOf { it.calories }
        val last30AvgKcal = last30Kcal / 30  // intentionally over 30 not over loggedDays — projection input
        val last30Workouts = last30Activities.size
        val last30Burned = caloriesBurnedByDate
            .filterKeys { !it.isBefore(today.minusDays(29)) && !it.isAfter(today) }
            .values.sum()
        val last30Steps = stepsByDate
            .filterKeys { !it.isBefore(today.minusDays(29)) && !it.isAfter(today) }
            .values.sum()

        val ym = YearMonth.from(today)
        val daysInMonth = ym.lengthOfMonth()
        val daysRemaining = daysInMonth - monthDays
        // Project: month so far + sliding-30d daily average × remaining days.
        // This is the "se continuar assim" arithmetic.
        val projectedMonthKcal = monthKcal + last30AvgKcal * daysRemaining
        val targetKcal = latestTarget?.targetKcal ?: 0
        val projectedDelta = if (targetKcal > 0) projectedMonthKcal - (targetKcal * daysInMonth) else 0
        val deltaSign = when {
            projectedDelta > 0 -> "surplus"
            projectedDelta < 0 -> "deficit"
            else -> "neutral"
        }

        sb.append("\n**Month-to-date** (").append(monthStartDate).append(" → ").append(today)
            .append(", ").append(monthDays).append(" days elapsed of ").append(daysInMonth)
            .append("): ").append(monthKcal).append(" kcal · avg ").append(monthAvgKcal)
            .append(" kcal/day · ").append(monthDaysWithLogs).append(" days with logs")
            .append(" · avg-on-logged-days ").append(monthAvgKcalOnLoggedDays).append(" kcal/day\n")

        sb.append("**Sliding 30-day window**: ").append(last30Kcal).append(" kcal · avg ")
            .append(last30AvgKcal).append(" kcal/day (")
            .append(last30DaysWithLogs).append("/30 days logged) · ")
            .append(last30Workouts).append(" workouts · ").append(last30Steps).append(" steps · ")
            .append(last30Burned).append(" kcal burned\n")

        if (targetKcal > 0 && daysRemaining > 0) {
            sb.append("**End-of-month projection** (\"se continuar assim\"): if the sliding-30d daily ")
                .append("average (").append(last30AvgKcal).append(" kcal/day) holds for the remaining ")
                .append(daysRemaining).append(" days, total intake by ").append(ym.atEndOfMonth())
                .append(" ≈ ").append(projectedMonthKcal).append(" kcal vs target ")
                .append(targetKcal * daysInMonth).append(" kcal → projected ")
                .append(deltaSign).append(" of ").append(kotlin.math.abs(projectedDelta))
                .append(" kcal.\n")
        } else if (targetKcal > 0 && daysRemaining == 0) {
            sb.append("**End-of-month**: today is the last day of ").append(ym).append(".\n")
        }

        if (recentPerformanceSnapshots.isNotEmpty()) {
            val latest = recentPerformanceSnapshots.first()
            val perfDate = DAY_HOUR.format(
                java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(latest.capturedAtMs), zone))
            sb.append("\n## Última avaliação de performance ($perfDate)\n")
            latest.frontalShoulderRatio?.let { sb.append("- Ombros: ${"%.3f".format(it)}\n") }
            latest.frontalWaistRatio?.let    { sb.append("- Cintura: ${"%.3f".format(it)}\n") }
            latest.frontalHipRatio?.let      { sb.append("- Ancas: ${"%.3f".format(it)}\n") }
            latest.frontalThighRatio?.let    { sb.append("- Coxas: ${"%.3f".format(it)}\n") }
            latest.lateralBellyRatio?.let    { sb.append("- Barriga (lateral): ${"%.3f".format(it)}\n") }
            latest.coachReport?.let { report ->
                sb.append("- Resumo do coach: \"${report.take(120)}${if (report.length > 120) "…" else ""}\"\n")
            }
        }

        return sb.toString()
    }

    private fun dayOf(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun formatGrams(g: Double): String {
        // 1 decimal but trim trailing .0 to keep the prompt compact
        val rounded = (g * 10).toInt() / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}g" else "${rounded}g"
    }

    /**
     * Decodes the JSON blob stored in [FoodEntry.ingredientsJson] into a lean
     * projection carrying only the fields the prompt cares about (name +
     * weight). Kept here — instead of reusing `parseIngredients` from the UI
     * module — so this data-remote object doesn't invert dependencies on the
     * UI layer just to read a JSON column that originated here in the first
     * place (see [encodeIngredients] in GeminiRepository).
     *
     * Any parse failure returns an empty list; the caller falls back to the
     * legacy name-only rendering, so bad data never crashes the chat build.
     */
    private data class IngredientProjection(val name: String, val weightGrams: Double)

    private val moshiForIngredients: Moshi by lazy { Moshi.Builder().build() }
    private val ingredientListType by lazy {
        Types.newParameterizedType(List::class.java, IngredientDto::class.java)
    }

    private fun decodeIngredientsForContext(json: String?): List<IngredientProjection> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val adapter = moshiForIngredients.adapter<List<IngredientDto>>(ingredientListType)
            val parsed = adapter.fromJson(json) ?: return emptyList()
            parsed.map { IngredientProjection(it.name, it.weightGrams) }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
