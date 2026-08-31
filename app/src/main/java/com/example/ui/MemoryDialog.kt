package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.UserInsight
import com.example.ui.theme.AccentSoft

/**
 * 2026-06-29 — Frente Memory C4. Transparency screen for the
 * `user_insights` table.
 *
 * Layout (top to bottom):
 *  1. **Header** with title + close button.
 *  2. **Cross-session digest** (read-only block) so the user can see what
 *     short summary is being carried over between sessions. Empty when
 *     CarryoverDigest hasn't accumulated anything yet.
 *  3. **Category filter chips** — "Tudo / preference / allergy / goal /
 *     professional / aggregate_pattern / other". Tapping one narrows
 *     every section below.
 *  4. **Pending** — insights queued for confirmation (`auto_extracted`
 *     or `llm_inferred` rows with `confirmedAtMs == null`). Confirm
 *     joins the recall pool; Discard soft-deletes.
 *  5. **Active** — confirmed non-expired insights, sorted by recency
 *     (lastReferencedAtMs DESC, then createdAtMs DESC). Lixeira icon
 *     soft-deletes.
 *  6. **Forgotten** (collapsed by default) — soft-deleted rows. Each
 *     has a Restore icon that clears `expiredAtMs` so the fact rejoins
 *     the active pool. NEVER hard-deletes anything.
 *
 * Design constraint (Hugo, 2026-06-29 directive "mantém os dados"):
 * nothing here HARD-deletes — every removal is a soft-delete via
 * `expiredAtMs`. Restore is the inverse. Edit-in-place is intentionally
 * absent: to "correct" a fact the user soft-deletes the wrong one and
 * lets the assistant propose a new one next turn. Append-only semantics,
 * same as `target_snapshots`.
 */
