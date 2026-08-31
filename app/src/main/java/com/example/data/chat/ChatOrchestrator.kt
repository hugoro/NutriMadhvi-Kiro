package com.example.data.chat

import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityDao
import com.example.data.local.ChatDao
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.local.FoodDao
import com.example.data.local.FoodHabitDao
import com.example.data.local.UserInsight
import com.example.data.remote.ActionProposalParser
import com.example.data.remote.ChatContextBuilder
import com.example.data.remote.Content
import com.example.data.remote.GeminiCacheClient
import com.example.data.remote.GeminiRequest
import com.example.data.remote.GeminiRetrofitClient
import com.example.data.remote.GeminiStreamingClient
import com.example.data.remote.GenerationConfig
import com.example.data.remote.GeminiStreamWatchdogCollector
import com.example.data.remote.InlineData
import com.example.data.remote.Part
import com.example.data.remote.QueryDataRequest
import com.example.data.remote.StreamChunk
import com.example.data.remote.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-first chat orchestrator — Android port of the
 * `decide locally → fetch in parallel → LLM synthesises` pattern from
 * `/Users/i548178/GitHub/ITOpsEA`.
 *
 * Replaces the LLM-decides-what-to-fetch path in
 * [com.example.data.remote.GeminiRepository.sendMessage] for the streaming
 * happy path. The legacy non-streaming `sendMessage` remains as a wrapper
 * so existing callers continue to work — the orchestrator is opt-in via
 * [handle].
 *
 * Flow:
 *   1. Persist user message (mirrors current behaviour, must happen before
 *      the network call so the bubble shows immediately).
 *   2. [IntentRouter.classify] — pure regex, picks DataQuery vs Reasoning.
 *   3. Build canonical snapshot (today + 7d + month + 30d + projection).
 *   4. If DataQuery, parallel-fetch into a PRE-FETCHED markdown block.
 *   5. Stream Gemini's response chunk-by-chunk via [GeminiStreamingClient].
 *   6. On Done, run the proposal parser ONCE on the full text, persist the
 *      cleaned message, emit Done to the UI.
 *
 * Paridade total with the old QUERY_DATA round-trip is preserved: if the
 * model still emits `[QUERY_DATA]` despite the snapshot already covering
 * common windows, the orchestrator runs the synchronous follow-up just
 * like before. Salvage parser cleans malformed markers.
 */
