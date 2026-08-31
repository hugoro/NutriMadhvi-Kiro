package com.example.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object GeminiRetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    // 2026-06-26: HttpLoggingInterceptor.Level.BODY buffers the ENTIRE
    // response body in memory before handing it back. For Gemini calls
    // carrying a 5–9 MB food image, the buffer + base64 dump into logcat
    // adds seconds of latency on the blocking path — and only became
    // visible after fix #4 added a 25 s wall-clock cap on the Pro
    // escalation: large-image Pro analyses now hit the timeout in
    // emulators / slow networks where they didn't before, surfacing as
    // "Modelo avançado indisponível" with the Flash result preserved.
    //
    // HEADERS keeps enough signal for auth/quota debugging without the
    // body bloat. Streaming and cache clients already use NONE.
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    val service: GeminiApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}
