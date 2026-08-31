package com.example.data.weather

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Fetches a 2-day forecast from the Portuguese Meteorological Institute
 * (IPMA, https://api.ipma.pt/) and renders it into a one-line context
 * block injected into the chat's system prompt. No API key required —
 * IPMA exposes a fully public, anonymous REST surface.
 *
 * Flow:
 *  1. `getWeatherFor(lat, lon, lang)` → resolve nearest IPMA city from
 *     a cached `distrits-islands.json` snapshot
 *  2. Fetch the city's daily forecast (`/forecast/meteorology/cities/daily/<id>.json`)
 *  3. Cache the result for [CACHE_TTL_MIN] minutes per city
 *
 * The fetcher is fail-safe: any IO error, JSON parse error, or out-of-
 * Portugal coordinate resolves to `null` and the caller silently omits
 * the [CONTEXTO LOCAL] block. The chat keeps working without weather.
 *
 * @see LocationHelper for the upstream lat/lon source
 */
class IpmaWeatherFetcher(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    /**
     * Return a rendered weather summary for the chat prompt, or null if
     * we can't resolve a meaningful forecast.
     *
     * Example output:
     * `Lisboa hoje: 16-27°C, céu pouco nublado, prob chuva 0%. Amanhã: 18-29°C, céu limpo, prob chuva 5%.`
     */
    suspend fun getWeatherSummary(lat: Double, lon: Double): WeatherSummary? {
        val city = resolveCity(lat, lon) ?: return null
        val daily = fetchDailyForecast(city.globalIdLocal) ?: return null
        val days = daily.data.take(2)
        if (days.isEmpty()) return null

        val today = days.first()
        val tomorrow = days.getOrNull(1)

        return WeatherSummary(
            city = city.local,
            todayMinC = today.tMin.toDoubleOrNull() ?: return null,
            todayMaxC = today.tMax.toDoubleOrNull() ?: return null,
            todayPrecipProb = today.precipitaProb.toDoubleOrNull() ?: 0.0,
            todayWeatherType = today.idWeatherType,
            tomorrowMinC = tomorrow?.tMin?.toDoubleOrNull(),
            tomorrowMaxC = tomorrow?.tMax?.toDoubleOrNull(),
            tomorrowPrecipProb = tomorrow?.precipitaProb?.toDoubleOrNull(),
            tomorrowWeatherType = tomorrow?.idWeatherType
        )
    }

    /** Nearest IPMA city to the given lat/lon, or null if more than ~80km
     *  from any Portuguese city (i.e. user is outside Portugal). */
    private suspend fun resolveCity(lat: Double, lon: Double): IpmaCity? {
        val cities = loadCityIndex() ?: return null
        // Equirectangular approximation — good enough for "which city is
        // closest" at sub-1km scale across Portugal's bounding box.
        val nearest = cities.minByOrNull { c ->
            val cLat = c.latitude.toDoubleOrNull() ?: return@minByOrNull Double.MAX_VALUE
            val cLon = c.longitude.toDoubleOrNull() ?: return@minByOrNull Double.MAX_VALUE
            val dLat = (lat - cLat) * 111.0
            val dLon = (lon - cLon) * 111.0 * cos(Math.toRadians(lat))
            hypot(dLat, dLon)
        } ?: return null

        val nLat = nearest.latitude.toDoubleOrNull()
        val nLon = nearest.longitude.toDoubleOrNull()
        if (nLat != null && nLon != null) {
            val dLat = (lat - nLat) * 111.0
            val dLon = (lon - nLon) * 111.0 * cos(Math.toRadians(lat))
            val km = hypot(dLat, dLon)
            if (km > 80.0) return null
        }
        return nearest
    }

    private suspend fun loadCityIndex(): List<IpmaCity>? {
        // The city index changes ~yearly; cache it on first fetch and
        // re-fetch only when the cache is older than 7 days.
        val cached = prefs.getString(KEY_CITY_INDEX_JSON, null)
        val ts = prefs.getLong(KEY_CITY_INDEX_TS, 0L)
        if (cached != null && System.currentTimeMillis() - ts < Duration.ofDays(7).toMillis()) {
            return parseCityIndex(cached)
        }
        val fresh = httpGetString(URL_CITY_INDEX) ?: return cached?.let(::parseCityIndex)
        prefs.edit()
            .putString(KEY_CITY_INDEX_JSON, fresh)
            .putLong(KEY_CITY_INDEX_TS, System.currentTimeMillis())
            .apply()
        return parseCityIndex(fresh)
    }

    private fun parseCityIndex(json: String): List<IpmaCity>? = runCatching {
        val type = Types.newParameterizedType(List::class.java, IpmaCity::class.java)
        moshi.adapter<List<IpmaCity>>(type).fromJson(json)
    }.getOrNull()

    private suspend fun fetchDailyForecast(globalIdLocal: Int): IpmaForecastResponse? {
        val cacheKey = "$KEY_FORECAST_PREFIX$globalIdLocal"
        val cachedJson = prefs.getString(cacheKey, null)
        val cachedTs = prefs.getLong("$cacheKey$KEY_TS_SUFFIX", 0L)
        val age = System.currentTimeMillis() - cachedTs
        if (cachedJson != null && age < CACHE_TTL_MIN * 60_000L) {
            return parseForecast(cachedJson)
        }
        val fresh = httpGetString(URL_FORECAST_PREFIX + globalIdLocal + URL_FORECAST_SUFFIX)
            ?: return cachedJson?.let(::parseForecast)
        prefs.edit()
            .putString(cacheKey, fresh)
            .putLong("$cacheKey$KEY_TS_SUFFIX", System.currentTimeMillis())
            .apply()
        return parseForecast(fresh)
    }

    private fun parseForecast(json: String): IpmaForecastResponse? = runCatching {
        moshi.adapter(IpmaForecastResponse::class.java).fromJson(json)
    }.getOrNull()

    private suspend fun httpGetString(url: String): String? {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class IpmaCity(
        val globalIdLocal: Int,
        val local: String,
        val latitude: String,
        val longitude: String
    )

    @JsonClass(generateAdapter = true)
    internal data class IpmaForecastResponse(
        val data: List<IpmaForecastDay>
    )

    @JsonClass(generateAdapter = true)
    internal data class IpmaForecastDay(
        val precipitaProb: String,
        val tMin: String,
        val tMax: String,
        val idWeatherType: Int,
        val classWindSpeed: Int,
        val predWindDir: String,
        val forecastDate: String
    )

    companion object {
        private const val PREFS_NAME = "ipma_weather_prefs"
        private const val KEY_CITY_INDEX_JSON = "city_index_json"
        private const val KEY_CITY_INDEX_TS = "city_index_ts"
        private const val KEY_FORECAST_PREFIX = "forecast_"
        private const val KEY_TS_SUFFIX = "_ts"
        private const val CACHE_TTL_MIN = 30L
        private const val URL_CITY_INDEX =
            "https://api.ipma.pt/open-data/distrits-islands.json"
        private const val URL_FORECAST_PREFIX =
            "https://api.ipma.pt/open-data/forecast/meteorology/cities/daily/"
        private const val URL_FORECAST_SUFFIX = ".json"
    }
}

/**
 * Renderable weather snapshot. The `idWeatherType` IDs are the IPMA
 * classification — translated to PT here so the LLM doesn't have to
 * decode them.
 */
data class WeatherSummary(
    val city: String,
    val todayMinC: Double,
    val todayMaxC: Double,
    val todayPrecipProb: Double,        // 0..100
    val todayWeatherType: Int,
    val tomorrowMinC: Double?,
    val tomorrowMaxC: Double?,
    val tomorrowPrecipProb: Double?,    // 0..100
    val tomorrowWeatherType: Int?
) {
    /** One-line PT summary suitable for direct inclusion in the system
     *  prompt's `[CONTEXTO LOCAL]` block. */
    fun toPromptLine(): String {
        val todayDesc = describeWeather(todayWeatherType)
        val sb = StringBuilder()
        sb.append("$city hoje: ${todayMinC.toInt()}–${todayMaxC.toInt()}°C, $todayDesc, prob chuva ${todayPrecipProb.toInt()}%.")
        if (tomorrowMinC != null && tomorrowMaxC != null) {
            val tomDesc = tomorrowWeatherType?.let(::describeWeather) ?: "—"
            val tomPrecip = tomorrowPrecipProb?.toInt() ?: 0
            sb.append(" Amanhã: ${tomorrowMinC.toInt()}–${tomorrowMaxC.toInt()}°C, $tomDesc, prob chuva $tomPrecip%.")
        }
        return sb.toString()
    }

    /**
     * True when conditions favour outdoor exercise: temperature in a
     * comfortable band AND precipitation probability ≤ 30%. The chat's
     * Activity coaching section reads this flag from the prompt context
     * to decide whether to recommend a walk/run vs an indoor session.
     */
    fun isOutdoorFriendlyToday(): Boolean {
        if (todayPrecipProb > 30.0) return false
        val avg = (todayMinC + todayMaxC) / 2.0
        return avg in 10.0..32.0
    }

    private fun describeWeather(id: Int): String = when (id) {
        1 -> "céu limpo"
        2 -> "céu pouco nublado"
        3 -> "céu parcialmente nublado"
        4 -> "céu muito nublado"
        5 -> "céu nublado por nuvens altas"
        6, 9 -> "aguaceiros/chuva"
        7 -> "aguaceiros fracos"
        8 -> "aguaceiros fortes"
        10, 15 -> "chuvisco"
        11 -> "chuva forte"
        12 -> "períodos de chuva"
        13 -> "períodos de chuva fraca"
        14 -> "períodos de chuva forte"
        16 -> "neblina"
        17, 26 -> "nevoeiro"
        18 -> "neve"
        19 -> "trovoada"
        20 -> "aguaceiros e poss. trovoada"
        21 -> "granizo"
        22 -> "geada"
        23 -> "chuva e poss. trovoada"
        24 -> "nebulosidade convectiva"
        25 -> "períodos muito nublado"
        27 -> "céu nublado"
        28 -> "aguaceiros de neve"
        29, 30 -> "chuva e neve"
        else -> "céu —"
    }
}