class ChatOrchestrator(
    private val chatDao: ChatDao,
    private val foodDao: FoodDao,
    private val activityDao: ActivityDao,
    private val foodHabitDao: FoodHabitDao,
    private val targetSnapshotDao: com.example.data.local.TargetSnapshotDao,
    private val bmrSnapshotDao: com.example.data.local.BmrSnapshotDao,
    private val bodyCompositionSnapshotDao: com.example.data.local.BodyCompositionSnapshotDao,
    private val userInsightDao: com.example.data.local.UserInsightDao,
    /**
     * 2026-07-06 (Frente I) — predictive Q&A cache DAO. When present,
     * `handle` checks it at the top of the turn: on a hash+fingerprint
     * hit the cached answer is emitted instantly and the deep tier
     * continues in the background to enrich the reply. Nullable so
     * tests / older wiring paths don't have to plumb it.
     */
    private val predictedQaDao: com.example.data.local.PredictedQaDao? = null,
    private val intentRouter: IntentRouter = IntentRouter,
    private val dataFetcher: DataFetcher = DataFetcher(foodDao, activityDao),
    /**
     * 2026-07-06 (Frente F) — per-model in-memory circuit breaker. When
     * a Gemini model 503s twice in a row it gets skipped for 60 s so
     * subsequent sends jump straight to the next tier without wasting
     * an attempt. Default is a fresh breaker; tests inject a stub.
     */
    private val circuitBreaker: ModelCircuitBreaker = ModelCircuitBreaker(),
    /**
     * 2026-06-28 — Frente C (paralelismo text+chart). Injected by the
     * repository so the orchestrator can fire a second Gemini call in
     * parallel with the main text stream without circular construction.
     * `null` (the default) disables the parallel chart path — useful for
     * tests. The repository's [com.example.data.remote.GeminiRepository.createChatOrchestrator]
     * binds this to `::requestChart` on itself.
     *
     * Signature mirrors [com.example.data.remote.GeminiRepository.requestChart].
     */
    private val chartRequester: (suspend (
        userText: String,
        dataContextBlock: String,
        userName: String,
        language: String
    ) -> com.example.data.remote.ChartProposal?)? = null
) {

    /**
     * Single per-orchestrator snapshot cache. Keyed by date+counts so any DB
     * mutation invalidates implicitly. TTL 60s on top to absorb very rapid
     * back-to-back sends. Mirrors ITOpsEA's lightweight memoisation.
     */
    /**
     * 2026-07-06 (Frente C) — the snapshot cache now carries the raw
     * `foods` + `activities` rows read for the canonical 31-day window
     * as well as the rendered text. This lets [handle] pass those rows
     * into [DataFetcher.filterForIntent] instead of re-reading Room
     * when the intent window is a subset of 31d (which is the vast
     * majority of queries — "today", "last 7d", "this month" all fit).
     *
     * Rows are only held for the TTL of the cache (60s). After that
     * the next `handle` rebuilds them anyway.
     */
    private data class SnapshotCache(
        val key: String,
        val text: String,
        val builtAtMs: Long,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val foods: List<com.example.data.local.FoodEntry>,
        val activities: List<com.example.data.local.ActivityEntry>,
    )
    private val snapshotCache = MutableStateFlow<SnapshotCache?>(null)

    /**
     * Lifecycle-scoped background scope for fire-and-forget work that has to
     * outlive a single [handle] turn — currently just the Gemini context
     * cache TTL touch (a ~200ms POST that mustn't be cancelled the instant
     * the streaming flow completes, but MUST die when the ViewModel goes
     * away so we don't keep pinging the cache server after logout / process
     * unbind). [SupervisorJob] keeps one failing touch from poisoning the
     * next; [close] is invoked from `ChatViewModel.onCleared()`.
     *
     * Replaces a prior `GlobalScope.launch` that had no link to the
     * ViewModel lifecycle. See the `prefer-structured-concurrency` finding
     * in the 2026-06-26 efficiency review.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val digestRefreshJobs = SessionScopedJobDeduper(backgroundScope)
    private val carryoverJobs = SessionScopedJobDeduper(backgroundScope)
    private val cachePersistJobs = SessionScopedJobDeduper(backgroundScope)
    private val cacheTouchJobs = SessionScopedJobDeduper(backgroundScope)
    private val insightTouchJobs = SessionScopedJobDeduper(backgroundScope)
    private val insightExtractJobs = SessionScopedJobDeduper(backgroundScope)
    private val cacheCreateDeferreds = SessionScopedDeferredRegistry<String?>(backgroundScope)

    internal class SessionScopedJobDeduper(
        private val scope: CoroutineScope
    ) {
        private val jobs = ConcurrentHashMap<Long, Job>()

        fun launchReplacing(
            sessionId: Long,
            logTag: String,
            logMessage: String,
            block: suspend () -> Unit,
        ) {
            val newJob = scope.launch {
                try {
                    block()
                } finally {
                    currentCoroutineContext()[Job]?.let { jobs.remove(sessionId, it) }
                }
            }
            val previous = jobs.put(sessionId, newJob)
            if (previous != null && previous !== newJob && previous.isActive) {
                android.util.Log.d(logTag, "$logMessage sessionId=$sessionId")
                previous.cancel()
            }
        }

        fun cancelAll() {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }

        internal fun activeJobCount(): Int = jobs.size
    }

    internal class SessionScopedDeferredRegistry<T>(
        private val scope: CoroutineScope
    ) {
        private val deferreds = ConcurrentHashMap<Long, Deferred<T>>()

        fun getOrStart(
            sessionId: Long,
            logTag: String,
            reuseMessage: String,
            producer: suspend () -> T,
        ): Deferred<T> {
            deferreds[sessionId]?.let { existing ->
                if (existing.isActive) {
                    android.util.Log.d(logTag, "$reuseMessage sessionId=$sessionId")
                    return existing
                }
                deferreds.remove(sessionId, existing)
            }

            var deferred: Deferred<T>? = null
            deferred = scope.async(start = CoroutineStart.LAZY) {
                try {
                    producer()
                } finally {
                    deferred?.let { created -> deferreds.remove(sessionId, created) }
                }
            }
            val raced = deferreds.putIfAbsent(sessionId, deferred)
            return if (raced != null) {
                deferred.cancel()
                android.util.Log.d(logTag, "$reuseMessage sessionId=$sessionId")
                raced
            } else {
                deferred.start()
                deferred
            }
        }

        fun cancelAll() {
            deferreds.values.forEach { it.cancel() }
            deferreds.clear()
        }

        internal fun activeDeferredCount(): Int = deferreds.size
    }

    /** Cancel pending background work. Call from the ViewModel's
     *  `onCleared()` so cache-touch coroutines don't outlive the screen. */
    fun close() {
        digestRefreshJobs.cancelAll()
        carryoverJobs.cancelAll()
        cachePersistJobs.cancelAll()
        cacheTouchJobs.cancelAll()
        insightTouchJobs.cancelAll()
        insightExtractJobs.cancelAll()
        cacheCreateDeferreds.cancelAll()
        backgroundScope.cancel()
    }

    /** Resolved-style entry point. Returns a cold [Flow] that the ViewModel
     *  collects in `viewModelScope.launch { handle(...).collect { ... } }`. */
    fun handle(
        sessionId: Long,
        text: String,
        imageBase64Stored: String?,
        language: String,
        // Live SH state (orchestrator does not own collectors; ViewModel
        // snapshots and passes the values).
        shStepsByDate: Map<LocalDate, Long>,
        shDailyActivity: Map<LocalDate, ShDailyActivity>,
        /** Per-session Samsung Health workouts in the active window. Fed
         *  into [DataFetcher] so the chat can comment on cardio/strength
         *  mix instead of seeing only daily totals. ViewModel snapshots
         *  `_shWorkouts.value` at call time. */
        shWorkouts: List<ShWorkout> = emptyList(),
        // Whether the assistant should stream. Disabling falls back to the
        // canonical `generateContent` blocking call.
        streaming: Boolean = true,
        /** When non-null, overrides `session.systemInstruction` for THIS turn
         *  only — the stored value in the DB is left untouched. ChatViewModel
         *  passes the freshly-built instruction (using the currently-loaded
         *  [UserProfile]) so cold-start sessions whose stored instruction
         *  baked in the default "Humano" name still address the user by
         *  their real name once Settings → Profile has hydrated. Also
         *  ensures that updates to the user's name in Settings take effect
         *  immediately on the next send, without requiring a new session. */
        baseInstructionOverride: String? = null,
        /** Display name of the current user, propagated to
         *  [ChatContextBuilder.build] so the canonical "Known facts about X"
         *  section uses the same name as the system prompt. Null falls back
         *  to the builder's `"the user"` default. */
        userNameOverride: String? = null,
        /** 2026-06-29 — Frente Memory C1.b. When `true`, the caller has
         *  already inserted the user [ChatMessage] row into the DB via
         *  [ChatDao.insertMessage] BEFORE invoking [handle]. The orchestrator
         *  then skips its own insertion (it would otherwise create a
         *  duplicate row). Used by [com.example.ui.ChatViewModel.sendMessage]
         *  so the Room Flow emits the user bubble immediately — before the
         *  typing indicator obscures the timeline — fixing the "ghost user
         *  message" bug where the bubble appeared only when the reply arrived.
         *  Default `false` keeps every other caller (edit-resubmit, future
         *  retries) on the legacy path. */
        skipUserMessageInsert: Boolean = false,
        /** 2026-06-29 — Frente Memory C3. Application context, used by the
         *  [CarryoverDigest] state machine (SharedPreferences-backed,
         *  needs a Context). When null, the carryover digest is neither
         *  injected nor updated this turn — useful for unit tests and
         *  for any caller that doesn't want cross-session memory. The
         *  ChatViewModel always passes `applicationContext` so the
         *  feature is on in production. */
        appContext: android.content.Context? = null
    ): Flow<HandleEvent> = flow {
        val started = System.currentTimeMillis()

        // --- 1. Persist user message (idempotent if caller already did it) ---
        if (!skipUserMessageInsert) {
            val userMsg = ChatMessage(
                sessionId = sessionId,
                sender = "user",
                text = text,
                imageBase64 = imageBase64Stored
            )
            chatDao.insertMessage(userMsg)
            chatDao.touchSession(sessionId)
        }

        // 2026-07-06 (Frente C) — one `getSessionById` per turn instead of
        // two. Pre-Frente-C code fetched here (line 189) then again at the
        // cache-name check (line 368). Both reads returned the same row for
        // the same turn; the second was a redundant Room hit. Track any
        // in-turn mutation to the session (title rename, cachedContentName
        // set) by updating `session` in memory so downstream reads use the
        // fresh copy without going back to Room.
        var session: ChatSession? = chatDao.getSessionById(sessionId)
        if (session != null && session!!.title == "New Chat") {
            val shortTitle = if (text.length > 25) text.take(25) + "..." else text
            val renamed = session!!.copy(title = shortTitle)
            chatDao.updateSession(renamed)
            session = renamed
        }

        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val msg = com.example.ui.tr(
                "API Key is not configured. Please enter your GEMINI_API_KEY in the Secrets panel in AI Studio.",
                language
            )
            chatDao.insertMessage(ChatMessage(sessionId = sessionId, sender = "gemini", text = msg, isError = true))
            emit(HandleEvent.Error(IllegalStateException(msg)))
            return@flow
        }

        // 2026-07-06 (Frente D) — createCache off critical path.
        //
        // Pre-Frente-D flow: on the first turn of a session,
        // `GeminiCacheClient.createCache` was awaited inline (~200 ms
        // HTTPS POST) right before the request was built, adding that
        // whole latency to the first send. Now we fire it as a
        // `backgroundScope.async` here — right after the API key gate,
        // before any of the DAO/prep work runs — so the POST is in
        // flight while the orchestrator does snapshot + fetch + rolling
        // window etc. When we're ready to build `GeminiRequest`, we
        // `withTimeoutOrNull(50L) { deferred.await() }` — if the cache
        // is already back we use it, otherwise this turn goes
        // `cache=miss` and the next turn will pick up the cachedContentName
        // from the DB. Identical UX to the pre-Frente-D happy path,
        // with the ~200 ms shaved off the first send.
        val baseInstruction = baseInstructionOverride ?: session?.systemInstruction.orEmpty()
        val cacheDeferred: Deferred<String?>? =
            if (session?.cachedContentName.isNullOrBlank() && baseInstruction.isNotBlank()) {
                android.util.Log.d(
                    "CHAT_CACHE",
                    "kickoff createCache async (baseInstruction=${baseInstruction.length}chars)"
                )
                cacheCreateDeferreds.getOrStart(
                    sessionId = sessionId,
                    logTag = "CHAT_CACHE",
                    reuseMessage = "reusing in-flight createCache for"
                ) {
                    GeminiCacheClient.createCache(
                        model = session?.model ?: "gemini-3.5-flash",
                        systemInstructionText = baseInstruction,
                        ttlSeconds = 3600,
                        apiKey = apiKey
                    )
                }
            } else null

        // --- 2. Local intent classification ---
        val today = LocalDate.now()
        val intent = intentRouter.classify(text, today)
        android.util.Log.d("CHAT_INTENT", "type=$intent")

        emit(HandleEvent.Phase(when (intent) {
            is ChatIntent.DataQuery -> com.example.ui.tr("analyzing_your_data", language)
            is ChatIntent.Reasoning -> com.example.ui.tr("thinking", language)
        }))

        // 2026-07-06 (Frente I) — predictive Q&A cache hit path. If
        // PredictiveQaWorker already synthesised an answer to what
        // this user is asking now, emit it as a FastReply instantly
        // so the bubble shows in <300 ms. Deep tier continues in
        // parallel and its Partials overwrite the fast bubble as
        // normal (same UX contract as Frente H).
        //
        // Guards (all must hold):
        //  - `predictedQaDao` was injected (production wiring).
        //  - The classifier landed on `DataQuery` (Reasoning intents
        //    hash unstably — the fingerprint would rarely match).
        //  - Row exists, hasn't expired, language matches, and
        //    fingerprint == current app state (no serving stale
        //    numbers).
        val predictedIntent: ChatIntent.DataQuery? = when (intent) {
            is ChatIntent.DataQuery -> intent
            else -> null
        }
        if (predictedQaDao != null && predictedIntent != null) {
            try {
                val hash = PredictionEngine.canonicalHash(predictedIntent, language)
                val cached = predictedQaDao.findByHash(hash)
                if (cached != null) {
                    val stateFingerprint = computePredictedQaFingerprint(today)
                    val now = System.currentTimeMillis()
                    val fresh = cached.expiresAtMs > now &&
                        cached.language == language &&
                        cached.dataFingerprint == stateFingerprint
                    if (fresh) {
                        android.util.Log.i(
                            "CHAT_PREDICTED_HIT",
                            "hash=$hash tag=${cached.tag} ageMs=${now - cached.createdAtMs} " +
                                "len=${cached.answerText.length}"
                        )
                        // 2026-07-07 (Fix #5) — SHORT-CIRCUIT on cache hit.
                        // Previously we emitted FastReply and kept the flow
                        // running; the deep-tier would then overwrite the
                        // bubble 5-10s later, which is the exact "bubble
                        // shifts under me" UX Frente H v2 was supposed to
                        // eliminate for reasoning intents. Predicted QA is
                        // stronger than fast-tier: the fingerprint guarantees
                        // the answer is still valid for THIS state. Persist
                        // it as a real assistant row, emit Done with that id,
                        // and return@flow. We skip digest refresh + carryover
                        // + chart (fire-and-forget tail below): they cost
                        // ~2 LLM round-trips and the whole point of Predicted
                        // QA is to avoid LLM calls when we already have the
                        // answer. Carryover misses one turn — acceptable
                        // trade for <300ms perceived latency.
                        val cachedText = cached.answerText
                        val cleanedCached = ChatToneRules.clean(cachedText)
                        val insertedId = chatDao.insertMessage(
                            ChatMessage(
                                sessionId = sessionId,
                                sender = "gemini",
                                text = cachedText
                            )
                        )
                        val elapsedMs = System.currentTimeMillis() - started
                        android.util.Log.i(
                            "CHAT_PERF",
                            "predicted_qa_hit total=${elapsedMs}ms replyChars=${cachedText.length}"
                        )
                        // 2026-07-09 — learn lightweight user facts BEFORE the
                        // short-circuit return. The fall-through extractor call
                        // below is unreachable on a cache hit, so without this
                        // a returning user's new facts ("now allergic to peanuts")
                        // stated on a predicted-QA-cached turn would never be
                        // persisted. Fire-and-forget, orthogonal to the reply.
                        launchInsightExtraction(
                            sessionId = sessionId,
                            text = text,
                            language = language,
                        )
                        emit(HandleEvent.Done(cleanedCached, insertedMessageId = insertedId))
                        return@flow
                    } else {
                        android.util.Log.d(
                            "CHAT_PREDICTED_MISS",
                            "hash=$hash reason=stale (expired=${cached.expiresAtMs <= now} " +
                                "lang=${cached.language != language} " +
                                "fp=${cached.dataFingerprint != stateFingerprint})"
                        )
                    }
                } else {
                    android.util.Log.d("CHAT_PREDICTED_MISS", "hash=$hash reason=absent")
                }
            } catch (t: Throwable) {
                android.util.Log.w("CHAT_PREDICTED_MISS", "lookup failed: ${t.message?.take(120)}")
            }
        }

        // Cache-miss / predicted-QA-disabled path: learn lightweight user
        // facts before the LLM turn. (The cache-HIT path fires its own
        // launchInsightExtraction inside the short-circuit above, since this
        // call is unreachable after that return@flow.)
        launchInsightExtraction(
            sessionId = sessionId,
            text = text,
            language = language,
        )

        // --- 3. Canonical snapshot (cached) ---
        val canonical = buildCanonicalSnapshot(today, userNameOverride ?: "the user")

        // --- 4. PRE-FETCHED block — ALWAYS injected.
        //   Even for `Reasoning` intents the model needs raw data rows to
        //   anchor against; the canonical snapshot's summary numbers
        //   ("Today consumed — 1450 kcal") are not enough to stop the model
        //   from improvising. 2026-06-22 (plan:
        //   preciso-que-vejas-isto-federated-quail) — synthesise an auto
        //   `DataQuery(BOTH, last 7d)` for Reasoning so the same DataFetcher
        //   + `PrefetchedData.toContextBlock` path runs in both branches.
        //   The block is capped by the existing row limits in
        //   PrefetchedData so token cost stays bounded.
        val effectiveIntent: ChatIntent.DataQuery = when (intent) {
            is ChatIntent.DataQuery -> intent
            is ChatIntent.Reasoning -> ChatIntent.DataQuery(
                type = DataType.BOTH,
                window = DateWindow(
                    from = today.minusDays(6),
                    to = today,
                    label = com.example.ui.tr("auto_last_7_days_label", language)
                ),
                originalText = text
            )
        }
        emit(HandleEvent.Phase(com.example.ui.tr("querying_records", language)))
        // 2026-07-06 (Frente C) — reuse the rows the canonical snapshot
        // just read (31-day window) when the intent window falls inside
        // it. Skips one `getFoodsBetween` + one `getActivitiesBetween`
        // Room round-trip per turn, which was previously overlapping the
        // snapshot's own reads with a strictly-narrower filter. Falls
        // back to the DAO path when either (a) the intent asks for a
        // window wider than the snapshot's 31d slice, or (b) the
        // snapshot cache is empty/stale (rare — TTL is 60s).
        val cached = snapshotCache.value
        val zoneForFetch = ZoneId.systemDefault()
        val intentStartMs = effectiveIntent.window.from.atStartOfDay(zoneForFetch).toInstant().toEpochMilli()
        val intentEndMs = effectiveIntent.window.to.plusDays(1).atStartOfDay(zoneForFetch).toInstant().toEpochMilli()
        val canReusePreloaded = cached != null &&
            intentStartMs >= cached.windowStartMs &&
            intentEndMs <= cached.windowEndMs
        val prefetchedData = if (canReusePreloaded && cached != null) {
            dataFetcher.filterForIntent(
                intent = effectiveIntent,
                preloadedFoods = cached.foods,
                preloadedActivities = cached.activities,
                zone = zoneForFetch,
                shStepsByDate = shStepsByDate,
                shDailyActivity = shDailyActivity,
                shWorkouts = shWorkouts
            )
        } else {
            dataFetcher.fetchForIntent(
                intent = effectiveIntent,
                shStepsByDate = shStepsByDate,
                shDailyActivity = shDailyActivity,
                shWorkouts = shWorkouts
            )
        }
        val prefetched: String = prefetchedData.toContextBlock(language)
        android.util.Log.d(
            "CHAT_FETCH",
            "intent=${if (intent is ChatIntent.Reasoning) "Reasoning(auto)" else "DataQuery"} " +
                "window=${effectiveIntent.window.from}→${effectiveIntent.window.to} " +
                "type=${effectiveIntent.type} block=${prefetched.length}chars"
        )

        // 2026-07-06 (Frente H) — two-tier response kickoff.
        //
        // For `ChatIntent.Reasoning` questions (open-ended "should I…?",
        // "why…?", "what if…?") the deep-tier answer takes a few seconds
        // to think + stream. Fire a fast-tier one-shot in parallel with
        // the deep prep so the user gets a credible 1-2 sentence answer
        // in ~500 ms, then the deep answer streams in and replaces it.
        //
        // `DataQuery` intents skip this — the deep response is already
        // fast (LLM only synthesises over pre-fetched data), so a second
        // call would be wasted quota. See ITOpsEA plan for the gate.
        val fastReplyDeferred: kotlinx.coroutines.Deferred<String?>? =
            if (intent is ChatIntent.Reasoning) {
                android.util.Log.d("CHAT_TWO_TIER", "kickoff fast-tier for Reasoning intent")
                backgroundScope.async {
                    val started = System.currentTimeMillis()
                    val fast = fastReplyBlockingCall(
                        userText = text,
                        canonicalSnapshot = canonical,
                        prefetchedBlock = prefetched,
                        userName = userNameOverride ?: "the user",
                        language = language,
                        apiKey = apiKey,
                    )
                    android.util.Log.i(
                        "CHAT_TWO_TIER",
                        "fast reply ready in ${System.currentTimeMillis() - started}ms " +
                            "(len=${fast?.length ?: 0})"
                    )
                    fast
                }
            } else null

        // --- 5. Build the system instruction + history exactly like the
        //     legacy `GeminiRepository.sendMessage` does, with the added
        //     PRE-FETCHED block when applicable.
        val knownHabitsBlock = buildKnownHabitsBlock()

        // 2026-06-29 — Frente Memory C2.b. Question-directed recall over
        // confirmed user_insights. Adds AT MOST a few hundred bytes per
        // turn, but lets the LLM see the facts most relevant to THIS
        // question (allergies, goals, routines) regardless of confidence
        // ranking. Mark every referenced insight afterwards so it climbs
        // the recency signal for next turn.
        val recallHits: List<com.example.data.chat.InsightRecaller.Ranked> = runCatching {
            val pool = userInsightDao.allConfirmedActive()
            com.example.data.chat.InsightRecaller.rankWithRelated(
                userText = text,
                pool = pool,
                maxItems = com.example.data.chat.InsightRecaller.DEFAULT_MAX_ITEMS,
            )
        }.getOrDefault(emptyList())
        val kbBlock: String = if (recallHits.isEmpty()) "" else buildString {
            append("\n\n**Most relevant memorized facts for THIS question** ")
            append("(sorted by relevance; do NOT contradict, you may build on them):\n")
            recallHits.forEach { r ->
                append("- [").append(r.insight.category).append("] ")
                append(r.insight.summary ?: r.insight.text)
                append('\n')
            }
        }
        android.util.Log.d(
            "CHAT_KB",
            "recall hits=${recallHits.size} ids=${recallHits.map { it.insight.id }}"
        )
        // Touch lastReferencedAtMs for every hit, fire-and-forget so the
        // prompt build never waits on these writes. Errors logged but
        // swallowed — a missed bump just means slightly less recency
        // weight next turn.
        if (recallHits.isNotEmpty()) {
            val touchNow = System.currentTimeMillis()
            insightTouchJobs.launchReplacing(
                sessionId = sessionId,
                logTag = "CHAT_KB",
                logMessage = "cancelling previous insight touch job for"
            ) {
                recallHits.forEach { r ->
                    runCatching { userInsightDao.markReferenced(r.insight.id, touchNow) }
                }
            }
        }

        // 2026-06-29 — Frente Memory C3. Cross-session continuity block.
        // The rolling-window of C1.a keeps a single session's history
        // bounded; the carryover digest bridges the gap when the user
        // opens a NEW session (or comes back after a long break) so
        // the model still has 1-2 sentences of recent context. Empty
        // string when there's nothing carried over yet, or when
        // [appContext] is null (test / headless callers).
        val carryoverBlock: String = appContext?.let { CarryoverDigest.toPromptBlock(it) } ?: ""

        // 2026-06-22 (plan ADENDO 2 — Fix 9): explicit Gemini context
        // cache for the long stable prefix of the system prompt
        // (HABIT_PROTOCOL + template + nameDirective ≈ 9-12kB ≈ 2.5k
        // tokens). One cache per session, persisted as
        // `chat_sessions.cachedContentName`. If a cache exists and is
        // still alive we send `cachedContent = name` + a SHORT system
        // instruction holding only the per-turn dynamic part. If the
        // cache call fails or the field is rejected (e.g. by streaming),
        // we silently fall back to the full system instruction — no
        // user-visible change. Touch the TTL in the background so an
        // active session doesn't get its cache evicted between turns.
        val dynamicSystemText = (canonical + prefetched + knownHabitsBlock + carryoverBlock + kbBlock).trimStart()
        // 2026-07-06 (Frente C) — reuse the `session` var captured at the
        // top of `handle` instead of re-fetching. If the session was
        // renamed above (title == "New Chat" branch), the in-memory copy
        // already reflects the new title.
        //
        // 2026-07-06 (Frente D) — `createCache` no longer runs inline
        // here. It was kicked off as a `backgroundScope.async` right
        // after the API-key gate; we only give it a 50 ms grace period
        // to complete so we don't add latency in the common cold-cache
        // case. If it already finished (fast network / warm route) we
        // pick up the name and persist for future turns; otherwise this
        // turn goes `cache=miss`, the async keeps running, and it will
        // land on the DB in time for the NEXT turn.
        var cacheName: String? = session?.cachedContentName
        if (cacheName.isNullOrBlank() && cacheDeferred != null) {
            val awaited = kotlinx.coroutines.withTimeoutOrNull(50L) { cacheDeferred.await() }
            if (!awaited.isNullOrBlank()) {
                cacheName = awaited
                session?.let {
                    val updated = it.copy(cachedContentName = awaited)
                    chatDao.updateSession(updated)
                    session = updated
                }
                android.util.Log.d("CHAT_CACHE", "createCache landed within grace → $awaited")
            } else {
                // Miss this turn but keep the deferred alive — it will
                // persist to the DB asynchronously when it eventually
                // completes so the NEXT turn benefits.
                val captured = cacheDeferred
                val capturedSessionId = sessionId
                cachePersistJobs.launchReplacing(
                    sessionId = capturedSessionId,
                    logTag = "CHAT_CACHE",
                    logMessage = "cancelling previous createCache waiter for"
                ) {
                    val late = runCatching { captured.await() }.getOrNull()
                    if (!late.isNullOrBlank()) {
                        runCatching {
                            chatDao.getSessionById(capturedSessionId)?.let { s ->
                                chatDao.updateSession(s.copy(cachedContentName = late))
                            }
                        }
                        android.util.Log.d(
                            "CHAT_CACHE",
                            "createCache landed AFTER send → $late (persisted for next turn)"
                        )
                    } else {
                        android.util.Log.w("CHAT_CACHE", "createCache async returned null/blank")
                    }
                }
                android.util.Log.d("CHAT_CACHE", "createCache miss this turn (still in flight)")
            }
        } else if (!cacheName.isNullOrBlank()) {
            // Existing cache — refresh TTL in the background so it
            // doesn't expire mid-conversation. Non-blocking: failure
            // logged in CHAT_CACHE. Runs on [backgroundScope] (orchestrator-
            // scoped SupervisorJob), not GlobalScope — so the touch dies
            // when the ViewModel does, but survives the end of this single
            // turn's streaming flow.
            val capturedName = cacheName
            cacheTouchJobs.launchReplacing(
                sessionId = sessionId,
                logTag = "CHAT_CACHE",
                logMessage = "cancelling previous touchCache for"
            ) {
                GeminiCacheClient.touchCache(capturedName, ttlSeconds = 3600, apiKey = apiKey)
            }
        }

        val useCache = !cacheName.isNullOrBlank()
        val systemInstructionText: String =
            if (useCache) dynamicSystemText
            else (baseInstruction + dynamicSystemText).trimStart()
        val systemInstruction = if (systemInstructionText.isNotBlank()) {
            Content(parts = listOf(Part(text = systemInstructionText)))
        } else null
        // Always materialise the full version too — used as a fallback
        // payload if the API rejects `cachedContent` (404/streaming-not-
        // supported). When `useCache` is false this is identical to
        // `systemInstruction` above.
        val fullSystemInstructionText = (baseInstruction + dynamicSystemText).trimStart()
        val fullSystemInstructionFallback: Content? =
            if (fullSystemInstructionText.isNotBlank())
                Content(parts = listOf(Part(text = fullSystemInstructionText)))
            else null
        android.util.Log.d(
            "CHAT_CACHE",
            if (useCache) "using $cacheName · systemInstruction reduced to ${systemInstructionText.length}chars"
            else "no cache · systemInstruction=${systemInstructionText.length}chars"
        )

        // History (filter errors)
        val dbMessagesRaw = chatDao.getMessagesForSession(sessionId)
        val dbMessages = dbMessagesRaw.filter { !it.isError }

        // 2026-06-29 — Frente C1.a: rolling-window history.
        //
        // The conversation history was previously sent in full to Gemini
        // on every turn. Madhvi's S25 has 420 chat_messages — each turn
        // shipped ~100 KB of history, which (a) slowed the streaming
        // response by seconds, (b) crowded out the dynamic context block
        // the model needs, and (c) burned context-cache misses on every
        // edit. The fix: cap the history at CAP_TURNS most-recent turns
        // OR CAP_CHARS aggregate text length (whichever hits first); the
        // turns that fall off the cap are summarised once into a
        // single `[PREVIOUS_TURNS_SUMMARY]` Content row.
        //
        // CRITICAL: rows are NEVER deleted from `chat_messages`. The
        // poda is purely in-memory at request-build time. The UI keeps
        // showing every turn; only the LLM sees the cap. `digestSummary`
        // is persisted on `chat_sessions` (v24) so subsequent turns
        // reuse it instead of asking Flash to summarise again — a
        // single summary survives until the cap rolls forward again.
        val CAP_TURNS = 30
        val CAP_CHARS = 32_000
        val cumulativeChars = mutableListOf<Int>()
        var runningChars = 0
        // Iterate in reverse so we measure "last N turns fit in budget".
        for (m in dbMessages.asReversed()) {
            runningChars += m.text.length + (m.imageBase64?.length ?: 0)
            cumulativeChars.add(runningChars)
        }
        // Find the largest suffix that fits BOTH caps.
        val keptCount = run {
            var n = 0
            for ((idx, totalChars) in cumulativeChars.withIndex()) {
                val turns = idx + 1
                if (turns > CAP_TURNS) break
                if (totalChars > CAP_CHARS) break
                n = turns
            }
            n.coerceAtLeast(0)
        }
        val needsRollingWindow = keptCount in 1 until dbMessages.size
        val trimmedMessages: List<ChatMessage>
        val keptMessages: List<ChatMessage>
        if (needsRollingWindow) {
            val cutIdx = dbMessages.size - keptCount
            trimmedMessages = dbMessages.subList(0, cutIdx)
            keptMessages = dbMessages.subList(cutIdx, dbMessages.size)
            android.util.Log.d(
                "CHAT_ROLL",
                "rolling window: total=${dbMessages.size} kept=$keptCount " +
                    "trimmed=${trimmedMessages.size} keptChars=${cumulativeChars.getOrNull(keptCount - 1) ?: 0}"
            )
        } else {
            trimmedMessages = emptyList()
            keptMessages = dbMessages
            android.util.Log.d(
                "CHAT_ROLL",
                "no rolling: total=${dbMessages.size} fits in cap (${runningChars}chars)"
            )
        }

        // Resolve digest: reuse the persisted one when it covers the
        // current trim set; otherwise ask Flash for a fresh one and
        // persist. We key invalidation on the number of trimmed turns:
        // if `session.digestSummary` exists AND the current trim count
        // equals what was summarised before, reuse. Otherwise rebuild.
        // (We over-trigger occasionally — a session that swings around
        // the cap line could regenerate — but the cost is one Flash
        // call per ~30 turns, negligible vs the saved per-turn payload.)
        // 2026-07-06 (Frente D) — digest deferral. Pre-Frente-D flow
        // regenerated the digest inline on every trim-boundary shift,
        // running a blocking Gemini `generateContent` call (up to 6 s
        // `withTimeoutOrNull`) on the CRITICAL path before the main
        // stream could open. That's a big latency spike for a payload
        // element that only matters when the model needs older context.
        //
        // New flow: this turn ALWAYS uses `persistedDigest` even if the
        // trim count moved — it's slightly stale but still helpful. After
        // `emit(HandleEvent.Done)` we kick a `backgroundScope.launch`
        // that regenerates the digest and writes it to the DB, so the
        // NEXT turn picks up the fresh version. First trim-crossing on
        // a session sees `persistedDigest == null` and just falls back
        // to truncation-only, which is a minor and self-correcting hit
        // (marker `[N]` lands after this turn's Done → next turn reuses).
        val persistedDigest: String? = session?.digestSummary
        val digestText: String? = if (trimmedMessages.isEmpty()) {
            null
        } else {
            // Cheap check: persist a marker `[N]` at the start of the
            // digest so we can detect "same trim count → reuse".
            val expectedMarker = "[${trimmedMessages.size}]"
            if (persistedDigest != null && persistedDigest.startsWith(expectedMarker)) {
                android.util.Log.d("CHAT_ROLL", "reusing persisted digest (${persistedDigest.length}chars)")
                persistedDigest
            } else if (persistedDigest != null) {
                android.util.Log.d(
                    "CHAT_ROLL",
                    "digest marker mismatch (persisted=${persistedDigest.take(6)}…, expected=$expectedMarker); " +
                        "using stale digest THIS turn, background refresh scheduled for after Done"
                )
                persistedDigest
            } else {
                android.util.Log.d("CHAT_ROLL", "no persisted digest; truncation-only this turn, refresh scheduled")
                null
            }
        }
        // Snapshot for the background refresh below — captured now so a
        // subsequent mutation of `trimmedMessages` (defensive) wouldn't
        // race the coroutine that runs after Done.
        val needsDigestRefresh: Boolean = trimmedMessages.isNotEmpty() &&
            (persistedDigest == null || !persistedDigest.startsWith("[${trimmedMessages.size}]"))
        val digestRefreshTrimmed: List<ChatMessage>? =
            if (needsDigestRefresh) trimmedMessages.toList() else null
        val digestRefreshExpectedMarker: String? =
            if (needsDigestRefresh) "[${trimmedMessages.size}]" else null

        // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail) —
        // Belt-and-braces against history pollution. Old sessions contain
        // dozens of assistant turns saying "Olá, Humano!" because the
        // initial app build shipped with `UserProfile.name = "Humano"` as a
        // default. Gemini Flash mimics that pattern from in-context
        // learning even when the system prompt's anti-pollution directive
        // tells it not to. By regex-substituting `\bHumano\b` (model role
        // only) before wrapping each message in a `Part`, the history seen
        // by the model becomes consistent with the system directive. We do
        // NOT mutate the stored DB rows — only the in-memory copy heading
        // to the LLM. User-role bubbles are never rewritten.
        val nameForScrub: String = userNameOverride ?: "the user"
        val humanoRegex = Regex("(?i)\\bHumano\\b")
        var scrubCount = 0
        val mappedKept = keptMessages.map { msg ->
            val parts = mutableListOf<Part>()
            val rewrittenText: String = if (msg.sender != "user" && humanoRegex.containsMatchIn(msg.text)) {
                scrubCount += 1
                msg.text.replace(humanoRegex, nameForScrub)
            } else msg.text
            parts.add(Part(text = rewrittenText))
            if (!msg.imageBase64.isNullOrBlank()) {
                resolveImagePayload(msg.imageBase64).forEach { img ->
                    parts.add(Part(inlineData = InlineData(mimeType = img.mimeType, data = img.base64)))
                }
            }
            Content(role = if (msg.sender == "user") "user" else "model", parts = parts)
        }
        val contents: List<Content> = if (digestText != null) {
            // Prepend a synthetic model-role row carrying the summary so
            // the conversation still reads in order. The model gets the
            // gist of older turns without the bytes.
            listOf(
                Content(
                    role = "model",
                    parts = listOf(Part(text = "[PREVIOUS_TURNS_SUMMARY] $digestText"))
                )
            ) + mappedKept
        } else {
            mappedKept
        }
        if (scrubCount > 0) {
            android.util.Log.d(
                "CHAT_PROTOCOL",
                "history scrubbed=$scrubCount Humano→$nameForScrub (in-memory only)"
            )
        }

        val modelToUse = session?.model ?: "gemini-3.5-flash"
        // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail, Fix 7) —
        // Cap extended thinking. Gemini 2.5+ Flash runs thinking by default
        // which adds 30–40s of pre-stream latency on long replies (observed
        // 43s for 2260 chars on Hugo's S25). The API enforces EXCLUSIVITY:
        // sending both `thinking_level` and `thinkingBudget` returns
        //   HTTP 400 "You can only set only one of thinking budget and
        //   thinking level"
        // — observed on `gemini-3.1-flash-lite-preview` 2026-06-22. So we
        // send `thinking_level = "low"` only. If a model rejects the
        // field with a different HTTP 400 (Fix 7c below), we re-issue
        // the same model without the field.
        val chatThinking = ThinkingConfig(thinkingLevel = "low")
        val chatGenerationConfig = GenerationConfig(thinkingConfig = chatThinking)
        val request = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            generationConfig = chatGenerationConfig,
            cachedContent = cacheName
        )

        // Pre-flight diagnostic: count inlineData parts across the assembled
        // request. If the user just attached an image but `inlineDataParts=0`,
        // the problem is upstream of Gemini (decode/path/file read) — not the
        // model. See plan: /Users/i548178/.claude/plans/no-chat-quando-tiro-*
        run {
            val inlineCount = contents.sumOf { c -> c.parts.count { it.inlineData != null } }
            val totalParts = contents.sumOf { it.parts.size }
            android.util.Log.d(
                "CHAT_IMG",
                "request built: contents=${contents.size} totalParts=$totalParts inlineDataParts=$inlineCount model=$modelToUse"
            )
        }

        // 2026-06-29 — Frente C1.a measurement: explicit payload-size
        // breakdown so we can prove the rolling-window is paying off in
        // the field. Emitted as a single CHAT_PERF line so it's easy to
        // grep. `historyChars` is the sum of `text` length across the
        // Content list — excludes inline image data so a single photo
        // doesn't dominate the number. `systemChars` covers the
        // injected systemInstruction (or the short cached form when the
        // session has a live Gemini context cache). `prepMs` is the
        // ms spent assembling everything BEFORE the LLM was called —
        // good guard against future regressions in the prep path.
        run {
            val historyChars = contents.sumOf { c -> c.parts.sumOf { it.text?.length ?: 0 } }
            val systemChars = systemInstruction?.parts?.sumOf { it.text?.length ?: 0 } ?: 0
            val prepMs = System.currentTimeMillis() - started
            android.util.Log.i(
                "CHAT_PERF",
                "prep=${prepMs}ms systemChars=$systemChars historyChars=$historyChars " +
                    "contentsRows=${contents.size} cache=${if (useCache) "hit" else "miss"} " +
                    "rollingApplied=${needsRollingWindow}"
            )
        }

        emit(HandleEvent.Phase(com.example.ui.tr("composing_response", language)))

        // 2026-07-06 (Frente H v2) — deep-first race gate.
        //
        // v1 blocked 1.5 s here waiting for the fast tier, then emit
        // FastReply unconditionally if any text came back. That was
        // wrong: even when the deep tier was going to start streaming
        // in another second, the user would see the fast bubble first
        // and then the deep text would REPLACE it 5 s later — text
        // changing under the reader's eyes.
        //
        // v2 flips the logic: assume deep will be fast (happy path).
        // Only if `FAST_GRACE_MS` (3 s) elapse WITHOUT deep having
        // produced its first token do we emit the fast reply. Otherwise
        // we cancel the fast Deferred and let deep stream normally into
        // the bubble, as if Frente H weren't there.
        //
        // `firstDeepPartialLatch` is completed by streamReply's
        // `onFirstToken` callback (see suspend fun signalFirstTokenOnce
        // in streamReply). The race side-launch below decides in the
        // background whether to inject a FastReply into the flow via
        // the `pendingFastReply` state — read below by the flow builder
        // right after streamReply returns its first Partial.
        val firstDeepPartialLatch = CompletableDeferred<Unit>()
        val pendingFastReply = kotlinx.coroutines.CompletableDeferred<String?>()
        if (fastReplyDeferred != null) {
            backgroundScope.launch {
                val raceStart = System.currentTimeMillis()
                // race: whichever completes first wins.
                val winner: String = select {
                    firstDeepPartialLatch.onAwait { "deep" }
                    onTimeout(FAST_GRACE_MS) { "grace" }
                }
                val elapsed = System.currentTimeMillis() - raceStart
                if (winner == "deep") {
                    android.util.Log.d(
                        "CHAT_TWO_TIER",
                        "fast_cancelled_deep_won deepFirstTokenMs=$elapsed"
                    )
                    fastReplyDeferred.cancel()
                    pendingFastReply.complete(null)
                } else {
                    // Grace elapsed. Now actually wait for the fast text
                    // (it might still be in flight past 3 s). Bound the
                    // extra wait so we don't hold the flow forever.
                    val fastText = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                        fastReplyDeferred.await()
                    }
                    if (!fastText.isNullOrBlank()) {
                        android.util.Log.i(
                            "CHAT_TWO_TIER",
                            "fast_emitted_deep_slow fastMs=$elapsed len=${fastText.length}"
                        )
                    } else {
                        android.util.Log.d(
                            "CHAT_TWO_TIER",
                            "fast_grace_elapsed_but_empty fastMs=$elapsed"
                        )
                    }
                    pendingFastReply.complete(fastText)
                }
            }
        } else {
            pendingFastReply.complete(null)
        }

        // 2026-06-28 — Frente C: kick off the parallel chart call IF the
        // local intent router thinks the user wants a visualisation AND a
        // [chartRequester] was injected at construction time. Runs on the
        // orchestrator's [backgroundScope] so it survives the flow
        // completion (we await it AFTER emitting Done). Started here so
        // both calls hit the network at roughly the same time and the
        // chart result is usually ready by the time the stream finishes.
        val wantsChart = chartRequester != null && intentRouter.wantsChart(text)
        val chartStartedAtMs = System.currentTimeMillis()
        val chartDeferred: kotlinx.coroutines.Deferred<com.example.data.remote.ChartProposal?>? =
            if (wantsChart && chartRequester != null) {
                android.util.Log.d(
                    "CHAT_PARALLEL",
                    "wantsChart=true, kicking off parallel chart call at +${System.currentTimeMillis() - started}ms"
                )
                backgroundScope.async {
                    chartRequester.invoke(
                        text,
                        prefetched,
                        userNameOverride ?: "the user",
                        language,
                    )
                }
            } else null

        // --- 6. Stream (or fall back to non-streaming) ---
        // 2026-07-06 (Frente H v2) — the onPartial callback wraps the
        // race logic: on the FIRST partial it waits for the race to
        // resolve (which happens near-instantly because it's just a
        // `select` inside backgroundScope), and if the race concluded
        // that the fast tier should be shown (deep was slow), it emits
        // the FastReply BEFORE the deep Partial. In the happy path
        // (deep is fast), the race returns "deep", `pendingFastReply`
        // resolves to null, no FastReply is ever emitted, and the
        // bubble goes straight from typing-dots to deep partial.
        var fastReplyEmitted = false
        val replyText: String = if (streaming) {
            try {
                streamReply(
                    modelToUse, apiKey, request, language,
                    onPartial = { partial ->
                        if (!fastReplyEmitted) {
                            fastReplyEmitted = true
                            // Race is already decided by the time we get
                            // here — signalFirstTokenOnce (via
                            // firstDeepPartialLatch.complete) unlocks the
                            // race, which finishes and completes
                            // pendingFastReply within the same tick.
                            val fastText = pendingFastReply.await()
                            if (!fastText.isNullOrBlank()) {
                                emit(HandleEvent.FastReply(fastText))
                            }
                        }
                        emit(HandleEvent.Partial(partial))
                    },
                    onPhase = { phase -> emit(HandleEvent.Phase(phase)) },
                    onFirstToken = {
                        // Complete the latch synchronously so the race
                        // in backgroundScope can decide "deep won".
                        if (!firstDeepPartialLatch.isCompleted) {
                            firstDeepPartialLatch.complete(Unit)
                        }
                    },
                    fullSystemInstructionForCacheFallback = fullSystemInstructionFallback,
                    onCacheRejected = { rejectedName ->
                        // Clear the column so the next turn re-creates a
                        // cache. Don't delete the server-side resource
                        // because it may simply have expired.
                        runCatching {
                            chatDao.getSessionById(sessionId)?.let {
                                chatDao.updateSession(it.copy(cachedContentName = null))
                            }
                        }
                        android.util.Log.d(
                            "CHAT_CACHE",
                            "cleared sessionId=$sessionId cachedContentName (was $rejectedName)"
                        )
                    }
                )
            } catch (t: Throwable) {
                // 2026-07-09 — rethrow only GENUINE outer cancellation (session
                // switch, ✕ button, ViewModel scope death) so structured
                // concurrency is honored. streamReply's own 90s budget throws
                // TimeoutCancellationException (a CancellationException subclass)
                // and MUST fall through to the blockingReply fallback below —
                // that is the whole point of this catch, per the Fix #2 comment.
                if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
                android.util.Log.w("CHAT_STREAM", "stream failed entirely, falling back to non-streaming: ${t.message?.take(120)}")
                emit(HandleEvent.Phase(com.example.ui.tr("composing_response", language)))
                // 2026-07-07 (Fix #2) — cap the blocking fallback so it can't
                // hang the send job indefinitely. blockingReply cascades 5
                // models with 1-2 attempts each and no internal wall-clock;
                // without this cap a slow-503 fleet could extend the turn far
                // beyond the 90s streamReply budget (which we're OUTSIDE of
                // here because this catch is on `streamReply` itself). Null
                // from withTimeoutOrNull falls into the same `?: run` path as
                // a nulled blockingReply, preserving the fast-tier fallback
                // and Error emission.
                kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    blockingReply(modelToUse, apiKey, request, language)
                }
                    ?: run {
                        // 2026-07-06 (Frente H v2) — deep failed completely.
                        // If the fast tier already produced a usable reply
                        // (race won, or we were mid-emit when deep threw),
                        // persist that text as the assistant row so the
                        // bubble the user was reading doesn't disappear
                        // when the session flow refreshes. Otherwise fall
                        // back to Error like pre-Frente-H behaviour.
                        val fastFallback = runCatching {
                            if (pendingFastReply.isCompleted) pendingFastReply.getCompleted() else null
                        }.getOrNull()
                        if (!fastFallback.isNullOrBlank()) {
                            android.util.Log.i(
                                "CHAT_TWO_TIER",
                                "deep_failed_kept_fast len=${fastFallback.length}"
                            )
                            val fallbackId = chatDao.insertMessage(
                                ChatMessage(
                                    sessionId = sessionId,
                                    sender = "gemini",
                                    text = fastFallback,
                                )
                            )
                            emit(HandleEvent.Done(fastFallback, insertedMessageId = fallbackId))
                            return@flow
                        }
                        emit(HandleEvent.Error(t))
                        return@flow
                    }
            }
        } else {
            // 2026-07-07 (Fix #2) — same cap as the streaming-catch path
            // above. Non-streaming callers pay the full model-cascade cost
            // sequentially, so a 30s ceiling here is strictly a floor for
            // the fast-tier fallback + Error branch.
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                blockingReply(modelToUse, apiKey, request, language)
            }
                ?: run {
                    // 2026-07-06 (Frente H v2) — same fallback for the
                    // non-streaming path. Rare (only if the caller
                    // explicitly disabled streaming), but preserving the
                    // fast bubble here too is consistent.
                    val fastFallback = runCatching {
                        if (pendingFastReply.isCompleted) pendingFastReply.getCompleted() else null
                    }.getOrNull()
                    if (!fastFallback.isNullOrBlank()) {
                        android.util.Log.i(
                            "CHAT_TWO_TIER",
                            "blocking_failed_kept_fast len=${fastFallback.length}"
                        )
                        val fallbackId = chatDao.insertMessage(
                            ChatMessage(
                                sessionId = sessionId,
                                sender = "gemini",
                                text = fastFallback,
                            )
                        )
                        emit(HandleEvent.Done(fastFallback, insertedMessageId = fallbackId))
                        return@flow
                    }
                    emit(HandleEvent.Error(IllegalStateException("LLM call failed")))
                    return@flow
                }
        }

        // --- 7. Optional QUERY_DATA fallback round-trip (paridade with old) ---
        val firstParse = ActionProposalParser.parse(replyText)
        val finalReplyText: String = if (firstParse.queryDataRequest != null) {
            emit(HandleEvent.Phase(com.example.ui.tr("querying_records", language)))
            android.util.Log.d(
                "CHAT_QUERY",
                "fallback type=${firstParse.queryDataRequest!!.type} " +
                    "from=${firstParse.queryDataRequest!!.fromDate} to=${firstParse.queryDataRequest!!.toDate}"
            )
            val resultBlock = runQueryAndFormat(firstParse.queryDataRequest!!)
            val followupContents = contents +
                Content(role = "model", parts = listOf(Part(text = replyText))) +
                Content(role = "user", parts = listOf(Part(text = "[QUERY_DATA_RESULT]\n$resultBlock")))
            val followupRequest = GeminiRequest(
                contents = followupContents,
                systemInstruction = systemInstruction,
                generationConfig = chatGenerationConfig,
                cachedContent = cacheName
            )
            // 2026-07-07 (Fix #2) — cap the QUERY_DATA follow-up. This
            // runs AFTER the stream already returned, so it's additive
            // to whatever the first round took. Without a cap a slow
            // 503-cascade here could keep the user waiting 20-30s past
            // the first answer with nothing on screen. On timeout we
            // fall back to the pre-QUERY_DATA replyText — the raw tag
            // just leaks through, but the user sees something.
            val followup = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                blockingReply(modelToUse, apiKey, followupRequest, language)
            }
            followup ?: replyText
        } else replyText

        // --- 8. Parse, persist cleaned, emit Done ---
        val finalParse = ActionProposalParser.parse(finalReplyText)
        // 2026-06-28 — apply the shared chat tone filter before showing the
        // bubble to the user. [ChatToneRules.clean] strips forbidden
        // sentences ("excelente ritmo", "continua assim", rhetorical
        // questions, soft imperatives) that the LLM still produces 5-10%
        // of the time despite the persona instructions. Persisted raw
        // text is left alone — the next ingest pass on the message Flow
        // re-derives proposals from it, and the cleaned text is what the
        // UI renders. No sentence cap on the chat path (notifications
        // keep their 2-sentence cap inside ProactivePromptBuilder).
        val sievedClean = ChatToneRules.clean(finalParse.cleanedText)
        val insertedMessageId = chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                sender = "gemini",
                // Persist the RAW final text so the existing
                // ingestAssistantMessage flow in the ViewModel can re-derive
                // proposals on the next collect — same shape as today.
                text = finalReplyText
            )
        )
        val elapsed = System.currentTimeMillis() - started
        android.util.Log.d("CHAT_ORCH", "completed in ${elapsed}ms (intent=${intent::class.simpleName})")
        android.util.Log.i(
            "CHAT_PERF",
            "total=${elapsed}ms replyChars=${finalReplyText.length} kbHits=${recallHits.size} " +
                "rollingApplied=${needsRollingWindow}"
        )
        // 2026-07-07 (Fix #6) — track how long after Done the flow keeps
        // running for the chart await. Before Fix #1, _isTyping stayed on
        // for this whole gap. If chart_wait_after_done is consistently
        // small (<500ms) the parallel pipeline is doing its job; if it's
        // 3-6s the parallel start point may be too late (kick earlier).
        val doneEmittedAtMs = System.currentTimeMillis()
        emit(HandleEvent.Done(sievedClean, insertedMessageId = insertedMessageId))

        // 2026-07-06 (Frente D) — background digest refresh. The trim
        // boundary shifted this turn (or the session had no digest at
        // all) but we didn't block the send on Flash. Kick the summary
        // now, persist to `session.digestSummary` so the NEXT turn
        // reuses it via the marker check. Fire-and-forget — a failure
        // here just means the next turn again falls back to truncation
        // only (no user-visible change).
        if (needsDigestRefresh && digestRefreshTrimmed != null && digestRefreshExpectedMarker != null) {
            val trimmedCopy = digestRefreshTrimmed
            val marker = digestRefreshExpectedMarker
            val lang = language
            val key = apiKey
            val fallback = session?.model ?: "gemini-3.5-flash"
            val sid = sessionId
            digestRefreshJobs.launchReplacing(
                sessionId = sid,
                logTag = "CHAT_ROLL",
                logMessage = "cancelling previous digest refresh for"
            ) {
                val started = System.currentTimeMillis()
                val fresh = runCatching {
                    summariseTrimmedTurns(
                        trimmed = trimmedCopy,
                        language = lang,
                        apiKey = key,
                        fallbackModel = fallback,
                    )
                }.getOrNull()
                if (!fresh.isNullOrBlank()) {
                    val marked = "$marker $fresh"
                    runCatching {
                        chatDao.getSessionById(sid)?.let {
                            chatDao.updateSession(it.copy(digestSummary = marked))
                        }
                    }
                    android.util.Log.i(
                        "CHAT_ROLL",
                        "digest refresh OK marker=$marker len=${marked.length} took=${System.currentTimeMillis() - started}ms"
                    )
                } else {
                    android.util.Log.w(
                        "CHAT_ROLL",
                        "digest refresh failed for marker=$marker after ${System.currentTimeMillis() - started}ms"
                    )
                }
            }
        }

        // 2026-06-29 — Frente Memory C3. Append a 1-sentence factual
        // summary of THIS turn to the cross-session carryover digest.
        // Background, fire-and-forget: the user has already seen the
        // reply, no point making them wait for this tiny meta-task.
        // The Flash call here is cheap (~256 tokens, single round trip)
        // and amortised across many turns since most of the time the
        // digest is just appended (no compression needed).
        if (appContext != null) {
            val ctx = appContext
            val userTurnText = text
            val asstReplyClean = sievedClean
            carryoverJobs.launchReplacing(
                sessionId = sessionId,
                logTag = "CHAT_CARRYOVER",
                logMessage = "cancelling previous carryover append for"
            ) {
                runCatching {
                    val sentence = summariseTurnForCarryover(
                        userText = userTurnText,
                        assistantReply = asstReplyClean,
                        language = language,
                        apiKey = apiKey,
                        fallbackModel = session?.model ?: "gemini-3.5-flash",
                    )
                    if (!sentence.isNullOrBlank()) {
                        CarryoverDigest.appendAndMaybeCompress(
                            context = ctx,
                            sentence = sentence,
                        ) { oldestChunk ->
                            compressCarryoverChunk(
                                chunk = oldestChunk,
                                language = language,
                                apiKey = apiKey,
                                fallbackModel = session?.model ?: "gemini-3.5-flash",
                            )
                        }
                    } else {
                        android.util.Log.d("CHAT_CARRYOVER", "skip: summariser returned blank")
                    }
                }.onFailure { t ->
                    android.util.Log.w("CHAT_CARRYOVER", "append failed: ${t.message?.take(120)}")
                }
            }
        }

        // --- 9. Frente C: emit ChartReady IF the parallel chart call
        //     produced a usable proposal. Awaited AFTER Done so the user
        //     sees the textual answer immediately and the chart attaches
        //     a beat later — that's the whole point of the parallel
        //     pipeline. If the chart parser already produced one inline
        //     in the main reply (finalParse.chartProposals not empty),
        //     skip the parallel result to avoid double-charting. */
        if (chartDeferred != null) {
            val alreadyHasInline = finalParse.chartProposals.isNotEmpty()
            if (alreadyHasInline) {
                android.util.Log.d(
                    "CHAT_PARALLEL",
                    "inline chart present in reply, cancelling parallel chart call"
                )
                chartDeferred.cancel()
            } else {
                // The async was kicked off at the start of streaming, so by
                // now it's likely already finished. Cap the extra wait at 6s
                // so a slow chart call never holds the typing-bubble cleared
                // state hostage.
                val chart = try {
                    kotlinx.coroutines.withTimeoutOrNull(6_000L) { chartDeferred.await() }
                } catch (t: Throwable) {
                    android.util.Log.w("CHAT_PARALLEL", "chartDeferred await failed: ${t.message}")
                    null
                }
                val chartElapsed = System.currentTimeMillis() - chartStartedAtMs
                // 2026-07-07 (Fix #6) — chart_wait_after_done is the window
                // where the flow is still running AFTER the user already saw
                // the text. Pre-Fix-#1 that window blocked _isTyping=false.
                val chartWaitAfterDone = System.currentTimeMillis() - doneEmittedAtMs
                if (chart != null) {
                    android.util.Log.i(
                        "CHAT_PERF",
                        "chart_wait_after_done=${chartWaitAfterDone}ms chart_total=${chartElapsed}ms kind=${chart.kind}"
                    )
                    android.util.Log.d(
                        "CHAT_PARALLEL",
                        "ChartReady kind=${chart.kind} series=${chart.series.size} (${chartElapsed}ms total)"
                    )
                    emit(HandleEvent.ChartReady(anchorMessageId = insertedMessageId, chart = chart))
                } else {
                    android.util.Log.i(
                        "CHAT_PERF",
                        "chart_wait_after_done=${chartWaitAfterDone}ms chart_total=${chartElapsed}ms result=null"
                    )
                    android.util.Log.d(
                        "CHAT_PARALLEL",
                        "chartDeferred returned null (${chartElapsed}ms), silencing"
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * 2026-06-28 — Frente A: proactivity inline no chat. Called by
     * [com.example.ui.ChatViewModel.maybeFireProactiveOnChatTabResumed] when
     * a [com.example.data.notifications.Trigger] has fired AND the chat-tab
     * cooldown gate (≥4h since the last proactive bubble) is satisfied.
     *
     * Differences vs [handle]:
     *  - No user message is persisted — there is no user turn here.
     *  - Skip [IntentRouter] entirely — we know the trigger.
     *  - Non-streaming call: the message is 1-2 sentences, streaming would
     *    add latency without UX benefit.
     *  - Skip context cache creation (cold-path, low frequency — never worth
     *    the cache POST).
     *  - Still runs [ChatToneRules.clean] so the same filter that scrubs
     *    "excelente ritmo" out of the chat path scrubs it here too.
     */
    fun handleProactive(
        sessionId: Long,
        trigger: com.example.data.notifications.Trigger,
        language: String,
        userName: String,
        shStepsByDate: Map<LocalDate, Long>,
        shDailyActivity: Map<LocalDate, ShDailyActivity>,
        shWorkouts: List<ShWorkout> = emptyList()
    ): Flow<HandleEvent> = flow {
        val started = System.currentTimeMillis()
        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            emit(HandleEvent.Error(IllegalStateException("API key missing for proactive turn")))
            return@flow
        }

        val today = LocalDate.now()

        // Canonical snapshot — same shape as the reactive path so the model
        // sees the same numbers. No PRE-FETCHED block here: proactive turns
        // are about THIS moment, not a long historical window.
        val canonical = buildCanonicalSnapshot(today, userName)

        // Build a tighter, trigger-specific prompt. The persona enforced
        // in ChatToneRules.personaFor (already baked into the session's
        // stored systemInstruction) handles tone; this turn adds the
        // proactive constraint.
        val triggerHint = when (trigger) {
            is com.example.data.notifications.Trigger.MealMissing ->
                "The ${trigger.category.name.lowercase()} window has closed and $userName has logged nothing for it today. " +
                    "State one concrete fact about it — time the window closed, average kcal for that meal over the last 3 days, " +
                    "or yesterday's same meal."
            is com.example.data.notifications.Trigger.ActivitySpike ->
                "Active calories this hour spiked: +${trigger.deltaKcal} kcal vs yesterday's same-hour average. " +
                    "State the delta as a single short fact. No celebration, no encouragement."
            is com.example.data.notifications.Trigger.MacrosAtRisk ->
                "It's past 18:00 and $userName is below target on both kcal (${trigger.kcalPct}%) and protein (${trigger.proteinPct}%). " +
                    "State how many grams of protein remain to the daily target as one short fact."
            is com.example.data.notifications.Trigger.DailySummary ->
                "End-of-day recap window. Sum up today in 1-2 short factual sentences: kcal logged vs target, " +
                    "protein vs target. No tomorrow tips, no congratulations."
        }
        val proactiveSystem = "You are sending an UNPROMPTED first message to $userName, who just opened the chat. " +
            "There was NO user message. Write 1-2 short sentences in language code: $language. " +
            "Ground every number in the snapshot below — do not invent. No greeting, no sign-off, no questions, " +
            "no emoji, no protocol tags ([HABIT_PROPOSAL], [LOG_MEAL_PROPOSAL], [CHART_PROPOSAL], etc.). " +
            triggerHint +
            "\n\n" + canonical

        // Single non-streaming call. The fallback chain is the same as
        // [blockingReply] uses but inlined here so we can keep this path
        // dependency-light (no [GeminiRequest] for chat history).
        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = "Send the proactive message now.")))
            ),
            systemInstruction = Content(parts = listOf(Part(text = proactiveSystem))),
            generationConfig = GenerationConfig(
                temperature = 0.4f,
                maxOutputTokens = 96,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )

        emit(HandleEvent.Phase("thinking_proactive"))

        val replyText = blockingReply(
            model = "gemini-3.5-flash",
            apiKey = apiKey,
            request = request,
            language = language
        )

        if (replyText.isNullOrBlank()) {
            android.util.Log.w("CHAT_PROACTIVE", "empty reply for trigger=${trigger.dedupKey}")
            emit(HandleEvent.Error(IllegalStateException("Empty proactive reply")))
            return@flow
        }

        // Same defensive filter the reactive path runs. The persona already
        // tells the model not to say "excelente ritmo" but with temperature
        // 0.4 it still slips ~5% of the time.
        val cleaned = ChatToneRules.clean(replyText)
        if (cleaned.isBlank()) {
            android.util.Log.w("CHAT_PROACTIVE", "clean() dropped every sentence — suppressing")
            emit(HandleEvent.Error(IllegalStateException("Proactive reply filtered out")))
            return@flow
        }

        chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                sender = "gemini",
                text = cleaned
            )
        )
        chatDao.touchSession(sessionId)
        val elapsed = System.currentTimeMillis() - started
        android.util.Log.d(
            "CHAT_PROACTIVE",
            "fired trigger=${trigger.dedupKey} (${elapsed}ms, len=${cleaned.length})"
        )
        // Touch unused params so a future extension (e.g. workout-aware
        // proactive nudges) sees they're part of the contract.
        @Suppress("UNUSED_EXPRESSION") shStepsByDate
        @Suppress("UNUSED_EXPRESSION") shDailyActivity
        @Suppress("UNUSED_EXPRESSION") shWorkouts
        emit(HandleEvent.Done(cleaned))
    }

    private fun launchInsightExtraction(
        sessionId: Long,
        text: String,
        language: String,
    ) {
        insightExtractJobs.launchReplacing(
            sessionId = sessionId,
            logTag = "CHAT_KB",
            logMessage = "cancelling previous insight extractor job for"
        ) {
            try {
                val candidates = com.example.data.chat.InsightExtractor.extract(text, language)
                if (candidates.isNotEmpty()) {
                    android.util.Log.d(
                        "CHAT_KB",
                        "extractor candidates=${candidates.size} cats=${candidates.map { it.category }}"
                    )
                    candidates.forEach { c ->
                        val ins = com.example.data.chat.InsightExtractor.toUserInsight(c, sessionId)
                        runCatching {
                            val existing = userInsightDao.findByText(ins.text, ins.category)
                            if (existing == null) {
                                val withKeywords = ins.copy(
                                    keywords = c.keywords.takeIf { it.isNotBlank() },
                                )
                                userInsightDao.insert(withKeywords)
                            } else {
                                userInsightDao.incrementMentionCount(existing.id)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("CHAT_KB", "extractor failed: ${t.message}")
            }
        }
    }

    internal data class StreamAttemptWatchdog(
        val startedAtMs: Long,
        val lastPartialAtMs: Long? = null,
    ) {
        fun timeoutReason(
            nowMs: Long,
            ttftTimeoutMs: Long = TTFT_TIMEOUT_MS,
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

    private data class StreamAttemptResult(
        val accumulated: String,
        val error: Throwable? = null,
    )

    private suspend fun collectStreamAttempt(
        modelName: String,
        apiKey: String,
        request: GeminiRequest,
        onPartial: suspend (String) -> Unit,
        onFirstToken: suspend () -> Unit,
        logLabel: String = modelName,
    ): StreamAttemptResult {
        val result = GeminiStreamWatchdogCollector.collect(
            model = modelName,
            apiKey = apiKey,
            request = request,
            logTag = "CHAT_STREAM",
            spec = GeminiStreamWatchdogCollector.Spec(
                ttftTimeoutMs = TTFT_TIMEOUT_MS,
                idleTimeoutMs = STREAM_IDLE_TIMEOUT_MS,
                tickMs = STREAM_WATCHDOG_TICK_MS,
                ttftReason = StreamAttemptWatchdog.REASON_TTFT,
                idleReason = StreamAttemptWatchdog.REASON_IDLE,
            ),
            clearTextOnStreamError = true,
            clearTextOnThrowable = true,
            onTimeoutLog = { reason ->
                when (reason) {
                    StreamAttemptWatchdog.REASON_TTFT ->
                        "$logLabel TTFT >${TTFT_TIMEOUT_MS}ms, cancelling"
                    StreamAttemptWatchdog.REASON_IDLE ->
                        "$logLabel idle >${STREAM_IDLE_TIMEOUT_MS}ms after first token, cancelling"
                    else -> "$logLabel watchdog timeout, cancelling"
                }
            },
            onFirstToken = onFirstToken,
            onPartial = { accumulated, _ ->
                onPartial(accumulated)
            },
            onDone = { accumulated, _, chunk ->
                val u = chunk.usage
                android.util.Log.i(
                    "CHAT_PERF",
                    "tokens prompt=${u?.promptTokenCount ?: "n/a"} " +
                        "candidates=${u?.candidatesTokenCount ?: "n/a"} " +
                        "total=${u?.totalTokenCount ?: "n/a"} " +
                        "cached=${u?.cachedContentTokenCount ?: 0} " +
                        "model=$modelName"
                )
            },
            onError = { chunk, _, _ ->
                android.util.Log.w(
                    "CHAT_STREAM",
                    "$logLabel stream error: ${chunk.cause.message} (partial len=${chunk.partial?.length ?: 0})"
                )
            },
        )
        return StreamAttemptResult(accumulated = result.fullText, error = result.error)
    }

    private suspend fun streamReply(
        model: String,
        apiKey: String,
        request: GeminiRequest,
        language: String,
        onPartial: suspend (String) -> Unit,
        onPhase: suspend (String) -> Unit,
        /** 2026-07-06 (Frente H v2) — fired exactly once, on the FIRST
         *  `StreamChunk.Partial` seen (across all fleet-loop retries).
         *  The caller uses it to complete the `firstDeepPartialLatch`
         *  that gates the fast-tier emit: if deep starts producing
         *  tokens before the grace window elapses, the fast reply is
         *  cancelled and never shown, avoiding the "text swap under
         *  the user's eyes" UX regression from H v1. Default no-op
         *  so existing callers don't have to plumb it. */
        onFirstToken: suspend () -> Unit = {},
        /** 2026-06-22 (plan ADENDO 2 — Fix 9): when set, the request was
         *  built with `cachedContent`. If the API rejects it (404/400
         *  related to the cache, or streaming-not-supported), strip the
         *  cache reference, replace the short systemInstruction with this
         *  full version (containing what was cached), and retry the same
         *  model. [onCacheRejected] is called so the orchestrator can
         *  clear the DB column and force re-creation on the next turn. */
        fullSystemInstructionForCacheFallback: Content? = null,
        onCacheRejected: suspend (cacheName: String) -> Unit = {}
    ): String {
        // Same model fleet as blockingReply, but only ONE attempt per model
        // on the streaming side because each is multi-second; iterating with
        // retries would push perceived latency past the user's patience. If
        // every model 503s, we surface the error so the caller can fall back
        // 2026-08-16 — chat cascade. 3.7-flash is text-safe but returns HTTP
        // 503 on VISION input, so when the request carries an image (inlineData
        // part) it is EXCLUDED everywhere — including from the `model` seed,
        // in case a session persisted 3.7-flash. Text-only turns keep it.
        val hasImage = request.contents.any { c -> c.parts.any { it.inlineData != null } }
        val models = if (hasImage) {
            listOf(model, "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite")
                .filterNot { it == "gemini-3.7-flash" }
        } else {
            listOf(model, "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite")
        }.distinct()
        var lastError: Throwable? = null
        // 2026-07-06 (Frente H v2) — one-shot flag so `onFirstToken` fires
        // exactly once across all fleet-loop retries and sub-branches
        // (post-cache, post-thinking). Some models emit their first
        // Partial in the retry branch, not the main branch, and we still
        // want the fast-tier gate to close as soon as ANY deep token
        // arrives.
        var firstTokenSignaled = false
        suspend fun signalFirstTokenOnce() {
            if (!firstTokenSignaled) {
                firstTokenSignaled = true
                runCatching { onFirstToken() }
            }
        }
        // 2026-06-22 (plan: preciso-que-vejas-isto-federated-quail, Fix 7c) —
        // If the API rejects `thinkingConfig` with a 400 "unknown field"
        // error (older endpoint, model without the feature), drop the
        // field once and re-issue the same call. Setting this on the
        // first rejection ensures subsequent model fallbacks within this
        // loop also drop the field, instead of re-failing the same way.
        var effectiveRequest: GeminiRequest = request
        // 2026-07-06 (Frente G) — outer wall-clock cap around the whole
        // fleet loop. The TTFT watchdog (Frente E) bounds each individual
        // model attempt's cold start, but a scenario where every model
        // emits ONE token then stalls would let the loop drag on for
        // minutes. 90 s is generous enough that a legitimately-long
        // Gemini answer (dozens of chart proposals) still fits, and if
        // we somehow hit it the outer `catch (t: Throwable)` in `handle`
        // (line 690+) falls through to `blockingReply`.
        return kotlinx.coroutines.withTimeout(90_000L) {
        for ((index, modelName) in models.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // 2026-07-06 (Frente F) — circuit breaker skip. If this model
            // has 503'd twice recently, don't waste another attempt on
            // it — jump to the next tier immediately. Half-open transition
            // is handled inside `isOpen` so the breaker naturally probes
            // again after the cooldown.
            if (circuitBreaker.isOpen(modelName)) {
                android.util.Log.d("CHAT_BREAKER", "skip $modelName (open)")
                continue
            }
            if (index > 0) {
                onPhase(
                    com.example.ui.tr("trying_alt_model", language)
                        .replace("%d/%d", "${index + 1}/${models.size}")
                        .let {
                            // Defensive: handle either "%d/%d" or two %d placeholders.
                            if (it.contains("%d")) {
                                it.replaceFirst("%d", "${index + 1}").replaceFirst("%d", "${models.size}")
                            } else it
                        }
                )
            }
            var accumulated = ""
            var streamError: Throwable? = null
            val attempt = collectStreamAttempt(
                modelName = modelName,
                apiKey = apiKey,
                request = effectiveRequest,
                onPartial = onPartial,
                onFirstToken = { signalFirstTokenOnce() },
                logLabel = modelName,
            )
            accumulated = attempt.accumulated
            streamError = attempt.error
            if (accumulated.isNotBlank()) {
                android.util.Log.d("CHAT_STREAM", "stream OK via $modelName (len=${accumulated.length})")
                circuitBreaker.recordSuccess(modelName)
                return@withTimeout accumulated
            }
            // Fix 9 fallback: API rejected the cachedContent reference
            // (cache expired/deleted server-side, or streaming endpoint
            // doesn't accept `cachedContent`). Drop the cache reference,
            // swap in the full system instruction (caller provided), and
            // retry the SAME model. The orchestrator's onCacheRejected
            // callback clears the DB column so the next turn re-creates.
            run {
                val cacheName = effectiveRequest.cachedContent
                val errMsgEarly = streamError?.message.orEmpty()
                val looksLikeCacheRejected = !cacheName.isNullOrBlank() &&
                    fullSystemInstructionForCacheFallback != null &&
                    (errMsgEarly.contains("HTTP 404", ignoreCase = true) ||
                        errMsgEarly.contains("CachedContent", ignoreCase = true) ||
                        errMsgEarly.contains("cached_content", ignoreCase = true) ||
                        errMsgEarly.contains("cachedContent", ignoreCase = true) ||
                        (errMsgEarly.contains("HTTP 400", ignoreCase = true) &&
                            errMsgEarly.contains("cache", ignoreCase = true)))
                if (looksLikeCacheRejected) {
                    android.util.Log.w(
                        "CHAT_CACHE",
                        "$modelName rejected $cacheName, falling back to full systemInstruction"
                    )
                    onCacheRejected(cacheName!!)
                    effectiveRequest = effectiveRequest.copy(
                        cachedContent = null,
                        systemInstruction = fullSystemInstructionForCacheFallback
                    )
                    var accumulatedC = ""
                    var streamErrorC: Throwable? = null
                    val retry = collectStreamAttempt(
                        modelName = modelName,
                        apiKey = apiKey,
                        request = effectiveRequest,
                        onPartial = onPartial,
                        onFirstToken = { signalFirstTokenOnce() },
                        logLabel = "$modelName (post-cache)",
                    )
                    accumulatedC = retry.accumulated
                    streamErrorC = retry.error
                    if (accumulatedC.isNotBlank()) {
                        android.util.Log.d(
                            "CHAT_STREAM",
                            "stream OK via $modelName after dropping cachedContent (len=${accumulatedC.length})"
                        )
                        circuitBreaker.recordSuccess(modelName)
                        return@withTimeout accumulatedC
                    }
                    streamError = streamErrorC ?: streamError
                }
            }
            // Fix 7c: detect "unknown field thinkingConfig/thinking_level"
            // style rejection OR the exclusivity error
            //   HTTP 400 "You can only set only one of thinking budget
            //   and thinking level"
            // observed on gemini-3.1-flash-lite-preview 2026-06-22 when
            // both fields were sent. Strip the field once and retry the
            // SAME model immediately before moving on to the next fallback.
            val errMsg = streamError?.message.orEmpty()
            val looksLikeThinkingRejected =
                effectiveRequest.generationConfig?.thinkingConfig != null &&
                    errMsg.contains("HTTP 400", ignoreCase = true) &&
                    (errMsg.contains("thinking", ignoreCase = true) ||
                        errMsg.contains("Unknown name", ignoreCase = true))
            if (looksLikeThinkingRejected) {
                android.util.Log.w(
                    "CHAT_STREAM",
                    "$modelName rejected thinkingConfig, retrying same model without it"
                )
                effectiveRequest = effectiveRequest.copy(
                    generationConfig = effectiveRequest.generationConfig?.copy(thinkingConfig = null)
                )
                // Retry the SAME model with the slimmer request before
                // moving to the next fallback.
                var accumulated2 = ""
                var streamError2: Throwable? = null
                val retry = collectStreamAttempt(
                    modelName = modelName,
                    apiKey = apiKey,
                    request = effectiveRequest,
                    onPartial = onPartial,
                    onFirstToken = { signalFirstTokenOnce() },
                    logLabel = "$modelName (post-thinking)",
                )
                accumulated2 = retry.accumulated
                streamError2 = retry.error
                if (accumulated2.isNotBlank()) {
                    android.util.Log.d(
                        "CHAT_STREAM",
                        "stream OK via $modelName after dropping thinkingConfig (len=${accumulated2.length})"
                    )
                    circuitBreaker.recordSuccess(modelName)
                    return@withTimeout accumulated2
                }
                streamError = streamError2 ?: streamError
            }
            lastError = streamError
            // 2026-07-06 (Frente F) — model exhausted all sub-branch
            // retries this iteration without producing text. Register
            // as a failure so the breaker opens after enough
            // consecutive misses.
            circuitBreaker.recordFailure(modelName)
            android.util.Log.w("CHAT_STREAM", "$modelName stream failed: ${streamError?.message?.take(120)}")
        }
        throw lastError ?: IllegalStateException("All streaming models exhausted")
        } // end withTimeout(90_000L)
    }

    /**
     * 2026-07-06 (Frente H) — fast-tier one-shot reply.
     *
     * Used ONLY for [ChatIntent.Reasoning] questions where the deep-tier
     * answer would otherwise leave the bubble spinning for seconds while
     * Gemini "thinks". This produces a 1–2 sentence direct answer using
     * `gemini-3.1-flash-lite-preview` with `thinkingBudget=0` and a
     * `maxOutputTokens=200` cap — typical wall-clock ~400–800 ms.
     *
     * The fast reply is context-aware but coarse: it sees the canonical
     * snapshot + prefetched data (same as deep) but a minimal system
     * instruction, no chat history, no cachedContent. It's meant to be
     * "credible instant answer" — a bridge until deep lands.
     *
     * Returns null on any failure — the caller silently skips the
     * fast-reply emit and the send proceeds as if two-tier wasn't
     * enabled for this turn. No user-visible degradation.
     */
    private suspend fun fastReplyBlockingCall(
        userText: String,
        canonicalSnapshot: String,
        prefetchedBlock: String,
        userName: String,
        language: String,
        apiKey: String,
    ): String? {
        // 2026-07-06 (Frente H v2) — factual, minimal, one sentence.
        //
        // v1 produced "fluffy" answers ("Vou analisar os teus dados…",
        // "É uma boa pergunta, para responder preciso saber…") that had
        // little value on their own. When the deep reply arrived 5s
        // later and replaced the fast bubble, the user saw the text
        // change under their eyes — confusing and low-signal.
        //
        // v2 forces ONE sentence, ≤20 words, ONE concrete fact from
        // the data block. If it's a subset of what the deep reply is
        // going to say, the replacement reads as an expansion rather
        // than a substitution.
        //
        // The `$userName` interpolation stays as context for the model
        // but is NOT quoted as a greeting — the system prompt bans that.
        val systemText = "Responde à pergunta do utilizador $userName numa única frase de no " +
            "máximo 20 palavras, em código de idioma: $language. Cita UM número concreto do " +
            "DATA block, ou UMA regra factual pequena. " +
            "Proibido: cumprimento, oferta de ajuda, verbo introdutório (\"Vou…\", \"Vamos…\", " +
            "\"Aqui está…\"), hedging (\"Depende…\", \"Vários factores…\"), pergunta de volta, " +
            "emoji, tags de protocolo, sign-off. Se o DATA block não chega para responder, " +
            "escreve exactamente: \"Sem dados suficientes.\"\n\n" +
            "$canonicalSnapshot\n\n$prefetchedBlock"
        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = userText)))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemText))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 60,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return try {
            val raw = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                blockingReply(
                    model = "gemini-3.1-flash-lite",
                    apiKey = apiKey,
                    request = request,
                    language = language,
                )
            }?.takeIf { it.isNotBlank() }?.trim() ?: return null

            // 2026-07-06 (Frente H v2) — belt-and-braces. Even with the
            // stricter prompt, Flash-Lite slips a "Olá!" or a
            // "Sinta-se à vontade para perguntar…" ~5% of the time.
            // `ChatToneRules.clean` is the same filter applied to the
            // deep reply (see line ~974). Run it here so the fast bubble
            // never shows tone-forbidden text.
            val cleaned = ChatToneRules.clean(raw)
            if (cleaned.isBlank()) {
                android.util.Log.w(
                    "CHAT_TWO_TIER",
                    "fast reply filtered to blank by ChatToneRules; suppressing"
                )
                null
            } else {
                cleaned
            }
        } catch (t: Throwable) {
            android.util.Log.w("CHAT_TWO_TIER", "fast reply failed: ${t.message?.take(120)}")
            null
        }
    }

    private suspend fun blockingReply(
        model: String,
        apiKey: String,
        request: GeminiRequest,
        @Suppress("UNUSED_PARAMETER") language: String
    ): String? {
        // Mirror the legacy GeminiRepository.generateContentWithFallback:
        // a single 503/429 on the streaming endpoint should NOT lose us the
        // turn — the model fleet has cheaper fallbacks that usually answer
        // immediately. Without this, an overloaded gemini-3.5-flash on the
        // streaming side blanks the whole reply (Hugo saw this at 01:51 on
        // 2026-06-18 with the 503 + 429 chain in logcat).
        //
        // 2026-06-29 — Frente 429-skip optimisation. Real-world bench on
        // S25 (366 msgs, v187) showed each turn burning 9-10s just
        // retrying `gemini-3.5-flash` 3× with exponential backoff before
        // falling through to flash-lite — even though flash-lite has its
        // OWN free-tier RPM bucket and would have answered on the first
        // try. We now do AT MOST ONE retry per model on 429 (with a
        // short 500ms wait to absorb genuine burst-quota transients);
        // if that retry still hits 429, the model is considered quota-
        // saturated and we jump to the next one immediately. Other
        // error codes (non-429) still hit the original `break` path
        // unchanged.
        // 2026-08-16 — vision-safe cascade: drop 3.7-flash (503 on images)
        // when the request carries an image, same as streamReply. Filter also
        // covers the `model` seed in case a session persisted 3.7-flash.
        val hasImage = request.contents.any { c -> c.parts.any { it.inlineData != null } }
        val models = if (hasImage) {
            listOf(model, "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite")
                .filterNot { it == "gemini-3.7-flash" }
        } else {
            listOf(model, "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite")
        }.distinct()
        for ((index, modelName) in models.withIndex()) {
            val maxAttempts = if (index == 0) 2 else 1
            for (attempt in 1..maxAttempts) {
                try {
                    val resp = GeminiRetrofitClient.service.generateContent(
                        model = modelName, apiKey = apiKey, request = request
                    )
                    val err = resp.error
                    if (err != null) {
                        val code = err.code ?: 0
                        // 429 (quota) deserves ONE retry on the same model
                        // to absorb burst transients, then we jump to the
                        // next model whose bucket is independent.
                        // 503 (UNAVAILABLE) jumps straight to the next model.
                        if (code == 429 || (err.message?.contains("429") == true)) {
                            android.util.Log.w(
                                "CHAT_ORCH",
                                "$modelName attempt $attempt: 429 ${if (attempt < maxAttempts) "→ short retry" else "→ next model"}"
                            )
                            if (attempt < maxAttempts) {
                                kotlinx.coroutines.delay(500L)
                                continue
                            } else break
                        }
                        android.util.Log.w("CHAT_ORCH", "$modelName: error code=$code, falling through")
                        break
                    }
                    val text = resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (!text.isNullOrBlank()) {
                        android.util.Log.d("CHAT_ORCH", "blocking reply via $modelName (attempt $attempt)")
                        return text
                    }
                    break
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    val isRate = e is retrofit2.HttpException && e.code() == 429
                    val isMessageRate = msg.contains("429")
                    android.util.Log.w("CHAT_ORCH", "$modelName attempt $attempt failed: $msg")
                    if (isRate || isMessageRate) {
                        if (attempt < maxAttempts) {
                            kotlinx.coroutines.delay(500L)
                            continue
                        } else break
                    }
                    break
                }
            }
        }
        android.util.Log.e("CHAT_ORCH", "blocking reply: all models exhausted")
        return null
    }

    /**
     * Exponential backoff with small jitter for Gemini 429 retries. The
     * previous linear `1s * attempt` schedule (1s, 2s) was too tight for the
     * 20 RPM free-tier bucket — Hugo saw cascades where 4 fast messages all
     * burned through the 3 retries before the quota window reset. Doubling
     * the base each attempt with a 300ms jitter spaces things out enough
     * that the second attempt usually lands in a fresh window while still
     * keeping the worst-case total under ~6s.
     *
     * @param attempt 1-based attempt counter (so the first retry waits 800ms).
     * @return delay in ms, capped at 8s.
     */
    private fun rateLimitBackoffMs(attempt: Int): Long {
        val base = 800L * (1L shl (attempt - 1).coerceAtLeast(0))
        val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 300)
        return (base + jitter).coerceAtMost(8_000L)
    }

    /**
     * 2026-06-29 — Frente C1.a (rolling-window history). One-shot
     * non-streaming Flash call that compresses the oldest [trimmed]
     * turns into a single 1-2 sentence digest the orchestrator
     * prepends to the next LLM payload. Returns `null` when the API
     * key is missing, the model fails, or the result is empty — in
     * which case the caller silently falls back to truncation only.
     *
     * The prompt is intentionally minimal because we already pay for
     * every byte: just ask for facts, no tone, no preamble. Cap at 2
     * sentences (~30 words) so the digest itself doesn't grow into the
     * cap it's supposed to shrink. Uses `thinkingBudget=0` and
     * `temperature=0.2` for speed + determinism — re-running this on
     * the same trim set with the same wording gives essentially the
     * same digest, so cache reuse downstream is meaningful.
     */
    private suspend fun summariseTrimmedTurns(
        trimmed: List<ChatMessage>,
        language: String,
        apiKey: String,
        fallbackModel: String,
    ): String? {
        if (trimmed.isEmpty()) return null
        // Compact textual representation of the trim set: one line per
        // turn, sender prefix, text truncated to 200 chars so a single
        // huge turn doesn't blow the digest request's own budget.
        val transcript = buildString {
            trimmed.forEach { m ->
                val role = if (m.sender == "user") "U" else "A"
                append(role).append(": ")
                append(m.text.take(200))
                if (m.text.length > 200) append("…")
                append('\n')
            }
        }.take(20_000) // hard cap on the digest input itself.

        val systemText = "Summarise the conversation below into 1-2 short factual sentences in language code: " +
            "$language. State concrete facts the user mentioned (foods, goals, complaints) and any commitments " +
            "the assistant made. NO greeting, NO meta-commentary, NO ellipsis, max 40 words total. Output only " +
            "the summary text.\n\nTRANSCRIPT:\n$transcript"

        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = "Output the summary now.")))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemText))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 256,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return try {
            kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                blockingReply(fallbackModel, apiKey, request, language)
            }?.takeIf { it.isNotBlank() }?.trim()
        } catch (t: Throwable) {
            android.util.Log.w("CHAT_ROLL", "digest failed: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            null
        }
    }

    /**
     * 2026-06-29 — Frente Memory C3. Boils ONE turn down to a single
     * factual sentence for the cross-session carryover digest. Called
     * fire-and-forget from [handle]'s background scope after the user
     * has already seen the reply. Returns `null` on failure (the
     * digest just doesn't grow this turn — no UX impact).
     *
     * Style hard-rules in the prompt: 1 short sentence, max 18 words,
     * pt-PT-style telegraph (e.g. "Hugo logou jantar de pizza" /
     * "Madhvi mencionou cansaço pós-treino" / "Almoço 540 kcal, baixo
     * em proteína"). NO greeting, NO opinion, NO followup-suggestion.
     */
    private suspend fun summariseTurnForCarryover(
        userText: String,
        assistantReply: String,
        language: String,
        apiKey: String,
        fallbackModel: String,
    ): String? {
        if (userText.isBlank() && assistantReply.isBlank()) return null
        val systemText = "You summarise ONE chat turn into a single short factual sentence in language code: " +
            "$language. Max 18 words. Telegraph style: state ONE concrete fact the user shared OR ONE concrete " +
            "fact the assistant gave back. NO greeting, NO opinion, NO follow-up suggestion, NO emoji, NO " +
            "imperatives, NO question. Output ONLY the sentence, no preamble."
        val userBlock = "User said: ${userText.take(300)}\nAssistant replied: ${assistantReply.take(500)}"
        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = userBlock)))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemText))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 80,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return try {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                blockingReply(fallbackModel, apiKey, request, language)
            }?.takeIf { it.isNotBlank() }?.trim()
        } catch (t: Throwable) {
            android.util.Log.w("CHAT_CARRYOVER", "summariseTurn failed: ${t.message?.take(120)}")
            null
        }
    }

    /**
     * 2026-06-29 — Frente Memory C3. When the carryover digest exceeds
     * [CarryoverDigest.MAX_CHARS], we ask Flash to compress the OLDEST
     * 60% into a single sentence. This helper does that single call.
     * Returns null on failure — the caller falls back to dropping the
     * oldest chunk entirely.
     */
    private suspend fun compressCarryoverChunk(
        chunk: String,
        language: String,
        apiKey: String,
        fallbackModel: String,
    ): String? {
        if (chunk.isBlank()) return null
        val systemText = "You compress the following stream of factual sentences into a single short summary " +
            "in language code: $language. Max 25 words, telegraph style. Preserve the most-mentioned " +
            "concrete facts (foods, training patterns, complaints). NO greeting, NO opinion, NO emoji. " +
            "Output ONLY the summary.\n\nSTREAM:\n${chunk.take(2000)}"
        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = "Compress now.")))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemText))),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 96,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return try {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                blockingReply(fallbackModel, apiKey, request, language)
            }?.takeIf { it.isNotBlank() }?.trim()
        } catch (t: Throwable) {
            android.util.Log.w("CHAT_CARRYOVER", "compressCarryoverChunk failed: ${t.message?.take(120)}")
            null
        }
    }

    private suspend fun buildCanonicalSnapshot(today: LocalDate, userName: String): String {
        val cached = snapshotCache.value
        // 2026-07-06 (Frente A) — parallelize the two count queries used to
        // build the cache key. They are independent and both hit Room; no
        // reason for `activityCount` to wait on `foodCount`. Same shape as
        // `DataFetcher.fetchForIntent`'s coroutineScope+async fan-out.
        val (foodCount, activityCount) = coroutineScope {
            val foodCountDeferred = async {
                runCatching { foodDao.countSince(0L) }.getOrDefault(0)
            }
            val activityCountDeferred = async {
                runCatching { activityDao.countSince(0L) }.getOrDefault(0)
            }
            foodCountDeferred.await() to activityCountDeferred.await()
        }
        // Include `userName` in the cache key so a settings change to the
        // user's name invalidates the cached snapshot immediately, instead of
        // serving the old text for up to 60s.
        val key = "$today|$foodCount|$activityCount|$userName"
        if (cached != null && cached.key == key &&
            (System.currentTimeMillis() - cached.builtAtMs) < 60_000L) {
            android.util.Log.d("CHAT_CTX", "snapshot cache hit (key=$key)")
            return cached.text
        }
        val zone = ZoneId.systemDefault()
        // 2026-06-26: windowed reads in place of full-table scans. The
        // `ChatContextBuilder` only ever filters foods/activities into
        // today / last7 / month-to-date / last30 buckets — the rows older
        // than ~30 days are read, deserialised, and immediately discarded.
        // For a power-user with 18+ months of logs that meant ~1600 rows
        // crossing the Room→Kotlin boundary on every snapshot rebuild for
        // ~100 rows of useful signal.
        //
        // The window starts at `today - 31d` (one extra day of slack to
        // absorb timezone shifts and the small drift between when the
        // builder computes `today` and when the query runs) and ends at
        // `tomorrowStart` (exclusive upper bound used by every filter
        // inside the builder).
        val windowStartMs = today.minusDays(31).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEndMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 2026-07-06 (Frente A) — six DAO reads used to run in strict
        // sequence. They are all independent (different tables, different
        // WHERE clauses). Fan-out via coroutineScope+async so wall-clock ≈
        // max(individual reads) instead of sum. Room's shared executor pool
        // handles concurrent reads on the same database; `DataFetcher`
        // already relies on the same pattern (see DataFetcher.kt:47-67).
        val buildStartedMs = System.currentTimeMillis()
        data class SnapshotReads(
            val foods: List<com.example.data.local.FoodEntry>,
            val activities: List<com.example.data.local.ActivityEntry>,
            val latestTarget: com.example.data.local.TargetSnapshot?,
            val latestBmr: com.example.data.local.BmrSnapshotEntity?,
            val confirmed: List<UserInsight>,
            val bodyComp: List<com.example.data.local.BodyCompositionSnapshotEntity>,
        )
        val reads = coroutineScope {
            val foodsDeferred = async {
                runCatching { foodDao.getFoodsBetween(windowStartMs, windowEndMs) }.getOrDefault(emptyList())
            }
            val activitiesDeferred = async {
                runCatching { activityDao.getActivitiesBetween(windowStartMs, windowEndMs) }.getOrDefault(emptyList())
            }
            val latestTargetDeferred = async {
                runCatching { targetSnapshotDao.latest() }.getOrNull()
            }
            val latestBmrDeferred = async {
                runCatching { bmrSnapshotDao.latest() }.getOrNull()
            }
            val confirmedDeferred = async {
                runCatching { userInsightDao.topConfirmed(8) }.getOrDefault(emptyList())
            }
            // 2026-06-26: pull the last 10 body-composition readings so the LLM
            // can reason about weight + body fat % + muscle mass trends. 10 is
            // generous enough to cover a few months of weekly weigh-ins without
            // bloating the prompt — each row is ~150 chars rendered.
            val bodyCompDeferred = async {
                runCatching { bodyCompositionSnapshotDao.recent(10) }.getOrDefault(emptyList())
            }
            SnapshotReads(
                foods = foodsDeferred.await(),
                activities = activitiesDeferred.await(),
                latestTarget = latestTargetDeferred.await(),
                latestBmr = latestBmrDeferred.await(),
                confirmed = confirmedDeferred.await(),
                bodyComp = bodyCompDeferred.await(),
            )
        }
        val readsMs = System.currentTimeMillis() - buildStartedMs
        val text = ChatContextBuilder.build(
            today = today,
            zone = zone,
            foods = reads.foods,
            activities = reads.activities,
            latestTarget = reads.latestTarget,
            latestBmr = reads.latestBmr,
            stepsByDate = emptyMap(),
            caloriesBurnedByDate = emptyMap(),
            confirmedInsights = reads.confirmed,
            userName = userName,
            bodyCompositionHistory = reads.bodyComp
        )
        val buildMs = System.currentTimeMillis() - buildStartedMs
        snapshotCache.value = SnapshotCache(
            key = key,
            text = text,
            builtAtMs = System.currentTimeMillis(),
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            foods = reads.foods,
            activities = reads.activities,
        )
        // 2026-07-06 (Frente A) — expose reads-only and total build ms so a
        // regression on the parallel fan-out is visible in logs. `readsMs`
        // ≈ max of the 6 async reads; `buildMs` adds ChatContextBuilder's
        // pure string work. Together they replace the pre-Frente-A single
        // "rebuilt" line as the primary latency signal for the snapshot.
        android.util.Log.d(
            "CHAT_CTX",
            "snapshot rebuilt (key=$key, len=${text.length}, readsMs=$readsMs, buildMs=$buildMs)"
        )
        return text
    }

    /** Force the next [handle] call to rebuild the snapshot. Call from VM
     *  after any food/activity/insight/habit write. */
    fun invalidateSnapshotCache() {
        snapshotCache.value = null
    }

    /**
     * 2026-07-06 (Frente I) — build the same fingerprint string the
     * [com.example.data.notifications.PredictiveQaWorker] uses, so a
     * cache hit only serves when the app state hasn't drifted since
     * synthesis. If any input changes (new meal logged, target updated,
     * new day) the fingerprint changes and the cache miss forces a
     * fresh answer through the normal path.
     */
    private suspend fun computePredictedQaFingerprint(today: java.time.LocalDate): String {
        val foodCount = runCatching { foodDao.countSince(0L) }.getOrDefault(0)
        val activityCount = runCatching { activityDao.countSince(0L) }.getOrDefault(0)
        val latestTarget = runCatching { targetSnapshotDao.latest() }.getOrNull()
        val latestBmr = runCatching { bmrSnapshotDao.latest() }.getOrNull()
        return "$foodCount|$activityCount|" +
            "${latestTarget?.effectiveDateEpochDay ?: 0}|" +
            "${latestBmr?.effectiveDateEpochDay ?: 0}|$today"
    }

    private suspend fun buildKnownHabitsBlock(): String {
        val habits = runCatching { foodHabitDao.getActiveHabitsSnapshot() }.getOrDefault(emptyList())
        if (habits.isEmpty()) return ""
        return buildString {
            append("\n\nAlready memorized habits (do NOT re-propose unchanged):\n")
            habits.forEach { h ->
                append("- id=").append(h.id)
                append(", trigger=\"").append(h.trigger).append("\"")
                append(", descriptor=\"").append(h.descriptor).append("\"")
                if (h.mealCategoryHint != null) append(", category=").append(h.mealCategoryHint.name)
                append('\n')
            }
        }
    }

    /** Resolved chat-attached image, ready to drop into a Gemini `InlineData`
     *  part. Carries the **actual** MIME type derived from the on-disk file
     *  extension — fixes the legacy bug where every attachment was declared
     *  as `image/jpeg` even when the user's gallery pick was HEIC/PNG/WebP. */
    private data class ResolvedImage(val base64: String, val mimeType: String)

    /** Decodes the per-message stored attachment payload (single path, JSON
     *  array of paths, or legacy raw base64) into a list of base64+MIME pairs
     *  ready for the Gemini request. Logs verbosely under tag `CHAT_IMG` so a
     *  silent file-read failure is visible in logcat — the previous
     *  `runCatching { … }.getOrNull()` swallowed every IOException and let
     *  the request go out image-less without a breadcrumb. */
    private fun resolveImagePayload(stored: String): List<ResolvedImage> {
        android.util.Log.d(
            "CHAT_IMG",
            "resolve: stored.len=${stored.length} head=${stored.take(100)}"
        )
        val parts = com.example.ui.ChatImageStore.decode(stored)
        android.util.Log.d("CHAT_IMG", "resolve: parts.size=${parts.size}")
        if (parts.isEmpty()) return emptyList()
        return parts.mapIndexedNotNull { i, entry ->
            val isP = com.example.ui.ChatImageStore.isPath(entry)
            android.util.Log.d(
                "CHAT_IMG",
                "  [$i] isPath=$isP entry.head=${entry.take(80)}"
            )
            if (isP) {
                val uri = android.net.Uri.parse(entry)
                val rawPath = uri.path
                if (rawPath == null) {
                    android.util.Log.w(
                        "CHAT_IMG",
                        "  [$i] uri.path was null scheme=${uri.scheme} entry=$entry"
                    )
                    return@mapIndexedNotNull null
                }
                // Legacy `content://com.example.fileprovider/chat_images/<x>`
                // rows give us `/chat_images/<x>` here, which doesn't exist as
                // an absolute path. The bytes ARE on disk though — under
                // `/data/data/<pkg>/files/<rawPath>`. Reconstruct that path so
                // historical messages keep working even after the
                // 2026-06-22 fix that started persisting `file://` URIs.
                val path = if (java.io.File(rawPath).exists()) {
                    rawPath
                } else {
                    val recovered = "/data/data/${com.example.BuildConfig.APPLICATION_ID}/files${rawPath}"
                    if (java.io.File(recovered).exists()) {
                        android.util.Log.d(
                            "CHAT_IMG",
                            "  [$i] recovered legacy content:// path → $recovered"
                        )
                        recovered
                    } else {
                        rawPath
                    }
                }
                val file = java.io.File(path)
                val exists = file.exists()
                val canRead = file.canRead()
                val len = if (exists) file.length() else -1L
                android.util.Log.d(
                    "CHAT_IMG",
                    "  [$i] file path=$path exists=$exists canRead=$canRead size=$len"
                )
                if (!exists || !canRead) {
                    android.util.Log.w(
                        "CHAT_IMG",
                        "  [$i] file missing/unreadable — skipping"
                    )
                    return@mapIndexedNotNull null
                }
                val bytes = try {
                    file.readBytes()
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "CHAT_IMG",
                        "  [$i] readBytes failed: ${t.javaClass.simpleName}: ${t.message}",
                        t
                    )
                    return@mapIndexedNotNull null
                }
                val mime = mimeFromPath(path)
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                android.util.Log.d(
                    "CHAT_IMG",
                    "  [$i] OK bytes=${bytes.size} b64.len=${b64.length} mime=$mime"
                )
                ResolvedImage(b64, mime)
            } else {
                // Legacy raw base64 row from before the file-path migration.
                // No MIME information is recoverable; JPEG was the previous
                // default so preserve that assumption.
                android.util.Log.d(
                    "CHAT_IMG",
                    "  [$i] legacy raw-base64 path, len=${entry.length}"
                )
                ResolvedImage(entry, "image/jpeg")
            }
        }
    }

    /** Derive Gemini's `inlineData.mimeType` from the on-disk file extension.
     *  `ChatImageStore.persistFromUri` already preserves the original
     *  extension (`.heic`/`.png`/`.webp`/…) so we only have to map it. */
    private fun mimeFromPath(path: String): String {
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private suspend fun runQueryAndFormat(req: QueryDataRequest): String {
        val zone = ZoneId.systemDefault()
        val from = runCatching { LocalDate.parse(req.fromDate) }.getOrNull()
        val to = runCatching { LocalDate.parse(req.toDate) }.getOrNull()
        if (from == null || to == null || from.isAfter(to)) {
            return "ERROR: invalid date range ${req.fromDate}..${req.toDate}"
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        if (days > 90) return "ERROR: window too wide ($days days). Ask the user to narrow to <=90 days."
        val startMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        return when (req.type) {
            "meals" -> {
                val rows = runCatching { foodDao.getFoodsBetween(startMs, endMs) }.getOrDefault(emptyList())
                if (rows.isEmpty()) "No meals logged between ${req.fromDate} and ${req.toDate}."
                else buildString {
                    append("Meals between ${req.fromDate} and ${req.toDate} (${rows.size} entries):\n")
                    rows.sortedBy { it.timestamp }.forEach { f ->
                        val date = java.time.Instant.ofEpochMilli(f.timestamp).atZone(zone).toLocalDate()
                        append("- ").append(date).append(" · ").append(f.name)
                            .append(" · ").append(f.calories).append("kcal")
                            .append(" · P").append(f.protein.toInt())
                            .append("/C").append(f.carbs.toInt())
                            .append("/F").append(f.fat.toInt())
                            .append(" · ").append(f.mealCategory.name.lowercase())
                            .append('\n')
                    }
                    val totalKcal = rows.sumOf { it.calories }
                    append("\nTotals: $totalKcal kcal across $days days (avg ${totalKcal / days} kcal/day).")
                }
            }
            "activity" -> {
                val rows = runCatching { activityDao.getActivitiesBetween(startMs, endMs) }.getOrDefault(emptyList())
                if (rows.isEmpty()) "No activity logged between ${req.fromDate} and ${req.toDate}."
                else buildString {
                    append("Activities between ${req.fromDate} and ${req.toDate} (${rows.size} entries):\n")
                    rows.sortedBy { it.timestamp }.forEach { a ->
                        val date = java.time.Instant.ofEpochMilli(a.timestamp).atZone(zone).toLocalDate()
                        append("- ").append(date).append(" · ").append(a.name)
                            .append(" · ").append(a.durationMinutes).append("min")
                            .append(" · ").append(a.caloriesBurned).append("kcal")
                            .append(" · ").append(a.activityType.name.lowercase())
                            .append('\n')
                    }
                    val totalMin = rows.sumOf { it.durationMinutes }
                    val totalKcal = rows.sumOf { it.caloriesBurned }
                    append("\nTotals: $totalMin min · $totalKcal kcal burned across $days days.")
                }
            }
            else -> "ERROR: unknown query type '${req.type}'."
        }
    }

    companion object {
        /** 2026-07-06 (Frente E) — Time-To-First-Token watchdog. If a
         *  given model doesn't emit any `StreamChunk.Partial` within
         *  this window, the stream is cancelled and the fleet loop
         *  moves to the next model. OkHttp's own `readTimeout(300s)`
         *  (`GeminiStreamingClient.kt`) is per-socket-read so a stall
         *  before ANY read completes still blocks up to 300 s under
         *  the old regime.
         *
         *  8 s covers cold-start on flaky networks; happy-path Flash
         *  answers begin streaming inside 500 ms. If a real region has
         *  a legitimately slow route, bump this value here — the fleet
         *  loop will keep trying the alternate models regardless. */
        internal const val TTFT_TIMEOUT_MS: Long = 8_000L

        /** 2026-07-09 — once a stream has produced its first token, keep
         *  waiting only while chunks continue landing within this gap.
         *  If the model goes silent for longer, cancel this attempt and
         *  move to the next fallback instead of waiting for the 90 s outer
         *  wall-clock or OkHttp's 300 s socket read timeout. */
        internal const val STREAM_IDLE_TIMEOUT_MS: Long = 15_000L

        /** Poll cadence for the TTFT / post-first-token idle watchdog.
         *  Small enough to react quickly, large enough to avoid busy-looping. */
        internal const val STREAM_WATCHDOG_TICK_MS: Long = 250L

        /** 2026-07-06 (Frente H v2) — grace period the two-tier gate
         *  gives the deep tier to produce its first token before
         *  falling back to the fast tier. If deep emits a Partial
         *  within this window, the fast reply is cancelled silently
         *  and never shown — no "text swap under the user's eyes"
         *  regression from v1. */
        internal const val FAST_GRACE_MS: Long = 3_000L
    }
}

/** Stream of UI events the ViewModel collects from [ChatOrchestrator.handle]. */
sealed class HandleEvent {
    /** Human-readable phase label for the typing bubble. */
    data class Phase(val text: String) : HandleEvent()

    /** Streaming partial — the cumulative text so far. */
    data class Partial(val accumulatedText: String) : HandleEvent()

    /** Stream finished, [cleanedText] is the proposal-stripped final answer.
     *  [insertedMessageId] is the id of the assistant row committed to Room
     *  for this turn, when the emitter actually inserted one. `null` for the
     *  proactive-turn path (persists via a separate flow) and for any future
     *  emit sites that don't own a Room insert. Voice Mode auto-speak reads
     *  this id to know exactly which bubble is "new" — do NOT infer it from
     *  the message-list tail (that race caused the previous-bubble bug). */
    data class Done(
        val cleanedText: String,
        val insertedMessageId: Long? = null,
    ) : HandleEvent()

    data class Error(val cause: Throwable) : HandleEvent()

    /**
     * 2026-07-06 (Frente H) — instant short answer emitted BEFORE the
     * main deep-tier stream begins. Only fires for [ChatIntent.Reasoning]
     * where the second Gemini call actually needs time to think; pure
     * `DataQuery` intents skip the fast tier because their deep response
     * is already fast (synth over pre-fetched data).
     *
     * UX contract: the ViewModel shows [text] in the streaming bubble
     * immediately. When the first deep [Partial] lands the bubble
     * SILENTLY replaces the fast text with the deep partial — same
     * position, same styling, no flash. If deep fails entirely, the
     * fast text is what gets persisted to `chat_messages`.
     */
    data class FastReply(val text: String) : HandleEvent()

    /**
     * 2026-06-28 — Frente C (paralelismo text+chart). Emitted by
     * [ChatOrchestrator.handle] AFTER [Done] when the IntentRouter
     * detected a chart-shaped question AND the parallel chart call
     * actually produced a [com.example.data.remote.ChartProposal].
     * Failures (429/timeout/empty) are silenced — no event emitted at
     * all, so the UI never sees a "chart loading" → "no chart" flash.
     *
     * The chart is anchored to the assistant message whose id is
     * [anchorMessageId], so the ViewModel can attach it via the
     * existing `_pendingChartProposals` map keyed by message id.
     */
    data class ChartReady(
        val anchorMessageId: Long,
        val chart: com.example.data.remote.ChartProposal
    ) : HandleEvent()
}
