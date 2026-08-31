package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.export.DriveBackupWorker
import com.example.data.export.GalleryMigrationWorker
import com.example.data.export.DriveExporter
import com.example.data.export.OrphanImageCleanupWorker
import com.example.data.health.DailyActivitySnapshotWorker
import com.example.data.local.ChatDatabase
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.notifications.PendingProactiveNudge
import com.example.data.notifications.ProactiveNudgeWorker
import com.example.data.notifications.PredictiveQaWorker
import com.example.data.remote.GeminiRepository
import com.example.data.voice.SpeechRecognizerController
import com.example.data.voice.TtsController
import com.example.ui.AppTab
import com.example.ui.ChatScreen
import com.example.ui.ChatViewModel
import com.example.ui.ChatViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel

    /** 2026-07-05 (Voice Mode): TTS + SpeechRecognizer controllers live on
     *  the Activity so their Context lifetime tracks the UI. The VM only
     *  sees them through its factory; onDestroy calls [TtsController.shutdown]
     *  so the platform engine can release its audio resources. */
    private var ttsController: TtsController? = null
    private var speechRecognizer: SpeechRecognizerController? = null

    /** Bloco I: runtime POST_NOTIFICATIONS launcher for API 33+. Result is
     *  intentionally ignored — the ProactiveNudgeWorker re-checks the
     *  permission each cycle and silently skips delivery if the user
     *  denied it. Asking once on cold start is enough; we never nag. */
    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /** Build 1.5 (2026-06-27): ACCESS_COARSE_LOCATION launcher for the
     *  chat's IPMA weather context. Result is intentionally ignored —
     *  when the user denies, the chat's `[CONTEXTO LOCAL]` block
     *  silently omits the weather line and recommendations fall back
     *  to non-weather-aware variants. */
    private val locationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /** 2026-07-05 (Voice Mode): RECORD_AUDIO launcher for the mic pill in
     *  the chat input row. The result is forwarded to the VM so it can
     *  either start listening (granted) or clear its "permission required"
     *  latch (denied). Registered here — not inside the VM — because
     *  ActivityResultLauncher registration must happen before Activity
     *  onStart per androidx contract. */
    private val recordAudioPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::viewModel.isInitialized) viewModel.onMicPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize local persistent database
        val database = ChatDatabase.getInstance(applicationContext)
        val chatDao = database.chatDao
        val foodDao = database.foodDao
        val activityDao = database.activityDao
        val foodHabitDao = database.foodHabitDao
        val targetSnapshotDao = database.targetSnapshotDao
        val bmrSnapshotDao = database.bmrSnapshotDao
        val bodyCompositionSnapshotDao = database.bodyCompositionSnapshotDao
        val dailyActivitySnapshotDao = database.dailyActivitySnapshotDao
        val userInsightDao = database.userInsightDao
        val predictedQaDao = database.predictedQaDao
        val ingredientNutritionDao = database.ingredientNutritionDao

        // 2. Initialize network & caching repository
        val repository = GeminiRepository(chatDao, foodDao, activityDao, foodHabitDao, targetSnapshotDao, bmrSnapshotDao, bodyCompositionSnapshotDao, dailyActivitySnapshotDao, userInsightDao, predictedQaDao)
        val nutritionLookupService = com.example.data.nutrition.NutritionLookupService(
            context = applicationContext,
            dao = ingredientNutritionDao,
            clusterBuilder = com.example.data.nutrition.IngredientClusterBuilder(
                foodDao = foodDao,
                habitDao = foodHabitDao,
            ),
        )
        val shRepository = com.example.data.health.SamsungHealthRepository(applicationContext)
        // Build 1.5 (2026-06-27): location + IPMA weather are injected
        // into the chat's system prompt as [CONTEXTO LOCAL]. Both are
        // permission-/network-tolerant: the chat keeps working when
        // either is unavailable. Construction is cheap (no IO until the
        // first call), so we wire them once at app startup.
        val locationHelper = com.example.data.location.LocationHelper(applicationContext)
        val weatherFetcher = com.example.data.weather.IpmaWeatherFetcher(applicationContext)

        // 3. Construct state owner ViewModel through its factory
        //    Voice Mode controllers are created here so their lifetime is
        //    tied to the Activity (see shutdown in [onDestroy]).
        ttsController = TtsController(applicationContext)
        speechRecognizer = SpeechRecognizerController(applicationContext)
        val factory = ChatViewModelFactory(
            repository,
            shRepository,
            locationHelper,
            weatherFetcher,
            ttsController,
            speechRecognizer,
            nutritionLookupService,
        )
        viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        // 3b. Observe the VM's "mic permission required" flag. When the
        //     user taps the mic pill without RECORD_AUDIO granted, the VM
        //     flips this to true and we launch the runtime dialog once.
        lifecycleScope.launch {
            viewModel.micPermissionRequired.collect { required ->
                if (required) {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        setContent {
            MyApplicationTheme {
                ChatScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 4. Schedule the daily Drive backup. KEEP policy means we don't reset the
        //    schedule on every app open — the periodic job, once enqueued, repeats
        //    on its own cadence even when the app is closed. The catch-up one-shot
        //    immediately below handles the case where the periodic slot was missed
        //    (Doze, no network, app uninstalled & reinstalled, etc.) and ensures
        //    that opening the app on a new calendar day always lands a fresh
        //    diario_nutricao.xlsx in Drive without the user lifting a finger.
        scheduleDriveBackups()

        // 5. Bloco I: schedule the hourly proactive-nudge worker. KEEP policy so
        //    re-opening the app doesn't reset the cadence. The worker itself
        //    honours master/quiet/per-trigger toggles read from NotificationPrefs.
        scheduleProactiveNudges()

        // 2026-07-06 (Frente I) — pre-compute answers to questions the
        // user is likely to ask, so the first bubble is instant.
        // PeriodicWorkRequest's minimum interval is 15 min; we pick
        // 30 min as a balance between freshness and quota.
        schedulePredictiveQaPrefetch()

        // 5b. Weekly sweep of orphaned image files in filesDir/food_images/ and
        //    filesDir/chat_images/. Without this, deleted meals and aborted
        //    chat-image attachments accumulate forever — months of testing can
        //    leave hundreds of MB stranded on disk. The worker's 30-day age
        //    floor makes it safe against in-flight flows.
        scheduleOrphanImageCleanup()

        // 5c. Daily snapshot of "active calories" for D-1 (yesterday). Freezes
        //    the SH `TOTAL_ACTIVE_CALORIES_BURNED` aggregate at ~04:00 so
        //    historical reports stay stable even if Samsung Health back-fills
        //    or edits workout records later. See [DailyActivitySnapshotWorker]
        //    for the rationale on the 04:00 choice.
        scheduleDailyActivitySnapshot()

        // 6. Bloco I: ask for POST_NOTIFICATIONS on API 33+ if we don't have it
        //    yet. The launcher is registered as a class-level property above so
        //    it survives configuration changes. Older APIs grant the permission
        //    at install time so there's nothing to do.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 6.5. Build 1.5 (2026-06-27): ask for COARSE location once on cold
        //      start so the chat's [CONTEXTO LOCAL] block can include the
        //      IPMA weather forecast. We never nag — denying simply omits
        //      the weather line on every subsequent turn. Wrapped in a
        //      runCatching so any GMS/Play-services oddity (rare on stock
        //      Pixels, more common on rooted ROMs) never crashes startup.
        runCatching {
            val locGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!locGranted) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        // 7. Bloco I: cold-start path for a notification tap — the system
        //    delivers the launching intent via getIntent() on first onCreate.
        //    onNewIntent handles the warm-start path (singleTask reuse).
        handleNotificationIntent(intent)
    }

    private fun scheduleDriveBackups() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayUntilNext0030Ms(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DriveBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        if (DriveExporter.shouldRunDailyCatchUp(applicationContext)) {
            val catchUp = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(applicationContext).enqueue(catchUp)
        }

        val migrationPrefs = getSharedPreferences(
            GalleryMigrationWorker.PREFS_NAME, Context.MODE_PRIVATE
        )
        if (!migrationPrefs.getBoolean(GalleryMigrationWorker.KEY_DONE, false)) {
            WorkManager.getInstance(applicationContext)
                .enqueue(OneTimeWorkRequestBuilder<GalleryMigrationWorker>().build())
        }
    }

    /** Bloco I: enqueue the proactive-nudge worker. Periodic 60 min with a
     *  15 min flex window (matches the design's "uma notificação por ciclo"
     *  cadence). Initial delay aligns the first run with the top-of-next-hour
     *  minus 5 minutes so meal nudges hit at ~09:55/14:55/21:55 — humans
     *  expect "missed breakfast" pings just before vs just after the hour. */
    private fun scheduleProactiveNudges() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ProactiveNudgeWorker>(
            60, TimeUnit.MINUTES, 15, TimeUnit.MINUTES
        )
            .setInitialDelay(initialDelayUntilNextHourMinusFiveMs(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            ProactiveNudgeWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** 2026-07-06 (Frente I) — schedule the predictive Q&A prefetch
     *  worker to run every 30 min. It bails internally when the user
     *  has been inactive >24 h or the API key isn't wired, so a paused
     *  app doesn't burn quota. Network constraint so we don't try to
     *  synth answers on airplane mode. KEEP policy so re-opening the
     *  app doesn't reset the cadence. */
    private fun schedulePredictiveQaPrefetch() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<PredictiveQaWorker>(
            30, TimeUnit.MINUTES, 5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "predictive_qa_prefetch",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Weekly orphan-image sweep. No network constraint (purely local FS
     *  work). Initial delay is randomised across 6–18 hours so two Hugo-
     *  registered devices opening the app at the same moment don't both
     *  hammer disk in the same minute — not a real problem at this fleet
     *  size, but cheap to spread. KEEP policy: reinstall preserves cadence
     *  to avoid re-running the sweep right after every cold start. */
    private fun scheduleOrphanImageCleanup() {
        val request = PeriodicWorkRequestBuilder<OrphanImageCleanupWorker>(
            7, TimeUnit.DAYS
        )
            .setInitialDelay(
                java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(6 * 60, 18 * 60),
                TimeUnit.MINUTES
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            OrphanImageCleanupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Daily 04:07-ish snapshot of yesterday's active-calories total. KEEP policy
     *  so reinstall doesn't immediately re-run a fresh window. Initial delay
     *  computed to the next 04:07 local — moved from 02:03 on 2026-06-27 because
     *  Samsung Health was observed to settle the day's `TOTAL_ACTIVE_CALORIES_BURNED`
     *  aggregate after 02:00, sometimes capturing partial values. 04:07 still falls
     *  inside the typical sleep window and `:07` off the hour avoids the same
     *  fleet-scale clustering problem `:00` and `:30` have. */
    private fun scheduleDailyActivitySnapshot() {
        val request = PeriodicWorkRequestBuilder<DailyActivitySnapshotWorker>(
            1, TimeUnit.DAYS
        )
            .setInitialDelay(initialDelayUntilNext0407Ms(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DailyActivitySnapshotWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** ms until the next 04:07 local time. */
    private fun initialDelayUntilNext0407Ms(): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val target = now.toLocalDate().atTime(LocalTime.of(4, 7))
        val next = if (now.isBefore(target)) target else target.plusDays(1)
        return java.time.Duration.between(now, next).toMillis().coerceAtLeast(60_000L)
    }

    /** ms until the next 00:30 local time. Picked off-the-hour intentionally
     *  (matches the off-:00 cron guidance in the project — a few thousand devices
     *  scheduling at 00:00 sharp would all hit Drive simultaneously). */
    private fun initialDelayUntilNext0030Ms(): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val target = now.toLocalDate().atTime(LocalTime.of(0, 30))
        val next = if (now.isBefore(target)) target else target.plusDays(1)
        return java.time.Duration.between(now, next).toMillis().coerceAtLeast(60_000L)
    }

    /** ms until five minutes before the next top-of-hour. Used to pace the
     *  proactive worker so its first run lands on a human-readable slot (e.g.
     *  09:55) rather than whatever wall-clock minute the user happened to
     *  open the app. Floor of 60_000 ms guarantees we never enqueue a job
     *  with a near-zero initial delay (which WorkManager would dispatch
     *  immediately and waste the slot). */
    private fun initialDelayUntilNextHourMinusFiveMs(): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val nextHour = now.toLocalDate().atTime(now.hour, 0).plusHours(1)
        val target = nextHour.minusMinutes(5)
        val anchor = if (now.isBefore(target)) target else nextHour.plusMinutes(55)
        return java.time.Duration.between(now, anchor).toMillis().coerceAtLeast(60_000L)
    }

    /**
     * Bloco I: singleTask reuse path. When the user taps a proactive
     * notification while the activity is already running, Android calls
     * onNewIntent with the launching intent — we forward to
     * [handleNotificationIntent] which drives the ViewModel + tab switch.
     * setIntent so subsequent getIntent() calls observe the latest tap.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Bloco I: shared cold-start + warm-start handler. Reads the two extras
     * that [ProactiveNudgeWorker.postNotification] writes onto the tap intent
     * and (a) selects the right session, (b) parks an [AppTab.CHAT] switch on
     * the ViewModel — the root composable in ChatScreen.kt consumes that flow
     * via animateScrollToPage. Safe to call with a null/empty intent (e.g.
     * stock LAUNCHER tap) — falls through with a no-op.
     *
     * 2026-06-26 redesign: when the intent carries
     * [PendingProactiveNudge.EXTRA_PENDING_NUDGE_JSON] (the new path), we
     * materialize the chat session + assistant message on-the-fly here
     * rather than reading a pre-created sessionId. Legacy fallback for
     * [ProactiveNudgeWorker.EXTRA_OPEN_SESSION_ID] is preserved so any
     * stale notification queued by an older build still opens correctly.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return
        val switchToChat = intent.getBooleanExtra(ProactiveNudgeWorker.EXTRA_SWITCH_TO_CHAT, false)

        val pendingJson = intent.getStringExtra(PendingProactiveNudge.EXTRA_PENDING_NUDGE_JSON)
        if (pendingJson != null) {
            // Consume the payload so a configuration change (rotation) that
            // re-delivers the intent doesn't re-materialize the same nudge.
            intent.removeExtra(PendingProactiveNudge.EXTRA_PENDING_NUDGE_JSON)
            val pending = PendingProactiveNudge.fromJson(pendingJson) ?: return
            materializeAndOpen(pending, switchToChat)
            return
        }

        val sessionId = intent.getLongExtra(ProactiveNudgeWorker.EXTRA_OPEN_SESSION_ID, -1L)
        if (sessionId > 0L && ::viewModel.isInitialized) {
            viewModel.selectSession(sessionId)
            if (switchToChat) viewModel.setPendingTabSwitch(AppTab.CHAT)
        }
    }

    /**
     * Inserts the chat session + assistant message from [pending] in a
     * single Room transaction, then drives the ViewModel to focus the new
     * session. Re-uses an existing session when [PendingProactiveNudge.proactiveTag]
     * matches one already in the DB — handles the "shared meals:<date> /
     * exercise:<iso-week>" thread reuse that the worker used to do
     * pre-redesign. Single-shot triggers (null tag) always create a new
     * session.
     *
     * Idempotency: if the user double-taps the same notification fast
     * enough that the intent is delivered twice before the first
     * materialization commits, we may insert two messages into the same
     * session. Acceptable: messages are append-only and the duplicate
     * shows up as two identical bubbles, not a duplicate session.
     */
    private fun materializeAndOpen(pending: PendingProactiveNudge, switchToChat: Boolean) {
        if (!::viewModel.isInitialized) return
        val db = ChatDatabase.getInstance(applicationContext)
        lifecycleScope.launch {
            val sessionId: Long = run {
                val tag = pending.proactiveTag
                val existing = if (tag != null) db.chatDao.findSessionByProactiveTag(tag) else null
                existing?.id ?: db.chatDao.insertSession(
                    ChatSession(
                        title = pending.sessionTitle,
                        createdAt = pending.deliveredAtMs,
                        systemInstruction = pending.systemInstruction,
                        sessionType = "proactive",
                        lastMessageAt = pending.deliveredAtMs,
                        proactiveTag = tag,
                    )
                )
            }
            db.chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "gemini",
                    text = pending.chatBubbleText,
                    timestamp = pending.deliveredAtMs,
                )
            )
            db.chatDao.touchSession(sessionId, pending.deliveredAtMs)

            viewModel.selectSession(sessionId)
            if (switchToChat) viewModel.setPendingTabSwitch(AppTab.CHAT)
        }
    }

    /**
     * Whenever the app returns to the foreground, refresh Samsung Health status and trigger a
     * (debounced) sync. The 5-minute debounce inside `syncFromSamsungHealth(force = false)`
     * prevents over-fetching when the user navigates rapidly. This catches the common case
     * where the user goes for a walk/run with the Galaxy Watch on, then opens the Reports tab
     * expecting to see the steps/active minutes/calories already there.
     */
    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.refreshSamsungHealthStatus()
            viewModel.syncFromSamsungHealth(force = false)
            viewModel.retryPendingMealAnalyses()
            // Opportunistic D-1 snapshot capture / refresh. Covers two cases the
            // periodic 04:07 worker alone can't reach:
            //   1. First app open of the day before 04:07.
            //   2. SH retroactively edited yesterday's workouts since the snapshot
            //      was frozen (Pixel 10 behaviour) — the inline helper re-syncs
            //      when drift > 10%. See [maybeCaptureSnapshotForYesterdayInline].
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    android.util.Log.i("MainActivity", "onResume: triggering inline D-1 snapshot")
                    com.example.data.health.maybeCaptureSnapshotForYesterdayInline(
                        applicationContext,
                        com.example.data.health.SamsungHealthRepository(applicationContext)
                    )
                    android.util.Log.i("MainActivity", "onResume: inline D-1 snapshot returned")
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "inline D-1 snapshot failed: ${e.message}", e)
                }
            }
        }
    }

    override fun onDestroy() {
        // 2026-07-05 (Voice Mode): free the platform TTS + SpeechRecognizer.
        // Both wrappers are null-safe on shutdown so partial init sequences
        // (e.g. an early crash during onCreate) never trip a second crash
        // here. Running last so any in-flight TTS/STT gets a clean stop
        // before the Activity teardown continues.
        try {
            speechRecognizer?.cancel()
            ttsController?.shutdown()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "voice shutdown error", t)
        }
        super.onDestroy()
    }
}
