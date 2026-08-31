package com.example.data.notifications

import android.content.Context

/**
 * Persistent dedup store for proactive notifications.
 *
 * Each key maps to the epoch-millis timestamp at which the corresponding notification
 * was last delivered. [wasSent] is the gate the worker checks before firing again, and
 * [gc] sweeps entries older than 10 days so the file stays small.
 *
 * Key conventions (callers MUST follow these so triggers don't collide):
 *  - `meal_missing:<CAT>:<iso-date>`     where `<CAT>` ∈ {`BREAKFAST`, `LUNCH`, `DINNER`}
 *  - `activity_spike:<iso-date>-<hour>`  e.g. `activity_spike:2026-06-22-15`
 *  - `macros_at_risk:<iso-date>`
 *  - `daily_summary:<iso-date>`
 *
 * Backed by `SharedPreferences("notification_dedup_pref")` — keep this name in sync with
 * any debug tooling that dumps the file.
 */
object NotificationDedupStore {

    private const val PREFS = "notification_dedup_pref"
    private const val TTL_DAYS = 10L
    private const val TTL_MS = TTL_DAYS * 24L * 60L * 60L * 1000L

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True iff [key] has a recorded send timestamp greater than 0. */
    fun wasSent(ctx: Context, key: String): Boolean {
        return prefs(ctx).getLong(key, 0L) > 0L
    }

    /** Record that the notification keyed by [key] was sent at [nowMs]. */
    fun markSent(ctx: Context, key: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putLong(key, nowMs).apply()
    }

    /** Drop every entry whose timestamp is older than [TTL_DAYS] days before [nowMs]. */
    fun gc(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        val sp = prefs(ctx)
        val cutoff = nowMs - TTL_MS
        val editor = sp.edit()
        var changed = false
        for ((k, v) in sp.all) {
            val ts = (v as? Long) ?: continue
            if (ts < cutoff) {
                editor.remove(k)
                changed = true
            }
        }
        if (changed) editor.apply()
    }
}
