package com.example.data.chat

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.local.TargetSnapshot
import com.example.data.local.TrainingBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 7 — **deterministic nutrition tools**.
 *
 * A pure, DAO-free seam that turns already-fetched rows into small typed
 * summaries for an arbitrary [DateWindow]. This is the structured sibling of
 * the two existing Markdown-only paths:
 *   - [com.example.data.remote.ChatContextBuilder.build] — one big canonical
 *     snapshot string baked into the system prompt every turn;
 *   - [PrefetchedData.toContextBlock] — a `[QUERY_DATA]` window rendered to
 *     Markdown.
 *
 * Neither of those returns *values*: Phase 8's bounded agentic layer needs to
 * call a computation for a window and get back numbers it can branch on, not
 * prose it must re-parse. These tools fill that gap while staying faithful to
 * the arithmetic already used elsewhere so answers never diverge:
 *   - **avg-on-logged-days** counts only days that actually have a meal row,
 *     matching `ChatContextBuilder.last7DayCount` / `DataFetcher`'s per-day
 *     rollup;
 *   - **lean mass** is derived `weight × (1 − bodyFat/100)`, matching the UI
 *     rule and `ChatContextBuilder`;
 *   - **body-composition series** dedups to one row per day (latest wins) and
 *     caps at 30, matching `ChatContextBuilder`'s series block.
 *
 * ### Design contract (mirrors the pattern the user approved for Phase 6/7)
 *   - **Pure.** Every function takes rows + a window explicitly; it never
 *     touches a DAO, a Flow, `System.currentTimeMillis()`, or any orchestrator
 *     state. This makes every tool trivially unit-testable with plain lists —
 *     no Mockito (not on the classpath), no fakes.
 *   - **No persistence, no schema.** Room stays at v33; these tools only read.
 *   - **Dual shape.** Each returns a typed `data class` (for Phase 8) that also
 *     carries `toContextBlock()` (for the chat context path), exactly like
 *     [PrefetchedData] — so a metric's definition and its rendering live in one
 *     place and evolve together.
 *
 * Callers are responsible for pre-filtering / supplying rows that cover the
 * window; the tools additionally window-filter defensively so a caller passing
 * a wider list still gets a correct answer for the requested span.
 */
object NutritionTools {

    // --- window helpers ----------------------------------------------------

