package com.example.ui

import com.example.ui.theme.AccentInk
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.TargetSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Audit dialog: lists every prescribed-target change in chronological order.
 * Lets the user understand why a past report shows different "meta" numbers
 * than today's profile — each row is the snapshot that was in force from its
 * effective date until the next row's. Append-only by design; nothing in the
 * UI deletes or rewrites past rows.
 */
@Composable
fun TargetHistoryDialog(
    snapshots: List<TargetSnapshot>,
    language: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = tr("Histórico de Metas", language),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = tr(
                        "Cada linha é uma meta que vigorou a partir da data indicada. Reports passados são comparados contra a meta dessa data, não contra a actual.",
                        language
                    ),
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall
                )

                if (snapshots.isEmpty()) {
                    Text(
                        text = tr("Sem alterações registadas ainda.", language),
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    // Newest first so the user sees the current target on top.
                    val sorted = remember(snapshots) {
                        snapshots.sortedWith(
                            compareByDescending<TargetSnapshot> { it.effectiveDateEpochDay }
                                .thenByDescending { it.id }
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sorted, key = { it.id }) { snap ->
                            TargetSnapshotRow(snap, language)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) {
                        Text(tr("Fechar", language))
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetSnapshotRow(snap: TargetSnapshot, language: String) {
    val effectiveDate = remember(snap.effectiveDateEpochDay) {
        LocalDate.ofEpochDay(snap.effectiveDateEpochDay)
    }
    val dateLabel = remember(effectiveDate) {
        // epoch day 0 (1970-01-01) is the seed sentinel — show it as "histórico"
        // instead of a literal date so the user isn't confused.
        if (effectiveDate == LocalDate.ofEpochDay(0L)) "—"
        else effectiveDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (dateLabel == "—") tr("Inicial (importado)", language) else dateLabel,
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${snap.targetKcal} kcal",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "P ${snap.targetProtein.toInt()}g · " +
                    "C ${snap.targetCarbs.toInt()}g · " +
                    "G ${snap.targetFat.toInt()}g",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "${snap.targetSteps} ${tr("passos", language)} · " +
                    "${snap.targetActiveMinutes} ${tr("min", language)} · " +
                    "${snap.targetActivityCalories} kcal",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
            if (!snap.note.isNullOrBlank()) {
                Text(
                    text = snap.note,
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
