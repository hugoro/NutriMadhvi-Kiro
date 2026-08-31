package com.example.data.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Renders a single 2-row "progress card" bitmap for proactive notifications
 * (Bloco G of the proactive plan v3, 2026-06-22). Designed to be attached
 * to a [androidx.core.app.NotificationCompat.BigPictureStyle] so the user
 * gets a glanceable visual of where today's macros / activity stand without
 * having to parse ASCII bars (the v2 approach which the user explicitly
 * flagged as "muito seco").
 *
 * Pure-Kotlin / pure-Canvas: no Context, no Compose, no resources. The
 * caller hands in [MetricRow]s and the renderer paints them on an
 * `ARGB_8888` bitmap that matches the dashboard navy palette
 * (`#0F172A`).
 *
 * Reuses the Paint pattern from `ReportRenderer.kt:68-90`. Tone is
 * deliberately neutral — overshoot (e.g. 209% steps) shows as a second
 * segment in a DARKER shade of the same base colour (Hugo 2026-06-23:
 * yellow/orange reads as warning; same-hue-darker reads as "fizeste mais"
 * without the danger semantic). The percentage label states the real
 * number; NO red / NO warning glyph.
 *
 * Layout v2 (Hugo 2026-06-23):
 *  - Label (left, bold) and percentage (right, subtle) on the top line.
 *  - Bar in the middle row.
 *  - Two value labels BELOW the bar with little tick marks: the goal
 *    sits exactly under the colour-transition point; the current value
 *    sits exactly under the bar's right edge.
 */
internal object NotificationBitmapRenderer {

    /** One metric to render. [current] / [target] drives the bar fill. When
     *  current > target, the bar grows beyond the 100% mark and the extra
     *  portion is painted in a darker shade of [barColorHex] (see
     *  [darkenForOvershoot]) instead of being clamped — the user gets an
     *  instant visual read of "limite esperado vs valor real".
     *  [barColorHex] expects an `0xAARRGGBB` literal — pass the same hex
     *  values used by the dashboard. */
    data class MetricRow(
        val label: String,
        val current: Int,
        val target: Int,
        val unit: String,
        val barColorHex: Long,
    )

    // Dashboard palette (matches the navy used by the Painel tab + share
    // capture). Hard-coded rather than read from theme — notification body
    // is rendered out-of-process and the device's theme provider may not
    // be available.
    private const val BG = 0xFF0F172AL
    private const val TEXT = 0xFFFFFFFFL
    private const val TEXT_SUBTLE = 0xFF94A3B8L
    private const val BAR_TRACK = 0x33FFFFFFL // 20% white over navy

    /**
     * Renders [rows] vertically. Returns an `ARGB_8888` bitmap sized
     * [widthPx] × computed-height. Bitmap is owned by the caller — call
     * [Bitmap.recycle] is NOT needed because NotificationCompat keeps a
     * reference until the notification is dismissed.
     */
    fun renderProgressBars(
        rows: List<MetricRow>,
        widthPx: Int = 1024,
    ): Bitmap {
        require(rows.isNotEmpty()) { "renderProgressBars: at least one row required" }

        // Geometry. Designed so a 2-row card fits an Android notification
        // expanded big-picture area without scrolling on a Pixel-class
        // device. The image scales down on smaller screens; lines stay
        // readable down to ~720px wide.
        val sidePad = 56f
        val topPad = 48f
        val bottomPad = 56f
        val rowGap = 64f
        val labelToBarGap = 18f
        val barToTickGap = 10f
        val tickHeight = 14f
        val tickToValueGap = 10f
        val barHeight = 36f
        val cornerRadius = barHeight / 2f
        val labelTextSize = 48f
        val pctTextSize = 40f
        val valueTextSize = 36f

        // Per-row block = label/pct line + bar + tick + value labels below.
        val rowHeight = labelTextSize + labelToBarGap + barHeight +
            barToTickGap + tickHeight + tickToValueGap + valueTextSize
        val heightPx = (topPad + rows.size * rowHeight + (rows.size - 1) * rowGap + bottomPad).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background — solid navy, no gradient. Cleanest at small zoom.
        canvas.drawColor(BG.toInt())

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT.toInt()
            textSize = labelTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT.toInt()
            textSize = pctTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val valueCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_SUBTLE.toInt()
            textSize = valueTextSize
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BAR_TRACK.toInt()
            style = Paint.Style.FILL
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        // Stroke around the surplus segment (Hugo 2026-06-23 v4):
        // single-colour bar + white outline around the portion above the
        // goal reads as "isto é o bónus" without the "still empty" feel of
        // a darker shade or a hue-shifted segment. Stroke width is small so
        // it doesn't overpower the bar on smaller densities.
        val surplusOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT.toInt() // white
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_SUBTLE.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }

        val barLeft = sidePad
        val barRight = widthPx - sidePad
        val barWidth = barRight - barLeft

        rows.forEachIndexed { index, row ->
            val blockTop = topPad + index * (rowHeight + rowGap)

            // Line 1: label (left) + percentage (right, white bold). The
            // numeric "920 / 500" pair is GONE from the top row — Hugo
            // 2026-06-23 said it reads as duplicated noise once the per-mark
            // labels below the bar exist.
            val labelBaseline = blockTop + labelTextSize
            canvas.drawText(row.label, barLeft, labelBaseline, labelPaint)
            val realPctValue = if (row.target > 0) {
                (row.current.toFloat() / row.target.toFloat() * 100f).toInt()
            } else 0
            if (row.target > 0) {
                canvas.drawText("$realPctValue%", barRight, labelBaseline, pctPaint)
            }

            // Line 2: bar — two-segment when there's overshoot. The bar's full
            // physical width represents max(100%, realPct). That way the colour
            // change between base and OVERSHOOT happens *exactly* at the 100%
            // mark, giving the user an instant visual read of "limite esperado
            // vs valor real" without needing a separator line. When realPct
            // ≤ 100, this collapses to a single-segment bar identical to the
            // pre-2026-06-23 rendering.
            val barTop = labelBaseline + labelToBarGap
            val barBottom = barTop + barHeight
            val realRatio = if (row.target > 0) {
                (row.current.toFloat() / row.target.toFloat()).coerceAtLeast(0f)
            } else 0f
            val totalSpan = maxOf(1f, realRatio) // 1f = 100% mark

            // Track spans the full physical width — represents totalSpan.
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barRight, barBottom),
                cornerRadius, cornerRadius, trackPaint
            )

            // Position of the "100% mark" on the X axis. When real ≤ 100,
            // that's exactly the right edge of the bar (= barRight). When
            // real > 100, it's the colour-transition point inside the bar.
            val goalX = barLeft + barWidth * (1f / totalSpan)
            // Position of the "current value" on the X axis. Always the end
            // of the coloured fill — when real ≤ 100 it's somewhere inside
            // the track; when real > 100 it's the right edge of the bar.
            val currentX = barLeft + barWidth * (minOf(realRatio, totalSpan) / totalSpan)

            if (realRatio > 0f) {
                // Single-segment fill in the base colour, spanning from 0%
                // up to min(real, totalSpan). When real ≤ 100, that's the
                // usual progress bar. When real > 100, the fill goes all
                // the way to barRight (because totalSpan == realRatio).
                val fillRight = barLeft + barWidth * (minOf(realRatio, totalSpan) / totalSpan)
                fillPaint.color = row.barColorHex.toInt()
                canvas.drawRoundRect(
                    RectF(barLeft, barTop, fillRight, barBottom),
                    cornerRadius, cornerRadius, fillPaint
                )
                // Surplus outline: a white rounded-rect stroke wrapping the
                // portion of the bar between the goal mark and the right
                // edge. Drawn slightly outside the bar (±halfStroke) so the
                // outline reads as a frame "around" the surplus rather than
                // a line on top of it.
                if (realRatio > 1f) {
                    val halfStroke = surplusOutlinePaint.strokeWidth / 2f
                    val outlineRadius = cornerRadius + halfStroke
                    val goalX = barLeft + barWidth * (1f / totalSpan)
                    canvas.drawRoundRect(
                        RectF(
                            goalX,
                            barTop - halfStroke,
                            barRight + halfStroke,
                            barBottom + halfStroke,
                        ),
                        outlineRadius, outlineRadius, surplusOutlinePaint,
                    )
                }
            }

            // Line 3: tick marks below the bar at the goal X and the current X.
            // When the two are within a hair of each other (no overshoot AND
            // ratio ≈ 1), drawing both ticks would just stack — skip the
            // current-X tick in that case so we don't get a fat dash.
            val tickTop = barBottom + barToTickGap
            val tickBottom = tickTop + tickHeight
            if (row.target > 0) {
                canvas.drawLine(goalX, tickTop, goalX, tickBottom, tickPaint)
            }
            val ticksOverlap = kotlin.math.abs(currentX - goalX) < (tickPaint.strokeWidth * 2f)
            if (row.target > 0 && !ticksOverlap) {
                canvas.drawLine(currentX, tickTop, currentX, tickBottom, tickPaint)
            }

            // Line 4: value labels — goal centred under its tick, current
            // centred under its tick. Use thousand-separated format for both.
            val valueBaseline = tickBottom + tickToValueGap + valueTextSize
            if (row.target > 0) {
                val goalLabel = formatThousands(row.target) + row.unit
                val clampedGoalX = clampForCentered(goalX, goalLabel, valueCenterPaint, barLeft, barRight)
                canvas.drawText(goalLabel, clampedGoalX, valueBaseline, valueCenterPaint)
            }
            val currentLabel = formatThousands(row.current) + row.unit
            // Skip the duplicate "current == goal" label when ticks overlap
            // (avoids two identical numbers stacking on the same X).
            if (!ticksOverlap || row.target <= 0) {
                val clampedCurrentX = clampForCentered(currentX, currentLabel, valueCenterPaint, barLeft, barRight)
                canvas.drawText(currentLabel, clampedCurrentX, valueBaseline, valueCenterPaint)
            }
        }

        return bitmap
    }

    /** Keep a centered-aligned text label fully inside [minX]..[maxX] when
     *  the requested anchor [x] would push half of it past the edge of the
     *  bar. Returns the clamped X to pass to `drawText` (Align.CENTER). */
    private fun clampForCentered(
        x: Float,
        text: String,
        paint: Paint,
        minX: Float,
        maxX: Float,
    ): Float {
        val halfWidth = paint.measureText(text) / 2f
        return x.coerceIn(minX + halfWidth, maxX - halfWidth)
    }

    /** "13845" → "13 845". Non-breaking space so the value never wraps
     *  mid-number. */
    private fun formatThousands(n: Int): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return sb.toString()
    }

    // Public colour constants — alinhadas com os cartões já visíveis na
    // app (Hugo 2026-06-23): se o utilizador reconhece uma cor associada
    // a uma métrica no Dashboard, a notificação tem de usar a MESMA cor.
    //   - KCAL: amarelo do cartão "Consumo de Hoje" (ChatScreen.kt:960).
    //   - STEPS: verde-lima do dot "Steps count" no cartão Activity Metrics
    //            (ChatScreen.kt:1214).
    //   - ACTIVE_KCAL: rosa-magenta do dot "Calories Burned" no mesmo
    //                  cartão (ChatScreen.kt:1278).
    //   - PROTEIN: mantém o verde-esmeralda que já era partilhado com a
    //     proteína em vários sítios.
    object Palette {
        const val KCAL: Long = 0xFFEAB308L          // amarelo Consumo de Hoje
        const val PROTEIN: Long = 0xFF10B981L       // verde-esmeralda
        const val CARBS: Long = 0xFF8B5CF6L         // violet — espelha barra Carboidratos do dashboard
        const val FAT: Long = 0xFFF59E0BL           // âmbar — distinto do KCAL (amarelo) p/ separar visualmente
        const val STEPS: Long = 0xFF4ADE80L         // verde-lima S-Health Steps
        const val ACTIVE_KCAL: Long = 0xFFEC4899L   // rosa S-Health Calories Burned
    }

    /**
     * Returns a darker shade of [baseColor]. Currently unused — kept around
     * because the surplus-rendering strategy has flip-flopped (dourado →
     * tom escuro → tom claro → contorno branco) and the next iteration may
     * want a same-hue variant again. Delete if a future cleanup wants it
     * gone.
     */
    @Suppress("unused")
    private fun darkenForOvershoot(baseColor: Long, factor: Float = 0.45f): Int {
        val a = ((baseColor ushr 24) and 0xFFL).toInt()
        val r = ((baseColor ushr 16) and 0xFFL).toInt()
        val g = ((baseColor ushr 8) and 0xFFL).toInt()
        val b = (baseColor and 0xFFL).toInt()
        // Linear interpolate toward 255 (white) by [factor].
        val br = (r + (255 - r) * factor).toInt().coerceIn(0, 255)
        val bg = (g + (255 - g) * factor).toInt().coerceIn(0, 255)
        val bb = (b + (255 - b) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (br shl 16) or (bg shl 8) or bb
    }
}
