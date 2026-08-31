package com.example.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.health.SamsungHealthRepository
import com.example.data.health.ShDailyActivity
import com.example.data.local.ChatDatabase
import com.example.data.local.MealCategory
import com.example.ui.tr
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Bloco G of the proactive-notification plan
 * (`/Users/i548178/.claude/plans/estou-a-pensar-em-twinkly-key.md`).
 *
 * Periodic worker scheduled hourly by [com.example.MainActivity.scheduleProactiveNudges]
 * (Bloco I). On each tick:
 *  1. Honours user prefs ([NotificationPrefs]): master switch + quiet hours.
 *  2. Bails on API 33+ if `POST_NOTIFICATIONS` was denied — never crashes the
 *     worker process for a missing permission.
 *  3. Asks [TriggerEvaluator] which (if any) of the four proactive nudges
 *     should fire right now — meal-missing > activity-spike > macros-at-risk
 *     > daily-summary. May be bypassed by [INPUT_FORCE_TRIGGER_NOW] for the
 *     "test notification" button in the settings screen (Bloco H).
 *  4. Calls [ProactivePromptBuilder.buildAndCall] for the prose body.
 *  5. Renders the progress-bar bitmap to `filesDir/proactive_nudges/` and
 *     packs the would-be session + message into a [PendingProactiveNudge]
 *     payload. **Nothing is written to the chat DB at this point** —
 *     the session is only materialized when the user taps the
 *     notification (see [MainActivity.handleNotificationIntent]). This is
 *     the 2026-06-26 redesign that stopped the proactive worker from
 *     polluting the chat list with un-tapped nudges.
 *  6. Posts the OS notification with the payload encoded as JSON in
 *     [PendingProactiveNudge.EXTRA_PENDING_NUDGE_JSON] on the tap intent;
 *     the glue in Bloco I focuses the chat tab and inserts the session +
 *     message in a single Room transaction.
 *  7. Marks the trigger key in [NotificationDedupStore] so the same key
 *     can't fire again until its window rolls over.
 *
 * Retries the LLM up to [MAX_RETRIES] when it returns `null` (network /
 * model hiccup) — never marks dedup unless we actually delivered a
 * notification. Wraps Samsung Health reads in `runCatching` so a missing
 * SH permission just skips ActivitySpike instead of failing the cycle.
 */
class ProactiveNudgeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "ProactiveNudge"
        const val UNIQUE_NAME = "proactive_nudge_hourly"
        const val CHANNEL_ID = "proactive_nudges"
        const val EXTRA_OPEN_SESSION_ID = "openSessionId"
        const val EXTRA_SWITCH_TO_CHAT = "switchToChatTab"
        const val INPUT_FORCE_TRIGGER_NOW = "forceTriggerNow"
        const val NOTIFICATION_ID_BASE = 70_000
        const val MAX_RETRIES = 2
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = NotificationPrefs.load(ctx)
        if (!prefs.masterEnabled) {
            Log.d(TAG, "master switch off — skip")
            return Result.success()
        }

        val forceTriggerNow = inputData.getBoolean(INPUT_FORCE_TRIGGER_NOW, false)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)

        if (!forceTriggerNow && prefs.isQuiet(now.hour)) {
            Log.d(TAG, "quiet hour ${now.hour} — skip")
            return Result.success()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.d(TAG, "POST_NOTIFICATIONS not granted — skip")
                return Result.success()
            }
        }

        NotificationDedupStore.gc(ctx)

        // Read user profile (name + language) directly from the SharedPreferences
        // used by ChatViewModel.loadUserProfile — the worker has no Compose
        // context so we can't call the @Composable helper. Defaults match the
        // app default ("Humano" / "pt-PT").
        val userPrefs = ctx.getSharedPreferences(
            "user_profile_pref",
            Context.MODE_PRIVATE
        )
        val userName = userPrefs.getString("profile_name", "Humano") ?: "Humano"
        val language = userPrefs.getString("profile_language", "pt-PT") ?: "pt-PT"

        // Samsung Health snapshot for today + yesterday. runCatching so a
        // missing SH permission just yields empty maps and ActivitySpike
        // silently skips — never crashes the worker.
        val today = now.toLocalDate()
        val shStart = today.minusDays(1).atStartOfDay(zone).toInstant()
        val shEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        val shRepo = SamsungHealthRepository(ctx)
        val shStepsByDate: Map<LocalDate, Long> = runCatching {
            shRepo.readDailySteps(shStart, shEnd)
        }.getOrElse {
            Log.w(TAG, "readDailySteps failed: ${it.javaClass.simpleName}: ${it.message}")
            emptyMap()
        }
        val shDailyActivity: Map<LocalDate, ShDailyActivity> = runCatching {
            shRepo.readDailyActivitySummary(shStart, shEnd)
        }.getOrElse {
            Log.w(TAG, "readDailyActivitySummary failed: ${it.javaClass.simpleName}: ${it.message}")
            emptyMap()
        }

        val db = ChatDatabase.getInstance(ctx)

        val trigger: Trigger? = if (forceTriggerNow) {
            buildForcedTrigger(prefs, today)
        } else {
            TriggerEvaluator.evaluate(
                nowZdt = now,
                prefs = prefs,
                dedup = { NotificationDedupStore.wasSent(ctx, it) },
                foodDao = db.foodDao,
                targetSnapshotDao = db.targetSnapshotDao,
                shStepsByDate = shStepsByDate,
                shDailyActivity = shDailyActivity,
            )
        }

        if (trigger == null) {
            Log.d(TAG, "no trigger fired")
            return Result.success()
        }

        Log.i(TAG, "trigger fired: ${trigger::class.simpleName} dedupKey=${trigger.dedupKey}")

        // ActivitySpike skips the LLM entirely (Hugo 2026-06-23): the title
        // "Atividade excelente!" + the three rendered progress bars already
        // tell the whole story. Adding prose ("+120 kcal acima de ontem.")
        // duplicates what the bars show and reads as noise. For every other
        // trigger we still ask Gemini for the body text.
        val text: String? = if (trigger is Trigger.ActivitySpike) {
            null
        } else {
            val generated = ProactivePromptBuilder.buildAndCall(
                ctx = ctx,
                db = db,
                trigger = trigger,
                language = language,
                userName = userName,
                shStepsByDate = shStepsByDate,
                shDailyActivity = shDailyActivity,
            )
            if (generated.isNullOrBlank()) {
                return if (runAttemptCount < MAX_RETRIES) {
                    Log.w(TAG, "LLM returned null — retry (attempt=$runAttemptCount)")
                    Result.retry()
                } else {
                    Log.w(TAG, "LLM returned null — giving up (attempts exhausted)")
                    Result.success()
                }
            }
            generated
        }

        // ─ Compose final body: deterministic visual header + LLM prose. ────
        // The header is computed in Kotlin from the same numbers the trigger
        // evaluator already used, so it's guaranteed to match reality even if
        // the model says something different in prose.
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStartMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayFoods = db.foodDao.getFoodsBetween(todayStartMs, tomorrowStartMs)
        val todayKcal = todayFoods.sumOf { it.calories }
        val todayProteinG = todayFoods.sumOf { it.protein }.toInt()
        val todayCarbsG = todayFoods.sumOf { it.carbs }.toInt()
        val todayFatG = todayFoods.sumOf { it.fat }.toInt()
        val latestTarget = db.targetSnapshotDao.latest()
        val visualHeader = ProactivePromptBuilder.buildVisualHeader(
            trigger = trigger,
            language = language,
            bodyKcal = todayKcal,
            targetKcal = latestTarget?.targetKcal ?: 0,
            bodyProteinG = todayProteinG,
            targetProteinG = (latestTarget?.targetProtein ?: 0.0).toInt(),
            stepsToday = shStepsByDate[today] ?: 0L,
            activeKcalToday = (shDailyActivity[today]?.activeCalories ?: 0f).toInt(),
        )
        // Chat bubble keeps the ASCII header — the chat UI cannot render an
        // inline image. Notification uses the rendered bitmap instead and
        // shows only the LLM prose as text body (v3, 2026-06-22).
        // When [text] is null (ActivitySpike, 2026-06-23) the chat bubble is
        // just the header — no trailing prose to append.
        val chatBubbleText = if (text != null) "$visualHeader\n\n$text" else visualHeader
        val notifText = text
        val notifPicture: android.graphics.Bitmap? = runCatching {
            NotificationBitmapRenderer.renderProgressBars(
                rows = buildMetricRows(
                    trigger = trigger,
                    todayKcal = todayKcal,
                    todayProteinG = todayProteinG,
                    todayCarbsG = todayCarbsG,
                    todayFatG = todayFatG,
                    latestTarget = latestTarget,
                    stepsToday = shStepsByDate[today] ?: 0L,
                    activeKcalToday = (shDailyActivity[today]?.activeCalories ?: 0f).toInt(),
                )
            )
        }.getOrElse {
            Log.w(TAG, "bitmap render failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }

        val sessionTitleKey = when (trigger) {
            is Trigger.MealMissing -> "notif_session_title_meals"
            is Trigger.ActivitySpike -> "notif_session_title_exercise"
            is Trigger.MacrosAtRisk -> "notif_session_title_macros"
            is Trigger.DailySummary -> "notif_session_title_summary"
        }
        val sessionTitle = tr(sessionTitleKey, language)
        val systemInstruction = ProactivePromptBuilder.baseSystemInstruction(userName)
        val nowMs = now.toInstant().toEpochMilli()

        // 2026-06-26 redesign: nothing gets written to the DB at delivery time.
        // The session + message are only materialized when the user actually
        // taps the notification — see [MainActivity.handleNotificationIntent].
        // The full payload travels through the PendingIntent as a JSON string
        // (+ a bitmap file path) so a dismissed notification leaves zero
        // residue. The previous behaviour created a ChatSession + ChatMessage
        // synchronously here, which polluted the chat list with un-tapped
        // proactive nudges. See memory dashboard-redesign-2026-06-26 for the
        // full rationale and follow-up.
        val bitmapFilePath = notifPicture?.let {
            PendingProactiveNudge.persistBitmap(ctx, it)
        }
        val pending = PendingProactiveNudge(
            proactiveTag = trigger.sessionTag,
            sessionTitle = sessionTitle,
            systemInstruction = systemInstruction,
            chatBubbleText = chatBubbleText,
            bitmapFilePath = bitmapFilePath,
            deliveredAtMs = nowMs,
        )

        postNotification(
            ctx = ctx,
            pending = pending,
            text = notifText,
            trigger = trigger,
            language = language,
            picture = notifPicture,
        )
        NotificationDedupStore.markSent(ctx, trigger.dedupKey, nowMs)
        Log.i(TAG, "delivered notification dedupKey=${trigger.dedupKey} tag=${trigger.sessionTag}")
        return Result.success()
    }

    /**
     * Builds a synthetic [Trigger] for the "Send test notification" path.
     * Picks the first enabled trigger type in priority order. Returns null
     * only if every per-trigger toggle is off (caller treats as a no-op
     * cycle).
     */
    private fun buildForcedTrigger(prefs: NotificationPrefs, today: LocalDate): Trigger? {
        return when {
            prefs.mealMissingEnabled ->
                Trigger.MealMissing(MealCategory.LUNCH, today)
            prefs.activitySpikeEnabled -> {
                val isoWeek = today.get(WeekFields.ISO.weekOfWeekBasedYear())
                val isoWeekYear = today.get(WeekFields.ISO.weekBasedYear())
                val isoWeekTag = String.format(
                    Locale.ROOT,
                    "exercise:%d-W%02d",
                    isoWeekYear,
                    isoWeek,
                )
                Trigger.ActivitySpike(
                    deltaKcal = 120,
                    isoDate = today,
                    hour = ZonedDateTime.now().hour,
                    isoWeekTag = isoWeekTag,
                )
            }
            prefs.macrosAtRiskEnabled ->
                Trigger.MacrosAtRisk(kcalPct = 45, proteinPct = 35, isoDate = today)
            prefs.dailySummaryEnabled ->
                Trigger.DailySummary(today)
            else -> null
        }
    }

    /**
     * Posts the OS notification. Tap launches [MainActivity] with the
     * pending-nudge payload encoded as JSON in [PendingProactiveNudge.EXTRA_PENDING_NUDGE_JSON];
     * [MainActivity.handleNotificationIntent] materializes the chat session
     * on first tap and routes to the chat tab. Channel is created on demand
     * on API 26+.
     *
     * Notification id is deterministic per (trigger.dedupKey, proactiveTag)
     * so a follow-up nudge that shares the same logical thread
     * (`meals:<date>` / `exercise:<iso-week>`) replaces the earlier visible
     * notification instead of stacking — matches the design that "one
     * thread, one chip in the shade". Per-tag rather than per-session
     * because, post-2026-06-26 redesign, the session id no longer exists
     * at delivery time.
     */
    private fun postNotification(
        ctx: Context,
        pending: PendingProactiveNudge,
        text: String?,
        trigger: Trigger,
        language: String,
        picture: android.graphics.Bitmap?,
    ) {
        ensureChannel(ctx, language)

        // Stable per-thread id so dismissed/superseded notifs collapse onto
        // each other. Tag-keyed when available (shared sessions), otherwise
        // dedup-keyed (single-shot).
        val notificationKey = pending.proactiveTag ?: trigger.dedupKey
        val notificationId = NOTIFICATION_ID_BASE + (notificationKey.hashCode() and 0x0FFFFFFF)

        val tapIntent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(PendingProactiveNudge.EXTRA_PENDING_NUDGE_JSON, pending.toJson())
            putExtra(EXTRA_SWITCH_TO_CHAT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val titleKey = when (trigger) {
            is Trigger.MealMissing -> "notif_title_meals"
            is Trigger.ActivitySpike -> "notif_title_activity_spike"
            is Trigger.MacrosAtRisk -> "notif_title_macros"
            is Trigger.DailySummary -> "notif_title_summary"
        }
        val title = tr(titleKey, language)

        // Collapsed shade preview: 1st sentence of the LLM prose. The bars
        // now live in the rendered bitmap (BigPicture), so collapsed text
        // stays clean — no ASCII bar leakage into the shade. When [text]
        // is null (ActivitySpike, 2026-06-23) the collapsed preview is the
        // title only.
        val collapsed = text?.split(Regex("(?<=[.!])\\s+"))
            ?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: text?.take(120)

        // v3 (2026-06-22): BigPictureStyle when we have a rendered bar card;
        // BigTextStyle as fallback when rendering failed for any reason.
        // When [text] is null we drop the summary line entirely — the bars
        // are the whole message.
        val style: NotificationCompat.Style = if (picture != null) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(picture)
                .also { if (text != null) it.setSummaryText(text) }
        } else {
            NotificationCompat.BigTextStyle().bigText(text ?: "")
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (collapsed != null) builder.setContentText(collapsed)
        val notification = builder.build()

        try {
            NotificationManagerCompat.from(ctx)
                .notify(notificationId, notification)
        } catch (se: SecurityException) {
            // Belt-and-braces — we already gated on POST_NOTIFICATIONS above,
            // but a race with the user revoking the permission mid-cycle
            // could still throw. Swallow so the worker reports success.
            Log.w(TAG, "notify denied: ${se.message}")
        }
    }

    /** Maps a [Trigger] to the metric rows the renderer should draw.
     *  Each row carries its own colour from [NotificationBitmapRenderer.Palette]
     *  so the notification ties back visually to the dashboard tabs.
     *
     *  ActivitySpike historically drew 2 rows (kcal activos + passos). A 3rd
     *  row "Kcal refeições" (amarelo) was added 2026-06-23 so the user gets
     *  the full picture at a glance: how much they burned AND how much they
     *  ate, since the two together drive the daily energy balance.
     *
     *  DailySummary expanded 2026-06-26 from 2 rows (Calorias + Proteína) to
     *  4 rows (Calorias + Proteína + Carboidratos + Gordura) to match the new
     *  dashboard which moved the per-macro progress bars to be the single
     *  source of truth — keeps vocabulary and ordering identical between push
     *  and screen. */
    private fun buildMetricRows(
        trigger: Trigger,
        todayKcal: Int,
        todayProteinG: Int,
        todayCarbsG: Int,
        todayFatG: Int,
        latestTarget: com.example.data.local.TargetSnapshot?,
        stepsToday: Long,
        activeKcalToday: Int,
    ): List<NotificationBitmapRenderer.MetricRow> {
        val targetKcal = latestTarget?.targetKcal ?: 0
        val targetProteinG = (latestTarget?.targetProtein ?: 0.0).toInt()
        val targetCarbsG = (latestTarget?.targetCarbs ?: 0.0).toInt()
        val targetFatG = (latestTarget?.targetFat ?: 0.0).toInt()
        return when (trigger) {
            is Trigger.ActivitySpike -> listOf(
                NotificationBitmapRenderer.MetricRow(
                    label = "Kcal activos",
                    current = activeKcalToday,
                    target = (latestTarget?.targetActivityCalories ?: 500).coerceAtLeast(1),
                    unit = "",
                    barColorHex = NotificationBitmapRenderer.Palette.ACTIVE_KCAL,
                ),
                NotificationBitmapRenderer.MetricRow(
                    label = "Passos",
                    current = stepsToday.toInt(),
                    target = (latestTarget?.targetSteps ?: 6000).coerceAtLeast(1),
                    unit = "",
                    barColorHex = NotificationBitmapRenderer.Palette.STEPS,
                ),
                NotificationBitmapRenderer.MetricRow(
                    label = "Kcal refeições",
                    current = todayKcal,
                    // coerceAtLeast(1): if no target prescribed yet we still
                    // want the renderer to skip the divide-by-zero guard and
                    // draw an empty bar with the goal at the right edge.
                    target = targetKcal.coerceAtLeast(1),
                    unit = "",
                    barColorHex = NotificationBitmapRenderer.Palette.KCAL,
                ),
            )
            else -> listOf(
                NotificationBitmapRenderer.MetricRow(
                    label = "Calorias",
                    current = todayKcal,
                    target = targetKcal,
                    unit = "",
                    barColorHex = NotificationBitmapRenderer.Palette.KCAL,
                ),
                NotificationBitmapRenderer.MetricRow(
                    label = "Proteína",
                    current = todayProteinG,
                    target = targetProteinG,
                    unit = "g",
                    barColorHex = NotificationBitmapRenderer.Palette.PROTEIN,
                ),
                NotificationBitmapRenderer.MetricRow(
                    label = "Carboidratos",
                    current = todayCarbsG,
                    target = targetCarbsG,
                    unit = "g",
                    barColorHex = NotificationBitmapRenderer.Palette.CARBS,
                ),
                NotificationBitmapRenderer.MetricRow(
                    label = "Gordura",
                    current = todayFatG,
                    target = targetFatG,
                    unit = "g",
                    barColorHex = NotificationBitmapRenderer.Palette.FAT,
                ),
            )
        }
    }

    private fun ensureChannel(ctx: Context, language: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channelName = tr("notification_preferences_title", language)
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
    }
}
