package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult

/**
 * Provider-neutral boundary for a single food-vision analysis.
 *
 * Deliberately decoupled from Room, Compose and the Gemini request/response
 * DTOs so a provider can be implemented, tested and swapped without touching
 * the downstream nutrition/persistence pipeline. The ensemble replaces the
 * *provider decision layer* only — [FoodAnalysisResult] stays the
 * compatibility boundary that `MealAnalysisOrchestrator.enrichResult()` and
 * the existing UI already understand.
 *
 * Implementations must be side-effect free with respect to user data: they
 * receive a transient, read-only representation of image bytes that the
 * caller already loaded, and must never write to the image store, rename or
 * re-encode the original file, or persist anything themselves.
 */
interface FoodVisionProvider {
    /** Stable identifier used in diagnostics and evidence bundles. */
    val id: String

    /**
     * True when the provider has everything it needs to run (API key present,
     * etc.). A provider that is not [isAvailable] must be skipped by the
     * router rather than invoked and failed — that keeps "not configured"
     * distinct from "the model failed", which matters for fallback policy.
     */
    fun isAvailable(): Boolean

    /**
     * Analyse the meal image.
     *
     * Implementations should return [FoodVisionOutcome.Failure] rather than
     * throwing for expected transport/parse problems, so the router can apply
     * its fallback policy deterministically. Cancellation
     * ([kotlinx.coroutines.CancellationException]) must still propagate.
     */
    suspend fun analyze(request: FoodVisionRequest): FoodVisionOutcome
}

/**
 * Everything a provider needs to analyse one meal, and nothing else.
 *
 * [base64Image] is a transient representation of bytes the caller already
 * read from the existing image file. Per the data-safety rules, providers
 * must treat it as read-only and must not attempt to resolve, rewrite or
 * delete the underlying file — they never learn its path.
 */
data class FoodVisionRequest(
    val base64Image: String?,
    val mimeType: String = "image/jpeg",
    val promptHint: String? = null,
    val language: String = "en",
    /** Rendered habits block from the existing prompt builder, when present. */
    val habitsBlock: String? = null,
    /** Habit IDs offered in the prompt, used to filter the model's echo. */
    val offeredHabitIds: Set<Long> = emptySet(),
    /** Edited summary for the re-analysis path; null for first-pass analysis. */
    val reanalysisSummary: String? = null,
) {
    val hasImage: Boolean get() = !base64Image.isNullOrBlank()
}

/** Why a provider call did not yield a usable result. */
enum class FoodVisionErrorKind {
    /** Provider ran but the result is too uncertain to accept as-is. */
    RESULT_UNCERTAIN,

    /** Transport failed (connectivity, DNS, socket). */
    REQUEST_FAILED,

    /** Provider replied, but the payload was not parseable into the schema. */
    INVALID_JSON,

    /** Provider exceeded the caller's wall-clock budget. */
    TIMEOUT,

    /** Provider signalled quota/rate limiting (HTTP 429). */
    RATE_LIMITED,

    /** Provider signalled a server-side outage (HTTP 5xx). */
    UNAVAILABLE,

    /**
     * Provider rejected the request because the encoded image was too large to
     * process (HTTP 413). On Groq this surfaces as a token-budget overflow —
     * the single request exceeds the per-minute allowance on its own — so
     * despite the `rate_limit_exceeded` code it is deterministic rather than
     * transient: retrying the same bytes will always fail, however long we
     * wait. Kept distinct from both [REQUEST_FAILED] and [RATE_LIMITED]
     * because the only useful responses are to shrink the image or escalate to
     * another provider, never to back off and retry unchanged.
     */
    PAYLOAD_TOO_LARGE,

    /** Provider is not configured (missing key) and was never invoked. */
    NOT_CONFIGURED,
}

/** Result of a single provider call. */
sealed interface FoodVisionOutcome {
    data class Success(val evidence: FoodAnalysisEvidence) : FoodVisionOutcome

    data class Failure(
        val provider: String,
        val kind: FoodVisionErrorKind,
        val latencyMs: Long = 0L,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : FoodVisionOutcome
}

/**
 * One provider's answer plus the routing metadata derived from it.
 *
 * Runtime-only by design: none of this is persisted in `FoodEntry` (the
 * first ensemble milestone deliberately adds no schema/migration), it exists
 * purely so the router can make and explain a decision.
 */
data class FoodAnalysisEvidence(
    val provider: String,
    val result: FoodAnalysisResult,
    val latencyMs: Long,
    val confidence: ConfidenceProfile,
    val requestId: String? = null,
)

/** A material difference between two providers' readings of the same image. */
data class AnalysisDisagreement(
    val field: String,
    val left: String,
    val right: String,
    /** True when the two readings are genuinely incompatible (as opposed to
     *  one simply being a more specific phrasing of the other). */
    val material: Boolean,
)

/** Runtime aggregate handed to the adjudicator and to debug diagnostics. */
data class AnalysisEvidenceBundle(
    val primary: FoodAnalysisEvidence,
    val challengers: List<FoodAnalysisEvidence> = emptyList(),
    val disagreements: List<AnalysisDisagreement> = emptyList(),
) {
    val hasMaterialDisagreement: Boolean get() = disagreements.any { it.material }
}
