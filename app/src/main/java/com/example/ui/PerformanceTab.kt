package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

// ─── Main tab composable ──────────────────────────────────────────────────────

@Composable
fun PerformanceTab(viewModel: ChatViewModel) {
    val snapshots by viewModel.performanceSnapshots.collectAsStateWithLifecycle()
    var showAssessment by remember { mutableStateOf(false) }

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

        // ── Empty state ───────────────────────────────────────────────────────
        if (snapshots.isEmpty()) {
            item { EmptyPerformanceState(onStart = { showAssessment = true }) }
            return@LazyColumn
        }

        val latest = snapshots.lastOrNull()

        // ── Coach report card (primary) ───────────────────────────────────────
        if (latest != null) {
            item {
                CoachReportCard(
                    snap = latest,
                    onGenerateReport = { viewModel.requestCoachReport(latest) }
                )
            }
        }

        // ── Silhouette ratio bars (secondary) ─────────────────────────────────
        if (latest != null && latest.hasSilhouetteData()) {
            item { SilhouetteRatioBars(latest) }
        }

        // ── Evolution chart (tertiary) ────────────────────────────────────────
        val silhouetteSnaps = snapshots.filter { it.hasSilhouetteData() }
        if (silhouetteSnaps.size >= 2) {
            item { SilhouetteEvolutionCard(silhouetteSnaps) }
        } else if (snapshots.size >= 2 && latest?.hasAngleData() == true) {
            // Legacy angle chart for v35-only installs
            item { LegacyAngleEvolutionCard(snapshots) }
        }

        // ── Backward compat: angle table for v35-only snapshots ───────────────
        if (latest != null && latest.hasAngleData() && !latest.hasSilhouetteData()) {
            item { LatestSnapshotCard(latest) }
        }

        // ── History ───────────────────────────────────────────────────────────
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

// ─── Coach report card ────────────────────────────────────────────────────────

@Composable
private fun CoachReportCard(
    snap: PerformanceSnapshotEntity,
    onGenerateReport: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Relatório Coach", color = AccentSoft,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(DATE_FMT.format(Date(snap.capturedAtMs)),
                    color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            }
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
                    Text("A gerar relatório...",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                    LinearProgressIndicator(
                        color = AccentSoft,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
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
}

// ─── Silhouette ratio bars ────────────────────────────────────────────────────

@Composable
private fun SilhouetteRatioBars(snap: PerformanceSnapshotEntity) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Medidas de silhueta", color = AccentSoft,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))

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

// ─── Silhouette evolution chart ───────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SilhouetteEvolutionCard(snaps: List<PerformanceSnapshotEntity>) {
    var selectedKpis by remember {
        mutableStateOf(setOf(SilhouetteKpi.FRONTAL_WAIST, SilhouetteKpi.FRONTAL_HIP))
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Evolução da silhueta", color = AccentSoft,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
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
}

// ─── Legacy angle chart (v35 backward compat) ────────────────────────────────

@Composable
private fun LegacyAngleEvolutionCard(snaps: List<PerformanceSnapshotEntity>) {
    var selected by remember {
        mutableStateOf(setOf(MobilityKpi.LEFT_HIP_SAG, MobilityKpi.RIGHT_HIP_SAG))
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Evolução (ângulos articulares)", color = AccentSoft,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
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
