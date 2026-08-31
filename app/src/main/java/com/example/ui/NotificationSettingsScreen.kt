package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.notifications.NotificationPrefs
import com.example.data.notifications.ProactiveNudgeWorker
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft

/**
 * Bloco H — User-facing settings for the proactive notification engine.
 *
 * Reads/writes [NotificationPrefs] directly on every interaction; there is no
 * "Save" button (matches the rest of the app's convention of immediate
 * persistence). When the master switch is off, the four per-trigger toggles
 * and the quiet-hours rows are greyed out, but the test button stays live so
 * the user always has an escape hatch to verify a fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val language = remember {
        ctx.getSharedPreferences("user_profile_pref", Context.MODE_PRIVATE)
            .getString("profile_language", "pt-PT") ?: "pt-PT"
    }
    var prefs by remember { mutableStateOf(NotificationPrefs.load(ctx)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun update(newPrefs: NotificationPrefs) {
        prefs = newPrefs
        NotificationPrefs.save(ctx, newPrefs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Text(
                text = tr("notification_preferences_title", language),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Divider(color = Color(0xFF334155))

            // Master switch — drives the enabled state of everything else.
            SettingsToggleRow(
                label = tr("notification_master", language),
                checked = prefs.masterEnabled,
                enabled = true,
                onChange = { update(prefs.copy(masterEnabled = it)) }
            )

            Divider(color = Color(0xFF334155))

            // Section heading: per-trigger toggles
            Text(
                text = tr("notification_triggers", language),
                color = AccentSoft,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )

            SettingsToggleRow(
                label = tr("notification_trigger_meal_missing", language),
                checked = prefs.mealMissingEnabled,
                enabled = prefs.masterEnabled,
                onChange = { update(prefs.copy(mealMissingEnabled = it)) }
            )
            SettingsToggleRow(
                label = tr("notification_trigger_activity_spike", language),
                checked = prefs.activitySpikeEnabled,
                enabled = prefs.masterEnabled,
                onChange = { update(prefs.copy(activitySpikeEnabled = it)) }
            )
            SettingsToggleRow(
                label = tr("notification_trigger_macros_risk", language),
                checked = prefs.macrosAtRiskEnabled,
                enabled = prefs.masterEnabled,
                onChange = { update(prefs.copy(macrosAtRiskEnabled = it)) }
            )
            SettingsToggleRow(
                label = tr("notification_trigger_daily_summary", language),
                checked = prefs.dailySummaryEnabled,
                enabled = prefs.masterEnabled,
                onChange = { update(prefs.copy(dailySummaryEnabled = it)) }
            )

            Divider(color = Color(0xFF334155))

            // Section heading: quiet hours
            Text(
                text = tr("notification_quiet_hours", language),
                color = AccentSoft,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )

            QuietHourRow(
                label = tr("notification_quiet_start", language),
                hour = prefs.quietStartHour,
                enabled = prefs.masterEnabled,
                onPick = { newHour -> update(prefs.copy(quietStartHour = newHour)) }
            )
            QuietHourRow(
                label = tr("notification_quiet_end", language),
                hour = prefs.quietEndHour,
                enabled = prefs.masterEnabled,
                onPick = { newHour -> update(prefs.copy(quietEndHour = newHour)) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Test-notification button. Stays enabled even when master is off,
            // so the user can always verify plumbing.
            Button(
                onClick = {
                    val req = OneTimeWorkRequestBuilder<ProactiveNudgeWorker>()
                        .setInputData(
                            workDataOf(ProactiveNudgeWorker.INPUT_FORCE_TRIGGER_NOW to true)
                        )
                        .build()
                    WorkManager.getInstance(ctx).enqueue(req)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
            ) {
                Text(tr("notification_test", language), color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Single row: label on the left, [Switch] on the right. */
@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val labelColor = if (enabled) Color.White else Color(0xFF64748B)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentPrimary,
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = Color(0xFF334155),
                disabledCheckedThumbColor = Color(0xFF475569),
                disabledCheckedTrackColor = Color(0xFF1E293B),
                disabledUncheckedThumbColor = Color(0xFF475569),
                disabledUncheckedTrackColor = Color(0xFF1E293B)
            )
        )
    }
}

/**
 * Clickable row that shows `label` + the current `hour`. Tapping opens an
 * Android [android.app.TimePickerDialog] in 24h mode. Minutes are ignored —
 * we only ever store hours. Wrap-around values (e.g. start=23, end=7) are
 * accepted by [NotificationPrefs.isQuiet] so no validation is needed here.
 */
@Composable
private fun QuietHourRow(
    label: String,
    hour: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val textColor = if (enabled) Color.White else Color(0xFF64748B)
    val hourLabel = String.format("%02d:00", hour)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B))
            .clickable(enabled = enabled) {
                android.app.TimePickerDialog(
                    ctx,
                    { _, hourOfDay, _ -> onPick(hourOfDay) },
                    hour,
                    0,
                    true
                ).show()
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = hourLabel,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
