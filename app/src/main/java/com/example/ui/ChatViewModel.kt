package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.health.SamsungHealthRepository
import com.example.data.health.ShWorkout
import com.example.data.health.displayLabel
import com.example.data.health.BmrSnapshot
import com.example.data.health.BmrSource
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import com.example.data.local.ChatSession
import com.example.data.local.ChatMessage
import com.example.data.local.FoodEntry
import com.example.data.local.FoodEditType
import com.example.data.local.appendEditLog
import com.example.data.local.restoreFromSnapshot
import com.example.data.local.FoodHabit
import com.example.data.local.MealCategory
import com.example.data.local.ActivityEntry
import com.example.data.remote.GeminiRepository
import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.ActivityAnalysisResult
import com.example.data.remote.HabitProposal
import com.example.data.remote.HabitProposalParser
import com.example.data.remote.ParsedHabitMessage
import com.example.data.voice.SpeechRecognizerController
import com.example.data.voice.TtsController
import com.example.ui.ReportActivityRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo waiting to be analysed by Gemini in the multi-pick flow. The
 *  caller is expected to have already persisted the bitmap to filesDir
 *  (see `Bitmap.persistToFoodImages`) and base64-encoded its bytes. */
data class PendingFoodPhoto(
    val base64: String,
    val imagePath: String?,
    /** Phase 5 (2026-06-13): explicit mime so HEIC/PNG/WebP captures aren't
     *  flattened to JPEG when sent to Gemini. Default keeps legacy callers
     *  compatible. */
    val mimeType: String = "image/jpeg"
)

data class UserProfile(
    val name: String = "Humano",
    val gender: String = "Female",
    val birthYear: Int = 1980,
    val activityLevel: String = "Average daily activity",
    val targetKcal: Int = 1800,
    val targetProtein: Double = 85.0,
    val targetCarbs: Double = 220.0,
    val targetFat: Double = 60.0,
    val targetSteps: Int = 6000,
    val targetActiveMinutes: Int = 90,
    val targetActivityCalories: Int = 500,
    /** Manual BMR/TMB override (kcal/day). 0 = unset → Samsung Health is used; if SH
     *  has nothing either, the dashboard line is hidden entirely. */
    val manualBmr: Int = 0,
    /** Epoch ms of when [manualBmr] was last changed. 0 = never set. Used to render
     *  a "last update" stamp next to manual values on the dashboard. */
    val manualBmrSavedAtMs: Long = 0L,
    val language: String = "pt-PT"
)

enum class CaptureMode { FOOD, ACTIVITY }

/**
 * Voice Mode state machine for the hands-free chat toggle (2026-07-05).
 * Match to `itopsea/SpeechManager.swift:6-13`:
 *   Idle       → mic button pill grey, no session.
 *   Listening  → SpeechRecognizer active, partial results echo in input.
 *   Thinking   → user's utterance was submitted; waiting for LLM to reply.
 *   Speaking   → TTS is reading the reply aloud.
 * The loop cycles Listening → Thinking → Speaking → Listening automatically
 * until the user taps the mic button again, which returns to Idle.
 */
enum class VoiceMode { Idle, Listening, Thinking, Speaking }

