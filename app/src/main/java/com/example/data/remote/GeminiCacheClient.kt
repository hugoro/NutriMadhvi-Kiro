package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Wrapper around Gemini's explicit-cache REST surface
 * (`POST/PATCH/DELETE /v1beta/cachedContents`). Created 2026-06-22 for
 * the plan ADENDO 2 — Fix 9 (preciso-que-vejas-isto-federated-quail).
 *
 * Why: the chat tab sends a long, mostly-identical `systemInstruction`
 * on every turn (HABIT_PROTOCOL alone is ~2,500 tokens, ~9.3kB). Uploading
 * the stable prefix once and referencing it by `cachedContents/{id}` on
 * every subsequent call eliminates per-turn re-tokenisation cost on the
 * Google side and ~9kB of transit per request.
 *
 * Contract for every public function:
 *  - Network errors return a sentinel (null / false) — never throw. The
 *    orchestrator must always be able to fall back to the non-cached
 *    code path; cache failure is non-fatal.
 *  - All work happens on [Dispatchers.IO].
 *  - Verbose logging under tag `CHAT_CACHE` so latency wins / failures
 *    are visible in logcat.
 */
object GeminiCacheClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val TAG = "CHAT_CACHE"

    private val logging = HttpLoggingInterceptor().apply {
        // Keep at NONE in production; bump to HEADERS for ad-hoc debug.
        level = HttpLoggingInterceptor.Level.NONE
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val createReqAdapter by lazy { moshi.adapter(CreateCacheRequest::class.java) }
    private val cacheResponseAdapter by lazy { moshi.adapter(CachedContentResponse::class.java) }
    private val patchReqAdapter by lazy { moshi.adapter(PatchCacheRequest::class.java) }

    /**
     * Create a new server-side cache holding [systemInstructionText] as
     * the system instruction. Models supported (per public docs as of
     * 2026-06-22): `gemini-2.5-flash`, `gemini-3.5-flash`, etc. Newer
     * Flash 3.x models commonly accept the same shape.
     *
     * The `contents` array MUST be present (API requires it) but can hold
     * a single trivial user/model turn — we send a one-token placeholder
     * so the cache is created against a valid conversation prefix.
     *
     * @return resource name like `cachedContents/abc123` on success, or
     *         null on any failure (network, HTTP 4xx/5xx, parse). The
     *         orchestrator should fall back to a non-cached request.
     */
    suspend fun createCache(
        model: String,
        systemInstructionText: String,
        ttlSeconds: Int = 3600,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            android.util.Log.w(TAG, "createCache skipped: API key not configured")
            return@withContext null
        }
        if (systemInstructionText.isBlank()) {
            android.util.Log.w(TAG, "createCache skipped: empty systemInstruction")
            return@withContext null
        }
        // The model name in cache requests must be fully qualified with
        // the `models/` prefix (per Google docs example).
        val qualifiedModel = if (model.startsWith("models/")) model else "models/$model"
        val body = CreateCacheRequest(
            model = qualifiedModel,
            systemInstruction = Content(parts = listOf(Part(text = systemInstructionText))),
            // Single dummy turn so the API accepts the create call.
            contents = listOf(
                Content(role = "user", parts = listOf(Part(text = "Hello.")))
            ),
            ttl = "${ttlSeconds}s"
        )
        val json = createReqAdapter.toJson(body)
        val req = Request.Builder()
            .url("${BASE_URL}v1beta/cachedContents?key=$apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(400).orEmpty()
                    android.util.Log.w(
                        TAG,
                        "createCache HTTP ${resp.code}: $errBody"
                    )
                    return@withContext null
                }
                val parsed = resp.body?.string()
                    ?.let { runCatching { cacheResponseAdapter.fromJson(it) }.getOrNull() }
                val name = parsed?.name
                if (name.isNullOrBlank()) {
                    android.util.Log.w(TAG, "createCache: response missing 'name'")
                    return@withContext null
                }
                android.util.Log.d(
                    TAG,
                    "created $name model=$qualifiedModel ttl=${ttlSeconds}s prompt=${systemInstructionText.length}chars"
                )
                return@withContext name
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "createCache exception: ${t.message?.take(120)}")
            return@withContext null
        }
    }

    /**
     * Refresh the TTL on an existing cache. Called on every chat turn so
     * an active session doesn't have its cache evicted.
     *
     * @return true on 200 OK, false on any failure (caller should then
     *         drop the cache reference and let the next turn re-create).
     */
    suspend fun touchCache(
        cacheName: String,
        ttlSeconds: Int = 3600,
        apiKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") return@withContext false
        if (cacheName.isBlank()) return@withContext false
        val body = PatchCacheRequest(ttl = "${ttlSeconds}s")
        val json = patchReqAdapter.toJson(body)
        // PATCH with updateMask=ttl per API contract.
        val req = Request.Builder()
            .url("${BASE_URL}v1beta/$cacheName?key=$apiKey&updateMask=ttl")
            .patch(json.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w(
                        TAG,
                        "touchCache $cacheName HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"
                    )
                    return@withContext false
                }
                android.util.Log.d(TAG, "touchCache $cacheName refreshed=${ttlSeconds}s")
                return@withContext true
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "touchCache exception: ${t.message?.take(120)}")
            return@withContext false
        }
    }

    /**
     * Delete a cache explicitly. Called when the session is deleted or
     * archived. Fire-and-forget; failures are logged but not surfaced.
     */
    suspend fun deleteCache(cacheName: String, apiKey: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") return@withContext
        if (cacheName.isBlank()) return@withContext
        val req = Request.Builder()
            .url("${BASE_URL}v1beta/$cacheName?key=$apiKey")
            .delete()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                android.util.Log.d(TAG, "deleteCache $cacheName status=${resp.code}")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "deleteCache exception: ${t.message?.take(120)}")
        }
    }

    // -------- Wire types (Moshi-generated) --------

    @JsonClass(generateAdapter = true)
    internal data class CreateCacheRequest(
        @Json(name = "model") val model: String,
        @Json(name = "systemInstruction") val systemInstruction: Content,
        @Json(name = "contents") val contents: List<Content>,
        @Json(name = "ttl") val ttl: String
    )

    @JsonClass(generateAdapter = true)
    internal data class PatchCacheRequest(
        @Json(name = "ttl") val ttl: String
    )

    @JsonClass(generateAdapter = true)
    internal data class CachedContentResponse(
        @Json(name = "name") val name: String? = null,
        @Json(name = "model") val model: String? = null,
        @Json(name = "expireTime") val expireTime: String? = null
    )
}