@Composable
fun MemoryDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val all by viewModel.allInsightsIncludingExpired.collectAsState()
    val lang = userProfile.language

    // 2026-06-29 — capture the carryover digest once when the dialog
    // opens. SharedPreferences-backed, doesn't emit on change, so a
    // simple `remember` is enough; reopening the dialog re-reads it.
    val carryover = remember { viewModel.readCarryoverDigest() }

    // 2026-06-29 — category filter chip state. "all" is the default.
    var selectedCategory by remember { mutableStateOf("all") }

    // 2026-06-29 — "Forgotten" collapsible state. Default collapsed
    // because most users want to see active facts first; the audit-
    // trail view is an opt-in.
    var forgottenExpanded by remember { mutableStateOf(false) }

    // Derive the three buckets from the unified flow. Filtering by
    // category happens AFTER the bucket split so the counts in each
    // section reflect "in this category" rather than "globally".
    val pending = remember(all, selectedCategory) {
        all.filter { it.confirmedAtMs == null && it.expiredAtMs == null }
            .filter { selectedCategory == "all" || it.category.equals(selectedCategory, ignoreCase = true) }
    }
    val active = remember(all, selectedCategory) {
        all.filter { it.confirmedAtMs != null && it.expiredAtMs == null }
            .filter { selectedCategory == "all" || it.category.equals(selectedCategory, ignoreCase = true) }
            .sortedWith(
                compareByDescending<UserInsight> { it.lastReferencedAtMs ?: 0L }
                    .thenByDescending { it.createdAtMs }
            )
    }
    val forgotten = remember(all, selectedCategory) {
        all.filter { it.expiredAtMs != null }
            .filter { selectedCategory == "all" || it.category.equals(selectedCategory, ignoreCase = true) }
            .sortedByDescending { it.expiredAtMs ?: 0L }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 700.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Header ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("What I remember about you", lang),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = tr("Close", lang),
                            tint = Color(0xFF94A3B8),
                        )
                    }
                }
                Text(
                    text = tr(
                        "Confirmed facts feed every chat. Pending ones wait for your OK.",
                        lang,
                    ),
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                )

                // ── Cross-session carryover digest (read-only) ──
                if (carryover.isNotBlank()) {
                    Divider(color = Color(0xFF334155))
                    SectionHeader(
                        title = tr("Recent context", lang),
                        count = 0,
                        showCount = false,
                    )
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = carryover,
                            color = Color(0xFFCBD5E1),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // ── Category filter chips ──
                Divider(color = Color(0xFF334155))
                val categories = listOf(
                    "all",
                    "preference",
                    "allergy",
                    "goal",
                    "professional",
                    "aggregate_pattern",
                    "other",
                )
                // Wrap the chips so they flow on small screens. We use a
                // simple Row with horizontal scroll since Compose's
                // FlowRow API is still experimental in some versions.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    categories.forEach { cat ->
                        CategoryFilterChip(
                            category = cat,
                            selected = cat == selectedCategory,
                            onClick = { selectedCategory = cat },
                            lang = lang,
                        )
                    }
                }

                // ── Pending section ──
                if (pending.isNotEmpty()) {
                    Divider(color = Color(0xFF334155))
                    SectionHeader(
                        title = tr("Pending — confirm to remember", lang),
                        count = pending.size,
                    )
                    pending.forEach { ins ->
                        PendingInsightRow(
                            insight = ins,
                            onConfirm = { viewModel.confirmInsight(ins.id) },
                            onDiscard = { viewModel.discardInsight(ins.id) },
                            lang = lang,
                        )
                    }
                }

                // ── Active section ──
                Divider(color = Color(0xFF334155))
                SectionHeader(
                    title = tr("Memorized — active in every chat", lang),
                    count = active.size,
                )
                if (active.isEmpty()) {
                    Text(
                        tr("Nothing memorized yet.", lang),
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    active.forEach { ins ->
                        ActiveInsightRow(
                            insight = ins,
                            onForget = { viewModel.discardInsight(ins.id) },
                            lang = lang,
                        )
                    }
                }

                // ── Forgotten (collapsible) ──
                if (forgotten.isNotEmpty()) {
                    Divider(color = Color(0xFF334155))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { forgottenExpanded = !forgottenExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (forgottenExpanded) Icons.Rounded.KeyboardArrowDown
                                else Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AccentSoft,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr("Forgotten", lang),
                            color = AccentSoft,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = forgotten.size.toString(),
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (forgottenExpanded) {
                        forgotten.forEach { ins ->
                            ForgottenInsightRow(
                                insight = ins,
                                onRestore = { viewModel.restoreInsight(ins.id) },
                                lang = lang,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, showCount: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = AccentSoft,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (showCount) {
            Text(
                text = count.toString(),
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CategoryFilterChip(
    category: String,
    selected: Boolean,
    onClick: () -> Unit,
    lang: String,
) {
    val (bg, fg) = if (selected) {
        AccentSoft to Color.White
    } else {
        Color(0xFF0F172A) to Color(0xFF94A3B8)
    }
    val label = if (category == "all") tr("All", lang) else category
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PendingInsightRow(
    insight: UserInsight,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    lang: String,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(insight.category)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = (insight.source ?: "?"),
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = insight.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onConfirm,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = tr("Confirm", lang),
                        tint = Color(0xFF22C55E),
                    )
                }
                IconButton(
                    onClick = onDiscard,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = tr("Discard", lang),
                        tint = Color(0xFFEF4444),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveInsightRow(
    insight: UserInsight,
    onForget: () -> Unit,
    lang: String,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CategoryChip(insight.category)
                Text(
                    text = insight.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (insight.mentionCount > 1) {
                        Text(
                            text = tr("mentions: ", lang) + insight.mentionCount,
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                        )
                    }
                    if (insight.confidence < 1.0) {
                        Text(
                            text = "conf ${(insight.confidence * 100).toInt()}%",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            IconButton(
                onClick = onForget,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = tr("Forget", lang),
                    tint = Color(0xFF94A3B8),
                )
            }
        }
    }
}

/** 2026-06-29 — Memory polish. Read-only view of a soft-deleted insight,
 *  with a Restore button (sets `expiredAtMs` back to null so the row
 *  rejoins the active pool). The audit trail is preserved either way:
 *  the row was never DELETEd from SQL. */
@Composable
private fun ForgottenInsightRow(
    insight: UserInsight,
    onRestore: () -> Unit,
    lang: String,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CategoryChip(insight.category)
                Text(
                    text = insight.text,
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(
                onClick = onRestore,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = tr("Restore", lang),
                    tint = AccentSoft,
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(category: String) {
    val (bg, fg) = when (category.lowercase()) {
        "allergy" -> Color(0x33EF4444) to Color(0xFFEF4444)
        "preference" -> Color(0x3322C55E) to Color(0xFF22C55E)
        "goal" -> Color(0x33EAB308) to Color(0xFFEAB308)
        "professional" -> Color(0x336366F1) to Color(0xFF818CF8)
        "aggregate_pattern" -> Color(0x3306B6D4) to Color(0xFF22D3EE)
        else -> Color(0x33334155) to Color(0xFF94A3B8)
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = category.lowercase(),
            color = fg,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
