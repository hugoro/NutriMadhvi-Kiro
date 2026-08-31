package com.example.data.location

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Coarse-grained (city-level) location helper used by the chat to inject
 * the IPMA weather forecast into the system prompt. We never request
 * FINE precision — IPMA grids are per-city, so COARSE (~2km radius) is
 * enough. The runtime prompt is fired from [ChatScreen] the first time
 * the user lands on the Chat tab; if the user denies, every call here
 * returns the last cached fix (which may be null) and the weather block
 * is silently omitted from the prompt.
 *
 * Persisted state lives in `location_prefs`:
 *  - `lat` / `lon` — Float; the most recent COARSE fix
 *  - `tsMs` — Long; epoch ms of that fix; used to age out stale values
 *    after 24h before forcing a refresh
 */
class LocationHelper(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the user granted `ACCESS_COARSE_LOCATION` (or the finer
     *  `ACCESS_FINE_LOCATION` which is a superset). The manifest only
     *  declares COARSE. */
    fun hasPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    /** Latest cached fix, if any. Used as fallback when a fresh request
     *  fails (no GPS lock, airplane mode, etc.). */
    fun cached(): CachedFix? {
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        val lat = prefs.getFloat(KEY_LAT, Float.NaN)
        val lon = prefs.getFloat(KEY_LON, Float.NaN)
        val ts = prefs.getLong(KEY_TS, 0L)
        if (lat.isNaN() || lon.isNaN()) return null
        return CachedFix(lat = lat.toDouble(), lon = lon.toDouble(), tsMs = ts)
    }

    /**
     * Try to obtain a COARSE-accuracy fix. Returns the freshest available
     * fix (live or cached) or null on failure / no permission. The
     * Play-services call falls back to `getLastLocation` when no live
     * provider is active — which is exactly what we want for a per-city
     * weather lookup that is OK with a 30-minute stale value.
     *
     * Never throws — failures resolve to null and the caller falls back
     * to omitting the [CONTEXTO LOCAL] block.
     */
    suspend fun current(): CachedFix? {
        if (!hasPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)

        // 1. Try a live fix (uses cell+wifi for COARSE on most devices,
        //    so it's fast and battery-cheap).
        //
        // 2026-07-06 (Frente B) — hard 1.2s cap. Play Services' `getCurrentLocation`
        // is documented as returning "as soon as possible" but has been observed
        // to hang for >5s when GNSS is cold, the device is indoors, and no
        // wifi is available. A stall here used to block the entire chat send
        // because `buildLocalContextBlock` awaited this inline. Even with the
        // prefetch, a slow fix would starve the prefetch coroutine and leave
        // `_localContextBlock` empty for the whole session. Cap it and fall
        // back to whatever [cached] returns.
        val live: Location? = withTimeoutOrNull(1200L) {
            try {
                suspendCancellableCoroutine { cont ->
                    @Suppress("MissingPermission")
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                }
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }

        if (live != null) {
            val fix = CachedFix(lat = live.latitude, lon = live.longitude, tsMs = System.currentTimeMillis())
            prefs.edit()
                .putFloat(KEY_LAT, fix.lat.toFloat())
                .putFloat(KEY_LON, fix.lon.toFloat())
                .putLong(KEY_TS, fix.tsMs)
                .apply()
            return fix
        }

        // 2. Live failed — fall back to the last cached fix (may be null).
        return cached()
    }

    data class CachedFix(val lat: Double, val lon: Double, val tsMs: Long)

    companion object {
        private const val PREFS_NAME = "location_prefs"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_TS = "tsMs"
    }
}
