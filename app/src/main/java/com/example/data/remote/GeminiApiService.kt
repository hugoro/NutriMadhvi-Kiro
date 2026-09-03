package com.example.data.remote

import com.example.BuildConfig
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
    // HEADERS logs the full request line, and every Gemini endpoint carries the
    // credential as a `?key=` query parameter — so the raw API key was being
    // written to logcat on every call, in release builds too (anything reading
    // the device log, including a bug report, would capture it). The level is
    // now tied to debug builds and the URL is scrubbed before it reaches the
    // log, so enabling verbose logging can never leak the credential.
    private val apiKeyInUrl = Regex("key=[^&\\s]+")

    private val redactingLogger = HttpLoggingInterceptor.Logger { message ->
        HttpLoggingInterceptor.Logger.DEFAULT.log(
            apiKeyInUrl.replace(message, "key=<redacted>")
        )
    }

    private val loggingInterceptor = HttpLoggingInterceptor(redactingLogger).apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    // Image turns push several hundred KB of base64 up the wire. On mobile
    // data a 60 s write budget was tight enough that large attachments
    // aborted mid-upload and surfaced as a generic failure, so the write
    // timeout gets the most headroom. `callTimeout` bounds the whole
    // exchange so a stalled socket can still never hang the turn forever.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
