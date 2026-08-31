package com.example.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentSoft
import java.time.LocalDate
import kotlin.math.max

/**
 * One coloured segment of a stacked bar.
 *
 * @param value The kcal-equivalent contribution of this segment. Macro callers
 *              should pre-convert grams to kcal (protein/carbs ×4, fat ×9) so
 *              the bar's total height represents the day's calorie total.
 */
data class BarSegment(val value: Double, val color: Color)

/**
 * One day's worth of bar data — the LocalDate keys the column, the segment list
 * stacks bottom-up. Empty segments / all-zero values render as the "empty day"
 * pill (a thin slate pip on the baseline).
 */
data class BarBucket(
    val date: LocalDate,
    val segments: List<BarSegment>,
    /** Accessibility description read by TalkBack when the column is focused. */
    val contentDescription: String? = null
)

/**
 * Per-day stacked-bar chart used by the Reports tab's "Evolução" section.
 *
 * Pure Compose [Canvas] — no external charting library. Y axis is implicit;
 * the highest segment-sum (or 1.15× the highest per-day target, whichever is
 * larger) sets the top. A single dashed horizontal line marks the *mean*
 * target across the range — when targets are versioned mid-range this is the
 * average. See plan §C "Target line(s)" — per-column lines deferred until
 * targets actually shift mid-week with frequency.
 *
 * Behaviour rules:
 *   - Bars: 60% of the column width, top corners rounded 8dp.
 *   - Empty days (segments sum to 0): draw a slate-500 pill on the baseline
 *     so the user still sees the column exists.
 *   - Empty period (every day is empty AND target is zero/null): render the
 *     [emptyPlaceholder] text centered in place of the canvas.
 *   - X labels: rendered with native Android Paint at the bottom of each
 *     column. For Monthly views (>14 buckets) [columnLabel] should already
 *     thin out (caller passes only every 3rd day's label, or whatever cadence
 *     reads well).
 *   - Accessibility: invisible focusable Box overlays each column so TalkBack
 *     can announce per-day totals when navigating with assistive tech. The
 *     Canvas itself is not a focus target.
 */
@Composable
fun StackedDayBarChart(
    buckets: List<BarBucket>,
    targetPerDay: Map<LocalDate, Double>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    columnLabel: (LocalDate) -> String,
    emptyPlaceholder: String? = null
) {
    val density = LocalDensity.current

    val maxBucketTotal = remember(buckets) {
        buckets.maxOfOrNull { it.segments.sumOf { s -> s.value } } ?: 0.0
    }
    val maxTarget = remember(targetPerDay) { targetPerDay.values.maxOrNull() ?: 0.0 }
    val meanTarget = remember(targetPerDay) {
        val nonZero = targetPerDay.values.filter { it > 0.0 }
        if (nonZero.isEmpty()) 0.0 else nonZero.average()
    }
    val yMax = remember(maxBucketTotal, maxTarget) {
        max(max(maxBucketTotal, maxTarget * 1.15), 100.0)
    }
    val allEmpty = maxBucketTotal == 0.0

    if (buckets.isEmpty() || (allEmpty && meanTarget == 0.0 && emptyPlaceholder != null)) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyPlaceholder ?: "",
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val labelPaint = remember(density) {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8") // slate-400
            textSize = with(density) { 9.sp.toPx() }
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val labelHeightPx = with(density) { 14.dp.toPx() }
    val barCornerPx = with(density) { 8.dp.toPx() }
    val emptyPillHeightPx = with(density) { 4.dp.toPx() }
    val emptyPillCornerPx = with(density) { 2.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val plotHeight = h - labelHeightPx
            val n = buckets.size
            if (n == 0) return@Canvas

            val columnWidth = w / n
            val barWidth = columnWidth * 0.6f

            // Target line: dashed, slate-violet, at the mean target value.
            if (meanTarget > 0.0) {
                val y = plotHeight - (meanTarget / yMax * plotHeight).toFloat()
                drawLine(
                    color = AccentSoft,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = with(density) { 1.dp.toPx() },
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }

            buckets.forEachIndexed { idx, bucket ->
                val total = bucket.segments.sumOf { it.value }
                val centerX = columnWidth * idx + columnWidth / 2f
                val left = centerX - barWidth / 2f
                val right = centerX + barWidth / 2f

                if (total == 0.0) {
                    // Empty-day pill on the baseline.
                    drawRoundRect(
                        color = Color(0xFF64748B),
                        topLeft = Offset(left, plotHeight - emptyPillHeightPx),
                        size = Size(barWidth, emptyPillHeightPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            emptyPillCornerPx, emptyPillCornerPx
                        )
                    )
                } else {
                    val barTotalHeight = (total / yMax * plotHeight).toFloat()
                    val barTop = plotHeight - barTotalHeight

                    // Build a clipping path that has rounded TOP corners only,
                    // so the last drawn segment naturally inherits the
                    // rounded shape.
                    val clipPath = Path().apply {
                        // Top-rounded rectangle, square bottom.
                        val r = barCornerPx.coerceAtMost(barTotalHeight / 2f)
                        moveTo(left, plotHeight)              // bottom-left
                        lineTo(left, barTop + r)              // up
                        // top-left curve
                        quadraticBezierTo(left, barTop, left + r, barTop)
                        lineTo(right - r, barTop)             // top edge
                        // top-right curve
                        quadraticBezierTo(right, barTop, right, barTop + r)
                        lineTo(right, plotHeight)             // down to baseline
                        close()
                    }

                    clipPath(clipPath) {
                        var y = plotHeight
                        bucket.segments.forEach { seg ->
                            if (seg.value <= 0.0) return@forEach
                            val segH = (seg.value / yMax * plotHeight).toFloat()
                            drawRect(
                                color = seg.color,
                                topLeft = Offset(left, y - segH),
                                size = Size(barWidth, segH)
                            )
                            y -= segH
                        }
                    }
                }

                // X label
                val label = columnLabel(bucket.date)
                if (label.isNotEmpty()) {
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        centerX,
                        h - 2f, // sit just above the bottom edge
                        labelPaint
                    )
                }
            }
        }

        // Accessibility overlay: one invisible Box per column so TalkBack can
        // step through days and announce per-day totals.
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Use a fillMaxSize Row of equally-weighted Boxes to mirror the
            // canvas column layout exactly. The Boxes carry only a
            // contentDescription — no visible drawing.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                buckets.forEach { bucket ->
                    val cd = bucket.contentDescription
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(
                                if (cd != null) Modifier.semantics { contentDescription = cd }
                                else Modifier
                            )
                    )
                }
            }
        }
    }
}

/**
 * Caption shown beneath the chart with the mean target. Callers can paint this
 * directly if they want to disclose the target numerically; we keep it as a
 * small helper so all chart users render it identically.
 */
@Composable
fun StackedDayBarChartTargetCaption(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = AccentSoft,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(top = 6.dp)
    )
}
