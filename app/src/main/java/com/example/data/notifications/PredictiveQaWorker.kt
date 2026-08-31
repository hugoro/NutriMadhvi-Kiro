package com.example.data.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.data.chat.ChatIntent
import com.example.data.chat.DataFetcher
import com.example.data.chat.PredictionEngine
import com.example.data.local.ChatDatabase
import com.example.data.local.PredictedQaEntity
import com.example.data.remote.ChatContextBuilder
import com.example.data.remote.Content
import com.example.data.remote.GeminiRequest
import com.example.data.remote.GeminiRetrofitClient
import com.example.data.remote.GenerationConfig
import com.example.data.remote.Part
import com.example.data.remote.ThinkingConfig
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 2026-07-06 (Frente I) — periodic background worker that pre-computes
 * answers to questions the user is likely to ask.
 *
 * On each tick:
 *  1. Guards: API key present; user active (opened the app in the last
 *     24h — no point burning quota for someone who has drifted away).
 *  2. Prune expired rows from `predicted_qa`.
 *  3. Compute the current app-state fingerprint (food count, activity
 *     count, latest target ts, latest bmr ts, today's date). Cache
 *     entries whose fingerprint no longer matches are effectively
 *     invalidated when [com.example.data.chat.ChatOrchestrator.handle]
 *     reads them (it verifies fingerprint on hit).
 *  4. Ask [PredictionEngine] which questions to prefetch.
 *  5. For each prediction, run [DataFetcher] + a single Flash-Lite
 *     synth to produce a 1-2 sentence answer. Persist to Room with
 *     TTL 4h.
 *
 * Quota budget: MAX_PREDICTIONS=6 per run × 1 LLM call = 6 calls per
 * run. Scheduled every 30 min → 288 calls/day worst case. In practice
 * the user-active gate + PredictionEngine cap keep this much lower.
 */
class PredictiveQaWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d(TAG, "skip: API key missing")
            return Result.success()
        }

        val db = ChatDatabase.getInstance(ctx)
        val chatDao = db.chatDao
        val foodDao = db.foodDao
        val activityDao = db.activityDao
        val targetDao = db.targetSnapshotDao
        val bmrDao = db.bmrSnapshotDao
        val insightDao = db.userInsightDao
        val predictedDao = db.predictedQaDao

        // Gate: user has to have opened the app in the last 24 h. We
        // proxy this via the most recent chat_session `updatedAt` —
        // opening the app touches this on every send / tab resume.
        val sessions = runCatching { chatDao.getAllSessionsFlow().first() }.getOrDefault(emptyList())
        val lastTouchMs = sessions.maxOfOrNull { it.lastMessageAt } ?: 0L
        val ageMs = System.currentTimeMillis() - lastTouchMs
        if (ageMs > 24L * 60L * 60L * 1000L) {
            Log.d(TAG, "skip: user inactive for ${ageMs / 60_000L}min")
            return Result.success()
        }

        val nowStarted = System.currentTimeMillis()
        val expired = runCatching { predictedDao.deleteExpired(nowStarted) }.getOrDefault(0)
        if (expired > 0) Log.d(TAG, "pruned $expired expired rows")

        val today = LocalDate.now()

        // Fingerprint used to invalidate cache on state change.
        val foodCount = runCatching { foodDao.countSince(0L) }.getOrDefault(0)
        val activityCount = runCatching { activityDao.countSince(0L) }.getOrDefault(0)
        val latestTarget = runCatching { targetDao.latest() }.getOrNull()
        val latestBmr = runCatching { bmrDao.latest() }.getOrNull()
        val fingerprint = "$foodCount|$activityCount|" +
            "${latestTarget?.effectiveDateEpochDay ?: 0}|" +
            "${latestBmr?.effectiveDateEpochDay ?: 0}|$today"

        // Prediction inputs.
        val nowHour = ZonedDateTime.now(ZoneId.systemDefault()).hour
        val insights = runCatching { insightDao.topConfirmed(6) }.getOrDefault(emptyList())
        // Recent user questions — snapshot from the current session
        // (or the most-recent, if none active). Kept small to bound
        // hash work.
        val recentQuestions: List<String> = runCatching {
            val session = sessions.maxByOrNull { it.lastMessageAt } ?: return@runCatching emptyList()
            chatDao.getMessagesForSession(session.id)
                .filter { it.sender == "user" && !it.isError }
                .takeLast(20)
                .map { it.text }
        }.getOrDefault(emptyList())

        // User's language, needed for the LLM prompt.
        val prefs = ctx.getSharedPreferences("user_profile_pref", Context.MODE_PRIVATE)
        val language = prefs.getString("profile_language", "pt-PT") ?: "pt-PT"
        val userName = prefs.getString("profile_name", "the user") ?: "the user"

        val predictions = PredictionEngine.predictLikelyQuestions(
            today = today,
            nowHour = nowHour,
            userInsights = insights,
            recentUserQuestions = recentQuestions,
        )
        Log.i(TAG, "predictions=${predictions.size} tags=${predictions.map { it.tag }}")

        // For each prediction: fetch data + synthesise answer + persist.
        val fetcher = DataFetcher(foodDao, activityDao)
        val zone = ZoneId.systemDefault()
        var synthed = 0
        for (pq in predictions) {
            try {
                val hash = PredictionEngine.canonicalHash(pq.intent, language)
                // Skip if we already have a fresh row for this hash
                // AND fingerprint matches — no need to re-synth.
                val existing = predictedDao.findByHash(hash)
                if (existing != null &&
                    existing.dataFingerprint == fingerprint &&
                    existing.expiresAtMs > System.currentTimeMillis()
                ) {
                    Log.d(TAG, "skip tag=${pq.tag} (fresh row exists)")
                    continue
                }

                // Fetch the data window. Pass empty SH maps — the
                // worker doesn't own SH state; the LLM synth uses the
                // DAO rows only.
                val prefetched = fetcher.fetchForIntent(
                    intent = pq.intent,
                    zone = zone,
                    shStepsByDate = emptyMap(),
                    shDailyActivity = emptyMap(),
                    shWorkouts = emptyList(),
                )
                val prefetchedBlock = prefetched.toContextBlock(language)

                // Canonical snapshot (compact form: only what the fast
                // synth needs — targets + BMR + numbers).
                val canonicalText = ChatContextBuilder.build(
                    today = today,
                    zone = zone,
                    foods = prefetched.meals,
                    activities = prefetched.activities,
                    latestTarget = latestTarget,
                    latestBmr = latestBmr,
                    stepsByDate = emptyMap(),
                    caloriesBurnedByDate = emptyMap(),
                    confirmedInsights = insights,
                    userName = userName,
                    bodyCompositionHistory = emptyList(),
                )

                val answer = synthAnswer(
                    userQuestion = pq.canonicalText,
                    canonical = canonicalText,
                    prefetched = prefetchedBlock,
                    userName = userName,
                    language = language,
                    apiKey = apiKey,
                ) ?: continue

                val expiresAtMs = System.currentTimeMillis() + TTL_MS
                predictedDao.upsert(
                    PredictedQaEntity(
                        queryHash = hash,
                        tag = pq.tag,
                        canonicalText = pq.canonicalText,
                        answerText = answer,
                        dataFingerprint = fingerprint,
                        language = language,
                        expiresAtMs = expiresAtMs,
                    )
                )
                synthed += 1
                Log.i(TAG, "persisted tag=${pq.tag} len=${answer.length}")
            } catch (t: Throwable) {
                Log.w(TAG, "synth failed for tag=${pq.tag}: ${t.message?.take(120)}")
            }
        }
        Log.i(
            TAG,
            "run complete predicted=${predictions.size} synthed=$synthed " +
                "took=${System.currentTimeMillis() - nowStarted}ms"
        )
        return Result.success()
    }

    private suspend fun synthAnswer(
        userQuestion: String,
        canonical: String,
        prefetched: String,
        userName: String,
        language: String,
        apiKey: String,
    ): String? {
        val systemText = "You are $userName's nutrition & training assistant. Answer the " +
            "user's question below in 1-2 short sentences in language code: $language. " +
            "Ground every number in the DATA block. NO greeting, NO sign-off, NO questions " +
            "back, NO emoji, NO protocol tags. If the DATA is insufficient, say so briefly.\n\n" +
            "$canonical\n\n$prefetched"
        val request = GeminiRequest(
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = userQuestion)))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemText))),
            generationConfig = GenerationConfig(
                temperature = 0.3f,
                maxOutputTokens = 200,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return try {
            val resp = GeminiRetrofitClient.service.generateContent(
                model = "gemini-3.1-flash-lite-preview",
                apiKey = apiKey,
                request = request,
            )
            resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?.takeIf { it.isNotBlank() }
                ?.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "Gemini call failed: ${t.message?.take(120)}")
            null
        }
    }

    companion object {
        private const val TAG = "CHAT_PREDICTED"
        /** Cache row TTL. 4 hours matches how long the underlying data
         *  is likely to remain fresh (a couple of meals or workouts
         *  will move fingerprint anyway, invalidating the row sooner). */
        internal const val TTL_MS: Long = 4L * 60L * 60L * 1000L
    }
}
