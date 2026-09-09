package com.example.data.meal

import com.example.BuildConfig
import com.example.data.local.ActivityEntry
import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import com.example.data.meal.ensemble.EnsembleAnalysisOrchestrator
import com.example.data.meal.ensemble.EnsembleConfig
import com.example.data.meal.ensemble.EnsembleMode
import com.example.data.meal.ensemble.EnsemblePrompts
import com.example.data.meal.ensemble.FoodVisionRequest
import com.example.data.meal.ensemble.GeminiFoodVisionProvider
import com.example.data.meal.ensemble.createBitdeerProvider
import com.example.data.meal.ensemble.createMealResultJudge
import com.example.data.meal.ensemble.createQwenGroqProvider
import com.example.data.remote.ActivityAnalysisResult
import com.example.data.remote.Content
import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.GeminiRepository
import com.example.data.remote.GeminiRequest
import com.example.data.remote.sanitizeAiNewlines
import com.example.data.remote.GeminiStreamingClient
import com.example.data.remote.GenerationConfig
import com.example.data.remote.ThinkingConfig
import com.example.data.remote.IngredientDto
import com.example.data.remote.GeminiStreamWatchdogCollector
import com.example.data.remote.InlineData
import com.example.data.remote.Part
import com.example.data.remote.StreamChunk
import com.example.data.remote.encodeIngredients
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Streaming analogue of [GeminiRepository.analyzeFoodImage] /
 * [GeminiRepository.reanalyzeFoodFromSummary]. Emits a [MealAnalysisEvent]
 * stream so the Activity tab can show real phase transitions ("A enviar
 * imagem…" → "A identificar o prato…" → "A extrair ingredientes…" → …)
 * and chips of ingredient names as they materialise in the SSE buffer.
 *
 * Reuses [GeminiStreamingClient] verbatim — same OkHttp setup, same
 * `:streamGenerateContent?alt=sse` endpoint, same cancellation propagation
 * via `awaitClose { call.cancel() }`. We do not duplicate transport.
 *
 * Constructed by [GeminiRepository.createMealAnalysisOrchestrator] so it
 * shares the same DAOs and helper functions (selectHabitsForPrompt,
 * buildHabitsPromptBlock, markHabitsApplied, languageInstruction) — those
 * are exposed `internal` on the repository specifically for this caller.
 *
 * Salvage philosophy: we never throw away the user's photo + typing on a
 * mid-stream failure. If the buffer contains at least a `name`, the
 * `Error` event carries a [FoodAnalysisResult] salvage payload; the VM
 * pre-populates `pendingFoodEntry` with that and shows the inline error
 * card with a Retry button.
 */
class MealAnalysisOrchestrator(
    private val repository: GeminiRepository,
    private val nutritionLookupService: com.example.data.nutrition.NutritionLookupService? = null,
) {

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val foodAdapter by lazy { moshi.adapter(FoodAnalysisResult::class.java) }
    private val activityAdapter by lazy { moshi.adapter(ActivityAnalysisResult::class.java) }

    /** Enrich ingredient macros from the nutrition DB cascade, then recalculate
     *  meal-level totals. Falls back to the original result on any failure or
     *  when no lookup service is configured. */
    private suspend fun enrichResult(
        collector: kotlinx.coroutines.flow.FlowCollector<MealAnalysisEvent>,
        result: FoodAnalysisResult,
    ): FoodAnalysisResult {
        val service = nutritionLookupService
        val ingredients = result.ingredients
        android.util.Log.i("NutritionEnrich",
            "enrichResult: service=${service != null} ingredients=${ingredients?.size ?: "null"}")
        if (service == null) return result
        if (ingredients == null) return result
        if (ingredients.isEmpty()) return result

        // 2026-08-16 — 4s cap (was 10s). Enrichment is on the critical path
        // before the edit card paints, so a long budget adds visible latency to
        // every analysis. Cluster staleness now uses a cheap COUNT (see
        // IngredientClusterBuilder) so a warm cache is fast; 4s is enough for a
        // cold rebuild on a normal history and bails gracefully otherwise.
        val enriched = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
            collector.emit(MealAnalysisEvent.Phase(PHASE_VERIFYING_NUTRIENTS))
            val enriched = service.enrichIngredients(ingredients)
            val anyMatched = enriched.any { it.sourceTag != null && it.sourceTag != "gemini" }
            android.util.Log.i("NutritionEnrich",
                "enriched ${enriched.size} ingredients, anyMatched=$anyMatched " +
                    "sources=${enriched.map { it.sourceTag }}")
            if (!anyMatched) return@withTimeoutOrNull result

            // Recalculate meal totals from the enriched per-ingredient values
            val totalKcal = enriched.sumOf { (it.calories ?: 0).toDouble() }.toInt()
            val totalProtein = enriched.sumOf { it.protein ?: 0.0 }
            val totalCarbs = enriched.sumOf { it.carbs ?: 0.0 }
            val totalFat = enriched.sumOf { it.fat ?: 0.0 }
            val totalFreeSugars = enriched.sumOf { it.freeSugarsG ?: 0.0 }
            val totalSatFat = enriched.sumOf { it.saturatedFatG ?: 0.0 }

            android.util.Log.i(
                "NutritionEnrich",
                "enriched ${enriched.count { it.sourceTag != "gemini" }}/${enriched.size} ingredients " +
                    "kcal: ${result.calories}→$totalKcal P: ${result.protein}→$totalProtein"
            )

            // Quality gate: if the recalculated total drifts implausibly far
            // from the LLM's own meal total, the ingredient breakdown is likely
            // inconsistent — keep the LLM total rather than a bad recalculation.
            val originalKcal = result.calories ?: 0
            val gate = com.example.data.nutrition.MacroPlausibilityGate
                .checkMealTotalsAgainstIngredients(
                    mealKcal = originalKcal,
                    ingredientKcalSum = totalKcal,
                    tolerance = 0.35,
                )
            if (gate is com.example.data.nutrition.MacroPlausibilityGate.Result.Suspicious) {
                android.util.Log.w("NutritionEnrich", "totals gate rejected: ${gate.reason} — keeping enriched ingredients but LLM totals")
                // Keep the enriched per-ingredient sourceTags (useful for the UI
                // breakdown) but do NOT overwrite the meal totals.
                return@withTimeoutOrNull result.copy(ingredients = enriched)
            }

            result.copy(
                calories      = totalKcal,
                protein       = totalProtein,
                carbs         = totalCarbs,
                fat           = totalFat,
                freeSugarsG   = totalFreeSugars,
                saturatedFatG = totalSatFat,
                ingredients   = enriched,
            )
        }
        if (enriched == null) {
            android.util.Log.w("NutritionEnrich", "enrichment timed out (>4s) — using LLM macros unchanged")
        }
        val afterEnrich = enriched ?: result
        return judgeIfUncertain(afterEnrich)
    }

    /**
     * Confidence-gated correction judge. Runs a fast text-only Qwen review of
     * the result against the user's [CorrectionCalibrator] history — but ONLY
     * when the model admitted uncertainty (`low`/`medium` confidence) and there
     * is actual correction history to check against. A confident result, or a
     * user with no corrections yet, is returned unchanged with zero added
     * latency.
     *
     * Fail-open at every step: no Groq key, empty calibration, or any judge
     * error leaves the result exactly as it came in. The judge is
     * plausibility-gated internally so it can never push inconsistent numbers.
     */
    private suspend fun judgeIfUncertain(result: FoodAnalysisResult): FoodAnalysisResult {
        val confidence = result.confidence?.trim()?.lowercase()
        val uncertain = confidence == "low" || confidence == "medium" ||
            confidence == "baixa" || confidence == "média" || confidence == "media"
        if (!uncertain) return result

        val calibrator = repository.correctionCalibrator ?: return result
        val judge = createMealResultJudge(EnsembleConfig.groqApiKey) ?: return result

        return runCatching {
            val calibration = calibrator.getCalibration()
            if (calibration.isEmpty) return result
            // The judge self-limits via its own withTimeout and is fail-open.
            judge.review(result, calibration)
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            android.util.Log.w("MealResultJudge", "judge stage failed — keeping original")
            result
        }
    }

    /**
     * Streaming food-image analysis. Mirror of
     * [GeminiRepository.analyzeFoodImage] but yielding [MealAnalysisEvent]s
     * instead of a one-shot Result.
     *
     * Either [base64Image] is non-blank (vision call) or null (text-only
     * call from the "Describe your meal…" path). Mime-type is honored only
     * when the image is present.
     */
    fun analyzeFoodImageStreaming(
        base64Image: String?,
        mimeType: String = "image/jpeg",
        promptHint: String? = null,
        language: String = "en",
        categoryHint: MealCategory? = null
    ): Flow<MealAnalysisEvent> = flow {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            emit(
                MealAnalysisEvent.Error(
                    messageKey = "analysis_failed_retry",
                    cause = IllegalStateException("API Key not configured"),
                    salvage = null
                )
            )
            return@flow
        }

        emit(MealAnalysisEvent.Phase(PHASE_UPLOADING))

        val habits = repository.selectHabitsForPrompt(
            repository.getActiveHabitsSnapshot(), categoryHint
        )
        val habitsBlock = repository.buildHabitsPromptBlock(habits, promptHint)
        val offeredIds = habits.map { it.id }.toSet()

        // Correction calibration — measured from the user's own past corrections
        // (AI estimate vs saved value). Empty until enough corrections accrue,
        // in which case the prompt is byte-identical to before. See
        // [com.example.data.nutrition.CorrectionCalibrator].
        val calibrationBlock = runCatching {
            repository.correctionCalibrator?.getCalibration()?.toPromptBlock().orEmpty()
        }.getOrDefault("")

        val promptText = buildAnalysisPrompt(
            promptHint = promptHint,
            language = language,
            habitsBlock = habitsBlock,
            calibrationBlock = calibrationBlock
        )

        val parts = mutableListOf<Part>()
        parts.add(Part(text = promptText))
        if (!base64Image.isNullOrBlank()) {
            parts.add(Part(inlineData = InlineData(mimeType = mimeType, data = base64Image)))
        }

        val request = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json",
                // 2026-07-10 — match chat's refined-but-fast reasoning. Without a
                // thinkingConfig, Flash ran at default (uncapped) thinking and
                // still mis-read images (e.g. 1.4g fat on papaya). "low" gives
                // chat-grade reasoning at ~5s, not the 30-40s of higher tiers.
                // Never send thinkingBudget alongside thinking_level — the API
                // rejects both (see ChatOrchestrator).
                thinkingConfig = ThinkingConfig(thinkingLevel = "low")
            )
        )

        if (tryEnsembleAnalysis(
                collector = this,
                request = request,
                base64Image = base64Image,
                mimeType = mimeType,
                promptHint = promptHint,
                habitsBlock = habitsBlock,
                offeredHabitIds = offeredIds,
                language = language,
                calibrationBlock = calibrationBlock,
            )
        ) {
            return@flow
        }

        consumeStream(
            collector = this,
            request = request,
            apiKey = apiKey,
            offeredHabitIds = offeredIds,
            isReanalysis = false,
            language = language,
            allowEscalation = true
        )
    }

    /**
     * Confidence-gated multi-model analysis, inserted at the *provider
     * decision layer* only.
     *
     * Returns true when the ensemble produced a result and already emitted
     * [MealAnalysisEvent.Done]; the caller then skips the Gemini streaming
     * path entirely. Returns false whenever the ensemble is disabled,
     * unconfigured, or failed to produce anything usable — in which case the
     * caller proceeds with the existing behaviour untouched.
     *
     * Everything downstream of the decision is unchanged: the winning result
     * still flows through [postProcessResult] and the existing nutrition
     * pipeline, so the models never own the final macros.
     */
    private suspend fun tryEnsembleAnalysis(
        collector: kotlinx.coroutines.flow.FlowCollector<MealAnalysisEvent>,
        request: GeminiRequest,
        base64Image: String?,
        mimeType: String,
        promptHint: String?,
        habitsBlock: String,
        offeredHabitIds: Set<Long>,
        language: String,
        calibrationBlock: String = "",
    ): Boolean {
        if (!EnsembleConfig.isEnsembleActive) return false
        // Ensemble routing is only meaningful when there is an image to get a
        // second opinion on; text-only re-analysis keeps its existing path.
        if (base64Image.isNullOrBlank()) return false

        val visionRequest = FoodVisionRequest(
            base64Image = base64Image,
            mimeType = mimeType,
            promptHint = promptHint,
            language = language,
            habitsBlock = habitsBlock,
            offeredHabitIds = offeredHabitIds,
        )

        // Build the primary provider based on the active ensemble mode.
        // Gemini is always the challenger/fallback regardless of which primary runs.
        val primary = when (EnsembleConfig.mode) {
            EnsembleMode.BITDEER_THEN_GEMINI -> {
                val bitdeerKey = EnsembleConfig.bitdeerApiKey
                if (bitdeerKey.isBlank()) return false
                createBitdeerProvider(apiKey = bitdeerKey) { req ->
                    buildAnalysisPrompt(
                        promptHint = req.promptHint,
                        language = req.language,
                        habitsBlock = req.habitsBlock.orEmpty(),
                        calibrationBlock = calibrationBlock,
                    ) + EnsemblePrompts.CANONICAL_NAMING_RULES
                }
            }
            else -> {
                val groqKey = EnsembleConfig.groqApiKey
                if (groqKey.isBlank()) return false
                createQwenGroqProvider(apiKey = groqKey) { req ->
                    buildAnalysisPrompt(
                        promptHint = req.promptHint,
                        language = req.language,
                        habitsBlock = req.habitsBlock.orEmpty(),
                        calibrationBlock = calibrationBlock,
                    ) + EnsemblePrompts.CANONICAL_NAMING_RULES
                }
            }
        }

        // Qwen adjudication only applies when Qwen-Groq is the primary.
        // For Bitdeer mode there is no adjudicator — the challenger (Gemini) is
        // already the strongest option we have for a third opinion.
        val adjudicator = when (EnsembleConfig.mode) {
            EnsembleMode.QWEN_ENSEMBLE -> primary // primary IS qwen in this branch
            else -> null
        }

        // Reuses the repository's existing blocking Gemini path (5-model
        // fallback + retries) rather than a second implementation, so
        // "Gemini as challenger" behaves exactly like Gemini does today.
        val gemini = GeminiFoodVisionProvider(
            hasApiKey = EnsembleConfig.geminiApiKey.isNotBlank(),
            call = { repository.runFoodImageBlockingForRequest(request = request, language = language) },
        )

        val ensemble = EnsembleAnalysisOrchestrator(
            primary = primary,
            challenger = gemini,
            adjudicator = adjudicator,
            policy = EnsembleConfig.policy,
        )

        collector.emit(MealAnalysisEvent.Phase(PHASE_ANALYZING))
        val outcome = runCatching { ensemble.analyze(visionRequest) }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("MealEnsemble", "ensemble threw; falling back to Gemini", e)
            }
            .getOrNull() ?: return false

        android.util.Log.d(
            "MealEnsemble",
            "path=${outcome.decisionPath.joinToString(">")} winner=${outcome.evidence.provider} " +
                "calls=${outcome.providerCallCount} latency=${outcome.evidence.latencyMs}ms"
        )

        val winner = outcome.evidence.result
        repository.markHabitsApplied(winner.appliedHabitIds)
        collector.emit(MealAnalysisEvent.Phase(PHASE_FINALIZING))
        collector.emit(MealAnalysisEvent.Done(postProcessResult(collector, winner)))
        return true
    }

    /**
     * Post-processing seam applied to an ensemble winner before it is emitted.
     *
     * Routes the ensemble winner through the same INSA nutrition-enrichment
     * and plausibility-gating stage the Gemini streaming path applies via
     * [enrichResult], keeping both analyzer paths symmetric so ensemble meals
     * get local-cluster macros too and the models never own the final numbers.
     */
    private suspend fun postProcessResult(
        collector: kotlinx.coroutines.flow.FlowCollector<MealAnalysisEvent>,
        result: FoodAnalysisResult,
    ): FoodAnalysisResult = enrichResult(collector, result)

    /**
     * Streaming re-analysis from an edited summary. Mirror of
     * [GeminiRepository.reanalyzeFoodFromSummary] — emits Phase events
     * keyed to `phase_reanalyzing_meal` and resolves Done with a
     * [FoodAnalysisResult] that the VM merges back into the [FoodEntry].
     *
     * When [base64Image] is non-blank, the original meal photo is sent
     * alongside the edited description so the model can combine both
     * signals (image = ground truth for portions; text = user's
     * corrections). The VM loads the bytes from `currentEntry.imagePath`
     * via `FoodImageStore.loadImageBytesAndMime` before calling this.
     * When null/blank, behaviour matches the legacy text-only path —
     * useful for entries inserted without a photo.
     */
    fun reanalyzeFoodFromSummaryStreaming(
        currentEntry: FoodEntry,
        newSummary: String,
        language: String = "en",
        base64Image: String? = null,
        mimeType: String = "image/jpeg"
    ): Flow<MealAnalysisEvent> = flow {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            emit(
                MealAnalysisEvent.Error(
                    messageKey = "analysis_failed_retry",
                    cause = IllegalStateException("API Key not configured"),
                    salvage = null
                )
            )
            return@flow
        }

        emit(MealAnalysisEvent.Phase(PHASE_REANALYZING))

        val habits = repository.selectHabitsForPrompt(
            repository.getActiveHabitsSnapshot(), currentEntry.mealCategory
        )
        // Hint for deterministic trigger pre-match: the edited summary plus the
        // current dish name is the closest text description of the meal here.
        val reanalysisHint = listOfNotNull(
            newSummary.takeIf { it.isNotBlank() },
            currentEntry.name.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        val habitsBlock = repository.buildHabitsPromptBlock(habits, reanalysisHint)
        val offeredIds = habits.map { it.id }.toSet()

        val hasImage = !base64Image.isNullOrBlank()
        val promptText = repository.buildFoodReanalysisPromptCore(
            currentEntry = currentEntry,
            newSummary = newSummary,
            language = language,
            habitsBlock = habitsBlock,
            imageAttached = hasImage
        )

        val parts = mutableListOf<Part>()
        parts.add(Part(text = promptText))
        if (hasImage) {
            parts.add(Part(inlineData = InlineData(mimeType = mimeType, data = base64Image!!)))
        }

        val request = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json",
                // 2026-07-10 — match chat's refined-but-fast reasoning. Without a
                // thinkingConfig, Flash ran at default (uncapped) thinking and
                // still mis-read images (e.g. 1.4g fat on papaya). "low" gives
                // chat-grade reasoning at ~5s, not the 30-40s of higher tiers.
                // Never send thinkingBudget alongside thinking_level — the API
                // rejects both (see ChatOrchestrator).
                thinkingConfig = ThinkingConfig(thinkingLevel = "low")
            )
        )

        consumeStream(
            collector = this,
            request = request,
            apiKey = apiKey,
            offeredHabitIds = offeredIds,
            isReanalysis = true,
            language = language,
            allowEscalation = hasImage
        )
    }

    internal data class ProcedureStreamWatchdog(
        val startedAtMs: Long,
        val lastPartialAtMs: Long? = null,
    ) {
        fun timeoutReason(
            nowMs: Long,
            ttftTimeoutMs: Long = STREAM_TTFT_TIMEOUT_MS,
            idleTimeoutMs: Long = STREAM_IDLE_TIMEOUT_MS,
        ): String? = GeminiStreamWatchdogCollector.timeoutReason(
            startedAtMs = startedAtMs,
            lastPartialAtMs = lastPartialAtMs,
            nowMs = nowMs,
            spec = GeminiStreamWatchdogCollector.Spec(
                ttftTimeoutMs = ttftTimeoutMs,
                idleTimeoutMs = idleTimeoutMs,
                tickMs = STREAM_WATCHDOG_TICK_MS,
                ttftReason = REASON_TTFT,
                idleReason = REASON_IDLE,
            ),
        )

        companion object {
            internal fun normalizeWatchdogThrowable(
                cause: Throwable,
                watchdogReason: String?
            ): Throwable? = GeminiStreamWatchdogCollector.normalizeThrowable(cause, watchdogReason)

            internal const val REASON_TTFT: String = "TTFT timeout"
            internal const val REASON_IDLE: String = "idle stream timeout"
        }
    }

    private data class StreamCollectionResult(
        val fullText: String,
        val chunkCount: Int,
        val error: Throwable? = null,
    )

    private suspend fun collectStreamWithWatchdog(
        model: String,
        apiKey: String,
        request: GeminiRequest,
        logTag: String,
        logLabel: String,
        onPartial: suspend (String) -> Unit = {},
        onDone: suspend (String, Int) -> Unit = { _, _ -> },
    ): StreamCollectionResult {
        val result = GeminiStreamWatchdogCollector.collect(
            model = model,
            apiKey = apiKey,
            request = request,
            logTag = logTag,
            spec = GeminiStreamWatchdogCollector.Spec(
                ttftTimeoutMs = STREAM_TTFT_TIMEOUT_MS,
                idleTimeoutMs = STREAM_IDLE_TIMEOUT_MS,
                tickMs = STREAM_WATCHDOG_TICK_MS,
                ttftReason = ProcedureStreamWatchdog.REASON_TTFT,
                idleReason = ProcedureStreamWatchdog.REASON_IDLE,
            ),
            onTimeoutLog = { reason ->
                when (reason) {
                    ProcedureStreamWatchdog.REASON_TTFT ->
                        "$logLabel TTFT >${STREAM_TTFT_TIMEOUT_MS}ms, cancelling"
                    ProcedureStreamWatchdog.REASON_IDLE ->
                        "$logLabel idle >${STREAM_IDLE_TIMEOUT_MS}ms after first token, cancelling"
                    else -> "$logLabel watchdog timeout, cancelling"
                }
            },
            onPartial = { fullText, _ ->
                onPartial(fullText)
            },
            onDone = { fullText, chunkCount, _ ->
                onDone(fullText, chunkCount)
            },
            onError = { chunk, chunkCount, fullText ->
                android.util.Log.w(
                    logTag,
                    "$logLabel stream Error chunks=$chunkCount bufferLen=${fullText.length}",
                    chunk.cause
                )
            },
        )
        return StreamCollectionResult(
            fullText = result.fullText,
            chunkCount = result.chunkCount,
            error = result.error,
        )
    }

    /**
     * Helper that reuses [GeminiStreamingClient.streamGenerateContent] and
     * translates [StreamChunk]s into [MealAnalysisEvent]s. Used by both
     * the image path and the summary-reanalysis path so phase transitions
     * stay in lockstep.
     */
    private suspend fun consumeStream(
        collector: kotlinx.coroutines.flow.FlowCollector<MealAnalysisEvent>,
        request: GeminiRequest,
        apiKey: String,
        offeredHabitIds: Set<Long>,
        isReanalysis: Boolean,
        language: String,
        /** Gate set by the caller: first-pass image analysis always allows
         *  escalation; re-analysis only allows it when the original photo
         *  was attached to the request (so the Pro pass has the same visual
         *  input — escalating a text-only re-analysis gives the Pro nothing
         *  the Flash didn't already see). */
        allowEscalation: Boolean
    ) {
        var progress = FoodAnalysisProgress()
        var lastPhase: String = if (isReanalysis) PHASE_REANALYZING else PHASE_UPLOADING
        var fullText = ""
        var chunkCount = 0
        // `return@collect` only exits the collect lambda — execution then
        // falls through to the "Stream finished cleanly" tail and emits a
        // second event. This flag short-circuits that tail when we already
        // emitted Done/Error from the inner branches (specifically the
        // blocking-fallback path when the stream returns 0 chunks).
        var terminated = false

        val stream = collectStreamWithWatchdog(
            model = FLASH_MODEL,
            apiKey = apiKey,
            request = request,
            logTag = "MealAnalysis",
            logLabel = "meal stream",
            onPartial = { accumulated ->
                fullText = accumulated
                val newProgress = PartialJsonExtractor.extractPartial(fullText, progress)
                if (newProgress != progress) {
                    progress = newProgress
                    collector.emit(MealAnalysisEvent.Partial(progress))
                }
                // Phase transitions are monotonic — once we move past a
                // gate we don't go back, even if a later chunk is "thinner".
                val nextPhase = nextPhase(lastPhase, progress, isReanalysis)
                if (nextPhase != lastPhase) {
                    lastPhase = nextPhase
                    collector.emit(MealAnalysisEvent.Phase(nextPhase))
                }
            },
            onDone = { finalText, chunks ->
                android.util.Log.d(
                    "MealAnalysis",
                    "stream Done chunks=$chunks bufferLen=${finalText.length} " +
                        "preview=${finalText.take(200).replace("\n", " ")}"
                )
            }
        )
        fullText = stream.fullText
        chunkCount = stream.chunkCount
        val streamError = stream.error
        if (streamError != null) {
            // If we never got a single chunk, the streaming endpoint
            // is probably refusing this request shape. Fall back to
            // the blocking path which has 5-model fallback + retries.
            if (chunkCount == 0) {
                collector.emit(MealAnalysisEvent.Phase(PHASE_FINALIZING))
                val fallback = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    runCatching {
                        repository.runFoodImageBlockingForRequest(
                            request = request,
                            language = language
                        )
                    }.getOrNull()
                }
                if (fallback != null) {
                    android.util.Log.d(
                        "MealAnalysis",
                        "Blocking fallback OK name='${fallback.name}' " +
                            "confidence=${fallback.confidence}"
                    )
                    val safeApplied = fallback.appliedHabitIds
                        ?.filter { it in offeredHabitIds }
                        ?.distinct()
                    val flashFallback = fallback.copy(
                        appliedHabitIds = safeApplied,
                        analysisModel = FLASH_MODEL,
                        escalated = false
                    )
                    val finalResult = if (allowEscalation && (ALWAYS_PRO_ON_IMAGE || shouldEscalate(flashFallback))) {
                        escalateToPro(
                            collector = collector,
                            request = request,
                            flashResult = flashFallback,
                            offeredHabitIds = offeredHabitIds,
                            language = language
                        ) ?: run {
                            repository.markHabitsApplied(safeApplied)
                            flashFallback.copy(proAttempted = true)
                        }
                    } else {
                        repository.markHabitsApplied(safeApplied)
                        flashFallback
                    }
                    collector.emit(MealAnalysisEvent.Done(enrichResult(collector, finalResult)))
                    terminated = true
                }
            }
            if (!terminated) {
                val salvage = PartialJsonExtractor.salvageFinal(fullText)
                collector.emit(
                    MealAnalysisEvent.Error(
                        messageKey = "analysis_failed_retry",
                        cause = streamError,
                        salvage = salvage
                    )
                )
                terminated = true
            }
        }

        if (terminated) return

        // Stream finished cleanly. Try Moshi on the final buffer; fall back to
        // regex-only salvage if the LLM emitted unparseable JSON.
        if (fullText.isBlank()) {
            android.util.Log.w("MealAnalysis", "Empty stream after $chunkCount chunks")
            collector.emit(
                MealAnalysisEvent.Error(
                    messageKey = "analysis_failed_retry",
                    cause = IllegalStateException("Empty stream"),
                    salvage = null
                )
            )
            return
        }

        val parsed = runCatching { foodAdapter.fromJson(fullText) }.getOrNull()
        if (parsed != null) {
            val safeApplied = parsed.appliedHabitIds
                ?.filter { it in offeredHabitIds }
                ?.distinct()
            val flashResult = parsed.copy(
                appliedHabitIds = safeApplied,
                analysisModel = FLASH_MODEL,
                escalated = false
            )
            android.util.Log.d(
                "MealAnalysis",
                "Flash done name='${flashResult.name}' cal=${flashResult.calories} " +
                    "confidence=${flashResult.confidence}"
            )

            // Automatic flash→pro escalation when the LLM admits low/medium
            // confidence in its own estimate. Caller-supplied [allowEscalation]
            // gates the text-only re-analysis path (no image attached → Pro
            // would see the same text the Flash saw, no benefit). Pro failure
            // (network, quota) silently falls back to the flash result — the
            // user sees a usable answer either way.
            if (allowEscalation && (ALWAYS_PRO_ON_IMAGE || shouldEscalate(flashResult))) {
                val escalated = escalateToPro(
                    collector = collector,
                    request = request,
                    flashResult = flashResult,
                    offeredHabitIds = offeredHabitIds,
                    language = language
                )
                if (escalated != null) {
                    collector.emit(MealAnalysisEvent.Done(enrichResult(collector, escalated)))
                    return
                }
                // Pro path failed — emit Done with the flash result but tag
                // `proAttempted = true` so the VM can pop the one-shot snackbar.
                repository.markHabitsApplied(safeApplied)
                collector.emit(MealAnalysisEvent.Done(enrichResult(collector, flashResult.copy(proAttempted = true))))
                return
            }

            repository.markHabitsApplied(safeApplied)
            collector.emit(MealAnalysisEvent.Done(enrichResult(collector, flashResult)))
        } else {
            android.util.Log.w(
                "MealAnalysis",
                "Moshi rejected — bufferLen=${fullText.length} preview=${fullText.take(300)}"
            )
            val salvage = PartialJsonExtractor.salvageFinal(fullText)
            if (salvage != null) {
                collector.emit(
                    MealAnalysisEvent.Error(
                        messageKey = "analysis_partial_check",
                        cause = IllegalStateException("Moshi rejected final JSON"),
                        salvage = salvage
                    )
                )
            } else {
                collector.emit(
                    MealAnalysisEvent.Error(
                        messageKey = "analysis_failed_retry",
                        cause = IllegalStateException("Moshi rejected and salvage failed"),
                        salvage = null
                    )
                )
            }
        }
    }

    /**
     * Monotonic phase-state machine. Order:
     *   uploading → analyzing → identifying_dish → extracting_ingredients
     *               → calculating_macros → finalizing
     * Re-analysis stays on `phase_reanalyzing_meal` from start to finish —
     * the phases above are tied to the meal-image vocabulary.
     */
    private fun nextPhase(
        current: String,
        p: FoodAnalysisProgress,
        isReanalysis: Boolean
    ): String {
        if (isReanalysis) return PHASE_REANALYZING
        val candidate = when {
            p.protein != null || p.carbs != null || p.fat != null || p.calories != null ->
                PHASE_CALCULATING_MACROS
            p.ingredientsSoFar.isNotEmpty() ->
                PHASE_EXTRACTING_INGREDIENTS
            p.name != null ->
                PHASE_IDENTIFYING_DISH
            p.rawBytesReceived > 0 ->
                PHASE_ANALYZING
            else ->
                PHASE_UPLOADING
        }
        return if (PHASE_ORDER.indexOf(candidate) >= PHASE_ORDER.indexOf(current)) candidate else current
    }

    private fun buildAnalysisPrompt(
        promptHint: String?,
        language: String,
        habitsBlock: String,
        /** Correction-calibration block from [CorrectionCalibrator]; appended
         *  after the shared core so the model calibrates its estimate to the
         *  user's own correction history. Empty string → no-op. */
        calibrationBlock: String = "",
    ): String = repository.buildFoodAnalysisPromptCore(
        language = language,
        promptHint = promptHint,
        habitsBlock = habitsBlock
    ) + calibrationBlock

    /**
     * Apply a [FoodAnalysisResult] (from streaming Done or salvage) onto a
     * [FoodEntry] using the same merge semantics as the legacy blocking
     * [GeminiRepository.reanalyzeFoodFromSummary]: numeric fields take the
     * fresh values, name/summary fall back to current if the LLM omitted
     * them, and the ingredients JSON is overwritten only when the model
     * returned a fresh breakdown (preserves prior JSON otherwise).
     */
    fun mergeReanalysis(
        currentEntry: FoodEntry,
        result: FoodAnalysisResult,
        newSummary: String
    ): FoodEntry {
        val mergedCarbs = result.carbs ?: currentEntry.carbs
        val mergedFat = result.fat ?: currentEntry.fat
        return currentEntry.copy(
            name = result.name ?: currentEntry.name,
            calories = result.calories ?: currentEntry.calories,
            protein = result.protein ?: currentEntry.protein,
            carbs = mergedCarbs,
            fat = mergedFat,
            // Propagate the engine behind this re-analysis so the caller can
            // record it in the activity log (AUTO row). Without this the copy
            // keeps the stale model and the edit log shows "Manual" with no
            // engine even though the AI just re-ran.
            analysisModel = result.analysisModel ?: currentEntry.analysisModel,
            fullSummary = (result.fullSummary ?: newSummary).sanitizeAiNewlines(),
            mealCategory = MealCategory.parse(result.mealCategory),
            appliedHabitIds = result.appliedHabitIds,
            ingredientsJson = result.ingredients
                ?.let { encodeIngredients(it) }
                ?: currentEntry.ingredientsJson,
            // Free sugars: prefer the fresh estimate, fall back to the stored
            // value, then clamp to [0, mergedCarbs] so we never render a
            // segment wider than the total. Symmetric with
            // GeminiRepository.reanalyzeFoodFromSummary.
            freeSugarsG = (result.freeSugarsG ?: currentEntry.freeSugarsG)
                ?.coerceAtMost(mergedCarbs)
                ?.coerceAtLeast(0.0),
            // Same pattern for saturatedFatG against mergedFat.
            saturatedFatG = (result.saturatedFatG ?: currentEntry.saturatedFatG)
                ?.coerceAtMost(mergedFat)
                ?.coerceAtLeast(0.0)
        )
    }

    /**
     * Streaming counterpart of
     * [GeminiRepository.reanalyzeActivityFromSummary]. Emits a Phase event
     * up-front so the edit-activity screen can render the typing bubble
     * ("A reanalisar o treino…"), then a single Done with the mapped
     * [ActivityEntry] rows ready for `repository.updateActivitySession`.
     *
     * Unlike the meal flow we don't ship a Partial event — activity payloads
     * are 3-5 small fields per exercise, no per-ingredient list to chip onto
     * the UI as bytes arrive. Phase + Done is enough to give the user
     * "something is happening" feedback during the 2-5 s LLM round-trip.
     *
     * Same fallback ladder as the food path: if the streaming endpoint
     * refuses (`chunkCount == 0`), fall back to the blocking
     * [GeminiRepository.reanalyzeActivityFromSummary] which has 5-model
     * fallback + retries baked in.
     */
    fun reanalyzeActivityFromSummaryStreaming(
        sessionId: String,
        newSummary: String,
        prevExercises: List<ActivityEntry>,
        language: String = "en"
    ): Flow<ActivityAnalysisStreamEvent> = flow {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            emit(
                ActivityAnalysisStreamEvent.Error(
                    messageKey = "analysis_failed_retry",
                    cause = IllegalStateException("API Key not configured")
                )
            )
            return@flow
        }

        emit(ActivityAnalysisStreamEvent.Phase(PHASE_REANALYZING_ACTIVITY))

        // Prompt mirrors GeminiRepository.reanalyzeActivityFromSummary so the
        // model behaviour is identical to the legacy blocking path.
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
            ${repository.languageInstruction(language)}
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = promptText)))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                responseMimeType = "application/json"
            )
        )

        var fullText = ""
        var chunkCount = 0
        var terminated = false

        val stream = collectStreamWithWatchdog(
            model = FLASH_MODEL,
            apiKey = apiKey,
            request = request,
            logTag = "ActivityAnalysis",
            logLabel = "activity stream",
            onPartial = { accumulated ->
                fullText = accumulated
            },
            onDone = { finalText, chunks ->
                android.util.Log.d(
                    "ActivityAnalysis",
                    "stream Done chunks=$chunks bufferLen=${finalText.length}"
                )
            }
        )
        fullText = stream.fullText
        chunkCount = stream.chunkCount
        val streamError = stream.error
        if (streamError != null) {
            if (chunkCount == 0) {
                // Streaming endpoint refused — fall back to the
                // blocking path that has 5-model fallback + retries.
                val fallback = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    runCatching {
                        repository.reanalyzeActivityFromSummary(
                            sessionId = sessionId,
                            newSummary = newSummary,
                            prevExercises = prevExercises,
                            language = language
                        ).getOrNull()
                    }.getOrNull()
                }
                if (fallback != null) {
                    android.util.Log.d(
                        "ActivityAnalysis",
                        "Blocking fallback OK rows=${fallback.size}"
                    )
                    emit(ActivityAnalysisStreamEvent.Done(fallback))
                    terminated = true
                }
            }
            if (!terminated) {
                emit(
                    ActivityAnalysisStreamEvent.Error(
                        messageKey = "analysis_failed_retry",
                        cause = streamError
                    )
                )
                terminated = true
            }
        }

        if (terminated) return@flow

        if (fullText.isBlank()) {
            android.util.Log.w("ActivityAnalysis", "Empty stream after $chunkCount chunks")
            emit(
                ActivityAnalysisStreamEvent.Error(
                    messageKey = "analysis_failed_retry",
                    cause = IllegalStateException("Empty stream")
                )
            )
            return@flow
        }

        val parsed = runCatching { activityAdapter.fromJson(fullText) }.getOrNull()
        if (parsed != null) {
            android.util.Log.d(
                "ActivityAnalysis",
                "Moshi OK exercises=${parsed.exercises?.size ?: 0}"
            )
            emit(ActivityAnalysisStreamEvent.Done(mapActivityResult(parsed, sessionId, prevExercises, newSummary)))
        } else {
            android.util.Log.w(
                "ActivityAnalysis",
                "Moshi rejected — bufferLen=${fullText.length} preview=${fullText.take(300)}"
            )
            emit(
                ActivityAnalysisStreamEvent.Error(
                    messageKey = "analysis_failed_retry",
                    cause = IllegalStateException("Moshi rejected final JSON")
                )
            )
        }
    }

    /**
     * Mirror of the row-mapping in
     * [GeminiRepository.reanalyzeActivityFromSummary]: per-index carry-over
     * from the previous exercises so we preserve `id` (when re-saving an
     * existing entry) and `notes` when the model omits them. Index alignment
     * — not name match — matches the legacy behaviour exactly.
     */
    private fun mapActivityResult(
        result: ActivityAnalysisResult,
        sessionId: String,
        prevExercises: List<ActivityEntry>,
        newSummary: String
    ): List<ActivityEntry> {
        val exercisesList = result.exercises ?: emptyList()
        val timestamp = prevExercises.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        return exercisesList.mapIndexed { index, exerciseDto ->
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

    /**
     * Run the flash→pro escalation: emit the [PHASE_REFINING_WITH_PRO]
     * phase, re-run the same [GeminiRequest] through the blocking path
     * pinned to [PRO_MODEL], and on success return the Pro result tagged
     * with `escalated=true`. Returns null when the Pro call fails (network,
     * quota, parse error) — caller falls back to the Flash result.
     *
     * Shared by the streaming-tail path (Moshi parse succeeded on the
     * SSE buffer) and the blocking-fallback path (zero chunks from the
     * stream endpoint → blocking Flash → still admits low/medium). Keeping
     * both paths funneled through one helper means the "Pro" badge in the
     * UI is consistent regardless of which transport produced the Flash
     * answer.
     */
    private suspend fun escalateToPro(
        collector: kotlinx.coroutines.flow.FlowCollector<MealAnalysisEvent>,
        request: GeminiRequest,
        flashResult: FoodAnalysisResult,
        offeredHabitIds: Set<Long>,
        language: String
    ): FoodAnalysisResult? {
        android.util.Log.d(
            "MealAnalysis",
            "Escalating to $PRO_MODEL (flash confidence=${flashResult.confidence})"
        )
        collector.emit(MealAnalysisEvent.Phase(PHASE_REFINING_WITH_PRO))
        // Hard wall-clock cap on the Pro escalation. The underlying
        // `runFoodImageBlockingForRequest` only protects against OkHttp's
        // own readTimeout (60s) — but at that point the user has been
        // staring at the "refining with Pro" bubble for a full minute with
        // a perfectly usable Flash result already sitting in memory. 25s is
        // generous for the typical 5–15s Pro turnaround on a food image and
        // cuts the worst case in half.
        //
        // `withTimeoutOrNull` returns null on timeout instead of throwing,
        // which is exactly what we want — the existing `proResult == null`
        // branch below already handles the failure path (PHASE_PRO_UNAVAILABLE
        // bubble + flash result tagged with proAttempted=true).
        val proResult = kotlinx.coroutines.withTimeoutOrNull(25_000L) {
            runCatching {
                repository.runFoodImageBlockingForRequest(
                    request = request,
                    initialModel = PRO_MODEL,
                    language = language
                )
            }.getOrNull()
        }
        if (proResult == null) {
            android.util.Log.w("MealAnalysis", "Pro escalation failed or timed out; keeping flash result")
            // Surface the failure to the user in two ways:
            //   1. Phase bubble swaps to "modelo avançado indisponível" for ~2s so the user
            //      reading the progress bubble sees what happened before the bubble dismisses.
            //   2. The result is tagged with `proAttempted = true` so the VM can
            //      pop a one-shot snackbar when the edit-meal screen receives the Done.
            collector.emit(MealAnalysisEvent.Phase(PHASE_PRO_UNAVAILABLE))
            kotlinx.coroutines.delay(2000)
            return null
        }
        val proSafeApplied = proResult.appliedHabitIds
            ?.filter { it in offeredHabitIds }
            ?.distinct()
        repository.markHabitsApplied(proSafeApplied)
        android.util.Log.d(
            "MealAnalysis",
            "Pro escalation OK name='${proResult.name}' " +
                "cal=${proResult.calories} confidence=${proResult.confidence}"
        )
        return proResult.copy(
            appliedHabitIds = proSafeApplied,
            analysisModel = PRO_MODEL,
            escalated = true
        )
    }

    companion object {
        const val PHASE_UPLOADING = "phase_uploading_image"
        const val PHASE_ANALYZING = "phase_analyzing_meal"
        const val PHASE_IDENTIFYING_DISH = "phase_identifying_dish"
        const val PHASE_EXTRACTING_INGREDIENTS = "phase_extracting_ingredients"
        const val PHASE_CALCULATING_MACROS = "phase_calculating_macros"
        const val PHASE_FINALIZING = "phase_finalizing"
        const val PHASE_REANALYZING = "phase_reanalyzing_meal"
        const val PHASE_REANALYZING_ACTIVITY = "phase_reanalyzing_activity"
        /** Streaming Flash done, but `confidence` came back `low`/`medium` →
         *  orchestrator is silently re-running the analysis through the more
         *  capable Pro model. UI swaps the "Aceitar e guardar refeição"
         *  button for a phase bubble while this is in flight. */
        const val PHASE_REFINING_WITH_PRO = "phase_refining_with_pro"

        /** Pro escalation was attempted but the Pro call failed (network,
         *  quota, parse error). The orchestrator holds this phase for ~2s
         *  so the user reading the progress bubble sees what happened, then
         *  emits the Flash result tagged with `proAttempted = true`. The VM
         *  pops a one-shot snackbar on top of the edit-meal screen. */
        const val PHASE_PRO_UNAVAILABLE = "phase_pro_unavailable"
        const val PHASE_VERIFYING_NUTRIENTS = "phase_verifying_nutrients"

        // 2026-08-14 — primary image-analysis model. Was gemini-3.5-flash,
        // whose 20/day free-tier quota exhausts fast. gemini-3.6-flash is
        // confirmed 200-OK with vision and has its own quota; the cascade in
        // generateContentWithFallback degrades to 3.5-flash / 3.7-flash /
        // 3.5-flash-lite on 429/503.
        const val FLASH_MODEL = "gemini-3.6-flash"
        // 2026-08-16 — escalation model. Was gemini-3.7-flash, which returns
        // HTTP 503 on VISION requests (see GeminiRepository cascade comment) —
        // and escalateToPro seeds the cascade with it first, so every
        // medium-confidence photo hit 503 → "Modelo avançado indisponível".
        // gemini-3.5-flash is vision-confirmed 200-OK with its own daily quota,
        // distinct from the 3.6-flash primary for diversity.
        const val PRO_MODEL = "gemini-3.5-flash"

        /** When true, ANY photo analysis (image present → `allowEscalation`)
         *  always escalates to Pro to produce the persisted metadata — Flash
         *  still streams first for a fast provisional paint, then Pro's result
         *  replaces it. When false, escalation reverts to the confidence /
         *  suspicious-macro heuristic ([shouldEscalate]). Kill-switch for the
         *  free-tier Pro RPM quota: flip to false to disable without a rebuild
         *  of the gate logic. Set to false now that the NutritionLookupService
         *  handles macro precision via INSA/USDA/OFF — Pro escalation is only
         *  needed when Flash confidence is low/medium (ambiguous portions,
         *  occluded dishes). */
        const val ALWAYS_PRO_ON_IMAGE = false

        /** Substrings (pt / en / gu) that identify raw, near-zero-fat whole
         *  produce for the suspicious-fat escalation heuristic. Kept
         *  intentionally narrow — only fruit and non-starchy vegetables that
         *  are genuinely low-fat. Fatty produce (avocado/abacate, coconut/coco,
         *  olives/azeitona, nuts/seeds) is deliberately NOT here. */
        internal val LOW_FAT_PRODUCE_PATTERNS: List<String> = listOf(
            // fruit
            "papaia", "papaya", "melão", "melao", "melon", "melancia", "watermelon",
            "maçã", "maca", "apple", "pera", "pear", "banana", "laranja", "orange",
            "morango", "strawberr", "mirtilo", "blueberr", "framboesa", "raspberr",
            "amora", "uva", "grape", "ananás", "ananas", "abacaxi", "pineapple",
            "manga", "mango", "kiwi", "pêssego", "pessego", "peach", "ameixa", "plum",
            "cereja", "cherry", "tangerina", "clementina", "romã", "roma", "pomegranate",
            "frutos vermelhos", "berries", "berry",
            // non-starchy vegetables
            "tomate", "tomato", "pepino", "cucumber", "alface", "lettuce",
            "espinafre", "spinach", "rúcula", "rucula", "cenoura", "carrot",
            "brócolo", "brocolo", "broccoli", "couve", "cabbage", "kale",
            "pimento", "pepper", "curgete", "courgette", "zucchini", "abobrinha",
            "beringela", "eggplant", "aubergine", "aipo", "celery", "rabanete", "radish",
            "cogumelo", "mushroom", "cebola", "onion", "salada", "salad", "vegetais", "legumes"
        )

        /** Substrings (pt / en) that identify beverages carrying essentially
         *  ZERO fat — water, black coffee/tea, and soft drinks / sodas. If the
         *  model puts fat on one of these it has mis-split the meal's fat onto a
         *  drink (the Coca-Cola case, 2026-07-10). Kept intentionally narrow:
         *  only drinks that are genuinely fat-free. Dairy / fat-bearing drinks
         *  (leite, latte, milkshake, smoothie, kefir, protein shake…) are
         *  handled by [DAIRY_DRINK_EXCLUSIONS] and NEVER flagged, because their
         *  fat is legitimate. */
        internal val ZERO_FAT_BEVERAGE_PATTERNS: List<String> = listOf(
            // water
            "água", "agua", "water",
            // soft drinks / sodas (substring covers diet / zero / light variants)
            "coca-cola", "coca cola", "cola", "coke", "refrigerante", "soda", "gasosa",
            "guaraná", "guarana", "sprite", "fanta", "pepsi", "seven up", "7up",
            "água tónica", "agua tonica", "tonic", "limonada", "lemonade",
            "energy drink", "bebida energética", "bebida energetica", "red bull",
            "iced tea", "chá gelado", "cha gelado", "ice tea",
            // black coffee / tea and infusions (no milk — see exclusions)
            "café", "cafe", "coffee", "espresso", "expresso", "americano",
            "chá", "cha", "tea", "infusão", "infusao", "infusion",
            // fruit juices (fat-free even though sugary)
            "sumo", "juice", "néctar", "nectar"
        )

        /** Substrings that mark a drink as dairy / fat-bearing. When ANY of
         *  these appears in the name, [isZeroFatBeverage] returns false even if
         *  a zero-fat pattern also matched (e.g. "café com leite", "latte",
         *  "chá com leite") — the fat on those is real and must be kept. */
        internal val DAIRY_DRINK_EXCLUSIONS: List<String> = listOf(
            "leite", "milk", "latte", "cappuccino", "capuccino", "galão", "galao",
            "meia de leite", "milkshake", "batido", "smoothie", "kefir",
            "iogurte líquido", "iogurte liquido", "yogurt drink", "lassi",
            "proteína", "proteina", "protein shake", "shake",
            "bebida vegetal", "oat milk", "soy milk", "almond milk",
            "bebida de aveia", "bebida de soja", "bebida de amêndoa", "bebida de amendoa",
            "creme", "cream", "nata", "chantilly"
        )

        internal const val STREAM_TTFT_TIMEOUT_MS: Long = 8_000L
        internal const val STREAM_IDLE_TIMEOUT_MS: Long = 15_000L
        internal const val STREAM_WATCHDOG_TICK_MS: Long = 250L

        private val PHASE_ORDER = listOf(
            PHASE_UPLOADING,
            PHASE_ANALYZING,
            PHASE_IDENTIFYING_DISH,
            PHASE_EXTRACTING_INGREDIENTS,
            PHASE_CALCULATING_MACROS,
            PHASE_FINALIZING,
            PHASE_REFINING_WITH_PRO,
            PHASE_PRO_UNAVAILABLE
        )

        /** Gate for the automatic flash→pro escalation. Escalates when the
         *  LLM admits uncertainty (`low`/`medium` confidence) OR when the
         *  result carries a tell-tale reasoning error the model won't flag
         *  itself: fat attributed to low-fat whole produce (the papaya-1.4g
         *  case, 2026-07-10). A confidently-wrong `high` result on produce fat
         *  now gets a Pro second opinion instead of shipping the error.
         *
         *  Whether the caller is allowed to escalate at all is decided by
         *  the `allowEscalation` flag on `consumeStream` (image-present
         *  precondition for re-analysis). */
        private fun shouldEscalate(result: FoodAnalysisResult): Boolean {
            val c = result.confidence?.trim()?.lowercase()
            if (c == "low" || c == "medium") return true
            return hasSuspiciousProduceFat(result) || hasSuspiciousBeverageFat(result)
        }

        /** Raw fruit / non-starchy vegetables carry ~0 fat. If the model put
         *  meaningful fat on such an ingredient, it almost certainly mis-split
         *  the meal's fat (spreading a fatty source onto the produce). Flags
         *  any produce-matched ingredient carrying > 0.3 g fat per 100 g. */
        private fun hasSuspiciousProduceFat(result: FoodAnalysisResult): Boolean {
            val ingredients = result.ingredients ?: return false
            return ingredients.any { ing ->
                val fat = ing.fat ?: return@any false
                val grams = ing.weightGrams
                if (grams <= 0.0) return@any false
                val fatPer100g = fat / grams * 100.0
                isLowFatProduce(ing.name) && fatPer100g > 0.3
            }
        }

        /** Zero-fat beverages (water, black coffee/tea, sodas, juices) carry
         *  0 g fat. Any meaningful fat on one is a mis-split — the Coca-Cola
         *  case (2026-07-10). Unlike produce we don't use a per-100g rate: a
         *  soda is flatly 0 g, so we flag on a small absolute floor (> 0.2 g,
         *  tolerating rounding noise). Dairy/fatty drinks are excluded by
         *  [isZeroFatBeverage] itself. */
        private fun hasSuspiciousBeverageFat(result: FoodAnalysisResult): Boolean {
            val ingredients = result.ingredients ?: return false
            return ingredients.any { ing ->
                val fat = ing.fat ?: return@any false
                isZeroFatBeverage(ing.name) && fat > 0.2
            }
        }

        /** @VisibleForTesting seam so unit tests can exercise the produce-fat
         *  heuristic directly (shouldEscalate is private). */
        internal fun hasSuspiciousProduceFatForTest(result: FoodAnalysisResult): Boolean =
            hasSuspiciousProduceFat(result)

        /** @VisibleForTesting seam for the zero-fat-beverage heuristic. */
        internal fun hasSuspiciousBeverageFatForTest(result: FoodAnalysisResult): Boolean =
            hasSuspiciousBeverageFat(result)

        /** Name-matches raw fruit / non-starchy vegetables (any language the
         *  user logs in — pt/en/gu). Deliberately conservative: only whole
         *  produce that is genuinely near-zero fat. Avocado, coconut, olives
         *  and nuts/seeds are intentionally EXCLUDED (they are fatty). */
        internal fun isLowFatProduce(name: String): Boolean {
            val n = name.trim().lowercase()
            if (n.isEmpty()) return false
            return LOW_FAT_PRODUCE_PATTERNS.any { n.contains(it) }
        }

        /** Name-matches genuinely fat-free beverages (water, black coffee/tea,
         *  sodas, juices) in any language the user logs in (pt/en). Returns
         *  false for any drink carrying a dairy / fatty token
         *  ([DAIRY_DRINK_EXCLUSIONS]) so "café com leite", "latte", "chá com
         *  leite", milkshakes and smoothies are never treated as zero-fat —
         *  their fat is legitimate and must be kept. */
        internal fun isZeroFatBeverage(name: String): Boolean {
            val n = name.trim().lowercase()
            if (n.isEmpty()) return false
            if (DAIRY_DRINK_EXCLUSIONS.any { n.contains(it) }) return false
            return ZERO_FAT_BEVERAGE_PATTERNS.any { n.contains(it) }
        }
    }
}
