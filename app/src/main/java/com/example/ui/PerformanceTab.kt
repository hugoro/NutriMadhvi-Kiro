package com.example.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.PerformanceSnapshotEntity
import com.example.data.remote.ChartKpi
import com.example.data.remote.ChartLine
import com.example.ui.chat.MultilineChart
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Silhouette ratio KPIs for the evolution chart ───────────────────────────

private enum class SilhouetteKpi(
    val label: String,
    val pick: (PerformanceSnapshotEntity) -> Float?
) {
    FRONTAL_SHOULDER("Ombros", { it.frontalShoulderRatio }),
    FRONTAL_WAIST("Cintura", { it.frontalWaistRatio }),
    FRONTAL_HIP("Ancas", { it.frontalHipRatio }),
    FRONTAL_THIGH("Coxas", { it.frontalThighRatio }),
    LATERAL_CHEST("Peito (lat)", { it.lateralChestRatio }),
    LATERAL_BELLY("Barriga (lat)", { it.lateralBellyRatio }),
    LATERAL_THIGH("Coxa (lat)", { it.lateralThighRatio }),
}

// ─── Legacy joint KPIs (kept for backward compat with v35 angle-only snapshots)

private enum class MobilityKpi(
    val label: String,
    val unit: String,
    val pick: (PerformanceSnapshotEntity) -> Float?
) {
    LEFT_SHOULDER_SAG("Ombro E (Sag)", "°", { it.leftShoulderSagittalDeg }),
    LEFT_SHOULDER_FRO("Ombro E (Fro)", "°", { it.leftShoulderFrontalDeg }),
    RIGHT_SHOULDER_SAG("Ombro D (Sag)", "°", { it.rightShoulderSagittalDeg }),
    RIGHT_SHOULDER_FRO("Ombro D (Fro)", "°", { it.rightShoulderFrontalDeg }),
    LEFT_HIP_SAG("Anca E (Sag)", "°", { it.leftHipSagittalDeg }),
    LEFT_HIP_FRO("Anca E (Fro)", "°", { it.leftHipFrontalDeg }),
    RIGHT_HIP_SAG("Anca D (Sag)", "°", { it.rightHipSagittalDeg }),
    RIGHT_HIP_FRO("Anca D (Fro)", "°", { it.rightHipFrontalDeg }),
    LEFT_ELBOW_SAG("Cotovelo E", "°", { it.leftElbowSagittalDeg }),
    RIGHT_ELBOW_SAG("Cotovelo D", "°", { it.rightElbowSagittalDeg }),
    LEFT_KNEE_SAG("Joelho E", "°", { it.leftKneeSagittalDeg }),
    RIGHT_KNEE_SAG("Joelho D", "°", { it.rightKneeSagittalDeg }),
}

private val DATE_FMT = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

// ─── Body plan UI (v39) ──────────────────────────────────────────────────────

/** Internal segmented navigation of the Performance tab. */
private enum class PerfTab { OVERVIEW, PLAN, PROGRESS }

private data class ParsedPriority(
    val zone: String,
    val direction: String, // REDUCE | GROW | HOLD
    val gapPp: Float,
    val tissue: String,
)

private data class ParsedTactic(
    val category: String,
    val text: String,
    val target: String?,
    val windowDays: Int,
)

/** Fail-soft parsers for the persisted JSON payloads (never throw). */
private fun parsePriorities(json: String?): List<ParsedPriority> = try {
    val arr = org.json.JSONArray(json ?: return emptyList())
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val zone = o.optString("zone", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ParsedPriority(
            zone = zone,
            direction = o.optString("direction", "HOLD"),
            gapPp = o.optDouble("gapPp", 0.0).toFloat(),
            tissue = o.optString("tissue", "UNKNOWN"),
        )
    }
} catch (e: Exception) {
    emptyList()
}

