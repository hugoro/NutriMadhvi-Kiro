package com.example.data.chat

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityDao
import com.example.data.local.ActivityEntry
import com.example.data.local.FoodDao
import com.example.data.local.FoodEntry
import com.example.data.local.TrainingBucket
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-first parallel data fetcher — Android port of the `runParallelFetch`
 * pattern in `/Users/i548178/GitHub/ITOpsEA/Sources/ShellManager.swift`.
 *
 * Once [IntentRouter] decides the user is asking about a data window, the
 * orchestrator calls [fetchForIntent] which fans out to the food/activity
 * DAOs and the in-memory Samsung Health maps in parallel using
 * [coroutineScope] + [async]. Total wall-clock = max(individual queries) ≈
 * a few ms, vs the ~5–10s a `[QUERY_DATA]` round-trip used to take.
 *
 * The Samsung Health maps are passed in as plain values (not flows) because
 * the canonical source of SH state is the [com.example.ui.ChatViewModel] —
 * we don't want a parallel collector here. The orchestrator snapshots
 * `_shStepsByDate.value` / `_shDailyActivity.value` at call time and hands
 * them in.
 */
class DataFetcher(
    private val foodDao: FoodDao,
    private val activityDao: ActivityDao
) {
    suspend fun fetchForIntent(
        intent: ChatIntent.DataQuery,
        zone: ZoneId = ZoneId.systemDefault(),
        shStepsByDate: Map<LocalDate, Long> = emptyMap(),
        shDailyActivity: Map<LocalDate, ShDailyActivity> = emptyMap(),
        shWorkouts: List<ShWorkout> = emptyList()
    ): PrefetchedData = coroutineScope {
        val window = intent.window
        val startMs = window.from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = window.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val mealsDeferred: Deferred<List<FoodEntry>>? = if (intent.type != DataType.ACTIVITY) {
            async { runCatching { foodDao.getFoodsBetween(startMs, endMs) }.getOrDefault(emptyList()) }
        } else null

        val activitiesDeferred: Deferred<List<ActivityEntry>>? = if (intent.type != DataType.MEALS) {
            async { runCatching { activityDao.getActivitiesBetween(startMs, endMs) }.getOrDefault(emptyList()) }
        } else null

        val stepsDeferred = async {
            shStepsByDate.filterKeys { d -> !d.isBefore(window.from) && !d.isAfter(window.to) }
        }
        val dailyDeferred = async {
            shDailyActivity.filterKeys { d -> !d.isBefore(window.from) && !d.isAfter(window.to) }
        }
        val workoutsDeferred = async {
            if (intent.type == DataType.MEALS) emptyList()
            else shWorkouts.filter { w ->
                val d = w.startTime.atZone(zone).toLocalDate()
                !d.isBefore(window.from) && !d.isAfter(window.to)
            }
        }

        val started = System.currentTimeMillis()
        val data = PrefetchedData(
            window = window,
            type = intent.type,
            meals = mealsDeferred?.await().orEmpty(),
            activities = activitiesDeferred?.await().orEmpty(),
            shSteps = stepsDeferred.await(),
            shDaily = dailyDeferred.await(),
            shWorkouts = workoutsDeferred.await()
        )
        val elapsed = System.currentTimeMillis() - started
        android.util.Log.d(
            "CHAT_FETCH",
            "intent=${intent.type} window=${window.from}..${window.to} " +
                "meals=${data.meals.size} activities=${data.activities.size} " +
                "shDays=${data.shDaily.size} shWorkouts=${data.shWorkouts.size} elapsedMs=$elapsed source=dao"
        )
        data
    }

    /**
     * 2026-07-06 (Frente C) — in-memory sibling of [fetchForIntent] that
     * accepts pre-loaded meal/activity rows (e.g. from
     * [ChatOrchestrator]'s canonical snapshot, which already reads 31 days
     * for the LLM's context block). Callers are responsible for ensuring
     * the pre-loaded window contains the intent window — [ChatOrchestrator]
     * checks `intent.window.days <= 31` before invoking this and falls back
     * to [fetchForIntent] otherwise.
     *
     * SH maps and workouts still fan out via `async` for consistency with
     * [fetchForIntent], but they're all pure `filter` on in-memory data —
     * the actual saving here is skipping the two Room queries that would
     * otherwise overlap the canonical snapshot's reads.
     */
    suspend fun filterForIntent(
        intent: ChatIntent.DataQuery,
        preloadedFoods: List<FoodEntry>,
        preloadedActivities: List<ActivityEntry>,
        zone: ZoneId = ZoneId.systemDefault(),
        shStepsByDate: Map<LocalDate, Long> = emptyMap(),
        shDailyActivity: Map<LocalDate, ShDailyActivity> = emptyMap(),
        shWorkouts: List<ShWorkout> = emptyList()
    ): PrefetchedData = coroutineScope {
        val window = intent.window
        val startMs = window.from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = window.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val meals = if (intent.type != DataType.ACTIVITY) {
            preloadedFoods.filter { it.timestamp in startMs until endMs }
        } else emptyList()
        val activities = if (intent.type != DataType.MEALS) {
            preloadedActivities.filter { it.timestamp in startMs until endMs }
        } else emptyList()
        val shSteps = shStepsByDate.filterKeys { d -> !d.isBefore(window.from) && !d.isAfter(window.to) }
        val shDaily = shDailyActivity.filterKeys { d -> !d.isBefore(window.from) && !d.isAfter(window.to) }
        val shWk = if (intent.type == DataType.MEALS) emptyList()
        else shWorkouts.filter { w ->
            val d = w.startTime.atZone(zone).toLocalDate()
            !d.isBefore(window.from) && !d.isAfter(window.to)
        }

        val started = System.currentTimeMillis()
        val data = PrefetchedData(
            window = window,
            type = intent.type,
            meals = meals,
            activities = activities,
            shSteps = shSteps,
            shDaily = shDaily,
            shWorkouts = shWk
        )
        val elapsed = System.currentTimeMillis() - started
        android.util.Log.d(
            "CHAT_FETCH",
            "intent=${intent.type} window=${window.from}..${window.to} " +
                "meals=${data.meals.size} activities=${data.activities.size} " +
                "shDays=${data.shDaily.size} shWorkouts=${data.shWorkouts.size} elapsedMs=$elapsed source=preloaded"
        )
        data
    }
}

