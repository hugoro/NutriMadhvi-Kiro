package com.example.data.meal.ensemble

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Groq transport for the OpenAI-compatible chat-completions endpoint.
 *
 * Kept separate from [com.example.data.remote.GeminiRetrofitClient] because
 * the wire format, auth scheme and base URL all differ; sharing the Gemini
 * client would have meant bending its request DTOs out of shape.
 *
 * No logging interceptor is installed at BODY level on purpose — a vision
 * request carries a multi-megabyte base64 image, and dumping it to logcat
 * both leaks user meal photos and adds seconds of latency (the same trap
 * already documented on the Gemini client).
 */
internal interface GroqApiService {
    @POST("openai/v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") bearer: String,
        @Body request: GroqChatRequest,
        /** Mirrors `request.model` so the rate-limit interceptor can attribute
         *  the response's remaining-token headers without parsing the body. */
        @Header(GroqRequestModel.HEADER) model: String = "",
    ): GroqChatResponse
}

internal object GroqRetrofitClient {
    private const val BASE_URL = "https://api.groq.com/"

    /**
     * Feeds [GroqTokenBudget] from the rate-limit headers Groq attaches to
     * every response, successes and errors alike. Doing it here rather than at
     * the call site means the budget stays current even for responses the
     * provider turns into typed failures and never inspects.
     */
    private val rateLimitInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val remaining = response.header("x-ratelimit-remaining-tokens")?.toIntOrNull()
        if (remaining != null) {
            val model = GroqRequestModel.of(request)
            if (model != null) {
                val resetMs = GroqTokenBudget
                    .parseResetDuration(response.header("x-ratelimit-reset-tokens"))
                    ?: DEFAULT_RESET_MS
                GroqTokenBudget.record(
                    model = model,
                    remainingTokens = remaining,
                    resetAfterMs = resetMs,
                    nowMs = System.currentTimeMillis(),
                )
            }
        }
        response
    }

    /** Fallback window when the reset header is absent or unparseable. */
    private const val DEFAULT_RESET_MS = 60_000L

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(rateLimitInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val service: GroqApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }
}

/**
 * Recovers the target model from an outgoing request so the budget can be
 * attributed correctly.
 *
 * The provider tags each call with a header rather than having the interceptor
 * re-read and re-buffer a multi-megabyte JSON body just to find one field.
 */
internal object GroqRequestModel {
    const val HEADER = "X-NutriMadhvi-Model"

    fun of(request: okhttp3.Request): String? =
        request.header(HEADER)?.takeIf { it.isNotBlank() }
}

@JsonClass(generateAdapter = true)
internal data class GroqChatRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<GroqMessage>,
    @Json(name = "temperature") val temperature: Double = 0.2,
    @Json(name = "response_format") val responseFormat: GroqResponseFormat? =
        GroqResponseFormat("json_object"),
    /** Groq-specific: `none` disables the reasoning pass for fast analysis. */
    @Json(name = "reasoning_effort") val reasoningEffort: String? = "none",
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
)

@JsonClass(generateAdapter = true)
internal data class GroqResponseFormat(
    @Json(name = "type") val type: String,
)

@JsonClass(generateAdapter = true)
internal data class GroqMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: List<GroqContentPart>,
)

/**
 * OpenAI-style multimodal content part. Exactly one of [text] / [imageUrl] is
 * populated; the unused one is omitted by Moshi rather than sent as null.
 */
@JsonClass(generateAdapter = true)
internal data class GroqContentPart(
    @Json(name = "type") val type: String,
    @Json(name = "text") val text: String? = null,
    @Json(name = "image_url") val imageUrl: GroqImageUrl? = null,
) {
    companion object {
        fun text(value: String) = GroqContentPart(type = "text", text = value)

        /** Wraps already-loaded bytes as a transient data URL. The original
         *  file is never touched, moved or re-encoded — we only base64 what
         *  the caller handed us. */
        fun image(base64: String, mimeType: String) = GroqContentPart(
            type = "image_url",
            imageUrl = GroqImageUrl("data:$mimeType;base64,$base64"),
        )
    }
}

@JsonClass(generateAdapter = true)
internal data class GroqImageUrl(
    @Json(name = "url") val url: String,
)

@JsonClass(generateAdapter = true)
internal data class GroqChatResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "choices") val choices: List<GroqChoice>? = null,
)

@JsonClass(generateAdapter = true)
internal data class GroqChoice(
    @Json(name = "message") val message: GroqResponseMessage? = null,
    @Json(name = "finish_reason") val finishReason: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class GroqResponseMessage(
    @Json(name = "content") val content: String? = null,
)