private fun parseTactics(json: String?): List<ParsedTactic> = try {
    val arr = org.json.JSONArray(json ?: return emptyList())
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val text = o.optString("text", "").trim().takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        ParsedTactic(
            category = o.optString("category", ""),
            text = text,
            target = o.optString("target", "").trim().takeIf { it.isNotBlank() },
            windowDays = o.optInt("windowDays", 28),
        )
    }
} catch (e: Exception) {
    emptyList()
}

@Composable
private fun PerfSegmentedControl(
    selected: PerfTab,
    onSelect: (PerfTab) -> Unit,
    lang: String,
) {
    val labels = listOf(
        PerfTab.OVERVIEW to tr("perf_tab_overview", lang),
        PerfTab.PLAN to tr("perf_tab_plan", lang),
        PerfTab.PROGRESS to tr("perf_tab_progress", lang),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { (tab, label) ->
            val isSelected = tab == selected
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) AccentPrimary else Color(0xFF1A1F2E),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(tab) },
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun PhaseBadge(phase: String?, lang: String) {
    if (phase.isNullOrBlank()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2A3350),
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Text(
                tr("phase_unknown", lang),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        return
    }
    val (label, color) = when (phase) {
        "CUT" -> tr("phase_cut", lang) to Color(0xFF38BDF8)
        "BULK" -> tr("phase_bulk", lang) to Color(0xFF4ADE80)
        "RECOMP" -> tr("phase_recomp", lang) to Color(0xFFA78BFA)
        else -> tr("phase_maintain", lang) to Color(0xFF94A3B8)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Plano (v39): latest body-plan checkpoint — phase badge, top-3 priority
 * zones (safety-gated directions), tactics list with source label, and a
 * retry that re-runs the LLM enrichment. Deterministic core is always
 * present; only the LLM parts can be pending/missing.
 */
@Composable
private fun BodyPlanView(
    checkpoints: List<com.example.data.local.BodyPlanCheckpointEntity>,
    hasGoalTargets: Boolean,
    lang: String,
    onRetry: (Long) -> Unit,
) {
    val cp = checkpoints.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1F2E), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (cp == null) {
            Text(
                tr("plan_no_checkpoint", lang),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
            )
            return
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("plan_phase", lang), color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            PhaseBadge(cp.phase, lang)
            Text(
                DATE_FMT.format(Date(cp.checkpointAtMs)),
                color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
            )
        }

        // ── Priorities ────────────────────────────────────────────────────────
        val priorities = parsePriorities(cp.prioritiesJson)
        if (priorities.isEmpty() && !hasGoalTargets) {
            Text(
                tr("plan_no_targets", lang),
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            )
        } else if (priorities.isNotEmpty()) {
            Text(tr("plan_priorities", lang),
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            priorities.forEach { p ->
                val (dirLabel, dirColor) = when (p.direction) {
                    "REDUCE" -> tr("plan_direction_reduce", lang) to Color(0xFFF87171)
                    "GROW" -> tr("plan_direction_grow", lang) to Color(0xFF4ADE80)
                    else -> tr("plan_direction_hold", lang) to Color(0xFF94A3B8)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF12172A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("zone_${p.zone}", lang),
                        color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${if (p.gapPp >= 0) "+" else ""}${p.gapPp.roundTo1Ui()}pp",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                    )
                    Text(
                        dirLabel,
                        color = dirColor, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ── Tactics ───────────────────────────────────────────────────────────
        val tactics = parseTactics(cp.tacticsJson)
        Text(tr("plan_tactics", lang),
            color = Color.White.copy(alpha = 0.6f),
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        if (tactics.isEmpty()) {
            Text(
                tr("plan_tactics_pending", lang),
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            )
        } else {
            tactics.forEach { t ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(t.text, color = Color.White, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.target?.let {
                            Text(it, color = AccentSoft, fontSize = 11.sp)
                        }
                        Text(
                            tr("plan_window_days", lang).replace("{days}", "${t.windowDays}"),
                            color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
                        )
                    }
                }
            }
            val sourceLabel = if (cp.tacticSource == "llm")
                tr("plan_tactics_source_llm", lang)
            else tr("plan_tactics_source_fallback", lang)
            Text(
                sourceLabel,
                color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // ── Retry (only when an LLM part is missing / fallback) ───────────────
        val needsEnrichment = cp.tacticSource != "llm" || cp.coachReport == null
        if (needsEnrichment) {
            OutlinedButton(
                onClick = { onRetry(cp.id) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(tr("plan_retry", lang), fontSize = 12.sp)
            }
        }
    }
}

/**
 * Progresso (v39): checkpoint timeline — date, phase, delta summary,
 * per-checkpoint report with its own retry.
 */
@Composable
private fun BodyProgressView(
    checkpoints: List<com.example.data.local.BodyPlanCheckpointEntity>,
    lang: String,
    onRetry: (Long) -> Unit,
) {
    if (checkpoints.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1F2E),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                tr("progress_empty", lang),
                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        checkpoints.forEach { cp ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1F2E), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DATE_FMT.format(Date(cp.checkpointAtMs)),
                        color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    PhaseBadge(cp.phase, lang)
                }
                cp.deltaSummary?.let {
                    Text(
                        tr("plan_delta_label", lang) + ": " + it,
                        color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                    )
                }
                if (cp.coachReport != null) {
                    Text(
                        cp.coachReport,
                        color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("progress_report_pending", lang),
                            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                        )
                        TextButton(onClick = { onRetry(cp.id) }) {
                            Text(tr("plan_retry", lang), fontSize = 12.sp, color = AccentSoft)
                        }
                    }
                }
            }
        }
    }
}

private fun Float.roundTo1Ui(): String {
    val r = kotlin.math.round(this * 10f) / 10f
    return if (r == kotlin.math.floor(r)) r.toInt().toString() else r.toString()
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun PerformanceSnapshotEntity.hasSilhouetteData(): Boolean =
    frontalShoulderRatio != null || frontalWaistRatio != null ||
        lateralChestRatio != null || lateralBellyRatio != null

private fun PerformanceSnapshotEntity.hasAngleData(): Boolean =
    leftShoulderSagittalDeg != null || leftHipSagittalDeg != null

private fun toRatioChartLine(kpi: SilhouetteKpi, snaps: List<PerformanceSnapshotEntity>): ChartLine {
    val values = snaps.map { kpi.pick(it)?.toDouble() }
    val nonNull = values.filterNotNull()
    val chartKpi = if (nonNull.size >= 2) {
        val delta = nonNull.last() - nonNull.first()
        ChartKpi(latest = nonNull.last(), delta = delta,
            deltaTone = when {
                // for waist/belly, decrease = positive
                kpi == SilhouetteKpi.FRONTAL_WAIST || kpi == SilhouetteKpi.LATERAL_BELLY ->
                    if (delta < -0.005) "positive" else if (delta > 0.005) "negative" else "neutral"
                else -> if (delta > 0.005) "positive" else if (delta < -0.005) "negative" else "neutral"
            }
        )
    } else null
    return ChartLine(label = kpi.label, unit = "", values = values, kpi = chartKpi)
}

// ─── Collapsible card wrapper ─────────────────────────────────────────────────
// A Surface whose titled header toggles a chevron and shows/hides [content].
// Used for the secondary Performance cards (coach report, evolution, measures,
// goal) so the silhouette stays at the top and the rest can be folded away.

@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    surfaceColor: Color = Color(0xFF1A1F2E),
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = AccentSoft,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Fechar" else "Abrir",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

// ─── Main tab composable ──────────────────────────────────────────────────────

@Composable
fun PerformanceTab(viewModel: ChatViewModel) {
    val snapshots by viewModel.performanceSnapshots.collectAsStateWithLifecycle()
    val latestGoal by viewModel.latestPerformanceGoal.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val bodyPlanCheckpoints by viewModel.bodyPlanCheckpoints.collectAsStateWithLifecycle()
    var showAssessment by remember { mutableStateOf(false) }
    val lang = userProfile.language

    // Internal segmented navigation (v39): Visão geral | Plano | Progresso.
    var perfTab by rememberSaveable { mutableStateOf(PerfTab.OVERVIEW) }

    // Collapse state for the secondary cards (silhouette + history stay fixed).
    // Coach report starts expanded; the rest collapsed to keep the view compact.
    var coachExpanded     by rememberSaveable { mutableStateOf(true) }
    var evolutionExpanded by rememberSaveable { mutableStateOf(false) }
    var measuresExpanded  by rememberSaveable { mutableStateOf(false) }
    var goalExpanded      by rememberSaveable { mutableStateOf(false) }

    if (showAssessment) {
        SilhouetteAssessmentScreen(
            source = "manual",
            onConfirm = { entity ->
                viewModel.savePerformanceSnapshotAndRequestReport(entity)
                showAssessment = false
            },
            onDismiss = { showAssessment = false }
        )
        return
    }

    val hasGoalTargets = latestGoal?.coachTargetJson != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Performance", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Análise de silhueta e composição",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                }
                Button(
                    onClick = { showAssessment = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Avaliar agora", fontSize = 13.sp)
                }
            }
        }

        // ── Segmented navigation (v39) ────────────────────────────────────────
        item {
            PerfSegmentedControl(selected = perfTab, onSelect = { perfTab = it }, lang = lang)
        }

        // ── Plano (v39): latest body-plan checkpoint ──────────────────────────
        if (perfTab == PerfTab.PLAN) {
            item {
                BodyPlanView(
                    checkpoints = bodyPlanCheckpoints,
                    hasGoalTargets = hasGoalTargets,
                    lang = lang,
                    onRetry = { viewModel.requestCheckpointEnrichment(it) },
                )
            }
            return@LazyColumn
        }

        // ── Progresso (v39): checkpoint timeline ─────────────────────────────
        if (perfTab == PerfTab.PROGRESS) {
            item {
                BodyProgressView(
                    checkpoints = bodyPlanCheckpoints,
                    lang = lang,
                    onRetry = { viewModel.requestCheckpointEnrichment(it) },
                )
            }
            return@LazyColumn
        }

        // ── Overview (original content) ───────────────────────────────────────
        // ── Empty state ───────────────────────────────────────────────────────
        if (snapshots.isEmpty()) {
            item { EmptyPerformanceState(onStart = { showAssessment = true }) }
            return@LazyColumn
        }

        val latest = snapshots.lastOrNull()
        val silhouetteSnaps = snapshots.filter { it.hasSilhouetteData() }

        // 1 ── Silhouette gap card (primary, always on top, not collapsible) ────
        if (latest != null && latest.hasSilhouetteData()) {
            val isMale = !userProfile.gender.equals("Female", ignoreCase = true)
            item {
                SilhouetteGoalCard(
                    snap = latest,
                    latestGoal = latestGoal,
                    isMale = isMale,
                )
            }
        }

        // 2 ── Coach report (collapsible) ──────────────────────────────────────
        if (latest != null) {
            item {
                CollapsibleCard(
                    title = "Relatório Coach",
                    expanded = coachExpanded,
                    onToggle = { coachExpanded = !coachExpanded },
                    surfaceColor = Color(0xFF1E293B),
                    trailing = {
                        Text(DATE_FMT.format(Date(latest.capturedAtMs)),
                            color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp))
                    },
                ) {
                    CoachReportContent(
                        snap = latest,
                        onGenerateReport = { viewModel.requestCoachReport(latest) },
                    )
                }
            }
        }

        // 3 ── Silhouette evolution (collapsible) ──────────────────────────────
        if (silhouetteSnaps.size >= 2) {
            item {
                CollapsibleCard(
                    title = "Evolução da silhueta",
                    expanded = evolutionExpanded,
                    onToggle = { evolutionExpanded = !evolutionExpanded },
                ) {
                    SilhouetteEvolutionContent(silhouetteSnaps)
                }
            }
        } else if (snapshots.size >= 2 && latest?.hasAngleData() == true) {
            item {
                CollapsibleCard(
                    title = "Evolução (ângulos articulares)",
                    expanded = evolutionExpanded,
                    onToggle = { evolutionExpanded = !evolutionExpanded },
                ) {
                    LegacyAngleEvolutionContent(snapshots)
                }
            }
        }

        // 4 ── Silhouette measures (collapsible) ───────────────────────────────
        if (latest != null && latest.hasSilhouetteData()) {
            item {
                CollapsibleCard(
                    title = "Medidas de silhueta",
                    expanded = measuresExpanded,
                    onToggle = { measuresExpanded = !measuresExpanded },
                ) {
                    SilhouetteRatioBarsContent(latest)
                }
            }
        }

        // 5 ── Goal input (collapsible) ────────────────────────────────────────
        item {
            CollapsibleCard(
                title = "O teu objetivo",
                expanded = goalExpanded,
                onToggle = { goalExpanded = !goalExpanded },
                surfaceColor = Color(0xFF1A2236),
                trailing = {
                    if (latestGoal?.coachTargetJson != null) {
                        Text("✓", color = Color(0xFF4ADE80), fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                },
            ) {
                PerformanceGoalInputContent(
                    currentGoal = latestGoal,
                    onSave = { viewModel.savePerformanceGoal(it) },
                )
            }
        }

        // ── Backward compat: angle table for v35-only snapshots ───────────────
        if (latest != null && latest.hasAngleData() && !latest.hasSilhouetteData()) {
            item { LatestSnapshotCard(latest) }
        }

        // 6 ── History ─────────────────────────────────────────────────────────
        if (snapshots.size > 1) {
            item {
                Text("Avaliações anteriores",
                    color = Color.White.copy(alpha = 0.6f),
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            items(snapshots.dropLast(1).reversed()) { snap ->
                SnapshotHistoryRow(snap)
            }
        }
    }
}

// ─── Coach report content (inside CollapsibleCard) ────────────────────────────

@Composable
private fun CoachReportContent(
    snap: PerformanceSnapshotEntity,
    onGenerateReport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            snap.coachReport != null -> {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    RichText(
                        modifier = Modifier.fillMaxWidth(),
                        style = com.halilibo.richtext.ui.RichTextStyle(
                            stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                boldStyle = androidx.compose.ui.text.SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                ),
                                italicStyle = androidx.compose.ui.text.SpanStyle(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = Color.White
                                ),
                            ),
                        )
                    ) {
                        Markdown(content = snap.coachReport)
                    }
                }
            }
            snap.hasSilhouetteData() -> {
                // Pending report: generation runs async; if it failed (or the
                // app restarted), the spinner below would spin forever — so a
                // 'Gerar novamente' retry is always offered (v39 fix).
                Text("A gerar relatório...",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                LinearProgressIndicator(
                    color = AccentSoft,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onGenerateReport,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Gerar novamente", fontSize = 12.sp)
                }
            }
            else -> {
                Text(
                    "Faz uma avaliação com fotos frontal e lateral para gerar o relatório de coach.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onGenerateReport,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                ) {
                    Text("Gerar relatório", fontSize = 12.sp)
                }
            }
        }
    }
}

// ─── Silhouette ratio bars content (inside CollapsibleCard) ───────────────────

@Composable
private fun SilhouetteRatioBarsContent(snap: PerformanceSnapshotEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val frontalRows = listOfNotNull(
            snap.frontalShoulderRatio?.let { "Ombros (frontal)" to it },
            snap.frontalWaistRatio?.let    { "Cintura (frontal)" to it },
            snap.frontalHipRatio?.let      { "Ancas (frontal)" to it },
            snap.frontalThighRatio?.let    { "Coxas (frontal)" to it },
        )
        val lateralRows = listOfNotNull(
            snap.lateralChestRatio?.let   { "Peito (lateral)" to it },
            snap.lateralBellyRatio?.let   { "Barriga (lateral)" to it },
            snap.lateralThighRatio?.let   { "Coxa (lateral)" to it },
        )
        val allRows = frontalRows + lateralRows
        val maxVal = allRows.maxOfOrNull { it.second } ?: 1f

        allRows.forEach { (label, value) ->
            RatioBar(label = label, value = value, maxValue = maxVal)
        }
    }
}

