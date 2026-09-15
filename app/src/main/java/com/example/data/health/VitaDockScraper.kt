package com.example.data.health

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "VitaDockScraper"

// Flow: GET /signin (CSRF + session) → POST /j_spring_security_check (authenticate)
// → GET / (initialise VDPortal_Session) → GET /portal/connect.php?module=target
// (mints the OAuth access token into the session via the grant redirect chain)
// → GET /portal/server/target_server.php (returns the full JSON dataset).
//
// The old approach scraped /portal/target_tab.php as HTML — WRONG: that page is a
// JS-populated template whose table is empty server-side, so the regex matched
// blank cells and stored weight-only rows. target_server.php is the AJAX data
// source the page's jQuery calls; it returns every field per reading as JSON.
private const val BASE = "https://cloud.vitadock.com"
private const val SIGNIN_URL = "$BASE/signin"
private const val LOGIN_URL = "$BASE/j_spring_security_check"
private const val HOME_URL = "$BASE/"
private const val CONNECT_URL = "$BASE/portal/connect.php?module=target"
private const val DATA_URL = "$BASE/portal/server/target_server.php?lang=en_GB&rnd=12345"
private const val REFERER_TARGET = "$BASE/portal/target_tab.php?lang=en_GB"

private val CSRF_RE = Regex("""_csrf" value="([^"]+)"""")

data class VitaDockReading(
    val timestampMs: Long,
    val weightKg: Float,
    val bodyFatPct: Float?,
    val muscleMassPct: Float?,
    val bodyWaterPct: Float?,
    val boneMassKg: Float?,
    val bmi: Float?,
)

sealed class VitaDockResult {
    data class Success(val readings: List<VitaDockReading>) : VitaDockResult()
    data class Error(val message: String) : VitaDockResult()
}

object VitaDockScraper {

    /**
     * Full login + fetch flow using [email]/[password]. Returns only readings
     * newer than [afterTimestampMs] (pass 0 to fetch all). No session cookies are
     * persisted — each call does a fresh login so there's nothing to expire.
     */
    suspend fun fetchDelta(
        context: Context,
        email: String,
        password: String,
        afterTimestampMs: Long,
    ): VitaDockResult = withContext(Dispatchers.IO) {
        try {
            val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            val cookieJar = mutableMapOf<String, String>()

            // Step 1: GET /signin — get JSESSIONID + CSRF token
            val signinHtml = get(SIGNIN_URL, ua, cookieJar)
            val csrf = CSRF_RE.find(signinHtml)?.groupValues?.get(1)
                ?: return@withContext VitaDockResult.Error("CSRF token not found")

            // Step 2: POST login
            val loginBody = "j_username=${enc(email)}&j_password=${enc(password)}" +
                "&_spring_security_remember_me=on&_csrf=${enc(csrf)}"
            post(LOGIN_URL, loginBody, SIGNIN_URL, ua, cookieJar)

            // Step 3: GET / — initialises VDPortal_Session in the PHP tier
            get(HOME_URL, ua, cookieJar)

            // Step 4: GET connect.php?module=target — runs the OAuth grant chain
            // (connect → /desiredaccessrights/request → callback → target.php),
            // minting the access token into the session. Without this,
            // target_server.php returns 400 "Access token not found".
            get(CONNECT_URL, ua, cookieJar, followRedirects = true, referer = HOME_URL)

            // Step 5: GET target_server.php — returns the full JSON dataset. The
            // page's jQuery calls this with an XHR header, so we mimic it.
            val json = get(
                DATA_URL, ua, cookieJar,
                referer = REFERER_TARGET,
                xhr = true,
            )

            if (!json.trimStart().startsWith("{") || !json.contains("bodyWeight")) {
                Log.w(TAG, "unexpected response (${json.length} chars) — login or grant failed")
                return@withContext VitaDockResult.Error("Login or OAuth grant failed")
            }

            val readings = parseJson(json, afterTimestampMs)
            Log.i(TAG, "fetched ${readings.size} readings for $email (after $afterTimestampMs)")
            VitaDockResult.Success(readings)

        } catch (e: Exception) {
            Log.w(TAG, "fetch failed", e)
            VitaDockResult.Error(e.message ?: "unknown")
        }
    }

    // ── JSON parser ───────────────────────────────────────────────────────────

    private fun parseJson(json: String, afterMs: Long): List<VitaDockReading> {
        val results = mutableListOf<VitaDockReading>()
        val root = JSONObject(json)
        val arr = root.optJSONArray("bodyWeight") ?: return results
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("x", 0L)
            if (ts <= 0L || ts <= afterMs) continue
            val weight = floatOrNull(o, "y") ?: continue
            results += VitaDockReading(
                timestampMs = ts,
                weightKg = weight,
                bodyFatPct = floatOrNull(o, "bodyFat"),
                muscleMassPct = floatOrNull(o, "muscleMass"),
                bodyWaterPct = floatOrNull(o, "bodyWater"),
                boneMassKg = floatOrNull(o, "boneMass"),
                bmi = floatOrNull(o, "bmi"),
            )
        }
        return results
    }

    // Returns null when the key is absent, JSON null, or non-numeric (0 stays 0).
    private fun floatOrNull(o: JSONObject, key: String): Float? {
        if (!o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d.toFloat()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun get(
        url: String,
        ua: String,
        jar: MutableMap<String, String>,
        followRedirects: Boolean = true,
        referer: String? = null,
        xhr: Boolean = false,
    ): String {
        var currentUrl = url
        repeat(10) {
            val conn = openConn(currentUrl, ua, jar, referer, xhr)
            conn.instanceFollowRedirects = false
            conn.connect()
            val status = conn.responseCode
            collectCookies(conn, jar)
            if (status in 301..302) {
                val loc = conn.getHeaderField("Location") ?: return ""
                conn.disconnect()
                currentUrl = if (loc.startsWith("http")) loc else "$BASE$loc"
                if (!followRedirects) return ""
                return@repeat
            }
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            return body
        }
        return ""
    }

    private fun post(
        url: String,
        body: String,
        referer: String,
        ua: String,
        jar: MutableMap<String, String>,
    ) {
        val conn = openConn(url, ua, jar, referer)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.instanceFollowRedirects = false
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.connect()
        collectCookies(conn, jar)
        conn.disconnect()
    }

    private fun openConn(
        url: String,
        ua: String,
        jar: MutableMap<String, String>,
        referer: String? = null,
        xhr: Boolean = false,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept", if (xhr) "application/json, text/javascript, */*" else "text/html,application/xhtml+xml,*/*")
        conn.setRequestProperty("Accept-Language", "en")
        if (xhr) conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        if (jar.isNotEmpty()) {
            conn.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return conn
    }

    private fun collectCookies(conn: HttpURLConnection, jar: MutableMap<String, String>) {
        var i = 1
        while (true) {
            val header = conn.getHeaderFieldKey(i) ?: break
            if (header.equals("Set-Cookie", ignoreCase = true)) {
                val value = conn.getHeaderField(i) ?: ""
                val nameValue = value.split(";")[0].trim()
                val eq = nameValue.indexOf('=')
                if (eq > 0) jar[nameValue.substring(0, eq)] = nameValue.substring(eq + 1)
            }
            i++
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
