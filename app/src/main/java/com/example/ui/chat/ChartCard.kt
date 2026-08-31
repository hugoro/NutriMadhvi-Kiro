package com.example.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.ChartPoint
import com.example.data.remote.ChartProposal
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Inline chart card embedded under an assistant bubble. Dispatches on
 * [ChartProposal.kind] to the right sub-composable. All three charts render
 * in plain Compose Canvas — zero external dependencies, dark theme baked in.
 *
 * Visual contract follows the existing proposal cards (HabitProposalCard,
 * LogMealProposalCard): dark slate background, thin accent border, 10dp
 * rounded corners, 12dp inner padding.
 */
@Composable
fun ChartCard(proposal: ChartProposal) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, AccentSoft.copy(alpha = 0.40f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = proposal.title,
                color = AccentSoft,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            proposal.subtitle?.let { sub ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sub,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            when (proposal.kind.lowercase()) {
                "bar" -> BarChart(
                    series = proposal.series,
                    unit = proposal.unit,
                    target = proposal.target,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                "line" -> LineChart(
                    series = proposal.series,
                    unit = proposal.unit,
                    target = proposal.target,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                "donut" -> DonutChart(
                    series = proposal.series,
                    unit = proposal.unit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                "multiline" -> MultilineChart(
                    lines = proposal.lines.orEmpty(),
                    xLabels = proposal.xLabels.orEmpty()
                )
                else -> Text(
                    text = "(unsupported chart kind: ${proposal.kind})",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ============================================================================
// BarChart — vertical bars with optional target reference line.
// ============================================================================

@Composable
private fun BarChart(
    series: List<ChartPoint>,
    unit: String,
    target: Double?,
    modifier: Modifier = Modifier
) {
    val barColor = AccentPrimary
    val highlightColor = AccentSoft
    val targetColor = Color(0xFFF59E0B)        // amber-500
    val axisColor = Color(0xFF334155)
    val labelColor = Color(0xFFCBD5E1)
    val valueColor = Color(0xFFE2E8F0)

    val maxVal = max(
        series.maxOf { it.value },
        target ?: Double.NEGATIVE_INFINITY
    ).let { if (it <= 0.0) 1.0 else it }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 9.sp)
    val valueStyle = TextStyle(color = valueColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val targetStyle = TextStyle(color = targetColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bottomLabelH = 14.dp.toPx()
        val topValueH = 12.dp.toPx()
        val chartH = h - bottomLabelH - topValueH
        val chartTop = topValueH

        // Axis baseline
        drawLine(
            color = axisColor,
            start = Offset(0f, chartTop + chartH),
            end = Offset(w, chartTop + chartH),
            strokeWidth = 1f
        )

        // Target reference line (dashed amber)
        target?.let { t ->
            val y = chartTop + chartH - (chartH * (t / maxVal)).toFloat().coerceIn(0f, chartH)
            drawLine(
                color = targetColor.copy(alpha = 0.7f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
            // Target label on the right edge, just above the line.
            val tLabel = measurer.measure("→ ${formatValue(t)}", targetStyle)
            drawText(
                textLayoutResult = tLabel,
                topLeft = Offset(w - tLabel.size.width - 2f, y - tLabel.size.height - 1f)
            )
        }

        val n = series.size
        val slot = w / n
        val barW = slot * 0.6f
        val gap = (slot - barW) / 2f

        series.forEachIndexed { i, p ->
            val barH = (chartH * (p.value / maxVal)).toFloat().coerceIn(0f, chartH)
            val x = i * slot + gap
            val y = chartTop + chartH - barH
            val color = if (p.highlight) highlightColor else barColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
            // Value above the bar
            val valLabel = measurer.measure(formatValue(p.value), valueStyle)
            val valX = x + barW / 2f - valLabel.size.width / 2f
            val valY = (y - valLabel.size.height - 1f).coerceAtLeast(0f)
            drawText(textLayoutResult = valLabel, topLeft = Offset(valX, valY))
            // Label below the bar
            val lab = measurer.measure(p.label, labelStyle)
            val labX = x + barW / 2f - lab.size.width / 2f
            val labY = chartTop + chartH + 2f
            drawText(textLayoutResult = lab, topLeft = Offset(labX, labY))
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    UnitFooter(unit = unit, target = target)
}

// ============================================================================
// LineChart — connected line + dot per point.
// ============================================================================

@Composable
private fun LineChart(
    series: List<ChartPoint>,
    unit: String,
    target: Double?,
    modifier: Modifier = Modifier
) {
    val lineColor = AccentPrimary
    val pointColor = AccentSoft
    val highlightColor = AccentSoft
    val targetColor = Color(0xFFF59E0B)
    val axisColor = Color(0xFF334155)
    val labelColor = Color(0xFFCBD5E1)

    val minVal = series.minOf { it.value }
    val maxVal = max(
        series.maxOf { it.value },
        target ?: Double.NEGATIVE_INFINITY
    )
    // Avoid divide-by-zero on flat series.
    val span = (maxVal - minVal).let { if (it <= 0.0) 1.0 else it }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 9.sp)
    val targetStyle = TextStyle(color = targetColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bottomLabelH = 14.dp.toPx()
        val chartH = h - bottomLabelH
        val chartTop = 6.dp.toPx()
        val plotH = chartH - chartTop

        drawLine(
            color = axisColor,
            start = Offset(0f, chartTop + plotH),
            end = Offset(w, chartTop + plotH),
            strokeWidth = 1f
        )

        target?.let { t ->
            val y = chartTop + plotH - (plotH * ((t - minVal) / span)).toFloat().coerceIn(0f, plotH)
            drawLine(
                color = targetColor.copy(alpha = 0.7f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
            val tLabel = measurer.measure("→ ${formatValue(t)}", targetStyle)
            drawText(
                textLayoutResult = tLabel,
                topLeft = Offset(w - tLabel.size.width - 2f, y - tLabel.size.height - 1f)
            )
        }

        val n = series.size
        val slot = if (n > 1) w / (n - 1).toFloat() else w
        val pts = series.mapIndexed { i, p ->
            val x = if (n > 1) i * slot else w / 2f
            val y = chartTop + plotH - (plotH * ((p.value - minVal) / span)).toFloat().coerceIn(0f, plotH)
            Offset(x, y) to p
        }
        // Connect line
        for (i in 0 until pts.size - 1) {
            drawLine(
                color = lineColor,
                start = pts[i].first,
                end = pts[i + 1].first,
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
        // Dots
        pts.forEach { (off, p) ->
            val r = if (p.highlight) 4.5f else 3f
            drawCircle(
                color = if (p.highlight) highlightColor else pointColor,
                radius = r,
                center = off
            )
        }
        // Labels — every Nth so they don't crowd.
        val every = max(1, n / 7)
        pts.forEachIndexed { i, (off, p) ->
            if (i % every != 0 && i != n - 1) return@forEachIndexed
            val lab = measurer.measure(p.label, labelStyle)
            val labX = (off.x - lab.size.width / 2f).coerceIn(0f, w - lab.size.width)
            val labY = chartTop + plotH + 2f
            drawText(textLayoutResult = lab, topLeft = Offset(labX, labY))
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    UnitFooter(unit = unit, target = target)
}

// ============================================================================
// DonutChart — categorical breakdown with right-hand legend.
// ============================================================================

@Composable
private fun DonutChart(
    series: List<ChartPoint>,
    unit: String,
    modifier: Modifier = Modifier
) {
    // Stable palette — slices rotate through these in order. Chosen to read
    // well against the dark card background.
    val palette = listOf(
        AccentPrimary,
        AccentSoft,
        Color(0xFF38BDF8),    // sky-400
        Color(0xFFF59E0B),    // amber-500
        Color(0xFFEC4899),    // pink-500
        Color(0xFF22D3EE)     // cyan-400
    )

    val total = series.sumOf { it.value }.let { if (it <= 0.0) 1.0 else it }
    val measurer = rememberTextMeasurer()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val side = min(size.width, size.height)
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outerR = side * 0.45f
                val innerR = outerR * 0.62f
                val stroke = outerR - innerR
                var start = -90f
                series.forEachIndexed { i, p ->
                    val sweep = ((p.value / total) * 360.0).toFloat()
                    drawArc(
                        color = palette[i % palette.size],
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(cx - (innerR + stroke / 2f), cy - (innerR + stroke / 2f)),
                        size = Size((innerR + stroke / 2f) * 2f, (innerR + stroke / 2f) * 2f),
                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                    )
                    start += sweep
                }
                // Centre label — total summary.
                val centreText = "${formatValue(total)} $unit"
                val centreStyle = TextStyle(
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val measured = measurer.measure(centreText, centreStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        cx - measured.size.width / 2f,
                        cy - measured.size.height / 2f
                    )
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            series.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette[i % palette.size])
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val pct = ((p.value / total) * 100.0).toInt()
                    Text(
                        text = "${p.label} · ${formatValue(p.value)} $unit ($pct%)",
                        color = Color(0xFFE2E8F0),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitFooter(unit: String, target: Double?) {
    val text = buildString {
        append("unidade: $unit")
        if (target != null) append(" · alvo: ${formatValue(target)} $unit")
    }
    Text(
        text = text,
        color = Color(0xFF64748B),
        fontSize = 9.sp
    )
}

/** Drop trailing zeros so "1530.0" → "1530" and "2.50" → "2.5". */
private fun formatValue(v: Double): String {
    if (!v.isFinite()) return "—"
    val abs = kotlin.math.abs(v)
    return when {
        abs >= 100.0 -> v.toLong().toString()
        abs >= 10.0 -> "%.1f".format(v).removeSuffix(".0")
        else -> "%.2f".format(v).removeSuffix("0").removeSuffix(".")
    }
}

/* ─────────────────────────────────────────────────────────────────────────── *
 * MULTILINE — Phase 4 (2026-07-05)
 * ─────────────────────────────────────────────────────────────────────────── */

/** Editorial palette for multiline series. Ordered so the first line gets
 *  the strongest accent; later lines desaturate slightly to keep the
 *  primary reading dominant. Colors are picked to remain distinguishable
 *  on the dark slate card (contrast ≥ 4.5:1 vs #0F172A per WCAG-AA)
 *  and on the dark tile background of the KPI header. */
private val MULTILINE_PALETTE = listOf(
    Color(0xFF60A5FA), // sky-400 — primary
    Color(0xFFF97316), // orange-500 — secondary
    Color(0xFF10B981), // emerald-500 — tertiary
    Color(0xFFA78BFA), // violet-400 — quaternary
)

/** Body-composition palette (2026-07-20). Kept SEPARATE from
 *  [MULTILINE_PALETTE] so recolouring the body-comp lines doesn't repaint the
 *  offscreen share PNG or generic LLM ChartProposal renders. Order matches the
 *  Evolução line order — Weight, Muscle, Fat, Water — and each [KpiTile] reads
 *  the same index so tiles and lines stay in sync.
 *  Contrast ≥ 4.5:1 vs the dark card #0F172A (WCAG-AA). */
private val BODYCOMP_PALETTE = listOf(
    Color(0xFFA78BFA), // violet-400 — Peso
    Color(0xFF10B981), // emerald-500 — Músculo
    Color(0xFFF97316), // orange-500 — Gordura
    Color(0xFF38BDF8), // sky-400 — Água
)

/** Per-band y-scale for a small-multiples line. Computed once by
 *  [computeRanges] and shared between the pinned axis gutter and the scrolling
 *  plot so both use the identical scale. */
private data class ChartRange(val min: Double, val max: Double)

/** Compute a padded y-range per line, mirroring the in-canvas logic of
 *  [MultilineCanvas]: 20% top/bottom padding over the raw span, with a
 *  per-unit absolute floor so a nearly-flat series doesn't collapse to one
 *  pixel row (and sub-unit noise isn't dramatised). Empty line → (0,1). */
private fun computeRanges(lines: List<com.example.data.remote.ChartLine>): List<ChartRange> =
    lines.map { line ->
        val vals = line.values.filterNotNull()
        if (vals.isEmpty()) ChartRange(0.0, 1.0)
        else {
            val minV = vals.min()
            val maxV = vals.max()
            val rawSpan = maxV - minV
            val absFloor = when ((line.unit ?: "").trim().lowercase()) {
                "kg" -> 1.5
                "%" -> 2.0
                "kcal" -> 200.0
                "min" -> 15.0
                "g" -> 20.0
                else -> maxV * 0.05
            }
            val span = maxOf(rawSpan, absFloor)
            val pad = span * 0.20
            val expand = (span - rawSpan) / 2.0
            ChartRange(minV - expand - pad, maxV + expand + pad)
        }
    }


/**
 * Multiline chart with a KPI header. 2-4 overlaid time-series share the
 * x-axis (index into [xLabels]). When all lines share a unit, one y-axis
 * is drawn on the left; when units mix (kg + %), the primary unit stays
 * left and the second unit lands on the right axis.
 *
 * Design contract:
 *  - **KPI tiles** on top — one per line — show the label, the latest value,
 *    and the delta (colored by [ChartKpi.deltaTone]).
 *  - **Line colors** come from [MULTILINE_PALETTE] in declaration order;
 *    the KPI tile's small color dot matches so the reader can trace
 *    "which line is which" at a glance.
 *  - **Missing values** (null in [ChartLine.values]) draw as gaps — the
 *    stroke lifts at that index and resumes at the next non-null. Prevents
 *    the "zero baseline dive" that misleads the eye when a day is missing.
 *  - **Y-axis auto-scale** per unit: each unit family gets its own
 *    min/max computed from the actual values, padded 8% top/bottom.
 */
@Composable
internal fun MultilineChart(
    lines: List<com.example.data.remote.ChartLine>,
    xLabels: List<String>,
    /** Forwarded to each [KpiTile]. False hides the absolute value (used by
     *  the body-composition card, whose table already shows current values). */
    showLatestValue: Boolean = true
) {
    if (lines.isEmpty() || xLabels.isEmpty()) return

    // Selected measurement index into [xLabels]. null = default (summary =
    // latest reading + full-window delta). When a point is tapped, every KPI
    // tile retargets to that instant (see [effectiveKpi]) and the canvas draws
    // a vertical highlight column across all bands. Lives here because both the
    // header Row and the canvas read it. Inert in the offscreen share render —
    // no tap ever fires there, so it stays null and the PNG is unchanged.
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // KPI header — one tile per line, evenly spaced.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lines.forEachIndexed { i, line ->
                val (kpi, forceShow) = effectiveKpi(line, selectedIndex)
                KpiTile(
                    label = line.label,
                    unit = line.kpiUnit ?: line.unit ?: "",
                    kpi = kpi,
                    accent = MULTILINE_PALETTE[i % MULTILINE_PALETTE.size],
                    modifier = Modifier.weight(1f),
                    // When a point is selected, promote the absolute value at
                    // that point even for delta-only callers (body-comp), so
                    // the tile reads "value at instant + delta up to latest".
                    showLatestValue = showLatestValue || forceShow
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Chart body — small multiples: each line gets its own band with a
        // local y-scale, sharing only the x-axis. Overlaying 3+ series on a
        // shared axis compresses very-different magnitudes (85 kg + 34 kg on
        // one axis) or exaggerates tiny moves (0.7 pp body-fat looks like a
        // cliff when the % axis auto-scales to a 1 pp span). Small multiples
        // fix both. Each band is ~72dp tall so the whole card scales with the
        // number of KPIs shown.
        val bandHeightDp = 72
        val chartHeightDp = bandHeightDp * lines.size + 22 // +22 for x-axis footer
        MultilineCanvas(
            lines = lines,
            xLabels = xLabels,
            selectedIndex = selectedIndex,
            onSelectIndex = { selectedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeightDp.dp)
        )
    }
}

/**
 * Compute the KPI a tile should display given the current selection.
 *
 *  - No selection (`selIdx == null`) → the line's own [ChartKpi] (default:
 *    latest reading + full-window delta). Behaviour unchanged.
 *  - A point is selected and this line has a reading there → a KPI whose
 *    `latest` is the value AT that point and whose `delta` is
 *    (latest reading − value at point), i.e. "what changed from this point up
 *    to today". The returned Boolean (`forceShow`) tells the tile to show the
 *    absolute value even for delta-only callers.
 *  - Selected but this line has no reading at that index (gap / out of range)
 *    → fall back to the default KPI so the other tiles can still retarget.
 *
 * Delta semantics differ between the two modes: default = latest − oldest;
 * selected = latest − point. The sign can flip, but [ChartKpi.deltaTone] is the
 * LLM's editorial label about the metric's direction (losing fat is "good"),
 * so reusing it keeps the tile colour meaning stable across modes.
 */
private fun effectiveKpi(
    line: com.example.data.remote.ChartLine,
    selIdx: Int?
): Pair<com.example.data.remote.ChartKpi?, Boolean> {
    if (selIdx == null) return line.kpi to false
    // When the KPI reports a different quantity than the plotted line (e.g.
    // muscle plotted in kg but KPI in %), retarget against that series so the
    // selected-point delta stays in the KPI's unit, not the plotted one.
    val series = line.kpiValues ?: line.values
    val v = series.getOrNull(selIdx) ?: return line.kpi to false
    val latest = series.lastOrNull { it != null }
    val delta = latest?.let { it - v }
    val tone = line.kpi?.deltaTone ?: "neutral"
    return com.example.data.remote.ChartKpi(latest = v, delta = delta, deltaTone = tone) to true
}

/* ─────────────────────────────────────────────────────────────────────────── *
 * MULTILINE (SCROLLABLE) — per-weigh-in body-composition strip (2026-07-20)
 *
 * Same visual language as [MultilineChart] (KPI header + small-multiple bands +
 * tap-to-select), but the plot is a horizontally-scrollable strip: one x-slot
 * per data point at a FIXED width, so every individual weigh-in is a real point
 * you can pan through. The y-axis min/max labels are PINNED in a fixed left
 * gutter so they don't scroll away. Uses [BODYCOMP_PALETTE].
 *
 * Only for the on-screen Evolução card — the offscreen share PNG keeps using
 * the non-scrollable [MultilineChart] so its bitmap stays byte-stable.
 * ─────────────────────────────────────────────────────────────────────────── */
@Composable
internal fun MultilineChartScrollable(
    lines: List<com.example.data.remote.ChartLine>,
    xLabels: List<String>,
    perPointDp: Int = 40,
    palette: List<Color> = BODYCOMP_PALETTE
) {
    if (lines.isEmpty() || xLabels.isEmpty()) return

    // Selected x-index (into xLabels). Same semantics as MultilineChart: tap a
    // point to retarget the KPI tiles to that instant; tap again / tap empty
    // resets to null (latest + full-window delta).
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val ranges = remember(lines) { computeRanges(lines) }

    val bandHeightDp = 72
    val xAxisFooterDp = 22
    val plotHeightDp = bandHeightDp * lines.size + xAxisFooterDp
    val gutterWidthDp = 44
    val nX = xLabels.size
    val stripWidthDp = (perPointDp * nX).coerceAtLeast(1)

    // Start scrolled to the most recent reading (right edge). maxValue is set
    // after the strip is measured, so run this once the point count is known.
    val scrollState = rememberScrollState()
    LaunchedEffect(nX) { scrollState.scrollTo(scrollState.maxValue) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // KPI header — one tile per line, evenly spaced (NOT scrolled).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lines.forEachIndexed { i, line ->
                val (kpi, _) = effectiveKpi(line, selectedIndex)
                KpiTile(
                    label = line.label,
                    unit = line.kpiUnit ?: line.unit ?: "",
                    kpi = kpi,
                    accent = palette[i % palette.size],
                    modifier = Modifier.weight(1f),
                    // Always show the absolute value: default = latest + full-
                    // window delta; when a point is selected, effectiveKpi
                    // retargets it to value@point + delta-to-latest.
                    showLatestValue = true
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Pinned y-axis gutter + horizontally-scrollable plot.
        Row(modifier = Modifier.fillMaxWidth()) {
            AxisGutterCanvas(
                ranges = ranges,
                lineCount = lines.size,
                modifier = Modifier
                    .width(gutterWidthDp.dp)
                    .height(plotHeightDp.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
            ) {
                ScrollingPlotCanvas(
                    lines = lines,
                    xLabels = xLabels,
                    ranges = ranges,
                    palette = palette,
                    selectedIndex = selectedIndex,
                    onSelectIndex = { selectedIndex = it },
                    modifier = Modifier
                        .width(stripWidthDp.dp)
                        .height(plotHeightDp.dp)
                )
            }
        }
    }
}

/**
 * STATIC (non-scrollable) sibling of [MultilineChartScrollable] for the
 * offscreen share-report PNG. Same visual language — KPI header (latest value +
 * full-window delta), pinned y-axis gutter, small-multiple bands, per-snapshot
 * points, [BODYCOMP_PALETTE] — but the plot fills the available width instead of
 * scrolling (a scroll container collapses to 0px when laid out into a bitmap).
 * All points compress into the width; no tap interactivity (offscreen never
 * receives touches, so selectedIndex stays null).
 */
@Composable
internal fun MultilineChartStatic(
    lines: List<com.example.data.remote.ChartLine>,
    xLabels: List<String>,
    palette: List<Color> = BODYCOMP_PALETTE
) {
    if (lines.isEmpty() || xLabels.isEmpty()) return
    val ranges = remember(lines) { computeRanges(lines) }
    val bandHeightDp = 72
    val xAxisFooterDp = 22
    val plotHeightDp = bandHeightDp * lines.size + xAxisFooterDp
    val gutterWidthDp = 44

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lines.forEachIndexed { i, line ->
                KpiTile(
                    label = line.label,
                    unit = line.kpiUnit ?: line.unit ?: "",
                    kpi = line.kpi,
                    accent = palette[i % palette.size],
                    modifier = Modifier.weight(1f),
                    showLatestValue = true
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            AxisGutterCanvas(
                ranges = ranges,
                lineCount = lines.size,
                modifier = Modifier
                    .width(gutterWidthDp.dp)
                    .height(plotHeightDp.dp)
            )
            ScrollingPlotCanvas(
                lines = lines,
                xLabels = xLabels,
                ranges = ranges,
                palette = palette,
                selectedIndex = null,
                onSelectIndex = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(plotHeightDp.dp)
            )
        }
    }
}

/**
 * Pinned left gutter: per-band max@top / min@bottom, right-aligned. Draws no
 * plot and no unit tag — it only holds the y-scale labels that would otherwise
 * scroll away with the plot. Uses the SAME [ranges] the plot uses.
 */
@Composable
private fun AxisGutterCanvas(
    ranges: List<ChartRange>,
    lineCount: Int,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFF64748B), fontSize = 9.sp)
    Canvas(modifier = modifier) {
        val paddingTop = 6.dp.toPx()
        val xAxisFooter = 22.dp.toPx()
        val totalPlotH = size.height - paddingTop - xAxisFooter
        if (totalPlotH <= 0f || lineCount <= 0) return@Canvas
        val bandH = totalPlotH / lineCount
        for (idx in 0 until lineCount) {
            val range = ranges.getOrNull(idx) ?: continue
            val bandTop = paddingTop + bandH * idx
            val bandBottom = bandTop + bandH
            val topLayout = textMeasurer.measure(formatValue(range.max), labelStyle)
            val botLayout = textMeasurer.measure(formatValue(range.min), labelStyle)
            drawText(
                textLayoutResult = topLayout,
                topLeft = Offset(
                    x = size.width - topLayout.size.width - 4.dp.toPx(),
                    y = bandTop + 2.dp.toPx()
                )
            )
            drawText(
                textLayoutResult = botLayout,
                topLeft = Offset(
                    x = size.width - botLayout.size.width - 4.dp.toPx(),
                    y = bandBottom - botLayout.size.height - 2.dp.toPx()
                )
            )
        }
    }
}

/**
 * The scrollable plot. Adapted from [MultilineCanvas]'s draw block, but:
 *  - `paddingLeft` shrinks to 8dp (y-axis labels moved to [AxisGutterCanvas]).
 *  - No in-band min/max labels, no per-band unit tag ("kg" is redundant).
 *  - Lines BRIDGE null gaps: a missing reading doesn't lift the pen; the
 *    stroke connects the last non-null to the next non-null (dots still only
 *    at real readings). Requested so sparsely-measured series (water) still
 *    read as one continuous line.
 *  - Uses the passed-in [ranges] + [palette].
 * Keeps: band dividers, mid grid, selected-column highlight, enlarged dot +
 * ring, bold selected x-label, and the tap inverse (snap 24dp, toggle-off).
 */
@Composable
private fun ScrollingPlotCanvas(
    lines: List<com.example.data.remote.ChartLine>,
    xLabels: List<String>,
    ranges: List<ChartRange>,
    palette: List<Color>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFF64748B), fontSize = 9.sp)

    val tapModifier = modifier.pointerInput(xLabels.size, selectedIndex) {
        detectTapGestures { off ->
            val nX = xLabels.size
            if (nX <= 1) { onSelectIndex(null); return@detectTapGestures }
            val paddingLeft = 8.dp.toPx()
            val paddingRight = 8.dp.toPx()
            val plotW = size.width - paddingLeft - paddingRight
            if (plotW <= 0f) { onSelectIndex(null); return@detectTapGestures }
            fun xAt(i: Int) = paddingLeft + plotW * i / (nX - 1)
            val raw = (off.x - paddingLeft) / plotW * (nX - 1)
            val idx = raw.roundToInt().coerceIn(0, nX - 1)
            val hit = abs(off.x - xAt(idx)) <= 24.dp.toPx()
            onSelectIndex(if (!hit || idx == selectedIndex) null else idx)
        }
    }

    Canvas(modifier = tapModifier) {
        val paddingLeft = 8.dp.toPx()
        val paddingRight = 8.dp.toPx()
        val paddingTop = 6.dp.toPx()
        val xAxisFooter = 22.dp.toPx()
        val plotW = size.width - paddingLeft - paddingRight
        val totalPlotH = size.height - paddingTop - xAxisFooter
        if (plotW <= 0f || totalPlotH <= 0f || lines.isEmpty()) return@Canvas

        val bandH = totalPlotH / lines.size
        val gridColor = Color(0xFF1E293B)
        val bandDivider = Color(0xFF334155).copy(alpha = 0.35f)
        val nX = xLabels.size

        fun xAt(i: Int): Float = if (nX <= 1) paddingLeft + plotW / 2f
                                 else paddingLeft + plotW * i / (nX - 1)

        val sel = selectedIndex?.takeIf { it in 0 until nX }
        if (sel != null) {
            val sx = xAt(sel)
            val bandW = 14.dp.toPx()
            drawRect(
                color = Color(0xFF38BDF8).copy(alpha = 0.08f),
                topLeft = Offset(sx - bandW / 2f, paddingTop),
                size = Size(bandW, totalPlotH)
            )
            drawLine(
                color = Color(0xFF38BDF8).copy(alpha = 0.55f),
                start = Offset(sx, paddingTop),
                end = Offset(sx, paddingTop + totalPlotH),
                strokeWidth = 1.5f
            )
        }

        lines.forEachIndexed { idx, line ->
            val color = palette[idx % palette.size]
            val range = ranges.getOrNull(idx) ?: ChartRange(0.0, 1.0)
            val span = (range.max - range.min).takeIf { it > 0.0 } ?: 1.0
            val bandTop = paddingTop + bandH * idx
            val bandBottom = bandTop + bandH

            if (idx > 0) {
                drawLine(
                    color = bandDivider,
                    start = Offset(paddingLeft, bandTop),
                    end = Offset(paddingLeft + plotW, bandTop),
                    strokeWidth = 1f
                )
            }

            // Faint mid-line grid inside the band.
            run {
                val y = bandTop + (bandH - 4.dp.toPx()) * 0.5f + 2.dp.toPx()
                drawLine(
                    color = gridColor,
                    start = Offset(paddingLeft, y),
                    end = Offset(paddingLeft + plotW, y),
                    strokeWidth = 1f
                )
            }

            val plotTop = bandTop + 4.dp.toPx()
            val plotBottom = bandBottom - 4.dp.toPx()
            val plotHBand = plotBottom - plotTop
            fun yAt(v: Double): Float {
                val yNorm = ((v - range.min) / span).toFloat().coerceIn(0f, 1f)
                return plotTop + plotHBand * (1f - yNorm)
            }

            // Draw the line — BRIDGE null gaps (don't clear prev on null), so
            // the stroke connects consecutive readings even across missing x's.
            var prev: Pair<Int, Double>? = null
            line.values.forEachIndexed { i, vNullable ->
                val v = vNullable ?: return@forEachIndexed
                val here = Offset(xAt(i), yAt(v))
                prev?.let { (pi, pv) ->
                    drawLine(
                        color = color,
                        start = Offset(xAt(pi), yAt(pv)),
                        end = here,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                val isSel = sel != null && i == sel
                drawCircle(
                    color = color,
                    radius = if (isSel) 4.5.dp.toPx() else 2.5.dp.toPx(),
                    center = here
                )
                if (isSel) {
                    drawCircle(
                        color = Color.White,
                        radius = 4.5.dp.toPx(),
                        center = here,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                prev = i to v
            }
        }

        // Shared x-axis labels at the bottom. With fixed per-point spacing we
        // can show more ticks; cap density so labels don't collide (~a tick
        // every ~48dp), and always include the selected index.
        val selLabelStyle = TextStyle(
            color = Color(0xFF38BDF8),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        val every = maxOf(1, (48.dp.toPx() / (plotW / maxOf(1, nX - 1))).roundToInt())
        val tickIndices = (xLabels.indices.filter { it % every == 0 || it == nX - 1 } +
            listOfNotNull(sel)).distinct()
        for (i in tickIndices) {
            val emphasised = sel != null && i == sel
            val layout = textMeasurer.measure(xLabels[i], if (emphasised) selLabelStyle else labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (xAt(i) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                    y = paddingTop + totalPlotH + 4.dp.toPx()
                )
            )
        }
    }
}


@Composable
private fun KpiTile(
    label: String,
    unit: String,
    kpi: com.example.data.remote.ChartKpi?,
    accent: Color,
    modifier: Modifier = Modifier,
    /** When false, the absolute latest value is hidden and the delta is
     *  promoted to the headline line. Used when a sibling table already
     *  shows the current value, so the tile focuses purely on the change. */
    showLatestValue: Boolean = true
) {
    val deltaColor = when (kpi?.deltaTone) {
        "good" -> Color(0xFF10B981) // emerald
        "bad" -> Color(0xFFEF4444) // red-500
        else -> Color(0xFF94A3B8) // slate — neutral
    }
    val deltaText = kpi?.delta?.let { d ->
        val sign = when {
            d > 0 -> "▲"
            d < 0 -> "▼"
            else -> "="
        }
        "$sign ${formatValue(kotlin.math.abs(d))}${if (unit.isNotBlank()) unit else ""}"
    } ?: "—"
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = Color(0xFFCBD5E1),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        if (showLatestValue) {
            Text(
                text = kpi?.latest?.let { "${formatValue(it)}${if (unit.isNotBlank()) " $unit" else ""}" } ?: "—",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = deltaText,
                color = deltaColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        } else {
            // Delta-only tile — promote the change to the headline line since
            // the absolute value lives in the sibling table.
            Text(
                text = deltaText,
                color = deltaColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MultilineCanvas(
    lines: List<com.example.data.remote.ChartLine>,
    xLabels: List<String>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFF64748B), fontSize = 9.sp)
    val axisLabelStyle = TextStyle(
        color = Color(0xFF64748B),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold
    )

    // Small-multiples layout: each line owns a horizontal band with its own
    // y-scale computed from that line's data only. Shared x-axis at the bottom.
    // Padding: 20% top/bottom of the local span, with a small absolute floor
    // so a nearly-flat series doesn't collapse to a single pixel row and
    // doesn't dramatise sub-unit noise (see 2026-07-05 evolucao chart: body-
    // fat went 26.9→25.7, a 1.2 pp span, and the earlier overlay auto-scaled
    // to that exact span so a mid-week 0 pp change looked like a plateau
    // ending in a cliff).
    data class Range(val min: Double, val max: Double)
    val rangeByLine: List<Range> = lines.map { line ->
        val vals = line.values.filterNotNull()
        if (vals.isEmpty()) Range(0.0, 1.0)
        else {
            val minV = vals.min()
            val maxV = vals.max()
            val rawSpan = maxV - minV
            // Absolute floor per unit — prevents tiny noise from filling the
            // whole band. Chosen to match the "meaningful move" threshold for
            // each metric family we care about.
            val absFloor = when ((line.unit ?: "").trim().lowercase()) {
                "kg" -> 1.5      // 1.5 kg is the smallest weight change worth eyeballing
                "%" -> 2.0       // 2 pp for body fat
                "kcal" -> 200.0
                "min" -> 15.0
                "g" -> 20.0
                else -> maxV * 0.05
            }
            val span = maxOf(rawSpan, absFloor)
            val pad = span * 0.20
            // Center the values inside the widened span.
            val expand = (span - rawSpan) / 2.0
            Range(minV - expand - pad, maxV + expand + pad)
        }
    }

    // Tap-to-select: invert xAt() to snap a tap to the nearest measurement
    // index, selecting that instant across ALL bands (shared x-axis). Uses the
    // same fixed dp constants as the draw block below — no geometry hoisting
    // needed since `size` here is px, matching the Canvas. Re-keyed on the point
    // count and current selection so the toggle-to-reset comparison stays fresh.
    val tapModifier = modifier.pointerInput(xLabels.size, selectedIndex) {
        detectTapGestures { off ->
            val nX = xLabels.size
            if (nX <= 1) { onSelectIndex(null); return@detectTapGestures }
            val paddingLeft = 44.dp.toPx()
            val paddingRight = 12.dp.toPx()
            val plotW = size.width - paddingLeft - paddingRight
            if (plotW <= 0f) { onSelectIndex(null); return@detectTapGestures }
            fun xAt(i: Int) = paddingLeft + plotW * i / (nX - 1)
            val raw = (off.x - paddingLeft) / plotW * (nX - 1)
            val idx = raw.roundToInt().coerceIn(0, nX - 1)
            // Reject taps outside the snap radius (tapped empty space → reset),
            // and toggle off when re-tapping the already-selected column.
            val hit = abs(off.x - xAt(idx)) <= 24.dp.toPx()
            onSelectIndex(if (!hit || idx == selectedIndex) null else idx)
        }
    }

    Canvas(modifier = tapModifier) {
        val paddingLeft = 44.dp.toPx()
        val paddingRight = 12.dp.toPx()
        val paddingTop = 6.dp.toPx()
        val xAxisFooter = 22.dp.toPx()
        val plotW = size.width - paddingLeft - paddingRight
        val totalPlotH = size.height - paddingTop - xAxisFooter
        if (plotW <= 0f || totalPlotH <= 0f || lines.isEmpty()) return@Canvas

        val bandH = totalPlotH / lines.size
        val gridColor = Color(0xFF1E293B)
        val bandDivider = Color(0xFF334155).copy(alpha = 0.35f)
        val nX = xLabels.size

        fun xAt(i: Int): Float = if (nX <= 1) paddingLeft + plotW / 2f
                                 else paddingLeft + plotW * i / (nX - 1)

        // Selected-column highlight — a faint full-height vertical band + line
        // at the tapped x, drawn behind the series so dots stay on top. Gated
        // on selectedIndex, so the offscreen share render (always null) draws
        // nothing extra and the PNG is unchanged.
        val sel = selectedIndex?.takeIf { it in 0 until nX }
        if (sel != null) {
            val sx = xAt(sel)
            val bandW = 14.dp.toPx()
            drawRect(
                color = Color(0xFF38BDF8).copy(alpha = 0.08f),
                topLeft = Offset(sx - bandW / 2f, paddingTop),
                size = Size(bandW, totalPlotH)
            )
            drawLine(
                color = Color(0xFF38BDF8).copy(alpha = 0.55f),
                start = Offset(sx, paddingTop),
                end = Offset(sx, paddingTop + totalPlotH),
                strokeWidth = 1.5f
            )
        }

        // Draw each band.
        lines.forEachIndexed { idx, line ->
            val color = MULTILINE_PALETTE[idx % MULTILINE_PALETTE.size]
            val range = rangeByLine[idx]
            val span = (range.max - range.min).takeIf { it > 0.0 } ?: 1.0
            val bandTop = paddingTop + bandH * idx
            val bandBottom = bandTop + bandH

            // Band background divider (skip on top band — clean top edge).
            if (idx > 0) {
                drawLine(
                    color = bandDivider,
                    start = Offset(paddingLeft, bandTop),
                    end = Offset(paddingLeft + plotW, bandTop),
                    strokeWidth = 1f
                )
            }

            // Faint mid-line grid inside the band (top, middle, bottom).
            for (frac in listOf(0f, 0.5f, 1f)) {
                val y = bandTop + (bandH - 4.dp.toPx()) * frac + 2.dp.toPx()
                if (frac == 0.5f) {
                    drawLine(
                        color = gridColor,
                        start = Offset(paddingLeft, y),
                        end = Offset(paddingLeft + plotW, y),
                        strokeWidth = 1f
                    )
                }
            }

            // Y-axis labels: top and bottom of the band (min/max of this line).
            run {
                val topLabel = formatValue(range.max)
                val botLabel = formatValue(range.min)
                val topLayout = textMeasurer.measure(topLabel, labelStyle)
                val botLayout = textMeasurer.measure(botLabel, labelStyle)
                drawText(
                    textLayoutResult = topLayout,
                    topLeft = Offset(
                        x = paddingLeft - topLayout.size.width - 4.dp.toPx(),
                        y = bandTop + 2.dp.toPx()
                    )
                )
                drawText(
                    textLayoutResult = botLayout,
                    topLeft = Offset(
                        x = paddingLeft - botLayout.size.width - 4.dp.toPx(),
                        y = bandBottom - botLayout.size.height - 2.dp.toPx()
                    )
                )
            }

            // Unit tag on the top-left of the band, above the top y-label.
            val unit = (line.unit ?: "").trim()
            if (unit.isNotBlank()) {
                val unitLayout = textMeasurer.measure(unit, axisLabelStyle)
                drawText(
                    textLayoutResult = unitLayout,
                    topLeft = Offset(
                        x = 0f,
                        y = bandTop + 2.dp.toPx()
                    )
                )
            }

            // Plot area inside the band — leave 4dp top/bottom for the y-labels.
            val plotTop = bandTop + 4.dp.toPx()
            val plotBottom = bandBottom - 4.dp.toPx()
            val plotHBand = plotBottom - plotTop
            fun yAt(v: Double): Float {
                val yNorm = ((v - range.min) / span).toFloat().coerceIn(0f, 1f)
                return plotTop + plotHBand * (1f - yNorm)
            }

            // Draw the line — gaps for null values.
            var prev: Pair<Int, Double>? = null
            line.values.forEachIndexed { i, vNullable ->
                val v = vNullable ?: run { prev = null; return@forEachIndexed }
                val here = Offset(xAt(i), yAt(v))
                prev?.let { (pi, pv) ->
                    drawLine(
                        color = color,
                        start = Offset(xAt(pi), yAt(pv)),
                        end = here,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                val isSel = sel != null && i == sel
                drawCircle(
                    color = color,
                    radius = if (isSel) 4.5.dp.toPx() else 2.5.dp.toPx(),
                    center = here
                )
                // Ring around the selected dot so it reads as "active" against
                // its own fill colour.
                if (isSel) {
                    drawCircle(
                        color = Color.White,
                        radius = 4.5.dp.toPx(),
                        center = here,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                prev = i to v
            }
        }

        // Shared x-axis labels at the bottom. Always include the selected
        // index so its label is visible even when it isn't a regular tick.
        val selLabelStyle = TextStyle(
            color = Color(0xFF38BDF8),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        val tickIndices = (when {
            nX <= 4 -> xLabels.indices.toList()
            else -> listOf(0, nX / 3, 2 * nX / 3, nX - 1)
        } + listOfNotNull(sel)).distinct()
        for (i in tickIndices) {
            val emphasised = sel != null && i == sel
            val layout = textMeasurer.measure(xLabels[i], if (emphasised) selLabelStyle else labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = xAt(i) - layout.size.width / 2f,
                    y = paddingTop + totalPlotH + 4.dp.toPx()
                )
            )
        }
    }
}