@Composable
private fun RatioBar(label: String, value: Float, maxValue: Float) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
            Text("%.3f".format(value), color = AccentSoft, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(0.08f),
            RoundedCornerShape(3.dp))) {
            val fraction = (value / maxValue).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(AccentSoft, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ─── Silhouette evolution content (inside CollapsibleCard) ────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SilhouetteEvolutionContent(snaps: List<PerformanceSnapshotEntity>) {
    var selectedKpis by remember {
        mutableStateOf(setOf(SilhouetteKpi.FRONTAL_WAIST, SilhouetteKpi.FRONTAL_HIP))
    }
    Column {
        // KPI chips — FlowRow so chips wrap instead of overflowing
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SilhouetteKpi.entries.forEach { kpi ->
                val on = kpi in selectedKpis
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (on) AccentPrimary else Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.clickable {
                        selectedKpis = if (on) {
                            if (selectedKpis.size > 1) selectedKpis - kpi else selectedKpis
                        } else {
                            if (selectedKpis.size < 4) selectedKpis + kpi else selectedKpis
                        }
                    },
                ) {
                    Text(
                        kpi.label,
                        color = if (on) Color.White else Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val xLabels = snaps.map { DATE_FMT.format(Date(it.capturedAtMs)) }
        val lines = selectedKpis.map { toRatioChartLine(it, snaps) }
        if (lines.isNotEmpty()) {
            MultilineChart(lines = lines, xLabels = xLabels, showLatestValue = true)
        }
    }
}

// ─── Legacy angle chart content (v35 backward compat) ────────────────────────

@Composable
private fun LegacyAngleEvolutionContent(snaps: List<PerformanceSnapshotEntity>) {
    var selected by remember {
        mutableStateOf(setOf(MobilityKpi.LEFT_HIP_SAG, MobilityKpi.RIGHT_HIP_SAG))
    }
    Column {
        KpiSelector(selected = selected, onToggle = { kpi ->
            selected = if (kpi in selected) {
                if (selected.size > 1) selected - kpi else selected
            } else {
                if (selected.size < 4) selected + kpi else selected
            }
        })
        Spacer(Modifier.height(10.dp))
        val xLabels = snaps.map { DATE_FMT.format(Date(it.capturedAtMs)) }
        val lines = selected.map { kpi ->
            val values = snaps.map { kpi.pick(it)?.toDouble() }
            val nonNull = values.filterNotNull()
            val chartKpi = if (nonNull.size >= 2) {
                val delta = nonNull.last() - nonNull.first()
                ChartKpi(latest = nonNull.last(), delta = delta,
                    deltaTone = if (delta > 0.5) "positive" else if (delta < -0.5) "negative" else "neutral")
            } else null
            ChartLine(label = kpi.label, unit = kpi.unit, values = values, kpi = chartKpi)
        }
        if (lines.isNotEmpty()) {
            MultilineChart(lines = lines, xLabels = xLabels, showLatestValue = true)
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyPerformanceState(onStart: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.FitnessCenter, null, tint = AccentSoft,
                modifier = Modifier.size(48.dp))
            Text("Análise de silhueta", color = Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "Tira 2 fotos (frente + lado) para obter uma análise coach " +
                    "baseada nos teus dados de composição corporal.",
                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
            )
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Iniciar avaliação")
            }
        }
    }
}

// ─── Legacy joint angle selector (kept for v35 compat) ───────────────────────

@Composable
private fun KpiSelector(selected: Set<MobilityKpi>, onToggle: (MobilityKpi) -> Unit) {
    val joints = listOf(
        "Ombro" to listOf(MobilityKpi.LEFT_SHOULDER_SAG, MobilityKpi.LEFT_SHOULDER_FRO,
            MobilityKpi.RIGHT_SHOULDER_SAG, MobilityKpi.RIGHT_SHOULDER_FRO),
        "Anca" to listOf(MobilityKpi.LEFT_HIP_SAG, MobilityKpi.LEFT_HIP_FRO,
            MobilityKpi.RIGHT_HIP_SAG, MobilityKpi.RIGHT_HIP_FRO),
        "Cotovelo" to listOf(MobilityKpi.LEFT_ELBOW_SAG, MobilityKpi.RIGHT_ELBOW_SAG),
        "Joelho" to listOf(MobilityKpi.LEFT_KNEE_SAG, MobilityKpi.RIGHT_KNEE_SAG),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        joints.forEach { (group, kpis) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(group, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp,
                    modifier = Modifier.width(52.dp))
                kpis.forEach { kpi ->
                    val on = kpi in selected
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (on) AccentPrimary else Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.clickable { onToggle(kpi) },
                    ) {
                        Text(kpi.label.removePrefix("$group "),
                            color = if (on) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

// ─── Latest angle snapshot card (legacy, v35 snapshots) ──────────────────────

@Composable
private fun LatestSnapshotCard(snap: PerformanceSnapshotEntity) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ângulos articulares", color = AccentSoft,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(DATE_FMT.format(Date(snap.capturedAtMs)),
                    color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            val rows = listOf(
                "Ombro E (Sag)" to snap.leftShoulderSagittalDeg,
                "Ombro E (Fro)" to snap.leftShoulderFrontalDeg,
                "Ombro D (Sag)" to snap.rightShoulderSagittalDeg,
                "Ombro D (Fro)" to snap.rightShoulderFrontalDeg,
                "Anca E (Sag)"  to snap.leftHipSagittalDeg,
                "Anca E (Fro)"  to snap.leftHipFrontalDeg,
                "Anca D (Sag)"  to snap.rightHipSagittalDeg,
                "Anca D (Fro)"  to snap.rightHipFrontalDeg,
                "Cotovelo E"    to snap.leftElbowSagittalDeg,
                "Cotovelo D"    to snap.rightElbowSagittalDeg,
                "Joelho E"      to snap.leftKneeSagittalDeg,
                "Joelho D"      to snap.rightKneeSagittalDeg,
            ).filter { it.second != null }
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (label, value) ->
                        Row(Modifier.weight(1f).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                                modifier = Modifier.weight(1f))
                            Text("${value?.toInt()}°", color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── History row ──────────────────────────────────────────────────────────────

@Composable
private fun SnapshotHistoryRow(snap: PerformanceSnapshotEntity) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF151A27),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(DATE_FMT.format(Date(snap.capturedAtMs)),
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            val summary = when {
                snap.hasSilhouetteData() -> listOfNotNull(
                    snap.frontalWaistRatio?.let { "Cin: ${"%.3f".format(it)}" },
                    snap.frontalHipRatio?.let   { "Anc: ${"%.3f".format(it)}" },
                ).joinToString("  ")
                snap.hasAngleData() -> listOfNotNull(
                    snap.leftHipSagittalDeg?.let  { "Anca E: ${it.toInt()}°" },
                    snap.rightHipSagittalDeg?.let { "Anca D: ${it.toInt()}°" },
                ).joinToString("  ")
                else -> "—"
            }
            Text(summary.ifEmpty { "—" }, color = AccentSoft.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}