    private fun startMs(window: DateWindow, zone: ZoneId): Long =
        window.from.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun endMs(window: DateWindow, zone: ZoneId): Long =
        window.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun dayOf(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun inWindow(ts: Long, window: DateWindow, zone: ZoneId): Boolean =
        ts in startMs(window, zone) until endMs(window, zone)

    private fun fmt1(v: Number): String = String.format("%.1f", v.toDouble())

    // === getCalorieSummary =================================================

    /**
     * Calorie totals for [window]. [target] is the prescribed daily kcal that
     * applies over the window (caller resolves it from `TargetSnapshotDao`);
     * when present, [CalorieSummary.vsTargetDelta] reports total consumed minus
     * `target × days` so a positive number means over-budget across the span.
     */
    fun getCalorieSummary(
        foods: List<FoodEntry>,
        window: DateWindow,
        target: TargetSnapshot? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CalorieSummary {
        val rows = foods.filter { inWindow(it.timestamp, window, zone) }
        val totalKcal = rows.sumOf { it.calories }
        val loggedDays = rows.map { dayOf(it.timestamp, zone) }.distinct().size
        val windowDays = window.days.coerceAtLeast(1)
        val perDayAvg = totalKcal / windowDays
        val avgOnLoggedDays = if (loggedDays > 0) totalKcal / loggedDays else 0
        // Budget compares consumed against the target applied across every day
        // of the window (not just logged days) — an unlogged day is still a day
        // the user had a budget. Null target ⇒ no verdict.
        val vsTargetDelta = target?.let { totalKcal - it.targetKcal * windowDays }
        return CalorieSummary(
            window = window,
            totalKcal = totalKcal,
            entryCount = rows.size,
            daysWithLogs = loggedDays,
            windowDays = windowDays,
            perDayAvg = perDayAvg,
            avgOnLoggedDays = avgOnLoggedDays,
            targetKcalPerDay = target?.targetKcal,
            vsTargetDelta = vsTargetDelta,
        )
    }

    // === getMacroSummary ===================================================

    /** Protein / carb / fat totals + per-day averages + energy-share split. */
    fun getMacroSummary(
        foods: List<FoodEntry>,
        window: DateWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MacroSummary {
        val rows = foods.filter { inWindow(it.timestamp, window, zone) }
        val protein = rows.sumOf { it.protein }
        val carbs = rows.sumOf { it.carbs }
        val fat = rows.sumOf { it.fat }
        val loggedDays = rows.map { dayOf(it.timestamp, zone) }.distinct().size.coerceAtLeast(1)
        // Energy-share split from the macro grams (4/4/9 kcal/g). Uses the
        // macro-derived energy, NOT FoodEntry.calories, so the three shares sum
        // to 100% by construction; kept separate from getCalorieSummary's
        // stored-kcal total on purpose.
        val pKcal = protein * 4.0
        val cKcal = carbs * 4.0
        val fKcal = fat * 9.0
        val macroKcal = pKcal + cKcal + fKcal
        val proteinPct = if (macroKcal > 0) (pKcal / macroKcal * 100).toInt() else 0
        val carbsPct = if (macroKcal > 0) (cKcal / macroKcal * 100).toInt() else 0
        val fatPct = if (macroKcal > 0) (fKcal / macroKcal * 100).toInt() else 0
        return MacroSummary(
            window = window,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            entryCount = rows.size,
            daysWithLogs = loggedDays,
            proteinPerDay = protein / loggedDays,
            carbsPerDay = carbs / loggedDays,
            fatPerDay = fat / loggedDays,
            proteinPct = proteinPct,
            carbsPct = carbsPct,
            fatPct = fatPct,
        )
    }

    // === getWeightTrend ====================================================

    /**
     * Weight / body-composition trajectory across [window]. [history] is the
     * append-only [BodyCompositionSnapshotEntity] list (newest-first, as the
     * DAO returns it). The result carries a per-day series (dedup, latest of
     * the day wins, cap 30) matching `ChatContextBuilder`'s series block, plus
     * first/last/delta and a per-day slope for the window.
     */
    fun getWeightTrend(
        history: List<BodyCompositionSnapshotEntity>,
        window: DateWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeightTrend {
        // Window-filter on the source timestamp; rows without one can't be
        // placed on the timeline so they're excluded from the trend (they still
        // exist for "latest known" elsewhere).
        val inWin = history.filter { r ->
            val ts = r.sourceTimestampMs ?: return@filter false
            val d = dayOf(ts, zone)
            !d.isBefore(window.from) && !d.isAfter(window.to)
        }
        // One row per day, latest-of-day wins, chronological.
        val byDay = linkedMapOf<LocalDate, BodyCompositionSnapshotEntity>()
        for (r in inWin.sortedBy { it.sourceTimestampMs }) {
            val day = dayOf(r.sourceTimestampMs!!, zone)
            byDay[day] = r
        }
        val series = byDay.entries.toList().takeLast(30).map { (d, r) ->
            WeightPoint(
                date = d,
                weightKg = r.weightKg,
                bodyFatPct = r.bodyFatPct,
                muscleMassKg = r.muscleMassKg,
                leanMassKg = r.bodyFatPct?.let { r.weightKg * (1f - it / 100f) },
            )
        }
        val first = series.firstOrNull()
        val last = series.lastOrNull()
        // A delta needs two distinct points. With 0 or 1 readings there is no
        // trend to report — leave delta/slope NULL rather than 0.0, because
        // "0 kg change" and "unknown" are different facts (a downstream agent
        // must not read a single-reading window as "weight stable").
        val hasTrend = series.size >= 2 && first != null && last != null
        val deltaKg = if (hasTrend) last!!.weightKg - first!!.weightKg else null
        // Slope per day across the observed span (last date − first date), NOT
        // the window width — a fair rate over the days we actually have data.
        val slopePerDay = if (hasTrend) {
            val spanDays = (last!!.date.toEpochDay() - first!!.date.toEpochDay()).coerceAtLeast(1)
            deltaKg!! / spanDays
        } else null
        return WeightTrend(
            window = window,
            readingCount = series.size,
            firstWeightKg = first?.weightKg,
            lastWeightKg = last?.weightKg,
            deltaKg = deltaKg,
            slopeKgPerDay = slopePerDay,
            series = series,
        )
    }

    // === getActivitySummary ================================================

    /**
     * Movement totals across [window], combining the two activity sources:
     *   - **manual** [ActivityEntry] rows (duration + calories the user logged);
     *   - **Samsung Health** — daily rollups ([shDaily] active kcal/min/distance,
     *     [shSteps] steps) and per-session [shWorkouts] bucketed by
     *     [TrainingBucket].
     *
     * SH steps/active-kcal are the source of truth for movement (memory
     * `activity-metrics-source-of-truth`); manual entries are surfaced
     * separately so a caller can tell logged workouts from device-measured
     * activity rather than double-counting.
     */
    fun getActivitySummary(
        window: DateWindow,
        activities: List<ActivityEntry> = emptyList(),
        shSteps: Map<LocalDate, Long> = emptyMap(),
        shDaily: Map<LocalDate, ShDailyActivity> = emptyMap(),
        shWorkouts: List<ShWorkout> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ActivitySummary {
        val manual = activities.filter { inWindow(it.timestamp, window, zone) }
        val steps = shSteps.filterKeys { !it.isBefore(window.from) && !it.isAfter(window.to) }
        val daily = shDaily.filterKeys { !it.isBefore(window.from) && !it.isAfter(window.to) }
        val workouts = shWorkouts.filter { w ->
            val d = w.startTime.atZone(zone).toLocalDate()
            !d.isBefore(window.from) && !d.isAfter(window.to)
        }

        val totalSteps = steps.values.sum()
        val shActiveKcal = daily.values.sumOf { it.activeCalories.toInt() }
        val shActiveMinutes = daily.values.sumOf { it.activeTime.toMinutes() }
        val shDistanceM = daily.values.fold(0f) { acc, d -> acc + d.distanceMeters }

        val manualMinutes = manual.sumOf { it.durationMinutes.toLong() }
        val manualKcal = manual.sumOf { it.caloriesBurned }

        // Per-bucket session rollup — same coarse categorisation the LLM sees
        // in DataFetcher, so weekly cardio/strength volume matches.
        val byBucket = workouts.groupBy { it.type.bucket() }
        val buckets = TrainingBucket.values().mapNotNull { b ->
            val ws = byBucket[b]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            BucketVolume(
                bucket = b,
                sessions = ws.size,
                minutes = ws.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() },
                activeKcal = ws.sumOf { it.activeKcal ?: 0 },
            )
        }

        return ActivitySummary(
            window = window,
            totalSteps = totalSteps,
            shActiveKcal = shActiveKcal,
            shActiveMinutes = shActiveMinutes,
            shDistanceMeters = shDistanceM.toInt(),
            shDaysWithData = daily.keys.union(steps.keys).size,
            workoutSessions = workouts.size,
            manualEntries = manual.size,
            manualMinutes = manualMinutes,
            manualKcal = manualKcal,
            buckets = buckets,
        )
    }

    // === result types ======================================================

    data class CalorieSummary(
        val window: DateWindow,
        val totalKcal: Int,
        val entryCount: Int,
        val daysWithLogs: Int,
        val windowDays: Int,
        val perDayAvg: Int,
        val avgOnLoggedDays: Int,
        val targetKcalPerDay: Int?,
        /** Total consumed − target×windowDays. Positive = over budget across
         *  the span. Null when no target was supplied. */
        val vsTargetDelta: Int?,
    ) {
        fun toContextBlock(): String = buildString {
            append("[CALORIE SUMMARY — ${window.from}→${window.to} (${window.label})]\n")
            if (entryCount == 0) {
                append("- No meals logged in this window.\n")
            } else {
                append("- Total: $totalKcal kcal · $entryCount entries · $daysWithLogs/$windowDays days with logs\n")
                append("- Avg: $perDayAvg kcal/day (window) · $avgOnLoggedDays kcal/day (logged days only)\n")
                if (targetKcalPerDay != null && vsTargetDelta != null) {
                    val sign = if (vsTargetDelta >= 0) "+" else ""
                    val verdict = if (vsTargetDelta >= 0) "over" else "under"
                    append("- Target: $targetKcalPerDay kcal/day → $sign$vsTargetDelta kcal $verdict budget across $windowDays days\n")
                }
            }
        }
    }

    data class MacroSummary(
        val window: DateWindow,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
        val entryCount: Int,
        val daysWithLogs: Int,
        val proteinPerDay: Double,
        val carbsPerDay: Double,
        val fatPerDay: Double,
        /** Energy-share of macro-derived kcal (4/4/9). Sums to ~100. */
        val proteinPct: Int,
        val carbsPct: Int,
        val fatPct: Int,
    ) {
        fun toContextBlock(): String = buildString {
            append("[MACRO SUMMARY — ${window.from}→${window.to} (${window.label})]\n")
            if (entryCount == 0) {
                append("- No meals logged in this window.\n")
            } else {
                append("- Totals: P${fmt1(proteinG)}g · C${fmt1(carbsG)}g · F${fmt1(fatG)}g ($entryCount entries, $daysWithLogs days)\n")
                append("- Per logged day: P${fmt1(proteinPerDay)}g · C${fmt1(carbsPerDay)}g · F${fmt1(fatPerDay)}g\n")
                append("- Energy split: protein $proteinPct% · carbs $carbsPct% · fat $fatPct%\n")
            }
        }
    }

    data class WeightPoint(
        val date: LocalDate,
        val weightKg: Float,
        val bodyFatPct: Float?,
        val muscleMassKg: Float?,
        val leanMassKg: Float?,
    )

    data class WeightTrend(
        val window: DateWindow,
        val readingCount: Int,
        val firstWeightKg: Float?,
        val lastWeightKg: Float?,
        val deltaKg: Float?,
        val slopeKgPerDay: Float?,
        val series: List<WeightPoint>,
    ) {
        fun toContextBlock(): String = buildString {
            append("[WEIGHT TREND — ${window.from}→${window.to} (${window.label})]\n")
            if (readingCount == 0) {
                append("- No body-composition readings in this window.\n")
            } else if (readingCount == 1) {
                append("- Single reading: ${fmt1(lastWeightKg ?: 0f)} kg (no trend from one point).\n")
            } else {
                val sign = if ((deltaKg ?: 0f) >= 0) "+" else ""
                append("- ${fmt1(firstWeightKg ?: 0f)} kg → ${fmt1(lastWeightKg ?: 0f)} kg · $sign${fmt1(deltaKg ?: 0f)} kg over $readingCount readings\n")
                slopeKgPerDay?.let { append("- Rate: ${fmt1(it * 7)} kg/week\n") }
                append("- Series (per day, newest last):\n")
                series.forEach { p ->
                    append("  - ${p.date}: ${fmt1(p.weightKg)} kg")
                    p.bodyFatPct?.let { append(", bf ${fmt1(it)}%") }
                    p.leanMassKg?.let { append(", lean ${fmt1(it)} kg") }
                    append("\n")
                }
            }
        }
    }

    data class BucketVolume(
        val bucket: TrainingBucket,
        val sessions: Int,
        val minutes: Long,
        val activeKcal: Int,
    )

    data class ActivitySummary(
        val window: DateWindow,
        val totalSteps: Long,
        val shActiveKcal: Int,
        val shActiveMinutes: Long,
        val shDistanceMeters: Int,
        val shDaysWithData: Int,
        val workoutSessions: Int,
        val manualEntries: Int,
        val manualMinutes: Long,
        val manualKcal: Int,
        val buckets: List<BucketVolume>,
    ) {
        fun toContextBlock(): String = buildString {
            append("[ACTIVITY SUMMARY — ${window.from}→${window.to} (${window.label})]\n")
            val hasSh = shDaysWithData > 0 || totalSteps > 0
            if (!hasSh && manualEntries == 0) {
                append("- No activity recorded in this window.\n")
            } else {
                if (hasSh) {
                    append("- Samsung Health: $totalSteps steps · $shActiveKcal active kcal · $shActiveMinutes active min · ${shDistanceMeters}m ($shDaysWithData days)\n")
                }
                if (workoutSessions > 0) {
                    val bucketLine = buckets.joinToString(" · ") {
                        "${it.bucket.name.lowercase()}: ${it.sessions} sess (${it.minutes}min, ${it.activeKcal}kcal)"
                    }
                    append("- Workouts: $workoutSessions sessions → $bucketLine\n")
                }
                if (manualEntries > 0) {
                    append("- Manual log: $manualEntries entries · ${manualMinutes}min · $manualKcal kcal\n")
                }
            }
        }
    }
}
