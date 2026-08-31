package com.example.data.meal

import com.example.data.local.ActivityEntry
import com.example.data.remote.FoodAnalysisResult

/**
 * Stream of UI events the [com.example.ui.ChatViewModel] collects from
 * [MealAnalysisOrchestrator.analyzeFoodImageStreaming] /
 * [MealAnalysisOrchestrator.reanalyzeFoodFromSummaryStreaming].
 *
 * Phase carries an i18n KEY (e.g. `phase_extracting_ingredients`); the UI
 * resolves it via `tr(key, language)` so we don't bake locale into the
 * data layer. Partial carries the incrementally-extracted [FoodAnalysisProgress]
 * so the capture card can show "Identificado: X" / "3 ingredientes
 * identificados" while bytes still trickle in. Done is what the legacy
 * blocking caller waits for; Error always carries a [salvage] payload when
 * we managed to extract anything from the partial buffer — UI then
 * pre-populates the edit form so Hugo doesn't lose his photo + typing if
 * the network drops mid-stream.
 */
sealed interface MealAnalysisEvent {
    /** i18n key for the current pipeline phase. */
    data class Phase(val key: String) : MealAnalysisEvent

    /** Latest snapshot of partial fields parsed from the stream so far. */
    data class Partial(val progress: FoodAnalysisProgress) : MealAnalysisEvent

    /** Stream finished cleanly; [result] is the canonical Moshi-parsed payload. */
    data class Done(val result: FoodAnalysisResult) : MealAnalysisEvent

    /**
     * Stream failed (network, HTTP, Moshi reject). [salvage] is non-null when
     * [PartialJsonExtractor] could pull at least a `name` out of the buffer —
     * UI then offers Retry while keeping whatever we got.
     */
    data class Error(
        val messageKey: String,
        val cause: Throwable?,
        val salvage: FoodAnalysisResult?
    ) : MealAnalysisEvent
}

/**
 * Incrementally-built view of what the LLM has emitted so far. Filled by
 * [PartialJsonExtractor.extractPartial] each time a new SSE chunk arrives.
 *
 * Monotonic by construction: a field once set is only overwritten when the
 * regex yields a "more complete" value — `ingredientsSoFar` only grows,
 * `name` is set on first match and never cleared. This lets the capture
 * card transition phases without flicker.
 */
data class FoodAnalysisProgress(
    val name: String? = null,
    val mealCategory: String? = null,
    val ingredientsSoFar: List<String> = emptyList(),
    val calories: Int? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    val rawBytesReceived: Int = 0
)

/**
 * Wrapper for [MealAnalysisEvent.Error] that the ViewModel exposes as a
 * [kotlinx.coroutines.flow.StateFlow] — UI binds to it for the inline error
 * card + Retry button. Kept distinct from the raw event so the VM can
 * resolve [MealAnalysisEvent.Error.messageKey] to a localised string once
 * (the data layer doesn't know the language).
 */
data class FoodAnalysisError(
    val messageKey: String,
    val resolvedMessage: String,
    val cause: Throwable?,
    val salvage: FoodAnalysisResult?
)

/**
 * Streaming counterpart of [MealAnalysisEvent] for the activity-reanalysis
 * path. The edit-activity screen subscribes to this so editing a workout's
 * summary and tapping "Confirm & Log Activity" surfaces the same
 * `TypingIndicatorBubble` ("A reanalisar o treino…" / "Re-analyzing workout…")
 * the meal flow shows. Mirror of [MealAnalysisEvent], minus the [Partial]
 * variant — activity payloads don't carry incremental fields worth chipping
 * onto the UI while bytes trickle in (no per-ingredient list).
 *
 * `Done.rows` is the post-mapping list ready to hand to
 * `repository.updateActivitySession`; the orchestrator does the same per-row
 * carry-over the legacy blocking [com.example.data.remote.GeminiRepository.reanalyzeActivityFromSummary]
 * did (preserve `id`, `timestamp`, `notes` from the previous row when the
 * model omitted them).
 */
sealed interface ActivityAnalysisStreamEvent {
    data class Phase(val key: String) : ActivityAnalysisStreamEvent
    data class Done(val rows: List<ActivityEntry>) : ActivityAnalysisStreamEvent
    data class Error(val messageKey: String, val cause: Throwable?) : ActivityAnalysisStreamEvent
}