/**
 * Fully-resolved data block matching one [ChatIntent.DataQuery]. The
 * [toContextBlock] renderer flattens it into Markdown the orchestrator
 * prepends to the system instruction, mirroring ITOpsEA's
 * `buildPrefetchedContext`.
 */
data class PrefetchedData(
    val window: DateWindow,
    val type: DataType,
    val meals: List<FoodEntry>,
    val activities: List<ActivityEntry>,
    val shSteps: Map<LocalDate, Long>,
    val shDaily: Map<LocalDate, ShDailyActivity>,
    val shWorkouts: List<ShWorkout> = emptyList()
) {
    /** Render as a markdown PRE-FETCHED block to be injected into the
     *  system instruction. The LLM is instructed to treat this as ground
     *  truth — no further QUERY_DATA round-trips needed. */
    fun toContextBlock(@Suppress("UNUSED_PARAMETER") language: String): String = buildString {
        append("\n\n[PRE-FETCHED DATA — the app already queried this for you, treat it as ground truth]\n")
        append("Window: ${window.from} → ${window.to} (${window.days} days, label: \"${window.label}\")\n")

        if (type != DataType.ACTIVITY) {
            append("\n## Meals\n")
            if (meals.isEmpty()) {
                append("- (no meals logged in this window)\n")
            } else {
                val totalKcal = meals.sumOf { it.calories }
                val totalP = meals.sumOf { it.protein.toDouble() }
                val totalC = meals.sumOf { it.carbs.toDouble() }
                val totalF = meals.sumOf { it.fat.toDouble() }
                val daysWithLogs = meals.map { java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.distinct().size
                append("Total: ${meals.size} entries · $totalKcal kcal · P${totalP.toInt()}/C${totalC.toInt()}/F${totalF.toInt()} g · $daysWithLogs days with logs · avg ${if (window.days > 0) totalKcal / window.days else 0} kcal/day · avg-on-logged-days ${if (daysWithLogs > 0) totalKcal / daysWithLogs else 0} kcal/day\n")
                // Per-day macro rollup — needed for chart proposals so the
                // LLM can quote a `series` of `{label, value}` per day without
                // re-aggregating raw rows itself. Sorted chronologically;
                // covers up to the last 14 days so token budget stays sane
                // (longer windows fall back to weekly summarisation, which
                // the LLM can do from raw rows below).
                val zoneId = java.time.ZoneId.systemDefault()
                val byDay = meals.groupBy { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }
                    .toSortedMap()
                if (byDay.size in 2..14) {
                    append("\n### Per-day macros (use these for chart series)\n")
                    byDay.forEach { (d, fs) ->
                        val k = fs.sumOf { it.calories }
                        val p = fs.sumOf { it.protein.toDouble() }
                        val c = fs.sumOf { it.carbs.toDouble() }
                        val f = fs.sumOf { it.fat.toDouble() }
                        append("- $d · ${k}kcal · P${"%.1f".format(p)}g · C${"%.1f".format(c)}g · F${"%.1f".format(f)}g\n")
                    }
                }
                // Cap the per-row dump so we don't blow up tokens on big windows —
                // 35 most recent entries is plenty to ground answers.
                val rows = meals.sortedByDescending { it.timestamp }.take(35)
                rows.forEach { f ->
                    val date = java.time.Instant.ofEpochMilli(f.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    append("- $date · ${f.name} · ${f.calories}kcal · P${f.protein.toInt()}/C${f.carbs.toInt()}/F${f.fat.toInt()} · ${f.mealCategory.name.lowercase()}\n")
                }
                if (meals.size > rows.size) {
                    append("- (${meals.size - rows.size} older entries omitted; totals above include all)\n")
                }
            }
        }

        if (type != DataType.MEALS) {
            append("\n## Activities (manual log)\n")
            if (activities.isEmpty()) {
                append("- (no manual activities logged in this window)\n")
            } else {
                val totalMin = activities.sumOf { it.durationMinutes }
                val totalKcal = activities.sumOf { it.caloriesBurned }
                append("Total: ${activities.size} entries · ${totalMin}min · ${totalKcal} kcal burned\n")
                val rows = activities.sortedByDescending { it.timestamp }.take(20)
                rows.forEach { a ->
                    val date = java.time.Instant.ofEpochMilli(a.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    append("- $date · ${a.name} · ${a.durationMinutes}min · ${a.caloriesBurned}kcal · ${a.activityType.name.lowercase()}\n")
                }
                if (activities.size > rows.size) {
                    append("- (${activities.size - rows.size} older entries omitted; totals above include all)\n")
                }
            }
        }

        if (shDaily.isNotEmpty() || shSteps.isNotEmpty()) {
            append("\n## Samsung Health (per-day)\n")
            val allDates = (shDaily.keys + shSteps.keys).toSortedSet()
            var totalSteps = 0L
            var totalActiveCalories = 0
            var totalActiveMinutes = 0L
            var totalDistance = 0f
            allDates.forEach { d ->
                val st = shSteps[d] ?: 0L
                val daily = shDaily[d]
                totalSteps += st
                if (daily != null) {
                    totalActiveCalories += daily.activeCalories.toInt()
                    totalActiveMinutes += daily.activeTime.toMinutes()
                    totalDistance += daily.distanceMeters
                }
            }
            append("Total: $totalSteps steps · $totalActiveCalories active kcal · $totalActiveMinutes active minutes · ${totalDistance.toInt()}m distance · ${allDates.size} days\n")
            // Per-day rows for the LLM to spot streaks/dips.
            allDates.toList().takeLast(35).forEach { d ->
                val st = shSteps[d] ?: 0L
                val daily = shDaily[d]
                val cal = daily?.activeCalories?.toInt() ?: 0
                val min = daily?.activeTime?.toMinutes() ?: 0L
                append("- $d · ${st} steps · ${cal} active kcal · ${min} active min\n")
            }
        }

        // Per-session workout breakdown — the daily rollup above only shows
        // active kcal/minutes; it cannot say whether those minutes were a
        // 60min run or a 45min weights session. The chat's "personal
        // trainer" answers rely on this distinction (cardio vs strength
        // volume, recovery, weekly mix) so we surface each session with
        // BOTH its specific Samsung modality (ShWorkout.sourceLabel — e.g.
        // "Pilates", "Weight machine") AND its coarse bucket (cardio /
        // strength / mobility / other). The LLM uses the specific label for
        // individual sessions and the bucket for weekly aggregates —
        // see HABIT_PROTOCOL. Categorisation is computed locally —
        // see [ActivityType.bucket] and [ExerciseTypeLabels] — so the
        // model never has to re-classify SH's ~110 raw exercise types.
        if (shWorkouts.isNotEmpty() && type != DataType.MEALS) {
            append("\n## Samsung Health workouts (individual sessions)\n")
            val sortedWorkouts = shWorkouts.sortedByDescending { it.startTime }
            val zone = ZoneId.systemDefault()
            // Bucket totals first so the LLM can pull weekly volume figures
            // without re-summing rows.
            val byBucket = sortedWorkouts.groupBy { it.type.bucket() }
            val bucketLine = TrainingBucket.values().mapNotNull { b ->
                val ws = byBucket[b] ?: return@mapNotNull null
                if (ws.isEmpty()) return@mapNotNull null
                val totalMin = ws.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
                val totalKcal = ws.sumOf { (it.activeKcal ?: 0) }
                "${b.name.lowercase()}: ${ws.size} sess (${totalMin}min, ${totalKcal}kcal)"
            }.joinToString(" · ")
            val grandMin = sortedWorkouts.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
            val grandKcal = sortedWorkouts.sumOf { (it.activeKcal ?: 0) }
            append("Total: ${sortedWorkouts.size} sessions · ${grandMin}min · ${grandKcal} active kcal\n")
            append("By bucket: $bucketLine\n")
            // Per-session rows capped at 20 most recent — same shape as the
            // manual activities block.
            val rows = sortedWorkouts.take(20)
            rows.forEach { w ->
                val date = w.startTime.atZone(zone).toLocalDate()
                val start = w.startTime.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString().take(5)
                val end = w.endTime.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString().take(5)
                val durMin = java.time.Duration.between(w.startTime, w.endTime).toMinutes()
                val kcal = w.activeKcal ?: 0
                // Row shape: fine-grained Samsung modality + coarse bucket.
                //   "Pilates · mobility"    — before: "YOGA (mobility)"
                //   "Weight machine · strength" — before: "STRENGTH (strength)"
                // The LLM should use the specific label when speaking about
                // individual sessions ("a tua sessão de Pilates de terça") and
                // the bucket for weekly volume — see HABIT_PROTOCOL bullet.
                // Fallback when sourceLabel is empty (UNDEFINED SDK point):
                // fall back to the bucket display key so the row still parses.
                val fineLabel = w.sourceLabel.takeIf { it.isNotBlank() }
                    ?: w.type.displayKey
                append("- $date $start–$end · $fineLabel · ${w.type.bucket().name.lowercase()} · ${durMin}min · ${kcal}kcal")
                if (!w.title.isNullOrBlank()) append(" · \"${w.title}\"")
                append("\n")
            }
            if (sortedWorkouts.size > rows.size) {
                append("- (${sortedWorkouts.size - rows.size} older sessions omitted; totals above include all)\n")
            }
        }

        append("[/PRE-FETCHED DATA]\n")
    }
}
