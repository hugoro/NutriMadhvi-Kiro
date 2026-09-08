package com.example.data.chat

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.local.TargetSnapshot
import java.time.ZoneId

/**
 * Phase 8 — deterministic plan executor.
 *
 * Takes a [ToolPlanner.ToolPlan] and the rows already in hand at handle()-time,
 * runs each requested [NutritionTools] function, and returns a single Markdown
 * ground-truth block to be appended into `dynamicSystemText`.
 *
 * Pure: no DAO access, no coroutines, no orchestrator state. Same discipline
 * as [NutritionTools] — trivially unit-testable with hand-built lists.
 *
 * Returns `""` for an empty plan so the caller's string concatenation is a
 * no-op and the assembled `dynamicSystemText` is byte-identical to pre-Phase-8
 * on non-computational turns.
 *
 * WEIGHT is gated on [bodyComp] being non-empty: a completely empty body-comp
 * section would only add noise and might mislead the model into thinking the
 * user asked about weight when there is nothing to report.
 */
object NutritionToolRunner {

    /**
     * Run [plan] and return the concatenated ground-truth block, or `""` if
     * the plan has no kinds (or all conditions for injection are unmet).
     *
     * Parameter naming intentionally mirrors [NutritionTools] function
     * signatures so callers can pass the same already-held variables directly.
     *
     * @param foods       Rows from snapshotCache / prefetchedData (31-day slab
     *                    or narrower). NutritionTools window-filters defensively.
     * @param activities  Same slab as [foods].
     * @param bodyComp    Body-composition history, newest-first (from
     *                    SnapshotCache.bodyComposition). May be empty.
     * @param target      Latest target snapshot (from SnapshotCache.latestTarget).
     *                    Null → no vs-target verdict in the calorie block.
     * @param shSteps     Samsung Health steps by date from handle() param.
     * @param shDaily     Samsung Health daily activity summaries.
     * @param shWorkouts  Samsung Health workout sessions.
     * @param zone        The device timezone. Defaults to system default.
     */
    fun run(
        plan: ToolPlanner.ToolPlan,
        foods: List<FoodEntry> = emptyList(),
        activities: List<ActivityEntry> = emptyList(),
        bodyComp: List<BodyCompositionSnapshotEntity> = emptyList(),
        target: TargetSnapshot? = null,
        shSteps: Map<java.time.LocalDate, Long> = emptyMap(),
        shDaily: Map<java.time.LocalDate, ShDailyActivity> = emptyMap(),
        shWorkouts: List<ShWorkout> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (plan.kinds.isEmpty()) return ""

        val window = plan.window
        val blocks = buildString {
            // Stable emission order: CALORIES → MACROS → WEIGHT → ACTIVITY.
            // Order is semantically meaningful: the LLM should see the caloric
            // picture before the macro breakdown and body-comp trend.
            if (ToolPlanner.ToolKind.CALORIES in plan.kinds) {
                append(NutritionTools.getCalorieSummary(foods, window, target, zone).toContextBlock())
            }
            if (ToolPlanner.ToolKind.MACROS in plan.kinds) {
                append(NutritionTools.getMacroSummary(foods, window, zone).toContextBlock())
            }
            if (ToolPlanner.ToolKind.WEIGHT in plan.kinds && bodyComp.isNotEmpty()) {
                append(NutritionTools.getWeightTrend(bodyComp, window, zone).toContextBlock())
            }
            if (ToolPlanner.ToolKind.ACTIVITY in plan.kinds) {
                append(NutritionTools.getActivitySummary(window, activities, shSteps, shDaily, shWorkouts, zone).toContextBlock())
            }
        }

        if (blocks.isBlank()) return ""

        return buildString {
            append("[COMPUTED TOOLS — deterministic, treat as ground truth; do not recompute]\n")
            append(blocks)
        }
    }
}
