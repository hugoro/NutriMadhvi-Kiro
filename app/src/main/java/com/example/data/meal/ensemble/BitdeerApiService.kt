package com.example.data.meal.ensemble

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Bitdeer transport for the OpenAI-compatible chat-completions endpoint.
 *
 * Bitdeer (`api-inference.bitdeer.ai`) speaks the same OpenAI-style wire
 * format as Groq, so we reuse the Groq DTOs ([GroqChatRequest] / [GroqChatResponse])
 * as-is — only the base URL differs.
 *
 * No rate-limit interceptor: Bitdeer does not expose Groq-style
 * `x-ratelimit-remaining-tokens` headers, so there is no token-budget
 * mechanism here. A 429 from Bitdeer surfaces as [FoodVisionErrorKind.RATE_LIMITED]
 * and is handled by the ensemble's fallback to Gemini, identical to how the
 * Qwen-Groq provider already handles exhausted windows.
 *
 * No logging interceptor at BODY level for the same reason as [GroqApiService]:
 * a vision request carries a multi-megabyte base64 image.
 */
internal interface BitdeerApiService {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") bearer: String,
        @Body request: GroqChatRequest,
    ): GroqChatResponse
}

internal object BitdeerRetrofitClient {
    private const val BASE_URL = "https://api-inference.bitdeer.ai/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val service: BitdeerApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(BitdeerApiService::class.java)
    }
}
