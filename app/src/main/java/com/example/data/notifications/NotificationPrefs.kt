package com.example.data.notifications

import android.content.Context

/**
 * User-facing preferences for the proactive notification engine.
 *
 * Persisted in [PREFS] SharedPreferences. Single source of truth for the
 * master switch, per-trigger toggles and quiet-hours window. Other components
 * (worker, settings screen) read/write via [load]/[save].
 */
data class NotificationPrefs(
    val masterEnabled: Boolean = true,
    /** Off by default — meal-missing nudges are explicitly opt-in (Hugo, 2026-06-22):
     *  they read as pressure unless the user actively asks for them. The settings
     *  screen surfaces the toggle so users who want the reminder can flip it. */
    val mealMissingEnabled: Boolean = false,
    val activitySpikeEnabled: Boolean = true,
    /** Off by default — same rationale as [mealMissingEnabled]. Macros-at-risk
     *  triggers in the post-18:00 window and a low-protein-day late nudge was
     *  flagged as too prescriptive. Opt-in. */
    val macrosAtRiskEnabled: Boolean = false,
    val dailySummaryEnabled: Boolean = true,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
) {
    /**
     * True when [hourOfDay] (0..23) falls inside the quiet window.
     *
     * Window is inclusive on start, exclusive on end, with wrap-around when
     * [quietStartHour] > [quietEndHour] (e.g. 23..7 ⇒ 23, 0, 1, 2, 3, 4, 5, 6).
     * When start == end the window is empty (never quiet).
     */
    fun isQuiet(hourOfDay: Int): Boolean {
        if (quietStartHour == quietEndHour) return false
        return if (quietStartHour < quietEndHour) {
            hourOfDay in quietStartHour until quietEndHour
        } else {
            hourOfDay >= quietStartHour || hourOfDay < quietEndHour
        }
    }

    companion object {
        const val PREFS = "notification_pref"

        const val K_MASTER = "master_enabled"
        const val K_MEAL_MISSING = "meal_missing_enabled"
        const val K_ACTIVITY_SPIKE = "activity_spike_enabled"
        const val K_MACROS_RISK = "macros_at_risk_enabled"
        const val K_DAILY_SUMMARY = "daily_summary_enabled"
        const val K_QUIET_START = "quiet_start_hour"
        const val K_QUIET_END = "quiet_end_hour"

        /** Schema version of the prefs file. Bumped when default changes need
         *  to be retro-applied to installs that already wrote the old values
         *  (SharedPreferences has no schema migration mechanism — we roll
         *  our own with this counter). */
        const val K_PREFS_VERSION = "prefs_version"
        private const val CURRENT_PREFS_VERSION = 2

        fun load(context: Context): NotificationPrefs {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // v1 → v2 migration (2026-06-22): meal-missing & macros-at-risk
            // moved from default-on to default-off. Users already had those
            // toggles persisted as `true` because the old default wrote them
            // back via NotificationPrefs.save. Reset them here ONCE so the
            // new default takes effect, then bump the version so we never
            // overwrite the user's choice again.
            val storedVersion = sp.getInt(K_PREFS_VERSION, 1)
            if (storedVersion < 2) {
                sp.edit()
                    .putBoolean(K_MEAL_MISSING, false)
                    .putBoolean(K_MACROS_RISK, false)
                    .putInt(K_PREFS_VERSION, CURRENT_PREFS_VERSION)
                    .apply()
            }

            val defaults = NotificationPrefs()
            return NotificationPrefs(
                masterEnabled = sp.getBoolean(K_MASTER, defaults.masterEnabled),
                mealMissingEnabled = sp.getBoolean(K_MEAL_MISSING, defaults.mealMissingEnabled),
                activitySpikeEnabled = sp.getBoolean(K_ACTIVITY_SPIKE, defaults.activitySpikeEnabled),
                macrosAtRiskEnabled = sp.getBoolean(K_MACROS_RISK, defaults.macrosAtRiskEnabled),
                dailySummaryEnabled = sp.getBoolean(K_DAILY_SUMMARY, defaults.dailySummaryEnabled),
                quietStartHour = sp.getInt(K_QUIET_START, defaults.quietStartHour),
                quietEndHour = sp.getInt(K_QUIET_END, defaults.quietEndHour),
            )
        }

        fun save(context: Context, prefs: NotificationPrefs) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_MASTER, prefs.masterEnabled)
                .putBoolean(K_MEAL_MISSING, prefs.mealMissingEnabled)
                .putBoolean(K_ACTIVITY_SPIKE, prefs.activitySpikeEnabled)
                .putBoolean(K_MACROS_RISK, prefs.macrosAtRiskEnabled)
                .putBoolean(K_DAILY_SUMMARY, prefs.dailySummaryEnabled)
                .putInt(K_QUIET_START, prefs.quietStartHour)
                .putInt(K_QUIET_END, prefs.quietEndHour)
                // Carry the current version on every save so a save before
                // the first migrating load still leaves the file consistent.
                .putInt(K_PREFS_VERSION, CURRENT_PREFS_VERSION)
                .apply()
        }
    }
}
