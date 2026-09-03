package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Wraps the existing Gemini blocking analysis as an ensemble provider.
 *
 * Behaviour-preserving by design: it delegates to the same
 * `GeminiRepository.runFoodImageBlockingForRequest` path (5-model fallback +
 * retries) that the meal orchestrator already falls back to today, rather
 * than reimplementing the call. That keeps Gemini's field-tested resilience
 * intact and makes `GEMINI_ONLY` genuinely equivalent to current behaviour.
 *
 * The [call] lambda keeps this class free of a hard dependency on
 * `GeminiRepository`, so the router and its tests do not need Room.
 */
class GeminiFoodVisionProvider(
    private val hasApiKey: Boolean,
    /** Returns the parsed result, or null when the underlying call failed. */
    private val call: suspend (FoodVisionRequest) -> FoodAnalysisResult?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : FoodVisionProvider {

    override val id: String = PROVIDER_ID

    override fun isAvailable(): Boolean = hasApiKey

    override suspend fun analyze(request: FoodVisionRequest): FoodVisionOutcome {
        if (!isAvailable()) {
            return FoodVisionOutcome.Failure(
                provider = id,
                kind = FoodVisionErrorKind.NOT_CONFIGURED,
                message = "GEMINI_API_KEY not configured",
            )
        }

        val started = nowMs()
        fun elapsed() = nowMs() - started

        val parsed = try {
            withTimeout(timeoutMs) { call(request) }
        } catch (e: TimeoutCancellationException) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.TIMEOUT, elapsed(), "timed out after ${timeoutMs}ms", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.REQUEST_FAILED, elapsed(), e.message, e)
        }

        if (parsed == null) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.REQUEST_FAILED, elapsed(), "no result")
        }
        if (parsed.name.isNullOrBlank() && parsed.ingredients.isNullOrEmpty()) {
            return FoodVisionOutcome.Failure(id, FoodVisionErrorKind.RESULT_UNCERTAIN, elapsed(), "empty result")
        }

        val safeApplied = parsed.appliedHabitIds
            ?.filter { it in request.offeredHabitIds }
            ?.distinct()
        val stamped = parsed.copy(appliedHabitIds = safeApplied)

        return FoodVisionOutcome.Success(
            FoodAnalysisEvidence(
                provider = id,
                result = stamped,
                latencyMs = elapsed(),
                confidence = ConfidenceScorer.score(stamped),
            )
        )
    }

    private companion object {
        const val PROVIDER_ID = "gemini"

        /** Matches the cap the meal orchestrator already applies to its own
         *  blocking fallback, so wrapping does not change timing behaviour. */
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }
}