class ChatViewModel(
    private val repository: GeminiRepository,
    private val shRepository: SamsungHealthRepository,
    /** Optional — set by [ChatViewModelFactory] for the foreground UI
     *  ViewModel, null for headless test instances. When null the chat
     *  silently omits the `[CONTEXTO LOCAL]` weather block. */
    private val locationHelper: com.example.data.location.LocationHelper? = null,
    /** Optional — see [locationHelper]. */
    private val weatherFetcher: com.example.data.weather.IpmaWeatherFetcher? = null,
    /** Optional — set by the factory. Missing controllers make speak/mic a
     *  no-op so headless tests (which don't have an Android context) still
     *  construct the VM. */
    private val ttsController: TtsController? = null,
    private val speechRecognizer: SpeechRecognizerController? = null,
    private val nutritionLookupService: com.example.data.nutrition.NutritionLookupService? = null,
) : ViewModel() {

    // Samsung Health flows
    private val _shStatus = MutableStateFlow(SamsungHealthRepository.Status.NEEDS_PERMISSION)
    val shStatus: StateFlow<SamsungHealthRepository.Status> = _shStatus.asStateFlow()

    private val _shStepsByDate = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())
    val shStepsByDate: StateFlow<Map<LocalDate, Long>> = _shStepsByDate.asStateFlow()

    /** Workouts read from Samsung Health, held in memory only. The
     *  activities table is reserved for exercises the user logs manually
     *  inside this app. Cleared on logout / permission revoke. */
    private val _shWorkouts = MutableStateFlow<List<ShWorkout>>(emptyList())
    val shWorkouts: StateFlow<List<ShWorkout>> = _shWorkouts.asStateFlow()

    /** Daily Activity Summary rollups (active time / active kcal / total kcal / distance)
     *  pulled directly from Samsung Health's ActivitySummary aggregates. These are the
     *  exact numbers shown by Samsung Health UI; the dashboard mirrors them 1:1 instead
     *  of re-deriving from steps × custom factors (which previously double-counted
     *  workout calories). */
    private val _shDailyActivity =
        MutableStateFlow<Map<LocalDate, com.example.data.health.ShDailyActivity>>(emptyMap())
    val shDailyActivity: StateFlow<Map<LocalDate, com.example.data.health.ShDailyActivity>> =
        _shDailyActivity.asStateFlow()

    private val _shLastSyncMs = MutableStateFlow(0L)
    val shLastSyncMs: StateFlow<Long> = _shLastSyncMs.asStateFlow()

    /** Append-only history of BMR/TMB measurements, keyed by effective date. The
     *  dashboard, "today's consumption" view, and reports tab read through this
     *  via [com.example.data.bmr.BmrResolver] so a TMB change today never
     *  silently re-anchors yesterday's "burned" totals.
     *
     *  Each Samsung Health BodyComposition refresh and each manual save in the
     *  profile dialog appends a new row via [appendBmrSnapshotIfChanged] — only
     *  if the value differs from the latest snapshot, to keep the table small. */
    val bmrSnapshots: StateFlow<List<com.example.data.local.BmrSnapshotEntity>> =
        repository.bmrSnapshots
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Frozen "active calories" totals per day, captured by
     *  [com.example.data.health.DailyActivitySnapshotWorker]. Read-only stream
     *  the dashboard/report layer uses to render historical days verbatim
     *  (today + future days fall back to live computation in
     *  [com.example.data.activity.DailyActivityResolver]). */
    val dailyActivitySnapshots:
        StateFlow<List<com.example.data.local.DailyActivitySnapshotEntity>> =
        repository.dailyActivitySnapshots
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live "current TMB" view — the most recent snapshot, mapped back to the
     *  legacy [BmrSnapshot] shape so the dashboard footer (which renders source +
     *  timestamp + value) keeps working. Returns null when the table is empty
     *  (fresh install with no manual value and no SH read yet) — the footer is
     *  already null-safe and hides itself in that case. */
    val bmr: StateFlow<BmrSnapshot?> = bmrSnapshots
        .map { list ->
            list.lastOrNull()?.let { row ->
                BmrSnapshot(
                    value = row.valueKcal,
                    source = if (row.source == com.example.data.local.BmrSnapshotEntity.SOURCE_SH)
                        BmrSource.SAMSUNG_HEALTH else BmrSource.MANUAL,
                    timestamp = row.sourceTimestampMs?.let { Instant.ofEpochMilli(it) }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Peso actual (kg) lido do Samsung Health via
     *  [com.example.data.health.SamsungHealthRepository.readLatestBodyCompositionWeight].
     *  Atualizado durante o sync periódico do SH (ver [syncFromSamsungHealth]).
     *  Null enquanto o SH não foi consultado, quando a permissão
     *  BODY_COMPOSITION não está concedida, ou quando nenhum body-composition
     *  entry existe no último ano. Reports tab consome este flow para
     *  surfacer "Peso actual" no Resumo Nutricional (2026-06-26). */
    /** Latest body-composition snapshot from the local cache (which is
     *  fed by Samsung Health on each successful sync). Survives cold-start,
     *  S-Health disconnects, and permission revocations: as long as we
     *  saw at least one weigh-in once, the "Peso actual" row in the
     *  Reports tab keeps showing the last-known reading. Null only on a
     *  fresh install that has never synced. */
    val latestBodyComposition:
        StateFlow<com.example.data.local.BodyCompositionSnapshotEntity?> =
        repository.observeLatestBodyComposition()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Loads body-composition snapshots in `[startMs, endMs)`, ascending
     *  by source timestamp. The Reports tab's Overview card uses this
     *  to drive the per-week / per-month evolution chart with a wide
     *  back-window so forward-fill can anchor on prior measurements. */
    suspend fun loadBodyCompositionHistory(
        startMs: Long,
        endMs: Long
    ): List<com.example.data.local.BodyCompositionSnapshotEntity> =
        repository.bodyCompositionSnapshotsBetween(startMs, endMs)

    // ── Performance / Mobility (v35) ─────────────────────────────────────────

    /** Live flow of all performance snapshots, ascending. Performance tab subscribes. */
    val performanceSnapshots: StateFlow<List<com.example.data.local.PerformanceSnapshotEntity>> =
        repository.observeAllPerformanceSnapshots()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live flow of the user's latest performance goal (v37). */
    val latestPerformanceGoal: StateFlow<com.example.data.local.PerformanceGoalEntity?> =
        repository.observeLatestPerformanceGoal()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Saves a mobility assessment snapshot. Ignores duplicates within 60 s. */
    fun savePerformanceSnapshot(entity: com.example.data.local.PerformanceSnapshotEntity) {
        viewModelScope.launch {
            repository.insertPerformanceSnapshot(entity)
        }
    }

    /** Saves a silhouette snapshot then generates the coach report async.
     *  The entity is persisted immediately (UI can show a spinner); once the
     *  LLM responds the report is written back via [updateCoachReport]. */
    fun savePerformanceSnapshotAndRequestReport(entity: com.example.data.local.PerformanceSnapshotEntity) {
        viewModelScope.launch {
            val id = repository.insertPerformanceSnapshot(entity)
            if (id > 0) generateCoachReport(entity.copy(id = id))
        }
    }

    /** Triggers async coach report generation for an already-saved snapshot. */
    fun requestCoachReport(entity: com.example.data.local.PerformanceSnapshotEntity) {
        viewModelScope.launch { generateCoachReport(entity) }
    }

    /** Saves the user's free-text performance goal and derives ratio targets via LLM.
     *  Idempotent: if the latest goal already has this exact text but no targets yet
     *  (generation previously failed), re-run generation on it instead of inserting
     *  a duplicate row. */
    fun savePerformanceGoal(text: String) {
        viewModelScope.launch {
            val latest = latestPerformanceGoal.value
            if (latest != null && latest.goalText == text && latest.coachTargetJson == null) {
                generateCoachTargets(latest)   // retry on the existing row
                return@launch
            }
            val entity = com.example.data.local.PerformanceGoalEntity(goalText = text)
            val id = repository.insertPerformanceGoal(entity)
            if (id > 0) generateCoachTargets(entity.copy(id = id))
        }
    }

    private suspend fun generateCoachTargets(goal: com.example.data.local.PerformanceGoalEntity) {
        val gender = _userProfile.value.gender.ifBlank { "Male" }
        val lang = _userProfile.value.language.ifBlank { "pt-PT" }
        val snapshot = repository.recentPerformanceSnapshots(1).firstOrNull()
        val bodyComp = repository.recentBodyCompositionSnapshots(10)
        val prompt = com.example.data.chat.PerformanceCoachBuilder.buildTargetsPrompt(
            goal.goalText, gender, lang, snapshot, bodyComp)
        val raw = repository.generateCoachReport(prompt)
        if (raw == null) {
            android.util.Log.w("SHD", "[coach-targets] LLM returned null (key/network) goal=${goal.id}")
            return
        }
        val json = extractCoachTargetsJson(raw)
        if (json == null) {
            android.util.Log.w("SHD", "[coach-targets] no [COACH_TARGETS] block in response goal=${goal.id}")
            return
        }
        // Clamp every target into the physiological reference range so a stray or
        // echoed-noise value from the LLM can never drive the silhouette.
        val clamped = com.example.data.chat.PerformanceCoachBuilder.clampTargetsJson(json)
        repository.updatePerformanceGoalTargets(goal.id, clamped, System.currentTimeMillis())
        android.util.Log.i("SHD", "[coach-targets] persisted goal=${goal.id} raw=$json clamped=$clamped")
    }

    private suspend fun generateCoachReport(entity: com.example.data.local.PerformanceSnapshotEntity) {
        if (entity.id <= 0L) return
        val bodyComp = repository.recentBodyCompositionSnapshots(10)
        val name = _userProfile.value.name.ifBlank { "Utilizador" }
        val lang = _userProfile.value.language.ifBlank { "pt-PT" }
        val goal = latestPerformanceGoal.value
        val prompt = com.example.data.chat.PerformanceCoachBuilder.buildPrompt(
            entity, bodyComp, name, lang, goal)
        val report = repository.generateCoachReport(prompt) ?: return
        // If the report contains [COACH_TARGETS], extract, clamp and persist them too
        val json = extractCoachTargetsJson(report)
        if (json != null && goal != null) {
            val clamped = com.example.data.chat.PerformanceCoachBuilder.clampTargetsJson(json)
            repository.updatePerformanceGoalTargets(goal.id, clamped, System.currentTimeMillis())
        }
        // Strip the block from the visible report before saving
        val cleanReport = stripCoachTargetsBlock(report)
        repository.updatePerformanceCoachReport(entity.id, cleanReport, System.currentTimeMillis())
    }

    /** Legacy projection: extracts just the weight from [latestBodyComposition]
     *  for callers that don't need the full snapshot. Kept around so the
     *  Reports tab's existing `shWeightSnap?.kg` call site stays untouched
     *  while we wire the richer body-composition fields. */
    val shWeight: StateFlow<com.example.data.health.WeightSnapshot?> =
        latestBodyComposition
            .map { snap ->
                snap?.let {
                    com.example.data.health.WeightSnapshot(
                        kg = it.weightKg,
                        timestamp = it.sourceTimestampMs?.let { ms -> Instant.ofEpochMilli(ms) }
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** One-shot per-process flag: flips on the first SH sync of this
     *  process so [shRepository.probeBodyCompositionFields] runs exactly
     *  once per cold-start. The result of the probe doesn't change
     *  between weighings within the same process (only changes when the
     *  user installs a new scale app), so once is enough. */
    private var bodyCompositionProbed: Boolean = false

    // Food logging flows
    val foods: StateFlow<List<FoodEntry>> = repository.allFoods
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isAnalyzingFood = MutableStateFlow(false)
    val isAnalyzingFood: StateFlow<Boolean> = _isAnalyzingFood.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    // ----- Streaming meal-analysis feedback (2026-06-18) -----
    // The Activity tab uses [com.example.data.meal.MealAnalysisOrchestrator]
    // to expose phase-by-phase progress to the user instead of a 5-15s
    // silent spinner. _foodAnalysisPhase carries an i18n KEY that the UI
    // resolves via tr() — keeps locale concerns in the UI layer. _foodAnalysisProgress
    // carries the partial fields the orchestrator pulled from the SSE buffer
    // (name, ingredient names, macros) so the capture card can render
    // "Identificado: …" + chips of ingredients while bytes still trickle in.
    // _foodAnalysisError + lastFoodAnalysisRequest power the inline retry path.
    private val mealOrchestrator: com.example.data.meal.MealAnalysisOrchestrator =
        repository.createMealAnalysisOrchestrator(nutritionLookupService)

    private val _foodAnalysisPhase = MutableStateFlow<String?>(null)
    val foodAnalysisPhase: StateFlow<String?> = _foodAnalysisPhase.asStateFlow()

    private val _foodAnalysisProgress = MutableStateFlow(com.example.data.meal.FoodAnalysisProgress())
    val foodAnalysisProgress: StateFlow<com.example.data.meal.FoodAnalysisProgress> =
        _foodAnalysisProgress.asStateFlow()

    private val _foodAnalysisError = MutableStateFlow<com.example.data.meal.FoodAnalysisError?>(null)
    val foodAnalysisError: StateFlow<com.example.data.meal.FoodAnalysisError?> =
        _foodAnalysisError.asStateFlow()

    /** One-shot informational messages the UI should surface as a transient
     *  snackbar (e.g. "Modelo avançado indisponível — análise inicial
     *  mantida."). Backed by a [MutableSharedFlow] with `replay=0` so each
     *  collection consumes the message exactly once — collectors that
     *  subscribe after the emission will not re-trigger it. The VM emits the
     *  raw i18n KEY; the UI resolves it via [tr] in the user's language. */
    private val _oneShotSnack = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val oneShotSnack: SharedFlow<String> = _oneShotSnack.asSharedFlow()

    /** Fires when the edit-meal / edit-activity view is *explicitly* closed —
     *  either by [rejectPendingFoodEntry] / [rejectPendingActivitySession]
     *  (Cancel button) or by [clearPendingSession] (bottom-nav tap).
     *  Saving no longer emits here so the edit view stays visible until the
     *  user leaves it themselves. `ChatScreen` collects this to animate the
     *  pager back to `originTab`. */
    private val _pendingClosed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val pendingClosed: SharedFlow<Unit> = _pendingClosed.asSharedFlow()

    /** Fires ~2s after a successful meal save (2026-07-10, Hugo's request):
     *  the "Refeição guardada" snackbar shows first, then the editor closes
     *  and the app navigates to the Histórico tab. Distinct from
     *  [pendingClosed] because the destination is always Histórico regardless
     *  of `originTab`, and the session is cleared here rather than re-filled.
     *  `ChatScreen` collects this to clear + animate the pager to HISTORY. */
    private val _navigateToHistoryAfterSave = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val navigateToHistoryAfterSave: SharedFlow<Unit> = _navigateToHistoryAfterSave.asSharedFlow()

    /** Tracks whether the user edited `fullSummary` in the edit-meal screen
     *  AFTER the initial analysis. Wired by the TextField onValueChange and
     *  consumed by [confirmAndSaveFoodEntry] to decide whether to run the
     *  re-analysis stream (phase bubble appears) or insert directly. */
    private val _summaryWasEditedAfterAnalysis = MutableStateFlow(false)
    val summaryWasEditedAfterAnalysis: StateFlow<Boolean> =
        _summaryWasEditedAfterAnalysis.asStateFlow()

    /** Tracks user-edited leftovers text when the persisted leftovers card is
     *  edited. Consumed by [confirmAndSaveFoodEntry] to decide whether to run
     *  the leftovers re-subtraction stream. Cleared after save. */
    private val _editedLeftoversText = MutableStateFlow<String?>(null)
    val editedLeftoversText: StateFlow<String?> = _editedLeftoversText.asStateFlow()

    fun markLeftoversEdited(text: String) {
        _editedLeftoversText.value = text
    }

    /** Mirror flag for the activity edit screen — gated by
     *  [confirmAndSaveActivitySession] so the re-analysis stream only fires
     *  when the user actually changed the workout summary. */
    private val _summaryWasEditedAfterActivityAnalysis = MutableStateFlow(false)
    val summaryWasEditedAfterActivityAnalysis: StateFlow<Boolean> =
        _summaryWasEditedAfterActivityAnalysis.asStateFlow()

    private var foodAnalysisJob: Job? = null

    /** Snapshot of the last analyse-food invocation so the inline error card
     *  can offer a Retry button without the UI having to thread the args back. */
    private data class FoodAnalysisRequest(
        val base64Image: String?,
        val imagePath: String?,
        val promptHint: String,
        val mimeType: String,
        val appendToSession: Boolean,
        /** When non-null, the resulting draft replaces the session slot at
         *  this index instead of appending or overwriting the whole list.
         *  Powers the "Replace photo" / "Retake photo" buttons on the
         *  Edit Meal screen when the session has a single item. */
        val replaceIndex: Int? = null
    )

    private var lastFoodAnalysisRequest: FoodAnalysisRequest? = null

    // Activity logging flows
    val activities: StateFlow<List<ActivityEntry>> = repository.allActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isAnalyzingActivity = MutableStateFlow(false)
    val isAnalyzingActivity: StateFlow<Boolean> = _isAnalyzingActivity.asStateFlow()

    private val _activityAnalysisError = MutableStateFlow<String?>(null)
    val activityAnalysisError: StateFlow<String?> = _activityAnalysisError.asStateFlow()

    private val _lastAnalyzedResult = MutableStateFlow<FoodAnalysisResult?>(null)
    val lastAnalyzedResult: StateFlow<FoodAnalysisResult?> = _lastAnalyzedResult.asStateFlow()

    fun insertFood(entry: FoodEntry) {
        viewModelScope.launch {
            repository.insertFood(entry)
        }
    }

    fun insertActivity(entry: ActivityEntry) {
        viewModelScope.launch {
            repository.insertActivity(entry)
        }
    }

    fun deleteFood(entry: FoodEntry) {
        viewModelScope.launch {
            repository.deleteFood(entry)
        }
    }

    fun deleteActivity(entry: ActivityEntry) {
        viewModelScope.launch {
            repository.deleteActivity(entry)
        }
    }

    fun clearAllFoods() {
        viewModelScope.launch {
            repository.clearAllFoods()
        }
    }

    /** Wipes every food log AND every manual activity log. Wired to the
     *  "Eliminar dados da aplicação" button on the Reports tab — see
     *  [chat redesign 2026-06-15] for the rename rationale. Samsung Health
     *  data is not stored here, so this never touches it. */
    fun wipeAppData() {
        viewModelScope.launch {
            repository.clearAllFoods()
            repository.clearAllActivities()
        }
    }

    fun analyzeAndLogFood(
        base64Image: String?,
        imagePath: String? = null,
        promptHint: String,
        mimeType: String = "image/jpeg",
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        startFoodAnalysisStream(
            request = FoodAnalysisRequest(
                base64Image = base64Image,
                imagePath = imagePath,
                promptHint = promptHint,
                mimeType = mimeType,
                appendToSession = false
            ),
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    /**
     * Analyse a single image and **append** the resulting draft FoodEntry to
     * the current session (instead of replacing it). Powers the "Add another
     * photo" button in the multi-photo capture flow. Errors populate
     * [_perItemAnalysisErrors] keyed by [imagePath] so the UI can show a
     * banner on the right card.
     */
    fun analyzeAndAppendFoodImage(
        base64Image: String,
        imagePath: String?,
        promptHint: String,
        mimeType: String = "image/jpeg",
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        startFoodAnalysisStream(
            request = FoodAnalysisRequest(
                base64Image = base64Image,
                imagePath = imagePath,
                promptHint = promptHint,
                mimeType = mimeType,
                appendToSession = true
            ),
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    /**
     * Analyse a single image and **replace** the draft at [targetIndex] of
     * the current session (default: head item). Powers the "Substituir
     * foto" / "Tirar de novo" buttons on the Edit Meal screen when the
     * session has exactly one item — Hugo's screenshot report 2026-07-01,
     * plan see-last-snap-looks-wobbly-quasar.md. Success clears any
     * per-item error keyed to the outgoing imagePath so a stale banner
     * doesn't linger on the replaced slot.
     */
    fun analyzeAndReplaceFoodImage(
        base64Image: String,
        imagePath: String?,
        promptHint: String,
        mimeType: String = "image/jpeg",
        targetIndex: Int = 0,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        val outgoingImagePath = _pendingFoodSession.value.getOrNull(targetIndex)?.imagePath
        startFoodAnalysisStream(
            request = FoodAnalysisRequest(
                base64Image = base64Image,
                imagePath = imagePath,
                promptHint = promptHint,
                mimeType = mimeType,
                appendToSession = false,
                replaceIndex = targetIndex
            ),
            onSuccess = {
                clearPerItemAnalysisError(outgoingImagePath)
                onSuccess()
            },
            onFailure = onFailure
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LEFTOVERS ANALYSIS
    // ─────────────────────────────────────────────────────────────────────────

    private val _isAnalyzingLeftovers = MutableStateFlow(false)
    val isAnalyzingLeftovers: StateFlow<Boolean> = _isAnalyzingLeftovers.asStateFlow()

    data class LeftoverValidationState(
        val original: FoodEntry,
        val leftovers: com.example.data.remote.FoodAnalysisResult,
        val consumedPreview: FoodEntry,
        val validation: com.example.data.meal.MealAnalysisOrchestrator.LeftoversValidation,
        val imagePath: String
    )

    private val _leftoverValidationPending = MutableStateFlow<LeftoverValidationState?>(null)
    val leftoverValidationPending: StateFlow<LeftoverValidationState?> =
        _leftoverValidationPending.asStateFlow()

    /**
     * Analyze leftovers photo and merge result into the original FoodEntry.
     * Flow: analyze leftovers → validate → (if plausible) merge → updateFood.
     * If implausible, emits validation state for UI dialog confirmation.
     *
     * @param currentEntry Original FoodEntry (must be persisted, id != 0)
     * @param base64Image Photo of leftovers (required)
     * @param imagePath Path where leftover photo was staged (not persisted)
     * @param mimeType Image mime type
     * @param onSuccess Called after successful update
     * @param onFailure Called on error
     */
    fun analyzeLeftovers(
        currentEntry: FoodEntry,
        base64Image: String,
        imagePath: String,
        mimeType: String = "image/jpeg",
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        if (currentEntry.id == 0L) {
            viewModelScope.launch {
                _oneShotSnack.emit("error_original_no_data")
            }
            onFailure("Entry not persisted")
            return
        }

        viewModelScope.launch {
            _isAnalyzingLeftovers.value = true
            _foodAnalysisPhase.value = com.example.data.meal.MealAnalysisOrchestrator.PHASE_ANALYZING_LEFTOVERS

            try {
                var streamedResult: com.example.data.remote.FoodAnalysisResult? = null
                var errorKey: String? = null

                // Collect stream from orchestrator
                mealOrchestrator.analyzeLeftoversStreaming(
                    currentEntry = currentEntry,
                    base64Image = base64Image,
                    language = _userProfile.value.language,
                    mimeType = mimeType
                ).collect { event ->
                    when (event) {
                        is com.example.data.meal.MealAnalysisEvent.Phase ->
                            _foodAnalysisPhase.value = event.key
                        is com.example.data.meal.MealAnalysisEvent.Done ->
                            streamedResult = event.result
                        is com.example.data.meal.MealAnalysisEvent.Error -> {
                            streamedResult = event.salvage
                            errorKey = event.messageKey
                        }
                        else -> { /* Ignore Partial events */ }
                    }
                }

                // Check if we got a result
                val result = streamedResult
                if (result == null) {
                    android.util.Log.e("FOOD_DIAG", "analyzeLeftovers: no result from stream")
                    _oneShotSnack.emit(tr("error_leftovers_analysis_failed", _userProfile.value.language))
                    onFailure("No result from analysis")
                    return@launch
                }

                android.util.Log.i("FOOD_DIAG", "analyzeLeftovers: got result, ${result.calories ?: 0} kcal leftovers")

                // Validate plausibility
                val validation = mealOrchestrator.validateLeftovers(currentEntry, result)
                android.util.Log.i("FOOD_DIAG", "analyzeLeftovers: validation isPlausible=${validation.isPlausible}, percent=${validation.leftoverPercent}%, warningKey=${validation.warningKey}")

                // Calculate preview of consumed (for UI display)
                val consumedPreview = mealOrchestrator.mergeLeftovers(
                    original = currentEntry,
                    leftoversResult = result,
                    language = _userProfile.value.language
                )

                // ALWAYS show result card for user confirmation (gives clear feedback
                // of what was detected and what will be saved). Card shows warning
                // badge if implausible, but user can confirm either way.
                _leftoverValidationPending.value = LeftoverValidationState(
                    original = currentEntry,
                    leftovers = result,
                    consumedPreview = consumedPreview,
                    validation = validation,
                    imagePath = imagePath
                )
                android.util.Log.i("FOOD_DIAG", "analyzeLeftovers: showing result card to user (plausible=${validation.isPlausible})")

            } catch (e: Exception) {
                android.util.Log.e("FOOD_DIAG", "analyzeLeftovers failed", e)
                _oneShotSnack.emit("error_leftovers_network_failed")
                onFailure(e.message ?: "Unknown error")
            } finally {
                _isAnalyzingLeftovers.value = false
                _foodAnalysisPhase.value = null
            }
        }
    }

    /**
     * Private helper to re-analyze and subtract leftovers from a meal entry.
     * Used by [confirmAndSaveFoodEntry] when the persisted leftovers card was
     * edited. Collects the streaming analysis and merges if successful.
     *
     * @param base The meal entry to subtract from (either the original via snapshot,
     *             or the just-reanalyzed dish if the summary was also edited)
     * @param leftoversText User-edited leftover text
     * @param language User's language setting
     * @return The merged entry with leftovers subtracted, or null on error
     */
    private suspend fun resubtractLeftovers(
        base: FoodEntry,
        leftoversText: String,
        language: String
    ): FoodEntry? {
        var streamedResult: com.example.data.remote.FoodAnalysisResult? = null
        var errorKey: String? = null

        mealOrchestrator.analyzeLeftoversFromTextStreaming(
            currentEntry = base,
            leftoversText = leftoversText,
            language = language
        ).collect { event ->
            when (event) {
                is com.example.data.meal.MealAnalysisEvent.Phase ->
                    _foodAnalysisPhase.value = event.key
                is com.example.data.meal.MealAnalysisEvent.Done ->
                    streamedResult = event.result
                is com.example.data.meal.MealAnalysisEvent.Error -> {
                    streamedResult = event.salvage
                    errorKey = event.messageKey
                }
                else -> { /* Ignore Partial events */ }
            }
        }

        val result = streamedResult ?: return null
        android.util.Log.i("FOOD_DIAG", "resubtractLeftovers: got result, ${result.calories ?: 0} kcal leftovers")

        return mealOrchestrator.mergeLeftovers(
            original = base,
            leftoversResult = result,
            language = language
        )
    }

    /**
     * Confirm leftovers anyway (user clicked "Confirm Adjustment" on result card).
     * Proceeds with merge despite plausibility warning.
     */
    fun confirmLeftoversAnyway() {
        val pending = _leftoverValidationPending.value ?: return
        viewModelScope.launch {
            try {
                // Use the pre-calculated consumedPreview instead of re-merging
                val withLog = pending.consumedPreview.appendEditLog(
                    type = com.example.data.local.FoodEditType.LEFTOVER,
                    model = pending.leftovers.analysisModel
                )

                // Update in DB
                repository.updateFood(withLog)
                android.util.Log.i("FOOD_DIAG", "confirmLeftoversAnyway: DB updated, entry id=${withLog.id}")

                // Update session state
                _pendingFoodSession.value = _pendingFoodSession.value.map { entry ->
                    if (entry.id == pending.original.id) withLog else entry
                }
                android.util.Log.i("FOOD_DIAG", "confirmLeftoversAnyway: session updated")

                // Clear the "summary edited" flag so a subsequent "Accept & Save"
                // does NOT trigger a fresh AI re-analysis that would overwrite the
                // subtracted macros. The leftover merge is authoritative. 2026-10-10.
                _summaryWasEditedAfterAnalysis.value = false
                _editedLeftoversText.value = null

                // Feedback to user
                val kcalDiff = (pending.original.calories ?: 0) - (pending.consumedPreview.calories ?: 0)
                val message = tr("snack_leftovers_adjusted", _userProfile.value.language)
                    .replace("{kcal}", kcalDiff.toString())
                _oneShotSnack.emit(message)
                android.util.Log.i("FOOD_DIAG", "confirmLeftoversAnyway: snackbar shown, diff=$kcalDiff kcal")
            } finally {
                _leftoverValidationPending.value = null
            }
        }
    }

    /**
     * Cancel leftovers confirmation dialog.
     */
    fun cancelLeftovers() {
        _leftoverValidationPending.value = null
    }

    // ── Delete leftovers / revert meal to its pre-adjustment state ──────────
    // The persisted leftovers card has an X. Tapping it asks for confirmation
    // (dialog driven by _leftoverDeletePending); confirming restores the meal
    // exactly from the snapshot stored in leftoverOriginalJson. 2026-10-10.
    private val _leftoverDeletePending = MutableStateFlow(false)
    val leftoverDeletePending = _leftoverDeletePending.asStateFlow()

    fun requestDeleteLeftovers() {
        _leftoverDeletePending.value = true
    }

    fun cancelDeleteLeftovers() {
        _leftoverDeletePending.value = false
    }

    fun confirmDeleteLeftovers() {
        val entry = _pendingFoodSession.value.firstOrNull()
        if (entry == null) {
            _leftoverDeletePending.value = false
            return
        }
        val snap = com.example.data.local.decodeLeftoverSnapshot(entry.leftoverOriginalJson)
        if (snap == null) {
            android.util.Log.e("FOOD_DIAG", "confirmDeleteLeftovers: no snapshot to revert from")
            _leftoverDeletePending.value = false
            return
        }
        viewModelScope.launch {
            try {
                val restored = entry.restoreFromSnapshot(snap)
                    .appendEditLog(type = com.example.data.local.FoodEditType.LEFTOVER_REMOVED, model = null)
                repository.updateFood(restored)
                _pendingFoodSession.value = _pendingFoodSession.value.map {
                    if (it.id == entry.id) restored else it
                }
                _summaryWasEditedAfterAnalysis.value = false
                _editedLeftoversText.value = null
                _oneShotSnack.emit(tr("snack_leftovers_removed", _userProfile.value.language))
                android.util.Log.i(
                    "FOOD_DIAG",
                    "confirmDeleteLeftovers: reverted entry ${entry.id} to ${snap.calories} kcal"
                )
            } catch (e: Exception) {
                android.util.Log.e("FOOD_DIAG", "confirmDeleteLeftovers failed", e)
            } finally {
                _leftoverDeletePending.value = false
            }
        }
    }

    /**
     * Shared merge + update logic for leftovers.
     */
    private suspend fun performLeftoverMergeAndUpdate(
        original: FoodEntry,
        leftoversResult: com.example.data.remote.FoodAnalysisResult,
        onSuccess: () -> Unit
    ) {
        android.util.Log.i("FOOD_DIAG", "performLeftoverMergeAndUpdate: original=${original.calories} kcal, leftovers=${leftoversResult.calories} kcal")

        // Merge leftovers
        val consumed = mealOrchestrator.mergeLeftovers(
            original = original,
            leftoversResult = leftoversResult,
            language = _userProfile.value.language
        )

        android.util.Log.i("FOOD_DIAG", "performLeftoverMergeAndUpdate: consumed=${consumed.calories} kcal after merge")

        // Append edit log
        val withLog = consumed.appendEditLog(
            type = com.example.data.local.FoodEditType.LEFTOVER,
            model = leftoversResult.analysisModel
        )

        // Update in DB
        repository.updateFood(withLog)
        android.util.Log.i("FOOD_DIAG", "performLeftoverMergeAndUpdate: DB updated, entry id=${withLog.id}")

        // Update session state so UI reflects changes immediately
        _pendingFoodSession.value = _pendingFoodSession.value.map { entry ->
            if (entry.id == original.id) withLog else entry
        }
        android.util.Log.i("FOOD_DIAG", "performLeftoverMergeAndUpdate: session updated")

        // Feedback to user
        val kcalDiff = (original.calories ?: 0) - (consumed.calories ?: 0)
        val message = tr("snack_leftovers_adjusted", _userProfile.value.language)
            .replace("{kcal}", kcalDiff.toString())
        _oneShotSnack.emit(message)
        android.util.Log.i("FOOD_DIAG", "performLeftoverMergeAndUpdate: snackbar shown, diff=$kcalDiff kcal")

        onSuccess()
    }

    /**
     * Cancel any in-flight food analysis stream. Called from the
     * "Cancelar análise" button on the capture card and from the typing
     * bubble's onCancel in the edit-meal screen. Clears phase + progress
     * state so the UI snaps back to the idle capture form.
     */
    fun cancelFoodAnalysis() {
        foodAnalysisJob?.cancel()
        foodAnalysisJob = null
        _foodAnalysisPhase.value = null
        _foodAnalysisProgress.value = com.example.data.meal.FoodAnalysisProgress()
        _isAnalyzingFood.value = false
        _isSavingEntry.value = false
    }

    /**
     * Re-fire the last food-analysis request after a transient failure.
     * Wired to the Retry button on the inline error card. No-op if there
     * was never a previous request (defensive).
     */
    fun retryLastFoodAnalysis() {
        val req = lastFoodAnalysisRequest ?: return
        _foodAnalysisError.value = null
        startFoodAnalysisStream(req)
    }

    /** Mark that the user edited `fullSummary` after the initial analysis;
     *  consumed by [confirmAndSaveFoodEntry] to gate the re-analysis stream. */
    fun markSummaryEdited() {
        _summaryWasEditedAfterAnalysis.value = true
    }

    /** Mirror of [markSummaryEdited] for the activity edit screen — wired by
     *  the workout-summary TextField onValueChange so
     *  [confirmAndSaveActivitySession] knows to re-stream the LLM analysis. */
    fun markActivitySummaryEdited() {
        _summaryWasEditedAfterActivityAnalysis.value = true
    }

    private fun startFoodAnalysisStream(
        request: FoodAnalysisRequest,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        // Memory [[proposal-ingest-idempotency-bug]]: cancel any in-flight job
        // before launching a new one so we never have two collectors racing.
        foodAnalysisJob?.cancel()
        lastFoodAnalysisRequest = request
        _foodAnalysisError.value = null
        _foodAnalysisPhase.value = com.example.data.meal.MealAnalysisOrchestrator.PHASE_UPLOADING
        _foodAnalysisProgress.value = com.example.data.meal.FoodAnalysisProgress()
        if (!request.appendToSession && request.replaceIndex == null) {
            _analysisError.value = null
        }

        foodAnalysisJob = viewModelScope.launch {
            _isAnalyzingFood.value = true
            try {
                mealOrchestrator.analyzeFoodImageStreaming(
                    base64Image = request.base64Image,
                    mimeType = request.mimeType,
                    promptHint = request.promptHint,
                    language = _userProfile.value.language
                ).collect { event ->
                    when (event) {
                        is com.example.data.meal.MealAnalysisEvent.Phase -> {
                            _foodAnalysisPhase.value = event.key
                        }
                        is com.example.data.meal.MealAnalysisEvent.Partial -> {
                            _foodAnalysisProgress.value = event.progress
                        }
                        is com.example.data.meal.MealAnalysisEvent.Done -> {
                            if (event.result.proAttempted && !event.result.escalated) {
                                _oneShotSnack.tryEmit("snack_pro_unavailable")
                            }
                            applyFoodAnalysisResult(event.result, request, onSuccess)
                        }
                        is com.example.data.meal.MealAnalysisEvent.Error -> {
                            handleFoodAnalysisError(event, request, onFailure)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val msg = t.message ?: tr("Unknown analysis error", _userProfile.value.language)
                android.util.Log.e(
                    "FOOD_DIAG",
                    "startFoodAnalysisStream UNCAUGHT: ${t.javaClass.simpleName}:'${t.message?.take(300)}' " +
                        "hasImage=${request.imagePath != null}",
                    t
                )
                persistDiagnostic(
                    tag = "food_analysis_uncaught",
                    data = "${t.javaClass.simpleName}:${t.message?.take(300)}"
                )
                _analysisError.value = msg
                onFailure(msg)
            } finally {
                _isAnalyzingFood.value = false
                _foodAnalysisPhase.value = null
            }
        }
    }

    private fun applyFoodAnalysisResult(
        result: FoodAnalysisResult,
        request: FoodAnalysisRequest,
        onSuccess: () -> Unit
    ) {
        _lastAnalyzedResult.value = result
        val rawSummary = result.fullSummary ?: result.analysisSummary ?: ""
        // Encode once; reused for storage and for the gram-weight repair.
        val ingredientsJson = com.example.data.remote.encodeIngredients(result.ingredients)
        // Deterministic gram-weight repair: gemini-3.5-flash often returns a
        // one-line fullSummary without the per-ingredient (~Xg) weights the
        // prompt requires; rebuild it from ingredientsJson (weightGrams is
        // reliably emitted). No-op when the model already complied.
        val summaryText = com.example.data.remote.FullSummaryRepair.repair(rawSummary, ingredientsJson, _userProfile.value.language) ?: rawSummary
        val timestamp = System.currentTimeMillis()
        // 2026-06-18 — meal category from clock, not from the LLM. The LLM's
        // call was effectively random; Hugo's mental model is the hour of
        // day. DESSERT is the one exception (content judgement, hour-agnostic).
        val llmCategory = com.example.data.local.MealCategory.parse(result.mealCategory)
        val resolvedCategory =
            com.example.data.local.MealCategoryFromTime.classify(timestamp, llmCategory)
        val entry = FoodEntry(
            name = result.name ?: "Custom Food",
            calories = result.calories ?: 0,
            protein = result.protein ?: 0.0,
            carbs = result.carbs ?: 0.0,
            fat = result.fat ?: 0.0,
            timestamp = timestamp,
            imagePath = request.imagePath,
            analysisSummary = summaryText,
            fullSummary = summaryText,
            lastAiAnalyzedSummary = summaryText,
            mealCategory = resolvedCategory,
            appliedHabitIds = result.appliedHabitIds,
            ingredientsJson = ingredientsJson,
            freeSugarsG = result.freeSugarsG
                ?.coerceAtMost(result.carbs ?: 0.0)
                ?.coerceAtLeast(0.0),
            saturatedFatG = result.saturatedFatG
                ?.coerceAtMost(result.fat ?: 0.0)
                ?.coerceAtLeast(0.0),
            // Persist which LLM produced this analysis so the badge in
            // ConfidenceChip survives app restarts (result.analysisModel
            // only lived in-memory before).
            analysisModel = result.analysisModel
        )
        _pendingFoodSession.value = when {
            request.replaceIndex != null -> {
                val list = _pendingFoodSession.value
                if (list.isEmpty()) {
                    listOf(entry)
                } else {
                    val idx = request.replaceIndex.coerceIn(0, list.size - 1)
                    replaceSlotPreservingIdentity(list, idx, entry)
                }
            }
            request.appendToSession -> _pendingFoodSession.value + entry
            else -> listOf(entry)
        }
        // Fresh draft: the user has not yet edited the summary, so the
        // re-analysis branch in confirmAndSaveFoodEntry is gated off until
        // markSummaryEdited() is called.
        _summaryWasEditedAfterAnalysis.value = false
        _editedLeftoversText.value = null
        onSuccess()
    }

    private fun handleFoodAnalysisError(
        event: com.example.data.meal.MealAnalysisEvent.Error,
        request: FoodAnalysisRequest,
        onFailure: (String) -> Unit
    ) {
        val lang = _userProfile.value.language
        val resolved = tr(event.messageKey, lang)
        // ── Diagnostic breadcrumb (persistent) ──────────────────────────────
        android.util.Log.e(
            "FOOD_DIAG",
            "handleFoodAnalysisError: messageKey='${event.messageKey}' " +
                "cause=${event.cause?.javaClass?.simpleName}:'${event.cause?.message?.take(200)}' " +
                "salvage=${event.salvage != null} " +
                "salvageName=${event.salvage?.name?.take(50)} " +
                "hasImage=${request.imagePath != null}"
        )
        event.cause?.let { android.util.Log.e("FOOD_DIAG", "stackTrace:", it) }
        persistDiagnostic(
            tag = "food_analysis_error",
            data = "key=${event.messageKey} cause=${event.cause?.javaClass?.simpleName}:" +
                "${event.cause?.message?.take(300)} salvage=${event.salvage != null}"
        )
        _foodAnalysisError.value = com.example.data.meal.FoodAnalysisError(
            messageKey = event.messageKey,
            resolvedMessage = resolved,
            cause = event.cause,
            salvage = event.salvage
        )
        // Salvage path: surface what we got so the user can edit + save
        // instead of losing the whole turn. We DO NOT fire onFailure here
        // — the callsite's onFailure path replaces pendingFoodEntry with a
        // hard-coded "Uploaded Meal (Manual Input pending)" fallback that
        // would clobber the salvaged data.
        val salvage = event.salvage
        if (salvage != null) {
            applyFoodAnalysisResult(salvage, request, onSuccess = {})
            return
        }
        if (request.imagePath != null) {
            _perItemAnalysisErrors.value =
                _perItemAnalysisErrors.value + (request.imagePath to resolved)
        }
        if (!request.appendToSession && request.replaceIndex == null) {
            _analysisError.value = resolved
        }
        onFailure(resolved)
    }

    // ── Persistent diagnostic store ─────────────────────────────────────────
    // Appends diagnostic breadcrumbs to SharedPreferences so they survive
    // process death. Keeps last 20 entries to avoid unbounded growth.
    // Read with: adb shell "run-as com.aistudio.geminichat.rvqzmx cat shared_prefs/food_diag.xml"
    private fun persistDiagnostic(tag: String, data: String) {
        try {
            val prefs = (cachedAppContext ?: return)
                .getSharedPreferences("food_diag", android.content.Context.MODE_PRIVATE)
            val ts = java.time.Instant.now().toString()
            val entry = "[$ts] $tag: $data"
            val existing = prefs.getString("log", "") ?: ""
            val lines = existing.lines().takeLast(19) + entry
            prefs.edit().putString("log", lines.joinToString("\n")).apply()
        } catch (_: Throwable) { /* never crash for diagnostics */ }
    }

    /**
     * Analyse N images in parallel and replace the session with their drafts.
     * Backs the multi-select photo picker. Failed items are dropped from the
     * session list but recorded in [_perItemAnalysisErrors] so the UI can show
     * which photos didn't analyse. If every photo fails, the session stays
     * empty and the global [_analysisError] reflects the last failure.
     *
     * Caller is responsible for decoding/persisting the photos to filesDir
     * sequentially (to avoid OOM with many large bitmaps); only the network
     * round-trip to Gemini runs in parallel here.
     */
    fun analyzeFoodImagesParallel(
        photos: List<PendingFoodPhoto>,
        promptHint: String,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        if (photos.isEmpty()) return
        viewModelScope.launch {
            _isAnalyzingFood.value = true
            _analysisError.value = null
            _perItemAnalysisErrors.value = emptyMap()
            try {
                val deferred = photos.map { photo ->
                    async {
                        photo to repository.analyzeFoodImage(
                            base64Image = photo.base64,
                            mimeType = photo.mimeType,
                            promptHint = promptHint,
                            language = _userProfile.value.language
                        )
                    }
                }
                val results = deferred.awaitAll()

                val drafts = mutableListOf<FoodEntry>()
                val errors = mutableMapOf<String, String>()
                var lastError: String? = null
                for ((photo, res) in results) {
                    res.onSuccess { result ->
                        _lastAnalyzedResult.value = result
                        val rawSummary = result.fullSummary ?: result.analysisSummary ?: ""
                        // Same gram-weight repair as [applyFoodAnalysisResult]
                        // — the batch path must not persist one-line
                        // summaries without per-ingredient weights.
                        val ingredientsJson = com.example.data.remote.encodeIngredients(result.ingredients)
                        val summaryText = com.example.data.remote.FullSummaryRepair.repair(rawSummary, ingredientsJson, _userProfile.value.language) ?: rawSummary
                        // Clock-based category override (see [[MealCategoryFromTime]]).
                        val timestamp = System.currentTimeMillis()
                        val llmCategory = com.example.data.local.MealCategory.parse(result.mealCategory)
                        val resolvedCategory = com.example.data.local.MealCategoryFromTime
                            .classify(timestamp, llmCategory)
                        drafts += FoodEntry(
                            name = result.name ?: "Custom Food",
                            calories = result.calories ?: 0,
                            protein = result.protein ?: 0.0,
                            carbs = result.carbs ?: 0.0,
                            fat = result.fat ?: 0.0,
                            timestamp = timestamp,
                            imagePath = photo.imagePath,
                            analysisSummary = summaryText,
                            fullSummary = summaryText,
                            lastAiAnalyzedSummary = summaryText,
                            mealCategory = resolvedCategory,
                            appliedHabitIds = result.appliedHabitIds,
                            ingredientsJson = ingredientsJson,
                            freeSugarsG = result.freeSugarsG
                                ?.coerceAtMost(result.carbs ?: 0.0)
                                ?.coerceAtLeast(0.0),
                            saturatedFatG = result.saturatedFatG
                                ?.coerceAtMost(result.fat ?: 0.0)
                                ?.coerceAtLeast(0.0),
                            analysisModel = result.analysisModel
                        )
                    }.onFailure { exception ->
                        val msg = exception.message ?: tr("Unknown analysis error", _userProfile.value.language)
                        if (photo.imagePath != null) errors[photo.imagePath] = msg
                        lastError = msg
                    }
                }

                _pendingFoodSession.value = drafts
                _perItemAnalysisErrors.value = errors
                if (drafts.isEmpty() && lastError != null) {
                    _analysisError.value = lastError
                    onFailure(lastError!!)
                } else {
                    onSuccess()
                }
            } finally {
                _isAnalyzingFood.value = false
            }
        }
    }

    fun analyzeAndLogActivity(
        base64Image: String?,
        promptHint: String,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isAnalyzingActivity.value = true
            _activityAnalysisError.value = null
            repository.analyzeActivityImage(
                base64Image = base64Image,
                promptHint = promptHint,
                language = _userProfile.value.language
            ).onSuccess { result ->
                val sId = java.util.UUID.randomUUID().toString()
                val summaryText = result.fullSummary ?: result.analysisSummary ?: ""
                val list = result.exercises?.map { exerciseDto ->
                    ActivityEntry(
                        name = exerciseDto.name,
                        durationMinutes = exerciseDto.durationMinutes,
                        caloriesBurned = exerciseDto.caloriesBurned,
                        timestamp = System.currentTimeMillis(),
                        sessionId = sId,
                        fullSummary = summaryText,
                        lastAiAnalyzedSummary = summaryText,
                        activityType = com.example.data.local.ActivityType.fromName(exerciseDto.activityType),
                        notes = exerciseDto.notes
                    )
                } ?: listOf(
                    ActivityEntry(
                        name = result.name ?: "Custom Activity",
                        durationMinutes = result.durationMinutes ?: 30,
                        caloriesBurned = result.caloriesBurned ?: 150,
                        timestamp = System.currentTimeMillis(),
                        sessionId = sId,
                        fullSummary = summaryText,
                        lastAiAnalyzedSummary = summaryText,
                        activityType = com.example.data.local.ActivityType.fromName(result.name ?: "Other"),
                        notes = null
                    )
                )
                _pendingActivitySession.value = list
                _isAnalyzingActivity.value = false
                onSuccess()
            }.onFailure { exception ->
                val errorMsg = exception.message ?: tr("Unknown analysis error", _userProfile.value.language)
                _activityAnalysisError.value = errorMsg
                _isAnalyzingActivity.value = false
                onFailure(errorMsg)
            }
        }
    }

    // List of all conversations
    val sessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently selected session ID
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    // Stream messages for the active/selected session
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeMessages: StateFlow<List<ChatMessage>> = _selectedSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessagesForSessionFlow(sessionId)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Details of the currently active session
    val activeSession: StateFlow<ChatSession?> = combine(sessions, _selectedSessionId) { list, id ->
        list.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // UI Input State
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // Model configurations for *new* session creation
    private val _selectedModel = MutableStateFlow("gemini-3.5-flash")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // ── Food capture session ──
    // Source of truth for in-flight food entries during a capture session. A
    // session can hold 1 item (legacy single-photo flow) or N items (multi-photo
    // flow added in v11 — picker-multi-select or "Add another photo" button).
    //
    // The legacy `pendingFoodEntry` API stays compatible: when the UI reads it
    // we project the session as a singleton (item 0 or null). Existing call
    // sites that update by name keep working — they edit the head item.
    private val _pendingFoodSession = MutableStateFlow<List<FoodEntry>>(emptyList())
    val pendingFoodSession: StateFlow<List<FoodEntry>> = _pendingFoodSession.asStateFlow()

    // Per-photo analysis errors, keyed by image path. Lets the UI render an
    // error banner inside individual cards while still allowing manual entry
    // for the failed items and saving the rest. Empty in single-photo mode.
    private val _perItemAnalysisErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val perItemAnalysisErrors: StateFlow<Map<String, String>> = _perItemAnalysisErrors.asStateFlow()

    /** Legacy projection of the session head — kept for the existing single-item
     *  edit/save UI in ChatScreen. When the session is empty this is null;
     *  otherwise it's the first (and usually only) draft. */
    val pendingFoodEntry: StateFlow<FoodEntry?> = _pendingFoodSession
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Food habits — user-taught dietary patterns. Drives habit-aware analysis
    // and the FoodHabitsScreen. `allHabits` includes inactive ones for the
    // management screen; `habitsById` is a fast lookup for badges.
    val foodHabits: StateFlow<List<FoodHabit>> = repository.allHabits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val habitsById: StateFlow<Map<Long, FoodHabit>> = foodHabits
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun saveFoodHabit(habit: FoodHabit) {
        viewModelScope.launch { repository.upsertHabit(habit) }
    }

    fun deleteFoodHabit(habit: FoodHabit) {
        viewModelScope.launch { repository.deleteHabit(habit) }
    }

    fun setFoodHabitActive(id: Long, active: Boolean) {
        viewModelScope.launch { repository.setHabitActive(id, active) }
    }

    /** Per-chat-message habit proposals parsed from the assistant's reply.
     *  Key = ChatMessage.id. Items are removed once Confirm/Discard is invoked
     *  so the inline card disappears after the user acts. */
    private val _pendingHabitProposals = MutableStateFlow<Map<Long, List<HabitProposal>>>(emptyMap())
    val pendingHabitProposals: StateFlow<Map<Long, List<HabitProposal>>> = _pendingHabitProposals.asStateFlow()

    /** Phase D: parsed [LOG_MEAL_PROPOSAL] blocks awaiting user confirmation. */
    data class PendingMealProposal(
        val proposal: com.example.data.remote.LogMealProposal,
        /** Recent FoodEntry whose name matches this proposal closely (Levenshtein
         *  ≥ 0.7) within the last 24h, or null when the meal is novel. Drives the
         *  duplicate-warning banner in the inline card. */
        val duplicateOf: FoodEntry?
    )
    private val _pendingMealProposals = MutableStateFlow<Map<Long, List<PendingMealProposal>>>(emptyMap())
    val pendingMealProposals: StateFlow<Map<Long, List<PendingMealProposal>>> = _pendingMealProposals.asStateFlow()

    /** Phase D: parsed [LOG_ACTIVITY_PROPOSAL] blocks awaiting user confirmation. */
    private val _pendingActivityProposals = MutableStateFlow<Map<Long, List<com.example.data.remote.LogActivityProposal>>>(emptyMap())
    val pendingActivityProposals: StateFlow<Map<Long, List<com.example.data.remote.LogActivityProposal>>> = _pendingActivityProposals.asStateFlow()

    /** Phase E: parsed [INSIGHT_PROPOSAL] blocks awaiting user confirmation.
     *  Auto-confirmed insights (user_stated + confidence ≥0.85) are persisted
     *  silently and never appear here. */
    private val _pendingInsightProposals = MutableStateFlow<Map<Long, List<com.example.data.remote.InsightProposal>>>(emptyMap())
    val pendingInsightProposals: StateFlow<Map<Long, List<com.example.data.remote.InsightProposal>>> = _pendingInsightProposals.asStateFlow()

    /**
     * 2026-06-29 — Frente Memory C4. All non-expired UserInsight rows
     * (both confirmed and pending). Used by [com.example.ui.MemoryDialog]
     * to render the full "what I remember about you" view with sections.
     *
     * Backed by [com.example.data.local.UserInsightDao.allActiveFlow] so
     * the dialog updates immediately when the user confirms / discards /
     * forgets a row (or when a new auto-extracted candidate lands).
     */
    val allActiveInsights: StateFlow<List<com.example.data.local.UserInsight>> =
        repository.allActiveInsights()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** 2026-06-29 — Frente C4. User tapped Confirm on a pending insight in
     *  MemoryDialog. Flips `confirmedAtMs` to now so the recall ranker
     *  starts surfacing the fact. */
    fun confirmInsight(id: Long) {
        viewModelScope.launch { runCatching { repository.confirmUserInsight(id) } }
    }

    /** 2026-06-29 — Frente C4. User tapped Discard (pending) or Forget
     *  (active) in MemoryDialog. Soft-deletes via `expiredAtMs`. The row
     *  stays in the DB — never DELETE — so the audit trail survives. */
    fun discardInsight(id: Long) {
        viewModelScope.launch { runCatching { repository.expireUserInsight(id) } }
    }

    /** 2026-06-29 — MemoryDialog polish. Restores a soft-deleted insight
     *  (sets `expiredAtMs` back to null) so it rejoins the recall pool.
     *  Used by the "Restore" button on the expired-collapsible section. */
    fun restoreInsight(id: Long) {
        viewModelScope.launch { runCatching { repository.restoreUserInsight(id) } }
    }

    /** 2026-06-29 — MemoryDialog polish. ALL insights including soft-deleted
     *  ones. Used by the Memory screen to render the "Forgotten" collapsible
     *  section so the user can see (and optionally restore) facts that were
     *  previously soft-deleted. */
    val allInsightsIncludingExpired: StateFlow<List<com.example.data.local.UserInsight>> =
        repository.allInsightsIncludingExpired()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** 2026-06-29 — MemoryDialog polish. Read-only view of the cross-session
     *  carryover digest persisted in [com.example.data.chat.CarryoverDigest].
     *  Exposed as a `() -> String` rather than a StateFlow because the
     *  underlying SharedPreferences doesn't emit on change; the dialog
     *  reads-once on open, which is fine for an audit view. Returns empty
     *  string when nothing has been digested yet or when context is null. */
    fun readCarryoverDigest(): String =
        cachedAppContext?.let { com.example.data.chat.CarryoverDigest.load(it) } ?: ""

    /** Phase G (reformulado): parsed [GENERATE_REPORT_PROPOSAL] blocks. The
     *  user taps "Generate" to fire the export pipeline; "Discard" removes
     *  the card with no side-effect. Drive folder must already be linked
     *  via the Reports tab — surfaced as an inline error otherwise. */
    private val _pendingReportProposals = MutableStateFlow<Map<Long, List<com.example.data.remote.GenerateReportProposal>>>(emptyMap())
    val pendingReportProposals: StateFlow<Map<Long, List<com.example.data.remote.GenerateReportProposal>>> = _pendingReportProposals.asStateFlow()

    /** Phase 3 (chat-redesign 2026-06-28): parsed [CHART_PROPOSAL] blocks.
     *  Informational only — no Confirm/Discard, the chart simply renders
     *  below the assistant bubble for the lifetime of the session. */
    private val _pendingChartProposals = MutableStateFlow<Map<Long, List<com.example.data.remote.ChartProposal>>>(emptyMap())
    val pendingChartProposals: StateFlow<Map<Long, List<com.example.data.remote.ChartProposal>>> = _pendingChartProposals.asStateFlow()

    /** A breadcrumb left in place of a proposal card after the user has acted
     *  on it (or when the conversation moved on without an answer). Rendered
     *  as a single discrete line beneath the assistant bubble so the user
     *  can see what happened to each proposal that was offered.
     *
     *  - [Outcome.CONFIRMED] — tapped Confirm, the side-effect succeeded
     *    (habit upserted / meal logged / etc.).
     *  - [Outcome.DISCARDED] — tapped Discard explicitly.
     *  - [Outcome.DISMISSED] — implicitly dropped because the user sent
     *    another chat message instead of acting on the card.
     *
     *  [Kind] decides the icon + verb on the line; [label] is whatever
     *  identifies the proposal to a human (habit descriptor, meal name,
     *  insight text head, etc.).
     */
    enum class ProposalKind { HABIT, MEAL, ACTIVITY, INSIGHT, REPORT }
    enum class ProposalOutcome { CONFIRMED, DISCARDED, DISMISSED }
    data class ProposalReceipt(
        val kind: ProposalKind,
        val outcome: ProposalOutcome,
        val label: String,
        /** Stable enough to dedup if the same (kind,label) is re-receipted —
         *  monotonic counter, no collision concern. */
        val seq: Long
    )
    private val _proposalReceipts = MutableStateFlow<Map<Long, List<ProposalReceipt>>>(emptyMap())
    val proposalReceipts: StateFlow<Map<Long, List<ProposalReceipt>>> = _proposalReceipts.asStateFlow()
    private var receiptSeq = 0L
    private fun addReceipt(messageId: Long, kind: ProposalKind, outcome: ProposalOutcome, label: String) {
        val r = ProposalReceipt(kind, outcome, label, ++receiptSeq)
        _proposalReceipts.update { current ->
            current + (messageId to (current[messageId].orEmpty() + r))
        }
    }

    /** Cleaned versions of assistant messages (with [HABIT_PROPOSAL] markers
     *  stripped). Falls back to the raw message text when no override exists. */
    private val _cleanedMessageText = MutableStateFlow<Map<Long, String>>(emptyMap())
    val cleanedMessageText: StateFlow<Map<Long, String>> = _cleanedMessageText.asStateFlow()

    /** TTS mode (2026-10-05): per-message spoken script extracted from
     *  `[TTS_SUMMARY]` blocks, keyed by ChatMessage.id. Populated by
     *  [ingestAssistantMessage] (covers both the live collect and the
     *  session-history load path, since both re-run the parser over the
     *  persisted RAW text — the script survives app restarts). TTS playback
     *  prefers this over [cleanedMessageText]; a missing entry simply falls
     *  back to the pre-existing behaviour. Cleared with the rest of the
     *  per-message maps on session delete (see the purge site below). */
    private val _ttsSummaryText = MutableStateFlow<Map<Long, String>>(emptyMap())
    val ttsSummaryText: StateFlow<Map<Long, String>> = _ttsSummaryText.asStateFlow()

    /** Phase 2 of chat-redesign 2026-06-12 (#1b — hallucination warning).
     *  Set to `true` for assistant message ids whose text reads like the model
     *  claimed it persisted a habit but **no** [HABIT_PROPOSAL] block was
     *  emitted. Drives the inline amber warning card under the offending
     *  bubble. The user dismisses it via [dismissHabitWarning]. */
    private val _pendingHabitWarnings = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val pendingHabitWarnings: StateFlow<Map<Long, Boolean>> = _pendingHabitWarnings.asStateFlow()

    fun dismissHabitWarning(messageId: Long) {
        _pendingHabitWarnings.update { it - messageId }
    }

    /** Set of assistant `ChatMessage.id`s whose text has already been parsed
     *  into proposals. `activeMessages` re-emits on every Room change to
     *  `chat_messages` (a session switch with WhileSubscribed re-share, an
     *  edit, a delete, a `editedTimestamp` write) and re-walks the whole
     *  list — without this guard the same proposals would be re-injected
     *  into `_pendingHabitProposals` etc. AFTER the user already tapped
     *  Confirm / Discard, making the inline card pop back. Pruned by
     *  `pruneCachesForDeletedIds` when a message is removed by
     *  edit-and-resubmit / delete-and-after; otherwise grows
     *  monotonically (cheap — one Long per assistant message). Room
     *  message ids are globally unique so we don't need to reset on
     *  session change. */
    private val ingestedMessageIds = mutableSetOf<Long>()

    /** Parse a freshly-received assistant message and stash any proposals +
     *  cleaned text. Idempotent for the same id — guarded by
     *  [ingestedMessageIds] so confirm/discard sticks. */
    fun ingestAssistantMessage(messageId: Long, rawText: String) {
        if (messageId == 0L) return
        if (!ingestedMessageIds.add(messageId)) return
        // Phase D: use the richer parser that also extracts LOG_MEAL/LOG_ACTIVITY.
        val parsed = com.example.data.remote.ActionProposalParser.parse(rawText)
        if (parsed.habitProposals.isNotEmpty()) {
            _pendingHabitProposals.update { it + (messageId to parsed.habitProposals) }
        } else if (com.example.data.remote.HabitHallucinationDetector.looksLikeFakeSave(rawText)) {
            // Phase 4 v3 (2026-06-13): logging-only. The amber warning UI was
            // removed — the user shouldn't have to "reword" anything; the
            // parser now salvages malformed [SAVE_HABIT:...] markers into
            // legitimate proposals (HabitProposalParser.tryExtractFromBogus).
            // This branch only fires when the model claimed persistence in
            // natural language but emitted neither a canonical block NOR a
            // salvageable malformed marker — i.e. pure verbal hallucination.
            // We log it for prompt iteration; the user sees nothing.
            android.util.Log.w(
                "HABIT",
                "Possible hallucination — fakeSave detector matched but no salvage was emitted (messageId=$messageId): ${rawText.take(120)}"
            )
        }
        if (parsed.mealProposals.isNotEmpty()) {
            // De-dup against the last 24h of food entries. Done synchronously
            // off the in-memory snapshot so the card appears in the same frame.
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val recent = foods.value.filter { it.timestamp >= cutoff }
            val annotated = parsed.mealProposals.map { p ->
                PendingMealProposal(
                    proposal = p,
                    duplicateOf = com.example.data.local.MealSimilarity.findRecentSimilar(recent, p.name)
                )
            }
            _pendingMealProposals.update { it + (messageId to annotated) }
        }
        if (parsed.activityProposals.isNotEmpty()) {
            _pendingActivityProposals.update { it + (messageId to parsed.activityProposals) }
        }
        // Phase G: ad-hoc report request. Always surfaced as a card; we never
        // auto-fire the export — Hugo wants a chance to discard.
        if (parsed.reportProposals.isNotEmpty()) {
            _pendingReportProposals.update { it + (messageId to parsed.reportProposals) }
        }
        // Phase 3 (2026-06-28): inline chart cards. No persistence, no
        // confirmation — purely visual. The card sticks to its bubble for
        // the lifetime of the session.
        if (parsed.chartProposals.isNotEmpty()) {
            _pendingChartProposals.update { it + (messageId to parsed.chartProposals) }
        }
        // Phase E: insights — auto-confirm the high-confidence user-stated ones,
        // queue the rest as inline cards.
        if (parsed.insightProposals.isNotEmpty()) {
            val (auto, manual) = parsed.insightProposals.partition { it.autoConfirms }
            if (auto.isNotEmpty()) {
                viewModelScope.launch {
                    val now = System.currentTimeMillis()
                    auto.forEach { p ->
                        repository.insertUserInsight(
                            com.example.data.local.UserInsight(
                                text = p.text,
                                category = p.category,
                                confidence = p.confidence,
                                source = p.source,
                                sourceSessionId = _selectedSessionId.value,
                                confirmedAtMs = now
                            )
                        )
                    }
                }
            }
            if (manual.isNotEmpty()) {
                _pendingInsightProposals.update { it + (messageId to manual) }
            }
        }
        // Always populate so speakMessage() never falls back to raw msg.text
        _cleanedMessageText.update { it + (messageId to parsed.cleanedText) }
        // TTS mode: the spoken script (when the model emitted one) is keyed
        // alongside the cleaned text. Because this runs over the persisted
        // RAW text on BOTH the live collect and the history-load path, the
        // script survives app restarts — the 🔊 button and Voice Mode pick it
        // up from the map, never from the visible (stripped) text.
        if (!parsed.ttsSummary.isNullOrBlank()) {
            _ttsSummaryText.update { it + (messageId to parsed.ttsSummary) }
        }
    }

    fun confirmHabitProposal(messageId: Long, proposal: HabitProposal) {
        android.util.Log.i(
            "HABIT",
            "User confirmed habit: trigger='${proposal.trigger}', descriptor='${proposal.descriptor}', mealHint=${proposal.mealCategoryHint}"
        )
        viewModelScope.launch {
            repository.upsertHabit(
                com.example.data.local.FoodHabit(
                    trigger = proposal.trigger,
                    descriptor = proposal.descriptor,
                    mealCategoryHint = proposal.mealCategoryHint
                )
            )
            addReceipt(messageId, ProposalKind.HABIT, ProposalOutcome.CONFIRMED, proposal.descriptor)
            removeProposal(messageId, proposal)
        }
    }

    fun discardHabitProposal(messageId: Long, proposal: HabitProposal) {
        android.util.Log.i(
            "HABIT",
            "User discarded habit: trigger='${proposal.trigger}', descriptor='${proposal.descriptor}'"
        )
        addReceipt(messageId, ProposalKind.HABIT, ProposalOutcome.DISCARDED, proposal.descriptor)
        removeProposal(messageId, proposal)
    }

    /** Phase D: persist a meal proposal as a new FoodEntry. The chat-session
     *  id is stored in groupId as `chat:<sessionId>` so it's traceable back to
     *  the conversation that produced it. Memory `[[user-choice-wins-after-llm-reanalysis]]`:
     *  no caller mutates the macros here, but adding an "edit before confirm"
     *  UI later would override the LLM-suggested numbers verbatim.
     *
     *  Equivalence with manual capture (added 2026-06-22): when the LLM has
     *  filled the optional fields (`description` / `ingredients` /
     *  `appliedHabitIds`) and there is a recent image in the chat context,
     *  we want the resulting FoodEntry to be indistinguishable from one
     *  produced by the Activity-tab capture flow. Concretely:
     *   - `imagePath` is populated by byte-copying the most recent image of
     *     the conversation into `filesDir/food_images/`. We do NOT move the
     *     chat-image; the bubble continues to render from `chat_images/`.
     *   - `analysisSummary` / `fullSummary` / `lastAiAnalyzedSummary` are all
     *     set to `description` so the edit-meal screen renders the real
     *     summary AND the re-analysis baseline is in place.
     *   - `ingredientsJson` is the JSON-encoded breakdown, matching the
     *     manual analyzer output.
     *   - `mealCategory` falls back to [MealCategoryFromTime.classify] when
     *     the LLM omitted it — same helper the manual flow uses
     *     ([[activity-metrics-source-of-truth]] / `confirmAndSaveFoodEntry`).
     *   - `appliedHabitIds` propagates so the "Aplicado: …" badge shows.
     *  When the LLM omitted any of these (legacy cards, text-only chat with
     *  no breakdown), we fall back to the previous behaviour for those
     *  specific fields — never crashes, just degrades. */
    fun confirmMealProposal(
        context: android.content.Context,
        messageId: Long,
        item: PendingMealProposal
    ) {
        val sid = _selectedSessionId.value
        viewModelScope.launch {
            // Resolve the most recent image from the conversation context.
            // We walk backwards from `messageId` so we never pick a photo
            // attached AFTER the proposal — only one that was visible to
            // the LLM when it produced the card.
            val messages = activeMessages.value
            val anchorIdx = messages.indexOfFirst { it.id == messageId }
            val proposalModel = messages.getOrNull(anchorIdx)?.analysisModel
            val searchTo = if (anchorIdx >= 0) anchorIdx else messages.lastIndex
            var sourcePath: String? = null
            for (i in searchTo downTo 0) {
                val paths = ChatImageStore.decode(messages[i].imageBase64)
                    .filter { ChatImageStore.isPath(it) }
                if (paths.isNotEmpty()) {
                    sourcePath = paths.last()
                    break
                }
            }
            // Byte-copy into food_images/ (lossless, mirrors
            // FoodImageStore.persistFromUri's usual gallery path). Leaving
            // the source file in chat_images/ intact so the chat bubble
            // keeps rendering.
            val persistedImagePath = sourcePath?.let { srcUriString ->
                runCatching {
                    FoodImageStore.persistFromUri(context, android.net.Uri.parse(srcUriString))
                }.getOrNull()
            }
            val resolvedCategory = com.example.data.local.MealCategoryFromTime
                .classify(System.currentTimeMillis(), item.proposal.mealCategory)
            val description = item.proposal.description?.takeIf { it.isNotBlank() }
            // Legacy stub kept ONLY when the LLM did not produce a real
            // description — pre-2026-06-22 protocol or a card the model
            // emitted without enough context to summarise.
            val ingredientsJson = item.proposal.ingredients
                ?.takeIf { it.isNotEmpty() }
                ?.let { com.example.data.remote.encodeIngredients(it) }
            // Gram-weight repair: a chat-proposal description is freeform
            // model prose that frequently lacks the (~Xg) weights; rebuild
            // the weighted line from the proposal's ingredients when present.
            val fullSummaryFinal = com.example.data.remote.FullSummaryRepair.repair(
                description,
                ingredientsJson,
                _userProfile.value.language
            ) ?: (description ?: "Logged from chat session #$sid")
            repository.insertFood(
                FoodEntry(
                    name = item.proposal.name,
                    calories = item.proposal.calories,
                    protein = item.proposal.protein,
                    carbs = item.proposal.carbs,
                    fat = item.proposal.fat,
                    mealCategory = resolvedCategory,
                    imagePath = persistedImagePath,
                    analysisSummary = description,
                    fullSummary = fullSummaryFinal,
                    lastAiAnalyzedSummary = description,
                    appliedHabitIds = item.proposal.appliedHabitIds,
                    ingredientsJson = ingredientsJson,
                    groupId = sid?.let { "chat:$it" },
                    // Already clamped in ActionProposalParser to [0, carbs] / [0, fat].
                    freeSugarsG = item.proposal.freeSugarsG,
                    saturatedFatG = item.proposal.saturatedFatG,
                    // The macros here were produced by whichever model wrote the
                    // proposal card, so the meal inherits that message's model
                    // rather than showing no attribution at all.
                    analysisModel = proposalModel
                ).appendEditLog(FoodEditType.ADDED, proposalModel)
            )
            addReceipt(messageId, ProposalKind.MEAL, ProposalOutcome.CONFIRMED, item.proposal.name)
            removeMealProposal(messageId, item)
        }
    }

    fun discardMealProposal(messageId: Long, item: PendingMealProposal) {
        addReceipt(messageId, ProposalKind.MEAL, ProposalOutcome.DISCARDED, item.proposal.name)
        removeMealProposal(messageId, item)
    }

    fun confirmActivityProposal(messageId: Long, p: com.example.data.remote.LogActivityProposal) {
        val sid = _selectedSessionId.value
        viewModelScope.launch {
            val type = p.activityTypeName
                ?.let { runCatching { com.example.data.local.ActivityType.valueOf(it.uppercase()) }.getOrNull() }
                ?: com.example.data.local.ActivityType.OTHER
            repository.insertActivity(
                ActivityEntry(
                    name = p.name,
                    durationMinutes = p.durationMinutes,
                    caloriesBurned = p.caloriesBurned,
                    activityType = type,
                    sessionId = sid?.let { "chat:$it" } ?: "",
                    // Mirror the meal-proposal path (Hugo 2026-06-23): the
                    // LLM is now required to emit `description` on every
                    // LOG_ACTIVITY_PROPOSAL, so prefer that and fall back to
                    // the legacy stub only when the field is genuinely
                    // missing/blank (older sessions, malformed payloads).
                    fullSummary = p.description?.takeIf { it.isNotBlank() }
                        ?: "Logged from chat session #$sid",
                    // Fine-grained modality — mirrors ShWorkout.sourceLabel so a
                    // chat-confirmed "Pilates" entry persists the specific label
                    // instead of only the coarse YOGA bucket. Falls back to null
                    // (renderers then use `name` / `activityType.displayKey`).
                    sourceLabel = p.sourceLabel?.takeIf { it.isNotBlank() }
                )
            )
            addReceipt(messageId, ProposalKind.ACTIVITY, ProposalOutcome.CONFIRMED, p.name)
            removeActivityProposal(messageId, p)
        }
    }

    fun discardActivityProposal(messageId: Long, p: com.example.data.remote.LogActivityProposal) {
        addReceipt(messageId, ProposalKind.ACTIVITY, ProposalOutcome.DISCARDED, p.name)
        removeActivityProposal(messageId, p)
    }

    fun confirmInsightProposal(messageId: Long, p: com.example.data.remote.InsightProposal) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.insertUserInsight(
                com.example.data.local.UserInsight(
                    text = p.text,
                    category = p.category,
                    confidence = p.confidence,
                    source = p.source,
                    sourceSessionId = _selectedSessionId.value,
                    confirmedAtMs = now
                )
            )
            addReceipt(messageId, ProposalKind.INSIGHT, ProposalOutcome.CONFIRMED, p.text.take(60))
            removeInsightProposal(messageId, p)
        }
    }

    fun discardInsightProposal(messageId: Long, p: com.example.data.remote.InsightProposal) {
        addReceipt(messageId, ProposalKind.INSIGHT, ProposalOutcome.DISCARDED, p.text.take(60))
        removeInsightProposal(messageId, p)
    }

    /**
     * Phase G (reformulado): kick off an ad-hoc report export from the chat
     * card. Reuses [exportNutritionXlsx] under the hood but with a date filter
     * and a custom filename so multiple ad-hoc reports coexist in the Drive
     * folder without overwriting each other (and without overwriting the
     * canonical `diario_nutricao.xlsx` produced by the Reports tab button).
     */
    fun confirmReportProposal(
        context: android.content.Context,
        messageId: Long,
        p: com.example.data.remote.GenerateReportProposal
    ) {
        // Drive folder linked? If not, surface the same error string the
        // Reports tab uses so the user knows where to fix it.
        if (_exportFolderUri.value.isNullOrBlank()) {
            _exportError.value = tr("Please select the Drive folder first.", _userProfile.value.language)
            return
        }
        val from = runCatching { java.time.LocalDate.parse(p.fromDate) }.getOrNull()
        val to = runCatching { java.time.LocalDate.parse(p.toDate) }.getOrNull()
        if (from == null || to == null || from.isAfter(to)) {
            _exportError.value = "Invalid report range: ${p.fromDate} → ${p.toDate}"
            removeReportProposal(messageId, p)
            return
        }
        val safeStem = p.filenameStem
            .lowercase()
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .ifBlank { "relatorio_${p.fromDate}_${p.toDate}" }
        exportNutritionXlsx(
            context,
            fromDate = from,
            toDate = to,
            filenameOverride = "$safeStem.xlsx"
        )
        addReceipt(
            messageId,
            ProposalKind.REPORT,
            ProposalOutcome.CONFIRMED,
            "$safeStem.xlsx (${p.fromDate} → ${p.toDate})"
        )
        removeReportProposal(messageId, p)
    }

    fun discardReportProposal(messageId: Long, p: com.example.data.remote.GenerateReportProposal) {
        addReceipt(
            messageId,
            ProposalKind.REPORT,
            ProposalOutcome.DISCARDED,
            "${p.filenameStem.ifBlank { "report" }} (${p.fromDate} → ${p.toDate})"
        )
        removeReportProposal(messageId, p)
    }

    private fun removeReportProposal(messageId: Long, p: com.example.data.remote.GenerateReportProposal) {
        _pendingReportProposals.update { current ->
            val list = current[messageId].orEmpty().filterNot {
                it.fromDate == p.fromDate && it.toDate == p.toDate && it.filenameStem == p.filenameStem
            }
            if (list.isEmpty()) current - messageId else current + (messageId to list)
        }
    }

    private fun removeInsightProposal(messageId: Long, p: com.example.data.remote.InsightProposal) {
        _pendingInsightProposals.update { current ->
            val list = current[messageId].orEmpty().filterNot {
                it.text == p.text && it.category == p.category
            }
            if (list.isEmpty()) current - messageId else current + (messageId to list)
        }
    }

    private fun removeMealProposal(messageId: Long, item: PendingMealProposal) {
        _pendingMealProposals.update { current ->
            val list = current[messageId].orEmpty().filterNot {
                it.proposal.name == item.proposal.name && it.proposal.calories == item.proposal.calories
            }
            if (list.isEmpty()) current - messageId else current + (messageId to list)
        }
    }

    private fun removeActivityProposal(messageId: Long, p: com.example.data.remote.LogActivityProposal) {
        _pendingActivityProposals.update { current ->
            val list = current[messageId].orEmpty().filterNot {
                it.name == p.name && it.durationMinutes == p.durationMinutes
            }
            if (list.isEmpty()) current - messageId else current + (messageId to list)
        }
    }

    private fun removeProposal(messageId: Long, proposal: HabitProposal) {
        _pendingHabitProposals.update { current ->
            val list = current[messageId].orEmpty().filterNot {
                it.trigger == proposal.trigger && it.descriptor == proposal.descriptor
            }
            if (list.isEmpty()) current - messageId else current + (messageId to list)
        }
    }

    /**
     * Drop every still-pending proposal across all five kinds, leaving a
     * `DISMISSED` receipt under the assistant message so the user can
     * still see what was offered. Called when the user moves the chat
     * forward (sends another message) without explicitly tapping
     * Confirm/Discard — Hugo's rule "if the chat proceeds, the offer is
     * implicitly stale". No DB writes happen here; this only clears the
     * inline cards.
     */
    private fun dismissAllPendingProposals() {
        _pendingHabitProposals.value.forEach { (mid, list) ->
            list.forEach { p ->
                addReceipt(mid, ProposalKind.HABIT, ProposalOutcome.DISMISSED, p.descriptor)
            }
        }
        _pendingMealProposals.value.forEach { (mid, list) ->
            list.forEach { item ->
                addReceipt(mid, ProposalKind.MEAL, ProposalOutcome.DISMISSED, item.proposal.name)
            }
        }
        _pendingActivityProposals.value.forEach { (mid, list) ->
            list.forEach { p ->
                addReceipt(mid, ProposalKind.ACTIVITY, ProposalOutcome.DISMISSED, p.name)
            }
        }
        _pendingInsightProposals.value.forEach { (mid, list) ->
            list.forEach { p ->
                addReceipt(mid, ProposalKind.INSIGHT, ProposalOutcome.DISMISSED, p.text.take(60))
            }
        }
        _pendingReportProposals.value.forEach { (mid, list) ->
            list.forEach { p ->
                addReceipt(
                    mid,
                    ProposalKind.REPORT,
                    ProposalOutcome.DISMISSED,
                    "${p.filenameStem.ifBlank { "report" }} (${p.fromDate} → ${p.toDate})"
                )
            }
        }
        _pendingHabitProposals.value = emptyMap()
        _pendingMealProposals.value = emptyMap()
        _pendingActivityProposals.value = emptyMap()
        _pendingInsightProposals.value = emptyMap()
        _pendingReportProposals.value = emptyMap()
    }

    private val _pendingActivitySession = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val pendingActivitySession: StateFlow<List<ActivityEntry>> = _pendingActivitySession.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingActivityEntry: StateFlow<ActivityEntry?> = _pendingActivitySession
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _activeCaptureMode = MutableStateFlow(CaptureMode.FOOD)
    val activeCaptureMode: StateFlow<CaptureMode> = _activeCaptureMode.asStateFlow()

    fun setCaptureMode(mode: CaptureMode) {
        _activeCaptureMode.value = mode
    }

    private val _isSavingEntry = MutableStateFlow(false)
    val isSavingEntry: StateFlow<Boolean> = _isSavingEntry.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun setPendingFoodEntry(entry: FoodEntry?) {
        _pendingFoodSession.value = if (entry == null) emptyList() else listOf(entry)
    }

    /** Persists [entry] to the DB (so the onResume retry can find it) then sets it as pending. */
    fun insertAndSetPendingFoodEntry(entry: FoodEntry) {
        viewModelScope.launch {
            val withLog = entry.appendEditLog(FoodEditType.ADDED)
            val id = repository.insertFood(withLog)
            _pendingFoodSession.value = listOf(withLog.copy(id = id))
        }
    }

    fun setEditingFoodEntry(entry: FoodEntry) {
        _pendingFoodSession.value = listOf(entry)
    }

    fun setEditingActivitySession(sessionId: String) {
        viewModelScope.launch {
            val list = repository.getSession(sessionId)
            _pendingActivitySession.value = list
        }
    }

    fun setPendingActivitySession(list: List<ActivityEntry>) {
        _pendingActivitySession.value = list
    }

    fun updatePendingActivitySession(transformer: (List<ActivityEntry>) -> List<ActivityEntry>) {
        _pendingActivitySession.value = transformer(_pendingActivitySession.value)
    }

    fun updatePendingActivityEntryTimestamp(newTimestamp: Long) {
        _pendingActivitySession.value = _pendingActivitySession.value.map { it.copy(timestamp = newTimestamp) }
    }

    fun updatePendingFoodEntryTimestamp(newTimestamp: Long) {
        val list = _pendingFoodSession.value
        if (list.isEmpty()) return
        // Apply to head only (legacy single-item edit dialog operates on head).
        _pendingFoodSession.value = listOf(list.first().copy(timestamp = newTimestamp)) +
            list.drop(1)
    }

    fun updatePendingFoodEntry(
        name: String,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        fullSummary: String = "",
        mealCategory: com.example.data.local.MealCategory = com.example.data.local.MealCategory.SNACK
    ) {
        val list = _pendingFoodSession.value
        if (list.isEmpty()) return
        val current = list.first()
        // Manual edits of `carbs` / `fat` (via the edit-meal TextFields) may
        // lower the total below the previously-stored breakdown value. Clamp so
        // the dashboard/edit UI never renders a segment wider than the total.
        // We do NOT invalidate the estimate — the ratio is preserved as long
        // as the new denominator still exceeds the stored subvalue.
        val clampedFreeSug = current.freeSugarsG
            ?.coerceAtMost(carbs)
            ?.coerceAtLeast(0.0)
        val clampedSatFat = current.saturatedFatG
            ?.coerceAtMost(fat)
            ?.coerceAtLeast(0.0)
        val head = current.copy(
            name = name,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fullSummary = fullSummary,
            mealCategory = mealCategory,
            freeSugarsG = clampedFreeSug,
            saturatedFatG = clampedSatFat
        )
        _pendingFoodSession.value = listOf(head) + list.drop(1)
    }

    /** Update an arbitrary item in the multi-item session by index. Used by the
     *  multi-pick UI where each card edits a different draft. Single-photo flow
     *  uses [updatePendingFoodEntry] which targets the head. */
    fun updatePendingFoodSessionItemAt(index: Int, transformer: (FoodEntry) -> FoodEntry) {
        val list = _pendingFoodSession.value
        if (index < 0 || index >= list.size) return
        _pendingFoodSession.value = list.mapIndexed { i, e -> if (i == index) transformer(e) else e }
    }

    /** Append a single new draft to the current session — used when the user
     *  taps "Add another photo" while reviewing. */
    fun appendToPendingFoodSession(entry: FoodEntry) {
        _pendingFoodSession.value = _pendingFoodSession.value + entry
    }

    /** Move an item to the head of the session so the legacy edit dialog
     *  (which always operates on `pendingFoodEntry` = head) targets it. The
     *  relative order of the other items is preserved. */
    fun focusPendingFoodSessionItem(index: Int) {
        val list = _pendingFoodSession.value
        if (index <= 0 || index >= list.size) return
        val target = list[index]
        _pendingFoodSession.value = listOf(target) + list.filterIndexed { i, _ -> i != index }
    }

    /** Remove a single item from the session by index. If the session ends up
     *  empty, the UI naturally returns to the capture state. */
    fun removeFromPendingFoodSession(index: Int) {
        val list = _pendingFoodSession.value
        if (index < 0 || index >= list.size) return
        _pendingFoodSession.value = list.filterIndexed { i, _ -> i != index }
    }

    fun clearPerItemAnalysisError(imagePath: String?) {
        if (imagePath == null) return
        val current = _perItemAnalysisErrors.value
        if (imagePath in current) {
            _perItemAnalysisErrors.value = current - imagePath
        }
    }

    fun confirmAndSaveFoodEntry(
        /** Suspend loader the UI passes when it has the original meal photo
         *  on disk (i.e. `pendingFoodEntry.imagePath != null`). Implemented
         *  by the composable via [com.example.ui.FoodImageStore.loadImageBytesAndMime];
         *  the VM invokes it inside an IO dispatcher so the JPEG read +
         *  base64 encode (~200-500 ms for a 5 MB photo) does not block the
         *  main thread.
         *
         *  Returns `Pair<base64, mimeType>` or null when there is no image
         *  available (text-only entries) or the file is unreadable. When
         *  null, the re-analysis falls back to the legacy text-only path
         *  and the Pro escalation is skipped (no image → Pro adds nothing
         *  the Flash didn't already see). */
        loadReanalysisImage: (suspend () -> Pair<String, String>?)? = null
    ) {
        val list = _pendingFoodSession.value
        if (list.isEmpty()) return
        android.util.Log.i(
            "MealSave",
            "start n=${list.size} edited=${_summaryWasEditedAfterAnalysis.value} img=${loadReanalysisImage != null}"
        )
        // Memory [[proposal-ingest-idempotency-bug]]: cancel any in-flight
        // analysis (re-analysis or capture) before kicking off the save path.
        foodAnalysisJob?.cancel()
        foodAnalysisJob = viewModelScope.launch {
            _isSavingEntry.value = true
            _saveError.value = null
            _foodAnalysisError.value = null
            try {
                // 2+ items captured together → mint a shared groupId so the
                // history UI can render them under one parent card. Singletons
                // and legacy edits stay null (treated as standalone).
                val sessionGroupId: String? = if (list.size >= 2) {
                    "meal_${java.util.UUID.randomUUID().toString().take(12)}"
                } else null
                // Collect the persisted rows (with fresh row ids for inserts)
                // so we can re-emit them as the pending session. Post-save the
                // edit view stays open — see [[pending-close-explicit-only]] —
                // so the next Save press hits the `id != 0L` branch below and
                // calls updateFood() on the same row instead of inserting a
                // duplicate.
                val savedEntries = mutableListOf<FoodEntry>()
                for (entry in list) {
                    val withGroup = entry.copy(
                        groupId = entry.groupId ?: sessionGroupId
                    )
                    val summaryChanged = withGroup.fullSummary != withGroup.lastAiAnalyzedSummary
                    val notBlank = withGroup.fullSummary.isNotBlank()
                    val flagged = _summaryWasEditedAfterAnalysis.value
                    // Re-analyse ONLY when the user genuinely edited the text
                    // (`flagged`). A programmatic session refresh (e.g. after a
                    // leftover merge) re-inits editSummary without firing
                    // onValueChange, so `flagged` stays false and the subtracted
                    // macros survive. But if the user DOES edit the description
                    // after registering leftovers, that is an explicit request to
                    // recompute — honour it (the edit supersedes the leftovers
                    // adjustment). 2026-10-10.
                    val needsReanalysis = summaryChanged && notBlank && flagged
                    val finalEntry = if (needsReanalysis) {
                        // Streaming re-analysis: emits Phase events that the
                        // edit-meal screen renders as the typing bubble below
                        // the "Aceitar e Guardar Refeição" button.
                        _foodAnalysisPhase.value =
                            com.example.data.meal.MealAnalysisOrchestrator.PHASE_REANALYZING
                        // Load the original meal photo off the main thread so
                        // the Pro escalation has the same visual input the
                        // Flash had. Falls back to text-only when there is no
                        // image (loader returns null).
                        val imgPayload = loadReanalysisImage?.let { loader ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { loader() }.getOrNull()
                            }
                        }
                        var streamedResult: FoodAnalysisResult? = null
                        var streamErrorKey: String? = null
                        mealOrchestrator.reanalyzeFoodFromSummaryStreaming(
                            currentEntry = withGroup,
                            newSummary = withGroup.fullSummary,
                            language = _userProfile.value.language,
                            base64Image = imgPayload?.first,
                            mimeType = imgPayload?.second ?: "image/jpeg"
                        ).collect { event ->
                            when (event) {
                                is com.example.data.meal.MealAnalysisEvent.Phase ->
                                    _foodAnalysisPhase.value = event.key
                                is com.example.data.meal.MealAnalysisEvent.Partial ->
                                    _foodAnalysisProgress.value = event.progress
                                is com.example.data.meal.MealAnalysisEvent.Done -> {
                                    streamedResult = event.result
                                    if (event.result.proAttempted && !event.result.escalated) {
                                        _oneShotSnack.tryEmit("snack_pro_unavailable")
                                    }
                                }
                                is com.example.data.meal.MealAnalysisEvent.Error -> {
                                    // Salvage: if the LLM emitted at least a name,
                                    // honor it so the save still goes through with
                                    // partial data instead of bailing entirely.
                                    streamedResult = event.salvage
                                    streamErrorKey = event.messageKey
                                }
                            }
                        }
                        val result = streamedResult
                        if (result == null) {
                            // 2026-09-03 — a re-analysis failure here used to
                            // throw and abort the ENTIRE save, so the user's
                            // manually-edited summary (and the whole session)
                            // was silently lost even though the original
                            // AI-analysed macros were still perfectly valid.
                            // Instead: log it, surface a non-blocking notice,
                            // and fall through to save `withGroup` as-is (the
                            // edited text, previous macros) so the user never
                            // loses their entry to a transient network blip.
                            android.util.Log.w(
                                "MealSave",
                                "reanalysis failed key=$streamErrorKey — saving without updated macros"
                            )
                            _oneShotSnack.tryEmit("snack_reanalysis_failed_saved_anyway")
                            withGroup
                        } else {
                            val merged = mealOrchestrator
                                .mergeReanalysis(
                                    withGroup,
                                    result,
                                    withGroup.fullSummary,
                                    _userProfile.value.language
                                )
                            merged.copy(
                                // Manual mealCategory always wins — the user's pick from the edit
                                // dialog must never be overwritten by Gemini's classification of the
                                // summary text. ([[user-choice-wins-after-llm-reanalysis]])
                                mealCategory = withGroup.mealCategory,
                                // Preserve the groupId that the UI already settled on.
                                groupId = withGroup.groupId,
                                // Use the *actually-analysed* summary (post
                                // gram-repair — mergeReanalysis already
                                // applied it) as the next baseline so the
                                // re-analysis branch only fires when the
                                // user truly edits the summary again.
                                lastAiAnalyzedSummary = merged.fullSummary,
                                // A fresh re-analysis supersedes any prior leftovers
                                // adjustment — the macros now come straight from the
                                // model, so drop the snapshot and hide the leftovers
                                // card. 2026-10-10.
                                leftoverOriginalJson = null
                            )
                        }
                    } else {
                        withGroup
                    }

                    // ── LEFTOVERS RECOMPUTE ON EDIT ────────────────────────────
                    // If the persisted leftovers card was edited, re-analyze and
                    // subtract from the correct base: if the description was just
                    // reanalyzed (needsReanalysis=true), finalEntry is the fresh
                    // dish with snapshot cleared → subtract from it. Otherwise,
                    // rebuild the original from the existing pre-leftovers snapshot
                    // so we subtract from the true original (idempotent), not from
                    // already-reduced values. 2026-10-16.
                    val leftoversText = _editedLeftoversText.value
                    val leftoversEdited = !leftoversText.isNullOrBlank()
                    val entryAfterLeftovers = if (leftoversEdited) {
                        _foodAnalysisPhase.value =
                            com.example.data.meal.MealAnalysisOrchestrator.PHASE_ANALYZING_LEFTOVERS
                        val base = if (needsReanalysis) {
                            // Summary was just reanalyzed → finalEntry is the fresh dish
                            finalEntry
                        } else {
                            // Rebuild original from snapshot so subtraction is idempotent
                            val snap = com.example.data.local.decodeLeftoverSnapshot(
                                finalEntry.leftoverOriginalJson
                            )
                            if (snap != null) finalEntry.copy(
                                calories = snap.calories,
                                protein = snap.protein,
                                carbs = snap.carbs,
                                fat = snap.fat,
                                fullSummary = snap.fullSummary,
                                lastAiAnalyzedSummary = snap.lastAiAnalyzedSummary,
                                ingredientsJson = snap.ingredientsJson,
                                freeSugarsG = snap.freeSugarsG,
                                saturatedFatG = snap.saturatedFatG,
                                analysisModel = snap.analysisModel
                            ) else finalEntry
                        }
                        val merged = resubtractLeftovers(base, leftoversText!!, _userProfile.value.language)
                        if (merged != null) {
                            merged
                        } else {
                            _oneShotSnack.tryEmit("snack_reanalysis_failed_saved_anyway")
                            finalEntry
                        }
                    } else {
                        finalEntry
                    }

                    val saved = if (entryAfterLeftovers.id != 0L) {
                        // Determine edit log type based on what happened:
                        // - LEFTOVER when leftovers were edited (takes precedence)
                        // - AUTO when a fresh summary re-analysis ran and produced a model
                        // - MANUAL when only the user's own edit changed
                        val logType = if (leftoversEdited) {
                            FoodEditType.LEFTOVER
                        } else {
                            val reanalysed = needsReanalysis && entryAfterLeftovers.analysisModel != null
                            if (reanalysed) FoodEditType.AUTO else FoodEditType.MANUAL
                        }
                        val logModel = if (leftoversEdited) {
                            // Use the leftovers result's analysis model if available
                            entryAfterLeftovers.analysisModel
                        } else {
                            val reanalysed = needsReanalysis && entryAfterLeftovers.analysisModel != null
                            if (reanalysed) entryAfterLeftovers.analysisModel else null
                        }
                        val withLog = entryAfterLeftovers.appendEditLog(
                            type = logType,
                            model = logModel
                        )
                        android.util.Log.i("EditLog", "update id=${entryAfterLeftovers.id} type=$logType log=${withLog.editLogJson?.take(60)}")
                        repository.updateFood(withLog)
                        withLog
                    } else {
                        val withLog = entryAfterLeftovers.appendEditLog(
                            type = FoodEditType.ADDED,
                            model = entryAfterLeftovers.analysisModel
                        )
                        android.util.Log.i("EditLog", "insert id=0 log=${withLog.editLogJson?.take(60)}")
                        val newId = repository.insertFood(withLog)
                        withLog.copy(id = newId)
                    }
                    savedEntries += saved

                    // Capture the AI-vs-user gap for the correction calibrator.
                    // `_lastAnalyzedResult` still holds the pristine AI estimate
                    // (never cleared during editing); `saved` is what the user
                    // committed. Only record when there is a real correction —
                    // a pristine ADDED save with no edits records nothing.
                    maybeRecordCorrection(
                        aiResult = _lastAnalyzedResult.value,
                        savedEntry = saved,
                    )
                }
                // One line per save cycle. Enough to reason about: did it fire,
                // how many rows, did ids come back, was it insert vs update.
                android.util.Log.i(
                    "MealSave",
                    "ok n=${savedEntries.size} ids=${savedEntries.joinToString(",") { it.id.toString() }} reanalysis=${_summaryWasEditedAfterAnalysis.value}"
                )
                _pendingFoodSession.value = savedEntries
                _perItemAnalysisErrors.value = emptyMap()
                _analysisError.value = null
                _summaryWasEditedAfterAnalysis.value = false
                _editedLeftoversText.value = null
                _oneShotSnack.tryEmit("snack_meal_saved")
                // 2026-07-10 (Hugo) — after a successful save, let the
                // "Refeição guardada" snackbar breathe for 2s, then close the
                // editor and navigate to Histórico. Reverts the earlier
                // "keep view open" behaviour. The delay runs inside
                // foodAnalysisJob, so leaving early (Cancel / bottom-nav)
                // cancels it cleanly. isSavingEntry is cleared in `finally`
                // right after this, so the CTA isn't stuck disabled meanwhile.
                _isSavingEntry.value = false
                kotlinx.coroutines.delay(2_000)
                _pendingFoodSession.value = emptyList()
                _navigateToHistoryAfterSave.tryEmit(Unit)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("MealSave", "fail: ${e.javaClass.simpleName}: ${e.message}")
                _saveError.value = e.message
            } finally {
                _isSavingEntry.value = false
                _foodAnalysisPhase.value = null
                _foodAnalysisProgress.value = com.example.data.meal.FoodAnalysisProgress()
            }
        }
    }

    /**
     * Record the gap between the AI's original estimate ([aiResult]) and what
     * the user actually saved ([savedEntry]) for the correction calibrator —
     * but only when there is a *material* correction. A pristine save where the
     * user accepted the AI's numbers verbatim records nothing (no signal).
     *
     * "Material" = the dish name changed, OR any macro moved beyond a small
     * relative epsilon, OR the ingredient list changed. Rounding-level noise is
     * ignored so accepting-with-a-tiny-nudge doesn't pollute the bias.
     *
     * Never throws — capture is best-effort and must not break a save. The
     * heavy lifting (insert + trim + cache invalidation) lives in
     * [GeminiRepository.recordMealCorrection].
     */
    private suspend fun maybeRecordCorrection(
        aiResult: FoodAnalysisResult?,
        savedEntry: FoodEntry,
    ) {
        val ai = aiResult ?: return

        fun macroMoved(aiVal: Double?, userVal: Double): Boolean {
            if (aiVal == null) return false
            val denom = maxOf(userVal, aiVal, 1.0)
            return kotlin.math.abs(aiVal - userVal) / denom > MACRO_EPSILON
        }

        val nameChanged = !ai.name.isNullOrBlank() &&
            ai.name.trim().lowercase() != savedEntry.name.trim().lowercase()
        val caloriesMoved = ai.calories != null &&
            kotlin.math.abs(ai.calories - savedEntry.calories) >
                maxOf(savedEntry.calories, ai.calories, 1) * MACRO_EPSILON
        val macrosMoved = macroMoved(ai.protein, savedEntry.protein) ||
            macroMoved(ai.carbs, savedEntry.carbs) ||
            macroMoved(ai.fat, savedEntry.fat)

        val aiIngredientsJson = com.example.data.remote.encodeIngredients(ai.ingredients)
        val ingredientsChanged = (aiIngredientsJson ?: "") != (savedEntry.ingredientsJson ?: "") &&
            !(aiIngredientsJson.isNullOrBlank() && savedEntry.ingredientsJson.isNullOrBlank())

        if (!nameChanged && !caloriesMoved && !macrosMoved && !ingredientsChanged) return

        repository.recordMealCorrection(
            com.example.data.local.MealCorrectionEntity(
                mealCategory = savedEntry.mealCategory.name,
                model = ai.analysisModel ?: savedEntry.analysisModel,
                initialConfidence = ai.confidence,
                aiName = ai.name,
                userName = savedEntry.name,
                aiCalories = ai.calories,
                userCalories = savedEntry.calories,
                aiProtein = ai.protein,
                userProtein = savedEntry.protein,
                aiCarbs = ai.carbs,
                userCarbs = savedEntry.carbs,
                aiFat = ai.fat,
                userFat = savedEntry.fat,
                aiIngredientsJson = aiIngredientsJson,
                userIngredientsJson = savedEntry.ingredientsJson,
            )
        )
        android.util.Log.i(
            "MealCorrection",
            "captured gap name=$nameChanged kcal=$caloriesMoved macros=$macrosMoved ingr=$ingredientsChanged"
        )
    }

    fun confirmAndSaveActivitySession() {
        val list = _pendingActivitySession.value
        if (list.isEmpty()) return
        val head = list.first()
        // Memory [[proposal-ingest-idempotency-bug]]: cancel any in-flight
        // analysis stream before kicking off save — same single-job reuse as
        // the food path so we never have two collectors racing.
        foodAnalysisJob?.cancel()
        foodAnalysisJob = viewModelScope.launch {
            _isSavingEntry.value = true
            _saveError.value = null
            _foodAnalysisError.value = null
            try {
                val needsReanalysis = head.fullSummary != head.lastAiAnalyzedSummary &&
                    head.fullSummary.isNotBlank() &&
                    _summaryWasEditedAfterActivityAnalysis.value
                val finalRows = if (needsReanalysis) {
                    // Streaming re-analysis: emits Phase events that the
                    // edit-activity screen renders as the typing bubble below
                    // the "Confirm & Log Activity" CTA.
                    _foodAnalysisPhase.value =
                        com.example.data.meal.MealAnalysisOrchestrator.PHASE_REANALYZING_ACTIVITY
                    var streamedRows: List<ActivityEntry>? = null
                    var streamErrorKey: String? = null
                    mealOrchestrator.reanalyzeActivityFromSummaryStreaming(
                        sessionId = head.sessionId,
                        newSummary = head.fullSummary,
                        prevExercises = list,
                        language = _userProfile.value.language
                    ).collect { event ->
                        when (event) {
                            is com.example.data.meal.ActivityAnalysisStreamEvent.Phase ->
                                _foodAnalysisPhase.value = event.key
                            is com.example.data.meal.ActivityAnalysisStreamEvent.Done ->
                                streamedRows = event.rows
                            is com.example.data.meal.ActivityAnalysisStreamEvent.Error ->
                                streamErrorKey = event.messageKey
                        }
                    }
                    // 2026-09-03 — same fix as confirmAndSaveFoodEntry: don't
                    // let a transient re-analysis failure abort the whole
                    // save. Fall back to the previous rows (with the user's
                    // edited summary applied) instead of throwing.
                    if (streamedRows == null) {
                        android.util.Log.w(
                            "MealSave",
                            "activity reanalysis failed key=$streamErrorKey — saving without updated data"
                        )
                        _oneShotSnack.tryEmit("snack_reanalysis_failed_saved_anyway")
                        list.map { it.copy(fullSummary = head.fullSummary) }
                    } else {
                        streamedRows!!.map { it.copy(lastAiAnalyzedSummary = head.fullSummary) }
                    }
                } else {
                    list
                }
                repository.updateActivitySession(head.sessionId, finalRows)
                _pendingActivitySession.value = emptyList()
                _activityAnalysisError.value = null
                _summaryWasEditedAfterActivityAnalysis.value = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _saveError.value = e.message
            } finally {
                _isSavingEntry.value = false
                _foodAnalysisPhase.value = null
            }
        }
    }

    fun rejectPendingFoodEntry() {
        foodAnalysisJob?.cancel()
        foodAnalysisJob = null
        _pendingFoodSession.value = emptyList()
        _perItemAnalysisErrors.value = emptyMap()
        _analysisError.value = null
        _saveError.value = null
        _foodAnalysisPhase.value = null
        _foodAnalysisProgress.value = com.example.data.meal.FoodAnalysisProgress()
        _foodAnalysisError.value = null
        _summaryWasEditedAfterAnalysis.value = false
        _editedLeftoversText.value = null
        lastFoodAnalysisRequest = null
        _pendingClosed.tryEmit(Unit)
    }

    fun rejectPendingActivitySession() {
        // Cancel any in-flight reanalysis stream — same single-job reuse as
        // [rejectPendingFoodEntry] (we share `foodAnalysisJob` because only
        // one stream runs at a time across all edit screens).
        foodAnalysisJob?.cancel()
        foodAnalysisJob = null
        _pendingActivitySession.value = emptyList()
        _activityAnalysisError.value = null
        _saveError.value = null
        _foodAnalysisPhase.value = null
        _summaryWasEditedAfterActivityAnalysis.value = false
        _pendingClosed.tryEmit(Unit)
    }

    /** User navigated away via a bottom-nav tap. Silently closes the edit
     *  view without emitting [pendingClosed] — the caller is already animating
     *  the pager, so we don't want a second animation on top. Saved rows stay
     *  in the DB; only in-memory draft state is cleared. */
    fun clearPendingSession() {
        foodAnalysisJob?.cancel()
        foodAnalysisJob = null
        _pendingFoodSession.value = emptyList()
        _pendingActivitySession.value = emptyList()
        _perItemAnalysisErrors.value = emptyMap()
        _analysisError.value = null
        _activityAnalysisError.value = null
        _saveError.value = null
        _foodAnalysisPhase.value = null
        _foodAnalysisProgress.value = com.example.data.meal.FoodAnalysisProgress()
        _foodAnalysisError.value = null
        _summaryWasEditedAfterAnalysis.value = false
        _editedLeftoversText.value = null
        _summaryWasEditedAfterActivityAnalysis.value = false
        lastFoodAnalysisRequest = null
    }

    // Retry any food entries whose AI analysis failed previously (TTFT timeout /
    // 503) and were left as placeholder "Captured Meal (Manual Input pending)".
    // Called from MainActivity.onResume so the retry happens automatically
    // the next time the user opens the app after a failure, without any manual
    // intervention. Skips entries whose imagePath file no longer exists (e.g.
    // the user already manually edited the entry and the image was cleaned up).
    private val _mealRetryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun retryPendingMealAnalyses() {
        if (!_mealRetryInFlight.compareAndSet(false, true)) return
        val ctx = cachedAppContext ?: run { _mealRetryInFlight.set(false); return }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {            try {
                val pending = runCatching { repository.getPendingAnalysisFoodEntries() }
                    .getOrElse { return@launch }
                if (pending.isEmpty()) return@launch
                android.util.Log.i("FOOD_RETRY", "retryPendingMealAnalyses: ${pending.size} pending entries")
                for (entry in pending) {
                    val path = entry.imagePath ?: continue
                    val imagePayload = com.example.ui.FoodImageStore
                        .loadImageBytesAndMime(ctx, path)
                    if (imagePayload == null) {
                        android.util.Log.w("FOOD_RETRY", "image unreadable for entry ${entry.id}, skipping")
                        continue
                    }
                    android.util.Log.i("FOOD_RETRY", "retrying analysis for entry ${entry.id}")
                    val (base64, mime) = imagePayload
                    var analysisResult: com.example.data.remote.FoodAnalysisResult? = null
                    mealOrchestrator.analyzeFoodImageStreaming(
                        base64Image = base64,
                        mimeType = mime,
                        promptHint = "User-captured camera image",
                        language = _userProfile.value.language,
                    ).collect { event ->
                        when (event) {
                            is com.example.data.meal.MealAnalysisEvent.Done ->
                                analysisResult = event.result
                            else -> Unit
                        }
                    }
                    val result = analysisResult ?: run {
                        android.util.Log.w("FOOD_RETRY", "analysis produced no result for entry ${entry.id}, skipping")
                        continue
                    }
                    val rawSummary = result.fullSummary ?: result.analysisSummary ?: ""
                    // Gram-weight repair — same as the capture paths: an
                    // auto re-analysis resolving to gemini-3.5-flash can
                    // return a one-line summary without (~Xg) weights.
                    val retryIngredientsJson = com.example.data.remote.encodeIngredients(result.ingredients)
                    val summaryText = com.example.data.remote.FullSummaryRepair.repair(rawSummary, retryIngredientsJson, _userProfile.value.language) ?: rawSummary
                    val ts = entry.timestamp
                    val resolved = com.example.data.local.MealCategoryFromTime
                        .classify(ts, com.example.data.local.MealCategory.parse(result.mealCategory))
                    val updated = entry.copy(
                        name = result.name ?: entry.name,
                        calories = result.calories ?: entry.calories,
                        protein = result.protein ?: entry.protein,
                        carbs = result.carbs ?: entry.carbs,
                        fat = result.fat ?: entry.fat,
                        analysisSummary = summaryText,
                        fullSummary = summaryText,
                        lastAiAnalyzedSummary = summaryText,
                        mealCategory = resolved,
                        ingredientsJson = retryIngredientsJson,
                        freeSugarsG = result.freeSugarsG?.coerceAtMost(result.carbs ?: 0.0)?.coerceAtLeast(0.0),
                        saturatedFatG = result.saturatedFatG?.coerceAtMost(result.fat ?: 0.0)?.coerceAtLeast(0.0),
                        // Record the engine behind this auto-analysis so the meal-details
                        // activity log can show "Atualiz. Auto · <model>". The retry path
                        // previously dropped it, leaving analysisModel + edit-log model null.
                        analysisModel = result.analysisModel ?: entry.analysisModel,
                    )
                    repository.updateFood(updated.appendEditLog(FoodEditType.AUTO, model = result.analysisModel))
                    android.util.Log.i("FOOD_RETRY", "entry ${entry.id} updated: name='${updated.name}' kcal=${updated.calories}")
                    kotlinx.coroutines.delay(2_000L)
                }
            } finally {
                _mealRetryInFlight.set(false)
            }
        }
    }

    // Samsung Health sync operations
    fun refreshSamsungHealthStatus() {
        viewModelScope.launch {
            _shStatus.value = shRepository.status()
        }
    }

    fun requestSamsungHealthPermissions(activity: android.app.Activity) {
        viewModelScope.launch {
            val granted = shRepository.requestPermissions(activity)
            if (granted) {
                _shStatus.value = SamsungHealthRepository.Status.AVAILABLE
                syncFromSamsungHealth(force = true)
            } else {
                _shStatus.value = SamsungHealthRepository.Status.NEEDS_PERMISSION
            }
        }
    }

    /**
     * Manual-refresh handler for the dashboard's circular refresh button.
     *
     * Behaves like a normal forced sync, but additionally tops up any optional Samsung
     * Health permissions that aren't granted yet — currently BODY_COMPOSITION (BMR).
     * This matters for users who upgraded from a build that only asked for the original
     * 3 permissions: the SH UI hides toggles for permissions the app never requested,
     * so the only way to re-surface BMR is to fire `requestPermissions(SH_ALL_PERMISSIONS)`
     * again. Doing it on the refresh button keeps the trigger explicit and user-visible.
     */
    fun manualRefreshSamsungHealth(activity: android.app.Activity) {
        viewModelScope.launch {
            // Re-prompt for missing optional perms first (cheap if all granted: just
            // a getGrantedPermissions call with no UI). The dialog only opens when
            // something is actually missing.
            if (_shStatus.value == SamsungHealthRepository.Status.AVAILABLE
                && shRepository.hasMissingOptionalPermissions()) {
                shRepository.requestPermissions(activity)
            }
            syncFromSamsungHealth(force = true)
        }
    }

    fun syncFromSamsungHealth(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - _shLastSyncMs.value < 5 * 60 * 1000) {
            // Debounce automatic dashboard refreshes to 5 minutes
            return
        }
        // 2026-08-16 — run the whole SH read chain on Dispatchers.IO. Previously
        // this inherited Main from viewModelScope.launch{}, so the read bodies
        // (map-building, workout flatMap, the 4× aggregate summary) executed on
        // the main thread → UI froze on every foreground return until they
        // finished. StateFlow.value assignment is thread-safe, so the UI still
        // recomposes correctly once values land.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _shStatus.value = shRepository.status()
            if (_shStatus.value == SamsungHealthRepository.Status.AVAILABLE) {
                val endInstant = Instant.now()
                val startInstant = endInstant.minus(java.time.Duration.ofDays(30))

                // Steps are read-only and held in memory; we never write them
                // to the local DB. Source of truth stays Samsung Health.
                val steps = shRepository.readDailySteps(startInstant, endInstant)
                _shStepsByDate.value = steps

                // Workouts: held in memory for the dashboard's "today" rollup,
                // never imported into the activities table. Manually-logged
                // exercises (no shRecordId) remain the only persisted source.
                val workouts = shRepository.readWorkouts(startInstant, endInstant)
                _shWorkouts.value = workouts

                // ActivitySummary daily rollups — the same totals the SH UI shows
                // (Tempo ativo / Calorias de atividade / Distância). Read here so
                // the dashboard can mirror them without applying derived formulas.
                val activity = shRepository.readDailyActivitySummary(startInstant, endInstant)
                _shDailyActivity.value = activity

                // BodyComposition latest BMR — independent best-effort read. Returns
                // null if BODY_COMPOSITION wasn't granted or no measurements exist;
                // when present we append a snapshot keyed by today (only if the value
                // actually changed vs the latest row).
                val bmrSnap = shRepository.readLatestBodyCompositionBmr()
                if (bmrSnap != null) {
                    appendBmrSnapshotIfChanged(
                        valueKcal = bmrSnap.value,
                        source = BmrSource.SAMSUNG_HEALTH,
                        sourceTimestampMs = bmrSnap.timestamp?.toEpochMilli()
                    )
                }

                // Latest body composition from the same BODY_COMPOSITION
                // read path. Persists every Medisana-validated field
                // (weight + body fat % + muscle + water + BMI + height)
                // into [body_composition_snapshots] when new, so the chat
                // assistant has history and the UI has a last-known
                // fallback when SH is offline. Source of truth remains
                // S-Health — this is a cache, never edited directly.
                val bodyComp = shRepository.readLatestBodyCompositionAll()
                if (bodyComp != null) {
                    appendBodyCompositionSnapshotIfNew(bodyComp)
                }

                // One-shot diagnostic probe: dump every BodyComposition field
                // to logcat on the first SH sync of this process so Hugo can
                // see which fields his Medisana scale actually writes. Guard
                // is per-process (class-level boolean), so app cold-starts
                // trigger a fresh probe but foreground returns/onResume
                // re-syncs don't spam the log. Filter:
                //   adb logcat | grep SHD#body_composition
                // Added 2026-06-26.
                if (!bodyCompositionProbed) {
                    bodyCompositionProbed = true
                    shRepository.probeBodyCompositionFields()
                }

                _shLastSyncMs.value = now
            }
            // SH not available: no-op for BMR — the [bmrSnapshots] flow continues to
            // surface whatever's in the table (manual entries, prior SH reads), and
            // the dashboard footer reads from the freshest row regardless of source.
        }
    }

    // Note: workouts from Samsung Health are no longer imported into the
    // activities table. They live in [shWorkouts] for the dashboard rollup
    // and are read fresh on each sync — Samsung Health remains the source of
    // truth for steps and SH-tracked exercises. See syncFromSamsungHealth.

    /** Phase C: list of `file://` URIs (chat_images dir) the user has staged
     *  for the next outgoing message. Up to 5 entries. The `_attachedChatImageBase64`
     *  flow below is derived from this so existing UI bindings keep working
     *  during the transition. */
    private val _attachedChatImagePaths = MutableStateFlow<List<String>>(emptyList())
    val attachedChatImagePaths: StateFlow<List<String>> = _attachedChatImagePaths.asStateFlow()

    private val _attachedChatImageBase64 = MutableStateFlow<String?>(null)
    val attachedChatImageBase64: StateFlow<String?> = _attachedChatImageBase64.asStateFlow()

    /**
     * Legacy single-image setter. Kept so analyzeFood / other callers still
     * compile. New chat code should call [appendChatImagePath] instead.
     *
     * Passing `null` clears every staged image (multi or single).
     */
    fun attachChatImage(base64: String?) {
        if (base64 == null) {
            _attachedChatImagePaths.value = emptyList()
            _attachedChatImageBase64.value = null
        } else {
            _attachedChatImageBase64.value = base64
        }
    }

    /** Phase C: append a freshly-persisted disk path to the staged list. The
     *  picker callback is responsible for copying the bitmap to disk first
     *  via [ChatImageStore.persist]. Caps at 5 entries. */
    fun appendChatImagePath(path: String) {
        val current = _attachedChatImagePaths.value
        if (current.size >= 5) return
        _attachedChatImagePaths.value = current + path
    }

    fun removeChatImagePathAt(index: Int) {
        val current = _attachedChatImagePaths.value
        if (index !in current.indices) return
        _attachedChatImagePaths.value = current.toMutableList().also { it.removeAt(index) }
    }

    private val _systemInstructionJson = MutableStateFlow("")
    val systemInstructionJson: StateFlow<String> = _systemInstructionJson.asStateFlow()

    // Loading / Typing state
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    /** Human-readable phase label for the typing bubble. Updated by the
     *  orchestrator (HandleEvent.Phase) and by the elapsed-time watcher.
     *  Null when not typing. Added 2026-06-18 — gives the user a sense of
     *  what the app is doing during slow Gemini calls so they don't think
     *  it crashed. See [[chat-redesign-2026-06-18-orchestrator]]. */
    private val _typingPhase = MutableStateFlow<String?>(null)
    val typingPhase: StateFlow<String?> = _typingPhase.asStateFlow()

    // ─────────────────────────────────────────────────────────────────
    //  Voice Mode / TTS (2026-07-05)
    // ─────────────────────────────────────────────────────────────────
    /** Which ChatMessage.id is currently being read aloud, or null.
     *  Fed by [TtsController.speakingMessageId]. MessageBubbleItem flips
     *  its 🔊 icon based on this. Empty flow when the controller is null
     *  (headless tests). */
    val speakingMessageId: StateFlow<Long?> =
        ttsController?.speakingMessageId
            ?: MutableStateFlow<Long?>(null).asStateFlow()

    /** Hands-free mode state machine. See [VoiceMode] docs. */
    private val _voiceMode = MutableStateFlow(VoiceMode.Idle)
    val voiceMode: StateFlow<VoiceMode> = _voiceMode.asStateFlow()

    /** Live partial transcript from SpeechRecognizer while [voiceMode] is
     *  Listening. UI shows this in the input field so the user sees what
     *  the recognizer is hearing before submit fires. */
    val voicePartial: StateFlow<String> =
        speechRecognizer?.currentPartial
            ?: MutableStateFlow("").asStateFlow()

    /** Flips true when the mic tap ran into a missing RECORD_AUDIO
     *  permission — Activity observes and shows the runtime dialog. */
    val micPermissionRequired: StateFlow<Boolean> =
        speechRecognizer?.permissionRequired
            ?: MutableStateFlow(false).asStateFlow()

    /** Live streaming text — the cumulative reply emitted chunk-by-chunk
     *  while Gemini is still thinking. Null when not streaming. UI shows
     *  a "live bubble" with markdown render of this value. */
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    /** 2026-10-06 (Frente Thinking) — cumulative extended-thinking text for
     *  the in-flight turn (Gemini `thought` parts / Qwen reasoning). Rendered
     *  in the live "Raciocínio" card above the streaming bubble; cleared in
     *  [clearSendUiIfCurrent] when the turn ends, so the completed message
     *  shows ONLY the answer. Never persisted. */
    private val _thinkingText = MutableStateFlow<String?>(null)
    val thinkingText: StateFlow<String?> = _thinkingText.asStateFlow()

    /** 2026-07-06 (Frente B) — pre-computed `[CONTEXTO LOCAL]` block (GPS
     *  fix + IPMA weather). Populated by [prefetchLocalContext], read at
     *  send time in place of the previous inline `buildLocalContextBlock()`
     *  call which added seconds of latency to every send (blocking GPS fix
     *  + up to 3 sequential IPMA HTTPS GETs on the send coroutine, before
     *  `orchestrator.handle` was even invoked).
     *
     *  When empty (`""`) — no location permission, or the prefetch hasn't
     *  completed yet, or IPMA is down — the send simply omits the block,
     *  which is exactly the same fallback the inline version had via its
     *  `catch (_: Exception) { null }` gates. See `handle` in ChatOrchestrator
     *  for how the assistant tolerates a missing block. */
    private val _localContextBlock = MutableStateFlow<String>("")

    /** Cancellable handle for the in-flight send. The cancel ✕ button on
     *  the typing bubble cancels this. */
    private var sendJob: Job? = null
    private var sendGenerationCounter: Long = 0L
    private var activeSendGeneration: Long = 0L
    private var sendLaunchPendingGeneration: Long? = null

    private fun startSendGeneration(): Long {
        val generation = ++sendGenerationCounter
        activeSendGeneration = generation
        sendLaunchPendingGeneration = generation
        return generation
    }

    private fun finishSendLaunch(generation: Long) {
        if (sendLaunchPendingGeneration == generation) {
            sendLaunchPendingGeneration = null
        }
    }

    private fun isCurrentSend(generation: Long): Boolean = activeSendGeneration == generation

    private inline fun ifCurrentSend(generation: Long, block: () -> Unit) {
        if (isCurrentSend(generation)) block()
    }

    private fun clearSendUiIfCurrent(generation: Long) {
        ifCurrentSend(generation) {
            _isTyping.value = false
            _typingPhase.value = null
            _streamingText.value = null
            _thinkingText.value = null
        }
    }

    /** 2026-07-08 — ignore late UI updates from a cancelled or superseded
     *  send immediately, instead of waiting for its coroutine to unwind. */
    private fun invalidateActiveSendUi() {
        sendLaunchPendingGeneration = null
        activeSendGeneration = 0L
    }

    /** 2026-07-06 (Frente B) — cancellable handle for the local-context
     *  prefetch coroutine so a resume that fires before the previous one
     *  finishes doesn't stack. Fire-and-forget by design; cancellation on
     *  VM `onCleared()` is inherited from `viewModelScope`. */
    private var localContextPrefetchJob: Job? = null

    /** Lazy orchestrator — built once, invalidated alongside the snapshot
     *  cache by the helpers that persist food/activity/habit/insight rows. */
    private val orchestrator: com.example.data.chat.ChatOrchestrator by lazy {
        repository.createChatOrchestrator()
    }

    /** 2026-06-29 — Frente Memory C3. Application context, populated in
     *  [loadUserProfile] so [orchestrator.handle] can pass it through to
     *  the [com.example.data.chat.CarryoverDigest] state machine. Null
     *  until first call to loadUserProfile (which MainActivity does in
     *  onCreate, well before any chat send). */
    private var cachedAppContext: android.content.Context? = null

    override fun onCleared() {
        // Tear down the orchestrator's internal background scope (currently
        // used for the Gemini context-cache TTL touch). `viewModelScope`
        // cancels its own children automatically, but the orchestrator
        // holds an independent [SupervisorJob] for fire-and-forget work
        // that has to outlive a single send turn. Touching the lazy here
        // materialises it if the user never sent a message — cheap (just
        // a constructor + an empty SupervisorJob) and avoids the reflection
        // dance to peek at the lazy's state.
        orchestrator.close()
        super.onCleared()
    }

    /**
     * 2026-06-28 — Frente A (proactivity inline no chat). Called by
     * [ChatScreen.ChatTab]'s `LaunchedEffect(isVisible, selectedSessionId)`
     * each time the chat tab becomes visible. If the chat-tab cooldown gate
     * passes (≥4h since the previous proactive bubble) AND a trigger fires
     * for the current data state, this inserts one short unprompted
     * assistant message into the selected session.
     *
     * Gate is intentionally NOT a NotificationDedupStore lookup — the OS
     * notification path has its own dedup over the same triggers, but it
     * dedupes ONCE per trigger-window (e.g. one DailySummary per day). The
     * chat-tab gate is per chat-tab open, so a user who opens the tab in
     * the morning, closes it, and re-opens it 5h later can get a fresh
     * bubble even though they already saw the morning push notification.
     * The two paths complement each other rather than racing.
     *
     * No-op when the API key isn't configured, the master switch is off, or
     * a proactive job is already in flight (mutual-exclusion via [proactiveJob]).
     */
    private var proactiveJob: Job? = null
    fun maybeFireProactiveOnChatTabResumed(context: android.content.Context) {
        // 2026-07-06 (Frente B) — chat tab just came back into view; refresh
        // the local-context block so weather/location are current the next
        // time the user sends. Cheap (fetcher caches internally); a stale
        // block would say "Now: 09:34" when it's actually 15:12.
        prefetchLocalContext()
        if (isApiKeyMissing) return
        if (proactiveJob?.isActive == true) return
        val sessionId = _selectedSessionId.value ?: return
        proactiveJob = viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("chat_proactive", android.content.Context.MODE_PRIVATE)
                val lastFiredMs = prefs.getLong("last_proactive_at_ms", 0L)
                val now = System.currentTimeMillis()
                val cooldownMs = 4L * 60L * 60L * 1000L // 4h
                if (now - lastFiredMs < cooldownMs) {
                    android.util.Log.d(
                        "CHAT_PROACTIVE",
                        "gate: cooldown active (${(now - lastFiredMs) / 60_000L}min since last)"
                    )
                    return@launch
                }

                // Force-enable all four rules: NotificationPrefs has
                // meal-missing + macros-at-risk OFF by default because in a
                // PUSH notification they read as pressure. In the chat tab
                // the user actively chose to be here — different contract.
                val chatProactivePrefs = com.example.data.notifications.NotificationPrefs(
                    masterEnabled = true,
                    mealMissingEnabled = true,
                    activitySpikeEnabled = true,
                    macrosAtRiskEnabled = true,
                    dailySummaryEnabled = true,
                )
                val trigger = repository.evaluateProactiveTriggerForChat(
                    nowZdt = java.time.ZonedDateTime.now(),
                    prefs = chatProactivePrefs,
                    shStepsByDate = _shStepsByDate.value,
                    shDailyActivity = _shDailyActivity.value,
                )
                if (trigger == null) {
                    android.util.Log.d("CHAT_PROACTIVE", "gate: no trigger matched")
                    return@launch
                }
                android.util.Log.d(
                    "CHAT_PROACTIVE",
                    "firing trigger=${trigger.dedupKey} for sessionId=$sessionId"
                )

                // Persist the fired-at timestamp BEFORE the LLM call so a
                // crash mid-call doesn't leave the cooldown disarmed.
                prefs.edit().putLong("last_proactive_at_ms", now).apply()

                orchestrator.handleProactive(
                    sessionId = sessionId,
                    trigger = trigger,
                    language = _userProfile.value.language,
                    userName = _userProfile.value.name,
                    shStepsByDate = _shStepsByDate.value,
                    shDailyActivity = _shDailyActivity.value,
                    shWorkouts = _shWorkouts.value,
                ).collect { event ->
                    when (event) {
                        is com.example.data.chat.HandleEvent.Done -> {
                            android.util.Log.d("CHAT_PROACTIVE", "done len=${event.cleanedText.length}")
                        }
                        is com.example.data.chat.HandleEvent.Error -> {
                            android.util.Log.w("CHAT_PROACTIVE", "error: ${event.cause.message}")
                            // Reset the cooldown so an empty/filtered reply
                            // doesn't lock out the next 4h.
                            prefs.edit().putLong("last_proactive_at_ms", 0L).apply()
                        }
                        else -> Unit // Phase / Partial ignored — non-streaming path
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("CHAT_PROACTIVE", "failed: ${t.message}")
            }
        }
    }

    // Missing API Key check
    val isApiKeyMissing: Boolean = BuildConfig.GEMINI_API_KEY.isBlank() ||
            BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY"

    // Helper to get first emission
    private suspend fun <T> Flow<T>.collectFirst(action: (T) -> Unit) {
        this.take(1).collect(action)
    }

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    /** Append-only history of prescribed targets, keyed by effective date. The
     *  dashboard, reports tab, PNG/XLSX exporters and the historical-reanalysis
     *  LLM path all read through this so a target change today never silently
     *  re-anchors yesterday's numbers. See TargetResolver. */
    val targetSnapshots: StateFlow<List<com.example.data.local.TargetSnapshot>> =
        repository.targetSnapshots
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Voice Mode auto-loop: wire the timeline-observer + TTS-done-observer
        // that keep the hands-free cycle turning. Safe no-op when the VM was
        // built without controllers (headless tests).
        observeVoiceModeAutoSpeak()

        // Phase A: auto-resume the most recent session, or auto-create one if
        // none exist. This kills the "Nenhuma Sessão de Consulta Aberta" empty
        // state — opening the Chat tab now always lands on a usable session.
        // We keep collecting (not collectFirst) so a freshly-deleted last
        // session triggers an auto-create on the next emission.
        //
        // NOTE on placement: this init block sits AFTER `_userProfile` (and
        // friends) is declared because `createDefaultSystemInstruction()`
        // dereferences `_userProfile.value`. Putting the init earlier in the
        // class would crash with NPE the first time `sessions` emits, since
        // Kotlin initializes properties in source order.
        //
        // Bug fix (2026-06-08): the `autoCreating` boolean used to be flipped
        // back to false in the `finally` block after `createNewSession`
        // returned — but Room's Flow had already buffered another empty-list
        // emission while the suspend was running, and that buffered emission
        // re-ran the create branch, spawning duplicate "New Chat" sessions on
        // every cold start. The fix: only allow another auto-create after we
        // have OBSERVED a non-empty list (i.e. the inserted row has actually
        // surfaced through the Flow). Until then, ignore further empty-list
        // emissions.
        viewModelScope.launch {
            // Room's Flow emits an empty list as its FIRST emission while the
            // query is still running, even when the DB is full. If we acted
            // on that first empty emission we'd spawn a new "New Chat"
            // session on every cold start. Solution: do a one-shot suspend
            // read of the DB to decide whether the table is *actually* empty
            // before reacting to the Flow.
            val initialList = runCatching { sessions.first() }.getOrDefault(emptyList())
            // If the first emission was empty, give Room a beat and try
            // again — most of the time the second emission carries the real
            // contents. This avoids the false-positive create.
            val realList = if (initialList.isEmpty()) {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(2000) {
                        sessions.filter { true }.drop(1).first()
                    }
                }.getOrNull() ?: initialList
            } else {
                initialList
            }
            if (realList.isEmpty()) {
                val instruction = createDefaultSystemInstruction()
                val newId = repository.createNewSession(
                    systemInstruction = instruction,
                    model = "gemini-3.5-flash"
                )
                _selectedSessionId.value = newId
            } else {
                if (_selectedSessionId.value == null) {
                    _selectedSessionId.value = realList.first().id
                }
            }
            // From here on, just keep selectedSessionId in sync with the live
            // sessions list. Never auto-create — only the user creates new
            // chats explicitly via the dialog.
            sessions.collect { list ->
                if (list.isEmpty()) {
                    // The user deleted every session manually. Don't recreate
                    // automatically — let the empty drawer prompt the user
                    // to tap "+ Nova Consulta". (Memory: user owns creation.)
                    _selectedSessionId.value = null
                } else if (_selectedSessionId.value == null ||
                           _selectedSessionId.value !in list.map { it.id }) {
                    _selectedSessionId.value = list.first().id
                }
            }
        }
        // One-shot cleanup: drop any activities that were imported from
        // Samsung Health in pre-cleanup builds. New sessions never write SH
        // workouts to the activities table, so this is safe to run on every
        // launch — it's a no-op once empty.
        viewModelScope.launch {
            runCatching { repository.purgeShImportedActivities() }
        }
        // 2026-08-31 — background chat reconciliation (see
        // ChatOrchestrator.regularizeDanglingChatSessions doc comment).
        // Backfills a visible failure row on any session left with a
        // dangling user-only message from a send that never got a durable
        // record. Cheap no-op once there's nothing to backfill; never
        // deletes existing content.
        viewModelScope.launch {
            orchestrator.regularizeDanglingChatSessions(_userProfile.value.language)
        }
        // Phase E: aggregate-pattern analyzer. Runs at most once per 24h
        // (cooldown is enforced inside the repository against the
        // user_insights table itself, so no extra prefs file). Background
        // dispatcher so the cold-start of the chat tab isn't blocked by a
        // 90-day food scan. Findings show up as pending insights and surface
        // in the chat data card on the next assistant turn.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlin.runCatching {
                val n = repository.runAggregateAnalyzer()
                if (n > 0) android.util.Log.i("[INSIGHTS]", "Aggregate analyzer emitted $n new findings")
            }
        }
        // 2026-06-29 — Frente Memory C2.a (backfill). One-shot population
        // of the `keywords` + `summary` columns added in DB v23 for any
        // user_insights row that pre-dated the migration. Idempotent (no-op
        // after the first run). IO dispatcher because tokenisation reads
        // every row's text and writes back; runs in parallel with the
        // aggregate analyzer above — both touch user_insights but neither
        // contends for the same rows (analyzer INSERTs, backfill UPDATEs
        // the `keywords`/`summary` columns).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlin.runCatching {
                val filled = repository.backfillInsightKeywords()
                if (filled > 0) android.util.Log.i("CHAT_KB", "Backfilled keywords for $filled insights")
            }
        }
        // 2026-06-29 — Frente 3 curation is fired from [loadUserProfile]
        // because it needs a Context (SharedPreferences for the
        // idempotency flag) AND the user's birthYear loaded from prefs.
        // Init runs first (no context); loadUserProfile is the canonical
        // boot signal that the prefs are ready.
        //
        // 2026-06-29 update — Hugo asked us to **keep all existing data**
        // (Madhvi and Hugo's facts both stay). Auto-soft-deleting on age
        // mismatch is too aggressive: a fact whose text mentions an age
        // can still be valid history. The curation is therefore DISABLED
        // by default; it remains in the repository as a manual escape
        // hatch surfaced from MemoryScreen later. See [curateConflict-
        // ingAgeInsights] doc for the rule. */
        // Auto-parse assistant messages as they stream in. Already-ingested
        // ids are no-ops; users only see the inline card the first time, but
        // the cleaned-text override survives across recompositions.
        viewModelScope.launch {
            activeMessages.collect { list ->
                list.forEach { msg ->
                    if (!msg.isError && msg.sender != "user" && msg.id != 0L) {
                        ingestAssistantMessage(msg.id, msg.text)
                    }
                }
            }
        }
    }

    fun loadUserProfile(context: android.content.Context) {
        // 2026-06-29 — Frente Memory C3 wiring. Cache the application
        // context so [orchestrator.handle] can read/write the cross-
        // session carryover digest via [CarryoverDigest]. Always
        // applicationContext to avoid leaking an Activity.
        cachedAppContext = context.applicationContext
        // 2026-07-06 (Frente B) — kick the local-context prefetch as soon
        // as we have the app context. First send benefits from a warm
        // `_localContextBlock` without the GPS+IPMA chain blocking the
        // critical path. Failure is silently swallowed to "" — the prompt
        // will simply omit the [CONTEXTO LOCAL] block, same as before.
        prefetchLocalContext()
        val prefs = context.getSharedPreferences("user_profile_pref", android.content.Context.MODE_PRIVATE)
        // Memorize last opened chat across app restarts so the user lands
        // back on the conversation they were viewing instead of always
        // jumping to the most-recent session in DB. Always restore if a
        // saved id exists — the init block may have already auto-selected
        // the most-recent session (race), so we override here. If the saved
        // id no longer exists in DB, the init block's "selected not in list"
        // branch will rescue us and pick the most-recent.
        val savedSessionId = prefs.getLong("last_selected_session_id", -1L)
        if (savedSessionId > 0L) {
            _selectedSessionId.value = savedSessionId
        }
        // Now allow rememberSelectedSession to persist future selections.
        // Until this point we ignore writes so the init block's auto-select
        // doesn't trample the user's last chosen session.
        sessionPersistenceReady = true
        // Non-target identity / preference fields stay in SharedPreferences — they
        // aren't versioned because re-interpreting "name" or "language" historically
        // makes no sense.
        val name = prefs.getString("profile_name", "Humano") ?: "Humano"
        val gender = prefs.getString("profile_gender", "Female") ?: "Female"
        val birthYear = prefs.getInt("profile_birth_year", 1980)
        val activityLevel = prefs.getString("profile_activity", "Average daily activity") ?: "Average daily activity"
        val manualBmr = prefs.getInt("profile_manual_bmr", 0)
        val manualBmrSavedAtMs = prefs.getLong("profile_manual_bmr_saved_at_ms", 0L)
        val language = prefs.getString("profile_language", "pt-PT") ?: "pt-PT"

        // SYNCHRONOUS seed of the identity fields. These come from
        // SharedPreferences (sync read above) and MUST be visible on
        // _userProfile.value before any subsequent chat send, otherwise the
        // JIT system-prompt rebuild in sendMessage() races against the
        // suspend below and renders with the data-class defaults
        // (name="Humano", language="pt-PT", etc.). Targets get overlaid
        // asynchronously once Room returns — they are not needed for the
        // identity portion of the system prompt.
        _userProfile.value = _userProfile.value.copy(
            name = name,
            gender = gender,
            birthYear = birthYear,
            activityLevel = activityLevel,
            manualBmr = manualBmr,
            manualBmrSavedAtMs = manualBmrSavedAtMs,
            language = language
        )
        // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail) — Even
        // though the orchestrator's canonical-snapshot cache key already
        // includes `userName`, explicitly invalidating here closes the
        // 60-second TTL window where a cold-start may have populated the
        // cache with the data-class default ("Humano") between the time
        // the lazy field was first touched and the time prefs hydrated.
        orchestrator.invalidateSnapshotCache()

        viewModelScope.launch {
            // The target_snapshots table is seeded by MIGRATION_8_9 from whatever
            // values were already in user_profile_pref, so on a freshly upgraded
            // install latest() is non-null and matches what the user saw before.
            // On a *brand-new* install with no prefs and no DB rows yet, fall back
            // to the UserProfile() defaults so the dashboard still renders.
            val latest = repository.latestTargetSnapshot()
            // Overlay ONLY the target fields. Identity (name, gender, etc.)
            // was already seeded synchronously above, so a chat send that
            // raced this suspend already had the right values to build the
            // system prompt with.
            _userProfile.value = _userProfile.value.copy(
                targetKcal = latest?.targetKcal ?: 1800,
                targetProtein = latest?.targetProtein ?: 85.0,
                targetCarbs = latest?.targetCarbs ?: 220.0,
                targetFat = latest?.targetFat ?: 60.0,
                targetSteps = latest?.targetSteps ?: 6000,
                targetActiveMinutes = latest?.targetActiveMinutes ?: 90,
                targetActivityCalories = latest?.targetActivityCalories ?: 500
            )
            // The bmr StateFlow is derived from bmrSnapshots; nothing to recompute
            // here. If the table is empty after migration (install without a legacy
            // manual value), it stays empty until the next SH sync or saveUserProfile
            // populates it.

            // If a brand-new install raced the migration (no rows yet), seed one now
            // from the freshly resolved defaults so future appends have something to
            // diff against.
            if (latest == null) {
                repository.insertTargetSnapshot(
                    com.example.data.local.TargetSnapshot(
                        effectiveDateEpochDay = 0L,
                        targetKcal = _userProfile.value.targetKcal,
                        targetProtein = _userProfile.value.targetProtein,
                        targetCarbs = _userProfile.value.targetCarbs,
                        targetFat = _userProfile.value.targetFat,
                        targetSteps = _userProfile.value.targetSteps,
                        targetActiveMinutes = _userProfile.value.targetActiveMinutes,
                        targetActivityCalories = _userProfile.value.targetActivityCalories,
                        note = "initial seed (fresh install)",
                        createdAtMs = System.currentTimeMillis()
                    )
                )
            }
            // Same brand-new-install fallback for BMR: if the prefs file carries a
            // manual value but the migration never had a chance to seed (fresh
            // install with prefs imported some other way), populate one row at
            // epoch day 0 so the backwards-fill rule covers all historical days.
            if (manualBmr > 0 && repository.latestBmrSnapshot() == null) {
                repository.insertBmrSnapshot(
                    com.example.data.local.BmrSnapshotEntity(
                        effectiveDateEpochDay = 0L,
                        valueKcal = manualBmr,
                        source = com.example.data.local.BmrSnapshotEntity.SOURCE_MANUAL,
                        sourceTimestampMs = manualBmrSavedAtMs.takeIf { it > 0 },
                        createdAtMs = System.currentTimeMillis()
                    )
                )
            }
        }
        // 2026-06-29 — Frente 3: auto-curation REMOVED per Hugo's directive
        // "mantém os dados de Madhvi e Hugo atualmente". Insights with
        // mismatching age claims are NOT soft-deleted automatically — they
        // are surfaced in MemoryScreen with a hint and the user decides.
    }

    fun saveUserProfile(context: android.content.Context, profile: UserProfile) {
        _userProfile.value = profile
        // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail) — Drop
        // the orchestrator's snapshot cache so a rename ("Humano" → "Hugo")
        // takes effect on the very next send, not after the 60s TTL.
        orchestrator.invalidateSnapshotCache()
        val prefs = context.getSharedPreferences("user_profile_pref", android.content.Context.MODE_PRIVATE)
        // Persist non-target fields only. Targets live in target_snapshots and are
        // appended below if anything changed against the latest snapshot.
        prefs.edit().apply {
            putString("profile_name", profile.name)
            putString("profile_gender", profile.gender)
            putInt("profile_birth_year", profile.birthYear)
            putString("profile_activity", profile.activityLevel)
            putInt("profile_manual_bmr", profile.manualBmr)
            putLong("profile_manual_bmr_saved_at_ms", profile.manualBmrSavedAtMs)
            putString("profile_language", profile.language)
        }.apply()
        // Manual BMR edits: SharedPreferences is kept above as the latch for the
        // edit dialog's bound state. The append below versions the value so reports
        // pick up "from today onwards" without rewriting past days.
        appendBmrSnapshotIfChanged(
            valueKcal = profile.manualBmr,
            source = BmrSource.MANUAL,
            sourceTimestampMs = profile.manualBmrSavedAtMs.takeIf { it > 0 }
        )
        appendTargetSnapshotIfChanged(profile)
    }

    /** Compare the new [profile]'s target fields against the latest snapshot and,
     *  if anything differs, insert a new row keyed by today (local date). Older
     *  rows stay untouched, so reports about past periods continue to compare
     *  consumption against the targets that were prescribed back then.
     *
     *  Same-day double-edits (the user opens the dialog twice and tweaks the
     *  number again) collapse into a single "today" row by replacing the most
     *  recent same-day insert — but only if it's the absolute latest. We do this
     *  by inserting unconditionally; resolveTargetsByDay's tiebreaker is
     *  `id DESC` within the same effective date, so the freshest insert wins
     *  without us having to delete anything.
     */
    private fun appendTargetSnapshotIfChanged(profile: UserProfile) {
        viewModelScope.launch {
            val latest = repository.latestTargetSnapshot()
            val unchanged = latest != null &&
                latest.targetKcal == profile.targetKcal &&
                latest.targetProtein == profile.targetProtein &&
                latest.targetCarbs == profile.targetCarbs &&
                latest.targetFat == profile.targetFat &&
                latest.targetSteps == profile.targetSteps &&
                latest.targetActiveMinutes == profile.targetActiveMinutes &&
                latest.targetActivityCalories == profile.targetActivityCalories
            if (unchanged) return@launch

            val today = LocalDate.now().toEpochDay()
            repository.insertTargetSnapshot(
                com.example.data.local.TargetSnapshot(
                    effectiveDateEpochDay = today,
                    targetKcal = profile.targetKcal,
                    targetProtein = profile.targetProtein,
                    targetCarbs = profile.targetCarbs,
                    targetFat = profile.targetFat,
                    targetSteps = profile.targetSteps,
                    targetActiveMinutes = profile.targetActiveMinutes,
                    targetActivityCalories = profile.targetActivityCalories,
                    note = null,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            android.util.Log.d(
                "[Targets]",
                "appended snapshot effectiveDay=$today kcal=${profile.targetKcal} " +
                    "P=${profile.targetProtein} C=${profile.targetCarbs} F=${profile.targetFat}"
            )
        }
    }

    /**
     * Append a new bmr_snapshots row keyed by today's local date when [valueKcal]
     * differs from the latest stored value. Mirrors [appendTargetSnapshotIfChanged]:
     *
     *  - Skip if [valueKcal] <= 0 (SH BodyComposition returns 0 when the user has
     *    no measurement; the manual dialog already validates > 0 but defensive).
     *  - Skip if the latest snapshot already has the same valueKcal, regardless
     *    of source. A 1500 SH read followed by a 1500 manual save isn't worth a
     *    new row; the resolver picks "1500" either way.
     *  - Same-day double-edits collapse via the DAO's `id DESC` tiebreaker — both
     *    rows share today's epoch day, but `findEffectiveAt` reads the freshest.
     *    A few redundant rows over months are not a leak.
     */
    private fun appendBmrSnapshotIfChanged(
        valueKcal: Int,
        source: BmrSource,
        sourceTimestampMs: Long?
    ) {
        if (valueKcal <= 0) return
        viewModelScope.launch {
            val latest = repository.latestBmrSnapshot()
            if (latest != null && latest.valueKcal == valueKcal) return@launch

            val today = LocalDate.now().toEpochDay()
            val sourceStr = if (source == BmrSource.SAMSUNG_HEALTH)
                com.example.data.local.BmrSnapshotEntity.SOURCE_SH
            else com.example.data.local.BmrSnapshotEntity.SOURCE_MANUAL
            repository.insertBmrSnapshot(
                com.example.data.local.BmrSnapshotEntity(
                    effectiveDateEpochDay = today,
                    valueKcal = valueKcal,
                    source = sourceStr,
                    sourceTimestampMs = sourceTimestampMs,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            android.util.Log.d(
                "[BMR]",
                "appended snapshot effectiveDay=$today value=$valueKcal source=$source"
            )
        }
    }

    /**
     * Inserts a [BodyCompositionSnapshotEntity] for [reading] when the
     * source timestamp doesn't already exist in the table. Same physical
     * weigh-in re-read on a later sync collapses on the dedup check —
     * we don't want duplicate rows for one measurement.
     *
     * Refuses to persist a reading without a [sourceTimestampMs] (null
     * means we don't know when the scale wrote it, so we can't dedup and
     * would risk inserting a fresh row on every sync). In practice every
     * S-Health BodyComposition entry has a startTime, so this guard fires
     * only if the SDK returns null unexpectedly.
     */
    private fun appendBodyCompositionSnapshotIfNew(
        reading: com.example.data.health.BodyCompositionReading
    ) {
        val sourceTs = reading.timestamp?.toEpochMilli() ?: return
        viewModelScope.launch {
            if (repository.bodyCompositionSnapshotExists(sourceTs)) return@launch
            repository.insertBodyCompositionSnapshot(
                com.example.data.local.BodyCompositionSnapshotEntity(
                    sourceTimestampMs = sourceTs,
                    capturedAtMs = System.currentTimeMillis(),
                    weightKg = reading.weightKg,
                    bodyFatPct = reading.bodyFatPct,
                    muscleMassKg = reading.muscleMassKg,
                    totalBodyWaterKg = reading.totalBodyWaterKg,
                    bmiValue = reading.bmiValue,
                    heightCm = reading.heightCm,
                )
            )
            android.util.Log.d(
                "[BODY_COMP]",
                "appended snapshot sourceTs=$sourceTs weight=${reading.weightKg} bf%=${reading.bodyFatPct} muscle=${reading.muscleMassKg}"
            )
        }
    }

    fun createDefaultSystemInstruction(): String {
        return createDefaultSystemInstruction(sessionType = "personal")
    }

    /** Phase G: build a system instruction tailored to the kind of consultation
     *  the session represents. "nutritionist" emphasizes structured macro
     *  analysis and explicit references to ranges. "personal_trainer"
     *  emphasizes activity, recovery, and effort metrics. "personal" is the
     *  legacy default. */
    fun createDefaultSystemInstruction(sessionType: String): String {
        // Build 1.5 (2026-06-27): the legacy 3-way fork (personal /
        // nutritionist / personal_trainer) produced answers that were
        // either too vague ("personal" had zero bias) or one-sided
        // ("nutritionist" would not comment on training, the "personal
        // trainer" would not comment on macros). The user wants a single
        // integrated persona that always reasons across nutrition AND
        // physical activity, anchors every recommendation to a real
        // number from the snapshot, and pushes one concrete next-step
        // action. The `sessionType` parameter is kept for source/binary
        // compatibility with existing sessions stored in the DB, but it
        // no longer changes the system prompt — all new and old sessions
        // get the same unified bias.
        @Suppress("UNUSED_PARAMETER") val _legacySessionType = sessionType
        val base = buildSystemInstructionForTargets(
            profile = _userProfile.value,
            kcal = _userProfile.value.targetKcal,
            protein = _userProfile.value.targetProtein.toInt(),
            carbs = _userProfile.value.targetCarbs.toInt(),
            fat = _userProfile.value.targetFat.toInt()
        )
        val unifiedBias =
            "\n\nThis session is an INTEGRATED NUTRITION + TRAINING CONSULT. " +
                "Your persona blends a registered dietitian with a certified " +
                "personal trainer / coach — never separate the two. EVERY answer " +
                "must reason across BOTH dimensions when both are relevant.\n\n" +
                "Hard rules — objectivity & confidence (CRITICAL):\n" +
                "- Default to ONE-SHOT answers anchored in numbers from the live " +
                "snapshot. Do NOT ask the user 'what kind of advice do you want?' or " +
                "'are you asking as a nutritionist or trainer?' — assume integrated.\n" +
                "- Every recommendation MUST cite the date + the actual number from " +
                "the PRE-FETCHED DATA block (e.g. \"no dia 25/06 ficaste em 62g de " +
                "proteína vs alvo 85g — sobe para 1 ovo extra ao pequeno-almoço\"). " +
                "NEVER recommend something vague like \"considera mais proteína\" " +
                "without the concrete number AND date that justify it.\n" +
                "- ALWAYS end an analytical answer with 1–2 concrete next-step " +
                "ACTIONS (1 nutritional + 1 physical when both are relevant). " +
                "Format the actions as a short list with verbs in the imperative " +
                "(\"Acrescenta\", \"Caminha\", \"Reduz\", \"Substitui\").\n" +
                "- When the user asks an analytical/state question (\"como tem corrido " +
                "X?\", \"como estou de Y?\"), the answer pattern is:\n" +
                "  1. State of play in 1–2 sentences with the headline number from " +
                "the snapshot;\n" +
                "  2. Trend across the window (improving / stable / declining), " +
                "anchored in the dated rows;\n" +
                "  3. 1–2 imperative actions tailored to the next 24h (consider time " +
                "of day, weather from [CONTEXTO LOCAL], and recent training history).\n" +
                "- Cross-domain reasoning: when commenting nutrition, anchor it to " +
                "the workouts of the same date when relevant (\"no dia 25 fizeste 60min " +
                "de força — 95g de proteína nesse dia está bem para hipertrofia\"). When " +
                "commenting training, anchor it to the meals (\"ontem fizeste 75min " +
                "de cardio mas só comeste 180g de hidratos — para a próxima sessão " +
                "longa sobe os carbs\").\n" +
                "- Outdoor recommendations are CONDITIONAL on the `[CONTEXTO LOCAL]` " +
                "block. If precipitation probability > 30% OR temperature outside " +
                "10–32°C, suggest an indoor alternative (treadmill, mat workout, " +
                "indoor cycling) and SAY WHY based on the forecast. NEVER recommend " +
                "outdoor exercise without checking the line.\n" +
                "- Time-of-day awareness (use the `Now:` line in the canonical " +
                "snapshot): never recommend heavy/intense cardio after 21h; never " +
                "recommend heavy meals before 7h; prefer light activity (walk, " +
                "mobility) in the evening; reserve strength sessions for daytime " +
                "windows when possible.\n" +
                "- Anti-overtraining: if the last 3 days show ≥2 sessions of " +
                "high-intensity cardio OR strength, do NOT recommend another " +
                "intense session — recommend mobility, walking, or a rest day, " +
                "and explain why citing the dated sessions.\n" +
                "- NEVER contradict the live data. If the snapshot says \"protein " +
                "today = 95g, target = 85g\" do not write \"falta-te proteína\".\n\n" +
                "Evidence-based anchors (use these as defaults when the user has " +
                "no explicit prescribed targets, and as upper/lower bounds when " +
                "evaluating progress):\n" +
                "- Protein: 0.8–1.2 g/kg body weight/day for sedentary adults; " +
                "1.2–1.6 g/kg for active adults / endurance; 1.6–2.2 g/kg for " +
                "strength training / hypertrophy (ACSM / ISSN position stand).\n" +
                "- Physical activity (WHO 2020): 150–300 min/week moderate aerobic " +
                "OR 75–150 min/week vigorous, PLUS ≥2 strength sessions/week " +
                "covering major muscle groups, PLUS mobility/balance work for " +
                "older adults.\n" +
                "- Hydration: ~30–35 ml/kg/day baseline; add 400–800 ml per hour " +
                "of sustained training.\n" +
                "- Sleep: 7–9 h/night for adults (CDC / NSF). Note when meal " +
                "timing or training load may be impacting sleep.\n" +
                "- Carbs around training: 1–4 g/kg in the 1–4h before endurance " +
                "sessions >60min; 0.6–1.0 g/kg + 20–25g protein in the 30min " +
                "post-strength.\n" +
                "- Fibre: 25–38 g/day; flag persistent shortfalls.\n" +
                "- Saturated fat: keep < 10% of total daily calories (WHO).\n" +
                "- Added sugars: keep < 10% of total daily calories (WHO), ideally " +
                "< 5%.\n" +
                "Apply these as ranges, not absolutes — never quote the boundary as " +
                "a hard rule against the user. Always favour the user's prescribed " +
                "targets when they exist (the snapshot's daily targets are the " +
                "ground truth)."
        return base + unifiedBias
    }

    /**
     * System instruction anchored to the targets that were prescribed on a specific
     * past date. Use this when a future feature analyses a historical period and we
     * want the model to compare consumption against the targets from BACK THEN, not
     * against today's. Today's reanalyzeFoodFromSummary path doesn't pass targets to
     * the model at all (it's purely nutritional estimation), so no caller exists yet
     * — but the helper keeps the wiring honest if/when one is added.
     */
    suspend fun createSystemInstructionForDate(date: LocalDate): String {
        val snap = repository.findTargetEffectiveAt(date.toEpochDay())
        val p = _userProfile.value
        return if (snap == null) {
            createDefaultSystemInstruction()
        } else {
            buildSystemInstructionForTargets(
                profile = p,
                kcal = snap.targetKcal,
                protein = snap.targetProtein.toInt(),
                carbs = snap.targetCarbs.toInt(),
                fat = snap.targetFat.toInt()
            )
        }
    }

    private fun buildSystemInstructionForTargets(
        profile: UserProfile,
        kcal: Int,
        protein: Int,
        carbs: Int,
        fat: Int
    ): String {
        // 2026-06-28 — persona body now lives in
        // [com.example.data.chat.ChatToneRules.personaFor] (single source of
        // truth shared with GeminiRepository.createNewSession and
        // ProactivePromptBuilder.baseSystemInstruction). The previous
        // i18n-templated literal ("elegant professional dietitian assistant")
        // was flagged by the user as academic / Instagram-coach. The new
        // persona keeps Hugo's name + the targets but reframes the LLM as
        // "friend who happens to know nutrition" with an explicit list of
        // forbidden phrases (which [ChatToneRules.clean] also strips post-
        // hoc as a deterministic net).
        val persona = com.example.data.chat.ChatToneRules.personaFor(profile.name)
        val base = persona + "\n\n" +
            "Daily targets to honour: ${kcal} kcal, ~${protein}g protein, " +
            "~${carbs}g carbs, ~${fat}g fat. " +
            "User context: ${profile.gender}, born ${profile.birthYear}, " +
            "activity level ${profile.activityLevel}. " +
            "All responses MUST BE in language code: ${profile.language}."
        // Anti-history-pollution directive (2026-06-22): the model has strong
        // in-context learning and will mimic prior assistant turns. Older
        // builds shipped with `UserProfile.name = "Humano"` as a default and
        // produced replies starting with "Olá, Humano!" until the user opened
        // Settings to fix it. Those turns sit in the chat history forever and
        // Gemini Flash picks up the pattern on subsequent sends — even with
        // the correct system instruction. This explicit clause overrides the
        // history pattern: the user's name is `${profile.name}`, period.
        val nameDirective = "\n\nCRITICAL — USER NAME: The user's actual name is " +
            "'${profile.name}'. If any earlier turn in this conversation " +
            "addressed the user by a different name (for example 'Humano', " +
            "a placeholder, or any other label), that was an artefact of an " +
            "older app version — DO NOT repeat it. Address the user as " +
            "'${profile.name}' from now on."
        // 2026-06-22 (plan: ADENDO 2 — Fix 8) — Order the prompt so the
        // longest STABLE chunk comes first. HABIT_PROTOCOL is a 9.3kB
        // constant identical across every user, every session and every
        // turn → putting it at the start gives the Google implicit
        // caching layer a long byte-identical prefix to hit on. The
        // user-specific dynamic part (template + nameDirective) follows.
        return com.example.data.remote.HABIT_PROTOCOL + base + nameDirective
    }

    // ----- Drive export state -----
    // The Reports tab writes a single `diario_nutricao.xlsx` to a Drive folder the user
    // picks once via SAF (OpenDocumentTree). The persisted Uri is opaque (a tree URI of
    // the form `content://com.google.android.apps.docs.storage/...`) — we only need it
    // to call DocumentFile.fromTreeUri / createFile on subsequent exports. The Drive
    // app on the device handles the actual upload to the user's Google account, so we
    // never touch any OAuth scope or Sheets API.
    private val _exportFolderUri = MutableStateFlow<String?>(null)
    val exportFolderUri: StateFlow<String?> = _exportFolderUri.asStateFlow()

    private val _exportFolderName = MutableStateFlow<String?>(null)
    val exportFolderName: StateFlow<String?> = _exportFolderName.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _exportSuccess = MutableStateFlow<String?>(null)
    val exportSuccess: StateFlow<String?> = _exportSuccess.asStateFlow()

    /** SAF document URI of the file produced by the most recent successful export.
     *  Tapping the success banner on the Reports tab fires an ACTION_VIEW with this
     *  URI so the XLSX opens in Google Sheets / Drive. Cleared together with
     *  [_exportSuccess] when the user taps OK or a new export starts. */
    private val _exportSuccessUri = MutableStateFlow<String?>(null)
    val exportSuccessUri: StateFlow<String?> = _exportSuccessUri.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    /** Epoch ms of the last successful Drive export (manual or background). Reflects
     *  what's currently persisted in the `nutrition_export` SharedPreferences file
     *  under [com.example.data.export.DriveExporter.KEY_LAST_EXPORT_AT_MS]; the
     *  Worker writes there directly, so when the Reports tab calls
     *  [loadLastExportAtMs] after coming back to foreground it sees the latest
     *  value regardless of whether the manual button or the background job
     *  produced it. null = never exported successfully. */
    private val _lastExportAtMs = MutableStateFlow<Long?>(null)
    val lastExportAtMs: StateFlow<Long?> = _lastExportAtMs.asStateFlow()

    fun loadLastExportAtMs(context: android.content.Context) {
        _lastExportAtMs.value = com.example.data.export.DriveExporter.loadLastExportAtMs(context)
    }

    fun setExportError(message: String?) { _exportError.value = message }
    fun clearExportError() { _exportError.value = null }
    fun clearExportSuccess() {
        _exportSuccess.value = null
        _exportSuccessUri.value = null
    }

    fun loadExportFolderUri(context: android.content.Context) {
        val prefs = context.getSharedPreferences("nutrition_export", android.content.Context.MODE_PRIVATE)
        _exportFolderUri.value = prefs.getString("folder_uri", null)
        _exportFolderName.value = prefs.getString("folder_name", null)
    }

    fun saveExportFolderUri(context: android.content.Context, uri: String, folderName: String?) {
        val prefs = context.getSharedPreferences("nutrition_export", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("folder_uri", uri)
            .putString("folder_name", folderName)
            .apply()
        _exportFolderUri.value = uri
        _exportFolderName.value = folderName
    }

    /**
     * Generates a `.xlsx` covering the entire DB and writes it into the previously
     * selected SAF folder, replacing any prior `diario_nutricao.xlsx`. Idempotent: the
     * resulting Drive URL is stable across exports because the file path inside the
     * folder is fixed. Network sync is the Drive app's responsibility — even offline,
     * the file is queued and uploads when connectivity returns.
     *
     * Phase G: optional [fromDate]/[toDate] window filters foods + activities to
     * the inclusive range. [filenameOverride] lets ad-hoc reports from the chat
     * coexist with the canonical `diario_nutricao.xlsx` instead of overwriting it.
     */
    fun exportNutritionXlsx(
        context: android.content.Context,
        fromDate: java.time.LocalDate? = null,
        toDate: java.time.LocalDate? = null,
        filenameOverride: String? = null
    ) {
        val folderUriStr = _exportFolderUri.value
        if (folderUriStr.isNullOrBlank()) {
            _exportError.value = tr("Please select the Drive folder first.", _userProfile.value.language)
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isExporting.value = true
            _exportError.value = null
            _exportSuccess.value = null
            _exportSuccessUri.value = null
            try {
                // Snapshot the current dataset. The exporter consumes the full DB —
                // not just the period the Reports tab is showing — because the file
                // is meant to be a complete, share-with-clinic-grade history.
                val zoneIdExport = java.time.ZoneId.systemDefault()
                val rangeFilter: ((Long) -> Boolean)? = if (fromDate != null && toDate != null) {
                    val fromMs = fromDate.atStartOfDay(zoneIdExport).toInstant().toEpochMilli()
                    val toMs = toDate.plusDays(1).atStartOfDay(zoneIdExport).toInstant().toEpochMilli() - 1
                    ({ ts: Long -> ts in fromMs..toMs })
                } else null
                val foodsAll = repository.allFoods.firstOrNull() ?: emptyList()
                val activitiesAll0 = repository.allActivities.firstOrNull() ?: emptyList()
                val foods = if (rangeFilter != null) foodsAll.filter { rangeFilter(it.timestamp) } else foodsAll
                val activitiesAll = if (rangeFilter != null) activitiesAll0.filter { rangeFilter(it.timestamp) } else activitiesAll0

                val shAvailable = _shStatus.value == SamsungHealthRepository.Status.AVAILABLE
                val mergedActivityRows = buildReportActivityRows(
                    manuals = activitiesAll,
                    shWorkouts = if (shAvailable) _shWorkouts.value else emptyList(),
                    shDaily = if (shAvailable) _shDailyActivity.value else emptyMap(),
                    shAvailable = shAvailable,
                    zoneId = java.time.ZoneId.systemDefault()
                )

                // Group SH workouts by date for the day-cell summary the exporter builds.
                val shWorkoutsByDate = if (shAvailable) {
                    mergedActivityRows
                        .filter { it.source == ReportActivityRow.Source.SAMSUNG_HEALTH_WORKOUT }
                        .groupBy { row ->
                            val matched = _shWorkouts.value.firstOrNull { sw ->
                                // Match key must compute the same string as the row's `name`
                                // above (buildReportActivityRows) — both sides go through
                                // ShWorkout.displayLabel so a Pilates row matches its own
                                // source workout instead of a stale "Yoga / Stretch" fallback.
                                sw.displayLabel == row.name &&
                                java.time.Duration.between(sw.startTime, sw.endTime).toMinutes().toInt() == row.durationMinutes
                            }
                            matched?.startTime?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
                                ?: java.time.LocalDate.now()
                        }
                } else emptyMap()

                // Resolve which target snapshot was in force on each day that appears in
                // the dataset. The exporter prints the per-day meta on every meal row and
                // the accumulated meta on the daily/weekly/monthly TOTAL rows, so a target
                // change today does not silently re-anchor the older days against today's
                // numbers. See TargetResolver / target_snapshots.
                val targetSnapshotsList = repository.listTargetSnapshots()
                val targetsByDay: Map<java.time.LocalDate, com.example.data.local.TargetSnapshot> =
                    if (foods.isEmpty() || targetSnapshotsList.isEmpty()) emptyMap()
                    else {
                        val datesPresent = foods
                            .map { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zoneIdExport).toLocalDate() }
                            .toSortedSet()
                        val firstDay = datesPresent.first()
                        val lastDay = datesPresent.last()
                        com.example.data.targets.TargetResolver.resolveTargetsByDay(
                            targetSnapshotsList,
                            firstDay..lastDay
                        )
                    }

                // Frozen daily snapshots + full BMR history — used by the
                // exporter to compute the per-day "Gasto Total (kcal)" column
                // via DailyActivityResolver + BmrResolver, matching the value
                // shown in the Análise tab for the same day/week/month.
                val dailyActivitySnapshotsList =
                    repository.dailyActivitySnapshots.firstOrNull() ?: emptyList()
                val bmrSnapshotsAll =
                    repository.bmrSnapshots.firstOrNull() ?: emptyList()

                val inputs = com.example.data.export.DriveExporter.ExportInputs(
                    foods = foods,
                    activitiesManual = activitiesAll,
                    mergedActivityRows = mergedActivityRows,
                    stepsByDate = if (shAvailable) _shStepsByDate.value else emptyMap(),
                    shWorkoutsByDate = shWorkoutsByDate,
                    bmrSnapshot = bmr.value,
                    targetsByDay = targetsByDay,
                    language = _userProfile.value.language,
                    dailyActivitySnapshots = dailyActivitySnapshotsList,
                    shWorkoutsRaw = if (shAvailable) _shWorkouts.value else emptyList(),
                    shDailyByDate = if (shAvailable) _shDailyActivity.value else emptyMap(),
                    bmrSnapshotsAll = bmrSnapshotsAll,
                    shAvailable = shAvailable,
                )

                val lang = _userProfile.value.language
                when (val result = com.example.data.export.DriveExporter.export(context, inputs, filenameOverride)) {
                    is com.example.data.export.DriveExporter.ExportResult.Success -> {
                        _lastExportAtMs.value = com.example.data.export.DriveExporter.loadLastExportAtMs(context)
                        _exportSuccess.value = tr("Exported to Drive", lang) +
                            " (${result.folderName ?: ""}/${result.fileName})"
                        _exportSuccessUri.value = result.fileUri
                        // Piggy-back the .db snapshot. Logged-only failure: the
                        // XLSX is what the user picked the button for; the DB
                        // backup is a silent safety net behind it.
                        try {
                            com.example.data.export.DbBackupExporter.export(context)
                        } catch (e: Exception) {
                            android.util.Log.w("ChatViewModel", "db backup piggyback failed", e)
                        }
                    }
                    is com.example.data.export.DriveExporter.ExportResult.Failure -> {
                        _exportError.value = when (result.reason) {
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.NO_FOLDER ->
                                tr("Please select the Drive folder first.", lang)
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.FOLDER_UNREACHABLE ->
                                tr("Drive folder unreachable — please re-select it.", lang)
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.CREATE_FAILED ->
                                tr("Could not create the file in the chosen folder.", lang)
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.OPEN_OUTPUT_FAILED ->
                                tr("Could not open the file for writing.", lang)
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.ZERO_BYTES_AFTER_WRITE ->
                                tr("File appears empty after write — please try again.", lang)
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.PERMISSION_REVOKED ->
                                tr("Permission revoked — please re-select the Drive folder.", lang)
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.BUILDER_EMPTY,
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.IO,
                            com.example.data.export.DriveExporter.ExportResult.Failure.Reason.UNKNOWN ->
                                result.message
                        }
                    }
                }
            } catch (e: Exception) {
                _exportError.value = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                _isExporting.value = false
            }
        }
    }


    fun selectSession(sessionId: Long) {
        // 2026-07-07 (Fix #3) — cancel any in-flight send when the user
        // switches sessions. Previously the sendJob kept running against
        // the OLD sessionId (captured by closure in ChatOrchestrator), so
        // the assistant reply would land in the previous session's Room
        // rows and the user — now looking at the new session — perceived
        // it as "sem resposta". The typing indicator also stayed pinned
        // across the switch because _isTyping is session-agnostic. Cancel
        // eagerly and let the send flow's finally clear state. If the
        // user re-selects the source session before the cancel propagates,
        // the flow's `catch` swallows the CancellationException and the
        // orphaned Room row (already inserted eagerly for the user turn)
        // remains — same behaviour as the ✕ button.
        if (_selectedSessionId.value != sessionId && sendJob?.isActive == true) {
            android.util.Log.d(
                "CHAT_ORCH",
                "selectSession cancels in-flight sendJob (from=${_selectedSessionId.value} to=$sessionId)"
            )
            cancelSendJob()
        }
        _selectedSessionId.value = sessionId
    }

    // ---- Bloco I (proactive notifications): one-shot tab switch signal ----
    // The notification tap path in MainActivity.handleNotificationIntent
    // calls setPendingTabSwitch(AppTab.CHAT) after selectSession. The root
    // ChatScreen composable collects this flow and animateScrollToPage's the
    // pager, then calls consumeTabSwitch() to clear it. StateFlow rather than
    // SharedFlow so the value survives process death + redelivery.
    private val _pendingTabSwitch = MutableStateFlow<AppTab?>(null)
    val pendingTabSwitch: StateFlow<AppTab?> = _pendingTabSwitch.asStateFlow()
    fun setPendingTabSwitch(tab: AppTab) { _pendingTabSwitch.value = tab }
    fun consumeTabSwitch() { _pendingTabSwitch.value = null }

    // ---- 2026-06-15 cross-conversation search (WhatsApp-style) -------------
    //
    // Search lives at the VM layer so the result list is rebuildable
    // across config changes (rotation, keyboard, theme switch) without
    // re-querying SQLite. Flow is:
    //   1. UI calls [updateSearchQuery] on every keystroke.
    //   2. [searchQuery] StateFlow debounces to FTS via flatMapLatest below
    //      (200 ms — same as Gmail / WhatsApp on Android feels-like).
    //   3. [searchResults] surfaces the list to the UI.
    //   4. On a tap of a result, the UI calls [jumpToMessage], which
    //      switches sessions if needed and asks the chat list to scroll
    //      to the message via the [pendingJumpToMessageId] one-shot flow.

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @kotlinx.coroutines.FlowPreview
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    val searchResults: StateFlow<List<com.example.data.local.MessageSearchHit>> =
        _searchQuery
            .debounce(200L)
            .flatMapLatest { q ->
                kotlinx.coroutines.flow.flow {
                    if (q.trim().length < 2) {
                        emit(emptyList())
                    } else {
                        emit(repository.searchChatMessages(q))
                    }
                }
            }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L),
                emptyList()
            )

    fun updateSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
    }

    /**
     * One-shot signal: when non-null, the active chat list should scroll
     * to (and briefly highlight) the message with this id, then call
     * [consumeJumpRequest] to clear the latch. Set by [jumpToMessage]
     * after switching sessions if necessary.
     */
    private val _pendingJumpToMessageId = MutableStateFlow<Long?>(null)
    val pendingJumpToMessageId: StateFlow<Long?> = _pendingJumpToMessageId.asStateFlow()

    fun jumpToMessage(sessionId: Long, messageId: Long) {
        if (_selectedSessionId.value != sessionId) {
            _selectedSessionId.value = sessionId
        }
        _pendingJumpToMessageId.value = messageId
        // Close the search overlay after navigation. UI also clears the
        // query so re-opening starts fresh.
        _searchQuery.value = ""
    }

    fun consumeJumpRequest() {
        _pendingJumpToMessageId.value = null
    }

    /**
     * Persist the currently-selected session id so a cold app start can
     * resume the same conversation. Called by the Chat tab whenever the
     * active session changes (so we don't have to thread Context through
     * every selectSession call site).
     */
    fun rememberSelectedSession(context: android.content.Context) {
        if (!sessionPersistenceReady) return
        val id = _selectedSessionId.value ?: return
        context.getSharedPreferences("user_profile_pref", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("last_selected_session_id", id)
            .apply()
    }

    /**
     * Gate for [rememberSelectedSession] — flipped to true once
     * [loadUserProfile] has had a chance to restore the saved session id.
     * This prevents the init block's auto-select from trampling the saved
     * id before the restore happens.
     */
    private var sessionPersistenceReady = false

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun updateModelSelection(model: String) {
        _selectedModel.value = model
    }

    fun updateSystemInstructionInput(instruction: String) {
        _systemInstructionJson.value = instruction
    }

    fun createSession() {
        createSession(sessionType = "personal")
    }

    /**
     * Phase G: create a chat session of a specific type. The session-type bias
     * is baked into the system instruction at creation time so the model
     * answers in the right register for the rest of the conversation.
     *
     * Accepted values: `"personal"` (default), `"nutritionist"`,
     * `"personal_trainer"`. Unknown values fall through to the personal bias.
     */
    fun createSession(sessionType: String) {
        viewModelScope.launch {
            val instruction = if (_systemInstructionJson.value.isBlank()) {
                createDefaultSystemInstruction(sessionType)
            } else {
                _systemInstructionJson.value
            }
            // User-owns-creation rule: if the currently selected session is
            // still an untouched "New Chat" (default title, zero messages),
            // re-purpose it instead of spawning a duplicate. This prevents the
            // drawer filling up with empty "New Chat" rows when the user taps
            // "+ Nova Consulta" on a session they never wrote in.
            val sel = _selectedSessionId.value
            val existing = sel?.let { id -> sessions.value.firstOrNull { it.id == id } }
            if (existing != null && existing.title == "New Chat") {
                val msgs = runCatching {
                    repository.getMessagesForSessionFlow(existing.id).first()
                }.getOrDefault(emptyList())
                if (msgs.isEmpty()) {
                    repository.updateSession(
                        existing.copy(
                            systemInstruction = instruction,
                            sessionType = sessionType
                        )
                    )
                    _systemInstructionJson.value = ""
                    return@launch
                }
            }
            val newId = repository.createNewSession(
                systemInstruction = instruction,
                model = "gemini-3.5-flash",
                sessionType = sessionType
            )
            _selectedSessionId.value = newId
            _systemInstructionJson.value = "" // reset input draft
        }
    }

    fun deleteSession(session: ChatSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
            // If we deleted the active one, pick another or clear
            if (_selectedSessionId.value == session.id) {
                val remaining = sessions.value.filter { it.id != session.id }
                if (remaining.isNotEmpty()) {
                    _selectedSessionId.value = remaining.first().id
                } else {
                    _selectedSessionId.value = null
                }
            }
        }
    }

    fun renameSession(sessionId: Long, nextTitle: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId, nextTitle)
        }
    }

    fun clearHistory(sessionId: Long) {
        viewModelScope.launch {
            repository.clearSessionHistory(sessionId)
        }
    }

    fun updateActiveSessionParams(model: String, systemInstruction: String) {
        val current = activeSession.value ?: return
        viewModelScope.launch {
            repository.updateSession(
                current.copy(
                    model = model,
                    systemInstruction = systemInstruction
                )
            )
        }
    }

    /**
     * Build the `[CONTEXTO LOCAL]` block appended to the system prompt
     * once per turn. Carries time-of-day (always available) plus the
     * IPMA weather forecast (only when permission was granted AND the
     * user is geographically inside Portugal). Both pieces are silently
     * omitted when not available so the chat keeps working with zero
     * context loss. The block is short by design (≤ ~250 chars) so it
     * doesn't bloat the cached prefix.
     *
     * Output example (PT, permission granted, weather resolved):
     * ```
     * [CONTEXTO LOCAL]
     * Now: 2026-06-27 21:14 (Saturday, evening).
     * Weather (IPMA, Lisboa): hoje 18–28°C, céu pouco nublado, prob chuva 0%. Amanhã: 17–27°C, céu limpo, prob chuva 5%.
     * Outdoor exercise today: FAVOURABLE (use this when recommending walks/runs/rides).
     * [/CONTEXTO LOCAL]
     * ```
     */
    /** 2026-07-06 (Frente B) — internal builder used ONLY by
     *  [prefetchLocalContext]. Never call directly from the send path —
     *  read `_localContextBlock.value` instead. Wrapping timeouts around
     *  the two suspend calls (GPS + IPMA weather) is defence in depth:
     *  they were on the send critical path before this front and could
     *  add seconds of latency; the prefetch now runs on VM init / on
     *  chat tab resume, so a slow fix or a slow IPMA no longer blocks
     *  the user's message.
     *
     *  Bounds:
     *   - `helper.current()` — 1500 ms (a cold GPS + wifi/cell fix
     *     usually takes <400ms; anything above is Play Services
     *     misbehaving). On timeout falls back to the last cached fix
     *     inside [LocationHelper] (see its own withTimeoutOrNull guard).
     *   - `fetcher.getWeatherSummary()` — 3000 ms. The fetcher already
     *     memoises the city index (7d) and the forecast (30 min) in
     *     SharedPreferences, so a warm run is <100ms. The 3s cap
     *     covers a cold city-index fetch on flaky networks.
     *
     *  Any failure (permission denied, timeout, HTTP error) leaves the
     *  block empty — same graceful degradation as the pre-Frente-B code
     *  had via bare `catch (_: Exception)`; the difference is those
     *  failures are now visible via `CHAT_LOCAL_PREFETCH` breadcrumbs
     *  instead of silently swallowed.
     */
    private suspend fun buildLocalContextBlock(): String {
        val now = java.time.LocalDateTime.now()
        val dayName = now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL,
            com.example.ui.localeForLanguage(_userProfile.value.language)
        )
        val partOfDay = when (now.hour) {
            in 5..11 -> "morning"
            in 12..14 -> "midday"
            in 15..18 -> "afternoon"
            in 19..22 -> "evening"
            else -> "night"
        }
        val sb = StringBuilder()
        sb.append("\n\n[CONTEXTO LOCAL]\n")
        sb.append("Now: ${now.toLocalDate()} ${"%02d:%02d".format(now.hour, now.minute)} ($dayName, $partOfDay).\n")

        val helper = locationHelper
        val fetcher = weatherFetcher
        if (helper != null && fetcher != null && helper.hasPermission()) {
            val locStartedMs = System.currentTimeMillis()
            val fix = try {
                kotlinx.coroutines.withTimeoutOrNull(1500L) { helper.current() }
            } catch (t: Throwable) {
                android.util.Log.w("CHAT_LOCAL_PREFETCH", "location fix failed: ${t.message?.take(120)}")
                null
            }
            val locMs = System.currentTimeMillis() - locStartedMs
            if (fix != null) {
                val wxStartedMs = System.currentTimeMillis()
                val summary = try {
                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        fetcher.getWeatherSummary(fix.lat, fix.lon)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("CHAT_LOCAL_PREFETCH", "IPMA fetch failed: ${t.message?.take(120)}")
                    null
                }
                val wxMs = System.currentTimeMillis() - wxStartedMs
                if (summary != null) {
                    sb.append("Weather (IPMA, ${summary.city}): ${summary.toPromptLine()}\n")
                    val friendly = if (summary.isOutdoorFriendlyToday()) "FAVOURABLE" else "UNFAVOURABLE"
                    sb.append("Outdoor exercise today: $friendly (use this when recommending walks/runs/rides).\n")
                }
                android.util.Log.i("CHAT_LOCAL_PREFETCH", "locMs=$locMs weatherMs=$wxMs weatherOk=${summary != null}")
            } else {
                android.util.Log.i("CHAT_LOCAL_PREFETCH", "locMs=$locMs fix=null (skipped weather)")
            }
        }
        sb.append("[/CONTEXTO LOCAL]")
        return sb.toString()
    }

    /** 2026-07-06 (Frente B) — kick a background coroutine that rebuilds
     *  [_localContextBlock]. Cheap enough to fire on VM init and on every
     *  chat-tab resume: the OkHttp caches inside [IpmaWeatherFetcher]
     *  make repeat calls almost free after the first successful one.
     *
     *  A previously-in-flight prefetch is cancelled so a rapid resume
     *  doesn't queue multiple GPS fixes. This matches the "fire-and-forget
     *  but only one at a time" contract the proactive path already uses
     *  via [proactiveJob].
     */
    fun prefetchLocalContext() {
        localContextPrefetchJob?.cancel()
        localContextPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val block = runCatching { buildLocalContextBlock() }.getOrElse {
                android.util.Log.w("CHAT_LOCAL_PREFETCH", "prefetch aborted: ${it.message?.take(120)}")
                ""
            }
            _localContextBlock.value = block
            android.util.Log.i(
                "CHAT_LOCAL_PREFETCH",
                "totalMs=${System.currentTimeMillis() - started} blockLen=${block.length}"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Voice Mode / TTS control (2026-07-05)
    // ─────────────────────────────────────────────────────────────────
    /** Read [msg] aloud using the active profile language. Called from the
     *  per-bubble 🔊 button in MessageBubbleItem. Second tap on the same
     *  bubble stops (see TtsController.speak semantics). */
    fun speakMessage(msg: ChatMessage) {
        val tts = ttsController ?: return
        // TTS mode (2026-10-05): prefer the spoken script when the model
        // emitted one — it's written for the ear (radio-briefing register),
        // not a markdown-stripped copy of the visible reply. Falls back to
        // the cleaned text (proposal markers, [CHART], etc. already
        // stripped) so the TTS engine doesn't spell them out, then to the
        // raw message text if no cleaned copy exists yet.
        val readable = _ttsSummaryText.value[msg.id]
            ?: _cleanedMessageText.value[msg.id]
            ?: msg.text
        tts.speak(readable, _userProfile.value.language, msg.id)
    }

    /** Speak the most recent assistant reply in the active session. Used by
     *  the floating 🔊 button in the input row — the user shouldn't have to
     *  scroll to find the right bubble; the last one is almost always what
     *  they want to hear. Second tap toggles stop (via TtsController).
     *  No-op when the session has no assistant messages yet, or when the
     *  latest message is an error bubble. */
    fun speakLastAssistantMessage() {
        val list = activeMessages.value
        val target = list.lastOrNull { it.sender != "user" && !it.isError } ?: return
        speakMessage(target)
    }

    /** Stop any current TTS playback. */
    fun stopSpeaking() {
        ttsController?.stop()
    }

    /** Toggle the hands-free Voice Mode pill in the input row.
     *  Idle → Listening: SpeechRecognizer starts, silence timer auto-submits.
     *  Any other state → Idle: cancels mic + TTS. */
    fun toggleVoiceMode() {
        val stt = speechRecognizer ?: return
        if (_voiceMode.value != VoiceMode.Idle) {
            stt.cancel()
            ttsController?.stop()
            _voiceMode.value = VoiceMode.Idle
            return
        }
        startVoiceListening()
    }

    /** Kick off (or re-arm) a listening session. Used by [toggleVoiceMode]
     *  and by the auto-loop when TTS finishes speaking a reply. */
    private fun startVoiceListening() {
        val stt = speechRecognizer ?: return
        val lang = _userProfile.value.language
        _voiceMode.value = VoiceMode.Listening
        stt.startListening(
            language = lang,
            onPartial = { partial ->
                // Mirror partial results into the input field so the user
                // sees what the recognizer heard. If they tap the send
                // button anyway, sendMessage() picks it up straight from
                // _inputText.value.
                _inputText.value = partial
            },
            onFinal = { finalText ->
                _inputText.value = finalText
                _voiceMode.value = VoiceMode.Thinking
                // sendMessage() reads _inputText.value; we've already
                // populated it. The auto-speak loop reacts to bubble
                // insertions in [observeVoiceModeAutoSpeak].
                sendMessage()
            },
            onSessionEnded = { _ ->
                // Mic session died without capturing anything (silence window
                // elapsed, or NO_MATCH/SPEECH_TIMEOUT with no partial). Leave
                // Listening so the pill stops looking active — the user must
                // tap the mic again to speak. This is the fix for "I spoke but
                // nothing happened": before, the pill stayed lit over a dead
                // recognizer and the user talked into a mic that no longer
                // listened.
                if (_voiceMode.value == VoiceMode.Listening) {
                    _voiceMode.value = VoiceMode.Idle
                }
            }
        )
    }

    /** Call from Activity after the RECORD_AUDIO permission dialog resolves. */
    fun onMicPermissionResult(granted: Boolean) {
        speechRecognizer?.clearPermissionFlag()
        if (granted && _voiceMode.value == VoiceMode.Idle) {
            // User granted after tapping the mic in Idle state — start now.
            startVoiceListening()
        }
    }

    /** Set by the `HandleEvent.Done` collector after we speak an assistant
     *  bubble. Guards against double-speaking if a future emission path
     *  fires Done twice for the same id. Also flipped by [speakMessage] to
     *  keep the sentinel in sync with the manual 🔊 button. */
    private var lastSpokenAssistantId: Long = -1L

    private fun observeVoiceModeAutoSpeak() {
        val tts = ttsController ?: return
        @Suppress("UNUSED_VARIABLE") val stt = speechRecognizer ?: return

        // 2026-07-06 — Auto-speak trigger moved out of here. It now lives
        // in the `HandleEvent.Done` collector inside the send flow, so we
        // read the freshly-inserted bubble id directly from the
        // orchestrator instead of racing the Room re-emission against the
        // eager user-row insert (that race caused Voice Mode to speak the
        // PREVIOUS bubble on first-voice-turn / voice-after-text /
        // session-switch).
        //
        // What stays here: the mic re-arm loop that waits for TTS to
        // finish, then jumps back to Listening. Driven by
        // [TtsController.speakingMessageId] going null.
        viewModelScope.launch {
            tts.speakingMessageId.collect { id ->
                if (id == null && _voiceMode.value == VoiceMode.Speaking) {
                    // Small settle delay so the mic doesn't pick up the tail
                    // of the last spoken word from the phone speaker.
                    delay(300)
                    if (_voiceMode.value == VoiceMode.Speaking) {
                        startVoiceListening()
                    }
                }
            }
        }
    }

    private fun handleAssistantDone(event: com.example.data.chat.HandleEvent.Done) {
        _streamingText.value = null
        _isTyping.value = false
        _typingPhase.value = null
        maybeAutoSpeakDone(event)
    }

    private fun maybeAutoSpeakDone(event: com.example.data.chat.HandleEvent.Done) {
        val id = event.insertedMessageId
        val tts = ttsController
        if (id != null && tts != null && _voiceMode.value == VoiceMode.Thinking) {
            // TTS mode: the script extracted at Done (if any) beats the
            // visible text — Voice Mode is exactly the "hands-free briefing"
            // path, so it should hear the ear-written version.
            val readable = event.ttsSummary
                ?: _cleanedMessageText.value[id]
                ?: event.cleanedText
            if (readable.isNotBlank()) {
                lastSpokenAssistantId = id
                _voiceMode.value = VoiceMode.Speaking
                tts.speak(readable, _userProfile.value.language, id)
                android.util.Log.d(
                    "CHAT_VOICE",
                    "auto-speak id=$id chars=${readable.length} script=${event.ttsSummary != null} text=${readable.take(40)}"
                )
            }
        }
    }

    /** Set when a chat turn fails outright (no text was persisted). Carries an
     *  i18n key the UI resolves, so the user sees *why* the reply never
     *  arrived instead of the typing bubble silently vanishing. Cleared on
     *  retry, on a new send, and on session switch. */
    private val _chatSendError = MutableStateFlow<String?>(null)
    val chatSendError: StateFlow<String?> = _chatSendError.asStateFlow()

    /** Snapshot of the last send so [retryLastSend] can replay it verbatim —
     *  including attachments, which are the most expensive thing for a user
     *  to re-pick by hand. */
    private data class PendingSend(
        val sessionId: Long,
        val text: String,
        val imageBase64Stored: String?
    )

    private var lastSend: PendingSend? = null

    /** Re-run the last failed send. The user message row is already in the DB
     *  from the original attempt, so we skip re-inserting it — otherwise the
     *  timeline would show the same question twice. */
    fun retryLastSend() {
        val pending = lastSend ?: return
        if (_isTyping.value) return
        _chatSendError.value = null
        runSend(
            targetSessionId = pending.sessionId,
            textToSend = pending.text,
            imageToSend = pending.imageBase64Stored,
            alreadyInserted = true
        )
    }

    fun clearChatSendError() {
        _chatSendError.value = null
    }

    /** Map a failure to a user-facing i18n key. Network and quota problems are
     *  worth distinguishing because the user's best next action differs:
     *  "check your connection" vs "wait a moment and retry". Delegates to
     *  [com.example.data.chat.ChatOrchestrator.chatErrorKeyFor] (2026-08-31)
     *  so the transient in-memory card and the durable DB row persisted by
     *  the orchestrator on total failure always agree on the same message
     *  for the same cause. */
    private fun chatErrorKeyFor(t: Throwable?): String =
        com.example.data.chat.ChatOrchestrator.chatErrorKeyFor(t)

    fun sendMessage() {
        val textToSend = _inputText.value.trim()
        val sessionId = _selectedSessionId.value
        val imagePaths = _attachedChatImagePaths.value
        val legacySingleImage = _attachedChatImageBase64.value
        // Phase C: prefer the multi-image list when populated; otherwise fall
        // back to the legacy single-image base64 path so any caller that still
        // uses attachChatImage(base64) continues to work.
        val imageToSend: String? = when {
            imagePaths.isNotEmpty() -> ChatImageStore.encode(imagePaths)
            else -> legacySingleImage
        }

        if (textToSend.isEmpty() && imageToSend == null) return
        if (_isTyping.value) return
        if (sendLaunchPendingGeneration != null) return

        // Auto-dismiss any still-pending proposal cards from earlier
        // assistant turns. Hugo's rule (2026-06-13): if the user sends
        // another message instead of tapping Confirm/Discard, the offer is
        // implicitly stale — leave a DISMISSED breadcrumb on each so it
        // doesn't just vanish, but stop showing the actionable card.
        dismissAllPendingProposals()

        // Clear user typing field and image attachments
        _inputText.value = ""
        _attachedChatImageBase64.value = null
        _attachedChatImagePaths.value = emptyList()

        val targetSessionId = sessionId ?: return
        _chatSendError.value = null
        lastSend = PendingSend(targetSessionId, textToSend, imageToSend)
        runSend(
            targetSessionId = targetSessionId,
            textToSend = textToSend,
            imageToSend = imageToSend,
            alreadyInserted = false
        )
    }

    /**
     * Shared body of the send pipeline, used by both the first attempt and
     * [retryLastSend]. [alreadyInserted] skips the eager user-row insert on a
     * retry, where the row is already on screen from the failed attempt.
     */
    private fun runSend(
        targetSessionId: Long,
        textToSend: String,
        imageToSend: String?,
        alreadyInserted: Boolean
    ) {
        val sendGeneration = startSendGeneration()
        sendJob = viewModelScope.launch {
            // Tracks whether the turn produced anything the user can read. If
            // it did not, the `finally` block raises the inline error card
            // rather than letting the typing bubble vanish without a trace.
            var producedReply = false
            var failureCause: Throwable? = null

            // 2026-06-29 — Frente Memory C1.b. Insert the user ChatMessage
            // row EAGERLY here (before the typing indicator paints) so the
            // Room Flow behind the timeline emits the bubble in the same
            // frame the user pressed Send. The previous flow let the
            // orchestrator insert the row inside its `handle()` coroutine,
            // which meant the typing indicator could outpace the bubble
            // and Hugo saw "ghost" sends where his message only appeared
            // when the reply arrived. The orchestrator is told to skip its
            // own insertion via `skipUserMessageInsert=true` ONLY IF the
            // eager insert actually succeeded — otherwise the legacy path
            // (orchestrator inserts) still runs so we never lose the row.
            val eagerInsertOk = alreadyInserted || runCatching {
                repository.insertUserChatMessage(
                    sessionId = targetSessionId,
                    text = textToSend,
                    imageBase64Stored = imageToSend,
                )
            }.onFailure { t ->
                android.util.Log.w("CHAT_VM", "eager user insert failed (${t.message}); orchestrator will insert as fallback")
            }.isSuccess

            ifCurrentSend(sendGeneration) {
                _isTyping.value = true
                _typingPhase.value = tr("thinking", _userProfile.value.language)
                _streamingText.value = null
                _thinkingText.value = null
            }
            finishSendLaunch(sendGeneration)

            // Elapsed-time watcher — escalates the phase tag every 5/15/30s so
            // the user sees the bubble change instead of staring at static
            // dots. Cancelled implicitly when the parent job completes.
            val phaseWatcher = launch {
                val lang = _userProfile.value.language
                val started = System.currentTimeMillis()
                var lastTier = 0
                var lastThinkingLen = 0
                while (true) {
                    delay(1_000)
                    if (!isCurrentSend(sendGeneration)) break
                    val elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()
                    // 2026-10-06 (Frente Thinking) — reasoning tokens still
                    // landing is VISIBLE progress (the user is watching the
                    // model think), so it resets the escalation tiers: we no
                    // longer show "O servidor está lento" while the thinking
                    // card is actively growing.
                    val thinkingLen = _thinkingText.value?.length ?: 0
                    if (thinkingLen > lastThinkingLen) {
                        lastThinkingLen = thinkingLen
                        if (lastTier > 0) lastTier = 0
                    }
                    val tier = when {
                        elapsedSec >= 30 -> 3
                        elapsedSec >= 15 -> 2
                        elapsedSec >= 5  -> 1
                        else -> 0
                    }
                    if (tier != lastTier) {
                        lastTier = tier
                        when (tier) {
                            1 -> _typingPhase.value = tr("still_thinking", lang)
                            2 -> _typingPhase.value = tr("still_thinking_long", lang).replace("%d", elapsedSec.toString())
                            3 -> _typingPhase.value = tr("warning_slow", lang)
                        }
                    } else if (tier == 2) {
                        // Refresh seconds counter while in tier 2
                        _typingPhase.value = tr("still_thinking_long", lang).replace("%d", elapsedSec.toString())
                    }
                }
            }

            try {
                // JIT-built base instruction: passes the freshly-loaded
                // [UserProfile] name/targets so cold-start sessions whose
                // stored `systemInstruction` baked in the default "Humano"
                // address the user by their real name. Also makes name
                // changes in Settings take effect on the very next send.
                // See plan: /Users/i548178/.claude/plans/no-chat-quando-tiro-*
                val activeType = activeSession.value?.sessionType ?: "personal"
                // 2026-07-06 (Frente B) — read the pre-computed local-context
                // block (GPS + IPMA weather) instead of building it inline.
                // The prefetch runs on VM init and on chat tab resume, so
                // this is now a plain StateFlow read (~µs) rather than the
                // ~500-2500ms GPS+HTTP chain that used to sit right before
                // `orchestrator.handle` was invoked.
                val freshInstruction = createDefaultSystemInstruction(activeType) + _localContextBlock.value
                val liveName = _userProfile.value.name
                // 2026-10-05 (chat TTS mode) — one-shot prompt repair: sessions
                // created before the TTS-mode persona carry the OLD prompt in
                // `systemInstruction` (and often a server-side cachedContent
                // with the old prompt baked in). Refresh the row and drop the
                // stale cache so this turn's cache re-creation uses the new
                // prompt. No-op on already-refreshed sessions.
                // NB: persist only the STABLE base (no _localContextBlock —
                // that ephemeral weather/GPS block is appended per send and
                // must not be baked into the stored prompt).
                activeSession.value?.let { ses ->
                    val staleCache = repository.refreshStaleSystemInstruction(
                        ses,
                        createDefaultSystemInstruction(activeType)
                    )
                    if (staleCache != null) {
                        viewModelScope.launch {
                            runCatching {
                                com.example.data.remote.GeminiCacheClient
                                    .deleteCache(staleCache, BuildConfig.GEMINI_API_KEY)
                            }.onFailure { t ->
                                android.util.Log.w("CHAT_PROMPT_REFRESH", "deleteCache failed (cache expires on its own): ${t.message?.take(120)}")
                            }
                        }
                    }
                }
                orchestrator.handle(
                    sessionId = targetSessionId,
                    text = textToSend,
                    imageBase64Stored = imageToSend,
                    language = _userProfile.value.language,
                    shStepsByDate = _shStepsByDate.value,
                    shDailyActivity = _shDailyActivity.value,
                    shWorkouts = _shWorkouts.value,
                    streaming = true,
                    baseInstructionOverride = freshInstruction,
                    userNameOverride = liveName,
                    skipUserMessageInsert = eagerInsertOk,
                    appContext = cachedAppContext
                ).collect { event ->
                    if (!isCurrentSend(sendGeneration)) return@collect
                    when (event) {
                        is com.example.data.chat.HandleEvent.Phase -> {
                            _typingPhase.value = event.text
                        }
                        is com.example.data.chat.HandleEvent.FastReply -> {
                            // 2026-07-06 (Frente H) — fast tier landed
                            // before deep started streaming. Paint it into
                            // the same StateFlow the streaming bubble
                            // renders from; when deep's first Partial
                            // arrives it will overwrite this cleanly.
                            android.util.Log.d(
                                "CHAT_TWO_TIER",
                                "VM received FastReply len=${event.text.length}"
                            )
                            _streamingText.value = event.text
                        }
                        is com.example.data.chat.HandleEvent.Partial -> {
                            _streamingText.value = event.accumulatedText
                        }
                        is com.example.data.chat.HandleEvent.Thinking -> {
                            // 2026-10-06 (Frente Thinking) — cumulative
                            // reasoning; feeds the live card. Side-channel:
                            // never touches _streamingText (the answer).
                            _thinkingText.value = event.text
                        }
                        is com.example.data.chat.HandleEvent.Reset -> {
                            // 2026-09-08 (Phase 6, §13) — router failed over
                            // mid-stream. Discard the abandoned partial so the
                            // fallback provider re-renders from scratch; the
                            // dead provider's text is never appended/persisted.
                            android.util.Log.i(
                                "CHAT_ROUTER",
                                "VM Reset: ${event.from} -> ${event.to} (clearing partial)"
                            )
                            _streamingText.value = ""
                            // The dying provider's reasoning is abandoned with
                            // its partial — the fallback re-thinks from scratch.
                            _thinkingText.value = ""
                        }
                        is com.example.data.chat.HandleEvent.Done -> {
                            // 2026-07-07 (Fix #1) — clear typing indicator the
                            // moment the text lands, not on flow completion.
                            // The orchestrator still emits ChartReady up to 6s
                            // later; keeping _isTyping tied to the `finally`
                            // made the indicator linger AFTER the answer was
                            // visible whenever intentRouter.wantsChart fired,
                            // which the user perceives as "slow response" on
                            // devices with more data (S25). The `finally`
                            // below still clears defensively for early-exit
                            // paths (Error before Done, cancel, exception).
                            handleAssistantDone(event)
                            producedReply = true
                        }
                        is com.example.data.chat.HandleEvent.Error -> {
                            android.util.Log.w("CHAT_ORCH", "Error: ${event.cause.message}")
                            failureCause = event.cause
                        }
                        is com.example.data.chat.HandleEvent.ChartReady -> {
                            // 2026-06-28 — Frente C: parallel chart call
                            // completed AFTER the main text was rendered.
                            // Append the chart to the per-message map so
                            // the existing ChartCard composable picks it
                            // up on the next recomposition. We append
                            // (not replace) so a chart already inlined
                            // by the main parser is preserved — though in
                            // practice the orchestrator skips the parallel
                            // path when an inline chart was emitted.
                            android.util.Log.d(
                                "CHAT_PARALLEL",
                                "VM received ChartReady kind=${event.chart.kind} for msgId=${event.anchorMessageId}"
                            )
                            _pendingChartProposals.value = _pendingChartProposals.value.toMutableMap().apply {
                                val existing = get(event.anchorMessageId).orEmpty()
                                put(event.anchorMessageId, existing + event.chart)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("CHAT_ORCH", "send failed/cancelled: ${t.message}")
                // A cancellation is the user tapping ✕ — that is a deliberate
                // action, not a failure, so it must not raise the error card.
                if (t !is kotlinx.coroutines.CancellationException) {
                    failureCause = t
                }
            } finally {
                phaseWatcher.cancel()
                finishSendLaunch(sendGeneration)
                clearSendUiIfCurrent(sendGeneration)
                ifCurrentSend(sendGeneration) { sendJob = null }
                // Only the send that is still current may raise the error
                // card — a superseded turn must not overwrite the UI of the
                // one that replaced it.
                if (!producedReply && failureCause != null) {
                    ifCurrentSend(sendGeneration) {
                        _chatSendError.value = chatErrorKeyFor(failureCause)
                    }
                }
            }
        }
    }

    /** Cancel an in-flight send — wired to the ✕ on the typing/streaming
     *  bubble. 2026-06-29 — also clears UI state eagerly so the bubble
     *  disappears in the same frame the user taps X, instead of waiting for
     *  the streaming flow's `finally` block to fire (which can lag by
     *  hundreds of ms if Gemini's SSE socket is still draining).
     *  `sendJob?.cancel()` still propagates the cancellation downward; the
     *  flow's `finally` becomes a no-op clear since we've already cleared. */
    fun cancelSendJob() {
        invalidateActiveSendUi()
        sendJob?.cancel()
        sendJob = null
        _isTyping.value = false
        _typingPhase.value = null
        _streamingText.value = null
    }

    /**
     * Phase B+2 of chat-redesign 2026-06-12: builds the per-turn data
     * snapshot the assistant sees alongside the user prompt. Extracted from
     * [sendMessage] so [confirmEditAndResubmit] can reuse the exact same
     * pipeline — both call sites need a fresh snapshot built off the latest
     * in-memory state + a one-shot DAO drain (so a brand-new session sees
     * real numbers on the very first turn without waiting for the StateFlow
     * to emit through a collector).
     */
    private suspend fun buildDataSnapshot(): String {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val foods = runCatching { repository.allFoods.first() }.getOrDefault(emptyList())
        val activities = runCatching { repository.allActivities.first() }.getOrDefault(emptyList())
        val latestTarget = runCatching { repository.latestTargetSnapshot() }.getOrNull()
        val latestBmr = runCatching { repository.latestBmrSnapshot() }.getOrNull()
        val confirmedInsights = runCatching { repository.topConfirmedInsights() }.getOrDefault(emptyList())
        val burnedByDate = _shDailyActivity.value.mapValues { (_, d) -> d.activeCalories.toInt() }
        return com.example.data.remote.ChatContextBuilder.build(
            today = today,
            zone = zone,
            foods = foods,
            activities = activities,
            latestTarget = latestTarget,
            latestBmr = latestBmr,
            stepsByDate = _shStepsByDate.value,
            caloriesBurnedByDate = burnedByDate,
            confirmedInsights = confirmedInsights,
            // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail) —
            // Thread the live user name. Without this the canonical snapshot
            // says "Known facts about the user" while the system prompt says
            // "Address the user as 'Hugo'", and Gemini reverts to the
            // generic phrasing baked into the snapshot.
            userName = _userProfile.value.name
        )
    }

    // -----------------------------------------------------------------
    // Phase 2 of chat-redesign 2026-06-12 (#4 — edit/copy/delete)
    // -----------------------------------------------------------------

    /** When non-null, the input bar is in "editing" mode: the Send button
     *  becomes Save (✓), placeholder shifts, and a "Editing message" header
     *  appears above the field. The id is the user-message being rewritten. */
    private val _editingMessageId = MutableStateFlow<Long?>(null)
    val editingMessageId: StateFlow<Long?> = _editingMessageId.asStateFlow()

    /**
     * Enter edit mode for [messageId]. Only user-authored messages are
     * editable — assistant replies are immutable history. Loads the original
     * text into the input field; the original imageBase64 (if any) is
     * re-attached internally on save without round-tripping through the
     * Compose attachment state, since edit mode reuses the original images.
     */
    fun beginEditingMessage(messageId: Long) {
        val msg = activeMessages.value.firstOrNull { it.id == messageId } ?: return
        if (msg.sender != "user") return
        if (_isTyping.value) return
        _inputText.value = msg.text
        _editingMessageId.value = messageId
    }

    /** Leave edit mode without applying changes — restores empty input. */
    fun cancelEditing() {
        _editingMessageId.value = null
        _inputText.value = ""
    }

    /**
     * Apply the edit: deletes the original user message + every reply that
     * came after it (cascade), then re-sends with the new text and the
     * original images carried over. Blocks if the assistant is currently
     * streaming a reply (race against the `isTyping=true → DB insert` path).
     *
     * In-memory caches keyed by deleted message ids are pruned in the same
     * coroutine so orphan entries don't accumulate over a long session.
     */
    fun confirmEditAndResubmit() {
        val targetId = _editingMessageId.value ?: return
        val sessionId = _selectedSessionId.value ?: return
        val newText = _inputText.value.trim()
        if (newText.isBlank()) return
        val targetMsg = activeMessages.value.firstOrNull { it.id == targetId } ?: return
        if (targetMsg.sender != "user") return
        if (_isTyping.value) return
        if (sendLaunchPendingGeneration != null) return

        val anchorTs = targetMsg.timestamp
        val carryImages = targetMsg.imageBase64
        // Reset edit state synchronously so a double-tap on Save doesn't
        // schedule two cascade-deletes in parallel.
        _editingMessageId.value = null
        _inputText.value = ""

        // 2026-07-07 (Fix #4) — assign to `sendJob` so `cancelSendJob()`
        // (the ✕ button + selectSession's cancel) can reach an edit/resubmit
        // in flight. Previously this launch was orphaned: an edit-resubmit
        // that stalled (Gemini 503 cascade, TTFT watchdog fires late, etc.)
        // stayed running invisibly and _isTyping never cleared, forcing the
        // user to send a new message just to unstick the indicator.
        val sendGeneration = startSendGeneration()
        sendJob = viewModelScope.launch {
            // Enumerate the ids we're about to drop before deleting, so we
            // can scrub the in-memory caches keyed by message id (otherwise
            // pendingHabitProposals[oldId], _cleanedMessageText[oldId], etc.
            // become invisible orphans).
            val doomed = runCatching {
                repository.getMessagesFromTimestamp(sessionId, anchorTs)
            }.getOrDefault(emptyList())
            val doomedIds = doomed.map { it.id }
            repository.deleteMessagesFromTimestamp(sessionId, anchorTs)
            pruneCachesForDeletedIds(doomedIds)

            // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail) —
            // Route edit/resubmit through the SAME orchestrator pipeline as
            // [sendMessage]. The previous implementation called
            // `repository.sendMessage(...)` which (a) read the stale
            // `session.systemInstruction` from the DB (baked "Humano" name in
            // old sessions), (b) skipped `userNameOverride` so the canonical
            // snapshot fell back to "the user", and (c) bypassed the
            // anti-history-pollution name directive. By going through
            // `orchestrator.handle` here, edit-resubmits inherit every fix:
            // JIT instruction rebuild, live userName threading, PRE-FETCHED
            // data block (Fix 4), in-history "Humano" scrubbing (Fix 5).
            ifCurrentSend(sendGeneration) {
                _isTyping.value = true
                _typingPhase.value = tr("thinking", _userProfile.value.language)
                _streamingText.value = null
                _thinkingText.value = null
            }
            finishSendLaunch(sendGeneration)

            val phaseWatcher = launch {
                val lang = _userProfile.value.language
                val started = System.currentTimeMillis()
                var lastTier = 0
                while (true) {
                    delay(1_000)
                    if (!isCurrentSend(sendGeneration)) break
                    val elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()
                    val tier = when {
                        elapsedSec >= 30 -> 3
                        elapsedSec >= 15 -> 2
                        elapsedSec >= 5  -> 1
                        else -> 0
                    }
                    if (tier != lastTier) {
                        lastTier = tier
                        when (tier) {
                            1 -> _typingPhase.value = tr("still_thinking", lang)
                            2 -> _typingPhase.value = tr("still_thinking_long", lang).replace("%d", elapsedSec.toString())
                            3 -> _typingPhase.value = tr("warning_slow", lang)
                        }
                    } else if (tier == 2) {
                        _typingPhase.value = tr("still_thinking_long", lang).replace("%d", elapsedSec.toString())
                    }
                }
            }

            try {
                val activeType = activeSession.value?.sessionType ?: "personal"
                // 2026-07-06 (Frente B) — read the pre-computed local-context
                // block (GPS + IPMA weather) instead of building it inline.
                // The prefetch runs on VM init and on chat tab resume, so
                // this is now a plain StateFlow read (~µs) rather than the
                // ~500-2500ms GPS+HTTP chain that used to sit right before
                // `orchestrator.handle` was invoked.
                val freshInstruction = createDefaultSystemInstruction(activeType) + _localContextBlock.value
                val liveName = _userProfile.value.name
                orchestrator.handle(
                    sessionId = sessionId,
                    text = newText,
                    imageBase64Stored = carryImages,
                    language = _userProfile.value.language,
                    shStepsByDate = _shStepsByDate.value,
                    shDailyActivity = _shDailyActivity.value,
                    shWorkouts = _shWorkouts.value,
                    streaming = true,
                    baseInstructionOverride = freshInstruction,
                    userNameOverride = liveName,
                    appContext = cachedAppContext
                ).collect { event ->
                    if (!isCurrentSend(sendGeneration)) return@collect
                    when (event) {
                        is com.example.data.chat.HandleEvent.Phase -> {
                            _typingPhase.value = event.text
                        }
                        is com.example.data.chat.HandleEvent.FastReply -> {
                            // 2026-07-06 (Frente H) — same handler as the
                            // primary send path. Edit-resubmits reuse the
                            // two-tier flow so a rephrased reasoning
                            // question also gets the instant fast bubble.
                            _streamingText.value = event.text
                        }
                        is com.example.data.chat.HandleEvent.Partial -> {
                            _streamingText.value = event.accumulatedText
                        }
                        is com.example.data.chat.HandleEvent.Thinking -> {
                            // 2026-10-06 (Frente Thinking) — edit-resubmits
                            // share the primary path's live reasoning card.
                            _thinkingText.value = event.text
                        }
                        is com.example.data.chat.HandleEvent.Reset -> {
                            // 2026-09-08 (Phase 6, §13) — same as the primary
                            // send path: discard the abandoned partial on a
                            // mid-stream router failover so the fallback
                            // re-renders cleanly. Edit-resubmits share the flow.
                            android.util.Log.i(
                                "CHAT_ROUTER",
                                "VM Reset (edit): ${event.from} -> ${event.to} (clearing partial)"
                            )
                            _streamingText.value = ""
                            // Abandon the dying provider's reasoning too.
                            _thinkingText.value = ""
                        }
                        is com.example.data.chat.HandleEvent.Done -> {
                            // 2026-07-07 (Fix #1) — same reasoning as the
                            // primary send path: clear typing indicator on
                            // Done, not on `finally`. Chart wait keeps the
                            // flow alive up to 6s after the answer landed.
                            handleAssistantDone(event)
                        }
                        is com.example.data.chat.HandleEvent.Error -> {
                            android.util.Log.w("CHAT_ORCH", "edit-resubmit error: ${event.cause.message}")
                        }
                        is com.example.data.chat.HandleEvent.ChartReady -> {
                            // 2026-06-28 — Frente C: same handler as the
                            // primary send path. Edit-resubmits inherit
                            // the parallel chart pipeline so a chart
                            // question rephrased via edit still gets a
                            // chart bubble afterward.
                            _pendingChartProposals.value = _pendingChartProposals.value.toMutableMap().apply {
                                val existing = get(event.anchorMessageId).orEmpty()
                                put(event.anchorMessageId, existing + event.chart)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("CHAT_ORCH", "edit-resubmit failed/cancelled: ${t.message}")
            } finally {
                phaseWatcher.cancel()
                finishSendLaunch(sendGeneration)
                clearSendUiIfCurrent(sendGeneration)
                ifCurrentSend(sendGeneration) { sendJob = null }
            }
        }
    }

    /**
     * Delete the message + every later message in the same session. Used by
     * the long-press → Delete action sheet, after the confirmation dialog.
     */
    fun deleteMessageAndAfter(messageId: Long) {
        val sessionId = _selectedSessionId.value ?: return
        val msg = activeMessages.value.firstOrNull { it.id == messageId } ?: return
        viewModelScope.launch {
            val doomed = runCatching {
                repository.getMessagesFromTimestamp(sessionId, msg.timestamp)
            }.getOrDefault(emptyList())
            val doomedIds = doomed.map { it.id }
            repository.deleteMessagesFromTimestamp(sessionId, msg.timestamp)
            pruneCachesForDeletedIds(doomedIds)
            // If we just deleted the message currently being edited, exit
            // edit mode so the user isn't stranded on a phantom target.
            if (_editingMessageId.value in doomedIds) {
                _editingMessageId.value = null
                _inputText.value = ""
            }
        }
    }

    /** Copy a message's plain text to the system clipboard. The toast is
     *  raised by the caller (Composable has access to LocalContext). */
    fun copyMessageToClipboard(context: android.content.Context, text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("chat-message", text))
    }

    /**
     * Drop every Map entry keyed by an id in [ids] from the in-memory
     * proposal/warning caches. Cheap O(map-size) scan — the maps stay small
     * (one entry per assistant message, cleared on confirm/discard).
     */
    private fun pruneCachesForDeletedIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val s = ids.toSet()
        _pendingHabitProposals.update { it - s }
        _pendingMealProposals.update { it - s }
        _pendingActivityProposals.update { it - s }
        _pendingInsightProposals.update { it - s }
        // Also drop the ingest guard so if a message ever reappears with the
        // same id (Room won't reuse ids in practice, but stay defensive) it
        // can be re-parsed instead of being silently skipped.
        ingestedMessageIds.removeAll(s)
        _pendingReportProposals.update { it - s }
        _pendingChartProposals.update { it - s }
        _pendingHabitWarnings.update { it - s }
        _proposalReceipts.update { it - s }
        _cleanedMessageText.update { it - s }
        _ttsSummaryText.update { it - s }
    }

    private companion object {
        /** Relative macro tolerance below which an AI-vs-user difference is
         *  treated as rounding noise, not a correction (see
         *  [maybeRecordCorrection]). 5%. */
        const val MACRO_EPSILON = 0.05

        /** Extract the JSON payload between [COACH_TARGETS] and [/COACH_TARGETS].
         *  Avoids Android ICU regex edge-cases with [^}] character classes by using
         *  plain string indexOf instead. */
        fun extractCoachTargetsJson(text: String): String? {
            val open = "[COACH_TARGETS]"
            val close = "[/COACH_TARGETS]"
            val start = text.indexOf(open).takeIf { it >= 0 } ?: return null
            val end = text.indexOf(close, start).takeIf { it >= 0 } ?: return null
            val between = text.substring(start + open.length, end).trim()
            val jsonStart = between.indexOf('{').takeIf { it >= 0 } ?: return null
            val jsonEnd = between.lastIndexOf('}').takeIf { it >= 0 } ?: return null
            return between.substring(jsonStart, jsonEnd + 1)
        }

        fun stripCoachTargetsBlock(text: String): String {
            val open = "[COACH_TARGETS]"
            val close = "[/COACH_TARGETS]"
            val start = text.indexOf(open).takeIf { it >= 0 } ?: return text.trim()
            val end = text.indexOf(close, start).takeIf { it >= 0 } ?: return text.trim()
            return (text.substring(0, start) + text.substring(end + close.length)).trim()
        }
    }
}

class ChatViewModelFactory(
    private val repository: GeminiRepository,
    private val shRepository: SamsungHealthRepository,
    private val locationHelper: com.example.data.location.LocationHelper? = null,
    private val weatherFetcher: com.example.data.weather.IpmaWeatherFetcher? = null,
    private val ttsController: TtsController? = null,
    private val speechRecognizer: SpeechRecognizerController? = null,
    private val nutritionLookupService: com.example.data.nutrition.NutritionLookupService? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                repository,
                shRepository,
                locationHelper,
                weatherFetcher,
                ttsController,
                speechRecognizer,
                nutritionLookupService,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * Photo-replace on an *existing* meal must UPDATE that row, not insert a new
 * one (bug reported 2026-07-10: swapped photo didn't persist — the old photo
 * stayed in History and a duplicate row was created).
 *
 * The freshly-analysed [freshEntry] carries `id = 0` and a new `timestamp`,
 * so if it replaced the slot verbatim it would fall into the `insertFood`
 * branch of [ChatViewModel.confirmAndSaveFoodEntry] and leave the original
 * row untouched. This carries over the replaced slot's identity
 * (id / timestamp / groupId / mealCategory / appliedHabitIds) while keeping
 * the NEW imagePath + macros / summary / ingredients from the re-analysis, so
 * the save takes the `updateFood` branch and the same row gets the new photo.
 *
 * Applies equally to camera and gallery replaces: both hand the VM a
 * brand-new `food_images/` path, so the preview re-decodes the new photo and
 * the orphaned old file is reclaimed later by OrphanImageCleanupWorker.
 *
 * When the replaced slot is itself an unsaved draft (`id == 0`), there is no
 * identity to preserve — the fresh entry is kept as-is (still `id == 0` →
 * insert), which is the correct behaviour for the new-meal / append flows.
 *
 * Pure & side-effect-free so it can be unit-tested without constructing the
 * whole ViewModel.
 */
internal fun replaceSlotPreservingIdentity(
    list: List<FoodEntry>,
    idx: Int,
    freshEntry: FoodEntry
): List<FoodEntry> {
    if (list.isEmpty()) return listOf(freshEntry)
    val safeIdx = idx.coerceIn(0, list.size - 1)
    val original = list[safeIdx]
    val preserved = if (original.id != 0L) {
        freshEntry.copy(
            id = original.id,
            timestamp = original.timestamp,
            groupId = original.groupId,
            mealCategory = original.mealCategory,
            appliedHabitIds = original.appliedHabitIds
        )
    } else {
        freshEntry
    }
    return list.mapIndexed { i, existing -> if (i == safeIdx) preserved else existing }
}