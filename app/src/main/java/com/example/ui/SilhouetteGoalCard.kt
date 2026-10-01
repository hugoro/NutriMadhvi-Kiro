package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.PerformanceGoalEntity
import com.example.data.local.PerformanceSnapshotEntity
import com.example.ui.theme.AccentSoft
import org.json.JSONObject
import kotlin.math.abs

// ─── Targets (coach-recommended ratios) ────────────────────────────────────────
//
// IMPORTANT: targets come EXCLUSIVELY from the coach's recommendation
// (PerformanceGoalEntity.coachTargetJson). There are NO aesthetic default
// targets — if the coach has not produced a recommendation, no zone is coloured
// and the silhouette is shown neutral with an explanatory note. This avoids
// painting a generic "ideal" that contradicts what the coach actually said.

private data class SilhouetteTargets(
    val shoulder: Float?,
    val waist: Float?,
    val hip: Float?,
    val thigh: Float?,
    val chest: Float?,
    val belly: Float?,
    val lateralThigh: Float?,
) {
    val hasAny: Boolean
        get() = listOf(shoulder, waist, hip, thigh, chest, belly, lateralThigh).any { it != null }
}

private val EMPTY_TARGETS = SilhouetteTargets(null, null, null, null, null, null, null)

private fun parseTargets(json: String?): SilhouetteTargets {
    if (json.isNullOrBlank()) return EMPTY_TARGETS
    return try {
        val j = JSONObject(json)
        fun f(key: String): Float? =
            if (j.has(key) && !j.isNull(key)) j.optDouble(key).toFloat() else null
        SilhouetteTargets(
            shoulder     = f("shoulder"),
            waist        = f("waist"),
            hip          = f("hip"),
            thigh        = f("thigh"),
            chest        = f("chest"),
            belly        = f("belly"),
            lateralThigh = f("lateralThigh"),
        )
    } catch (_: Exception) { EMPTY_TARGETS }
}

// ─── Physiological validity gate ────────────────────────────────────────────────
//
// Pose-estimation noise occasionally yields impossible silhouette ratios
// (e.g. a shoulder width of 0.06 of body height, or a thigh of 0.36). Painting
// a colour from such a value would read as a recommendation when it is really a
// failed measurement. We clamp each measured ratio to a plausible physiological
// range per zone; anything outside is treated as "no valid measurement".

private val VALID_RANGE = mapOf(
    "shoulder" to (0.18f..0.55f),
    "waist"    to (0.12f..0.45f),
    "hip"      to (0.15f..0.50f),
    "thigh"    to (0.08f..0.30f),
    "chest"    to (0.12f..0.40f),
    "belly"    to (0.10f..0.40f),
    "ltrhigh"  to (0.08f..0.30f),
)

private fun validMeasure(key: String, raw: Float?): Float? {
    val r = raw ?: return null
    if (r.isNaN() || r <= 0f) return null
    val range = VALID_RANGE[key] ?: return r
    return if (r in range) r else null
}

// ─── Zone model ─────────────────────────────────────────────────────────────────

private enum class ZoneVerdict { GROW, REDUCE, HOLD, NO_TARGET, INVALID }

private data class ZoneBand(
    val label: String,
    val yTop: Float,          // normalised to pane height
    val yBottom: Float,
    val current: Float?,      // validated measured ratio — null = invalid/unmeasured
    val target: Float?,       // coach-recommended ratio — null = coach has no target
) {
    val midY: Float get() = (yTop + yBottom) / 2f

    val verdict: ZoneVerdict
        get() = when {
            current == null -> ZoneVerdict.INVALID
            target == null  -> ZoneVerdict.NO_TARGET
            current < target - HOLD_EPS -> ZoneVerdict.GROW
            current > target + HOLD_EPS -> ZoneVerdict.REDUCE
            else -> ZoneVerdict.HOLD
        }

    /** Signed delta current→target as a percentage of body height. null when no comparison. */
    val deltaPct: Float?
        get() = if (current != null && target != null) (target - current) * 100f else null

    companion object { const val HOLD_EPS = 0.012f }
}

// Y bands calibrated to human proportions mapped onto the PNG figure
// (body spans PNG-norm 0.087→0.898; landmarks from the 7.5-head canon).
private fun frontalBands(snap: PerformanceSnapshotEntity, t: SilhouetteTargets) = listOf(
    ZoneBand("Ombros", 0.22f, 0.29f, validMeasure("shoulder", snap.frontalShoulderRatio), t.shoulder),
    ZoneBand("Cintura", 0.38f, 0.44f, validMeasure("waist", snap.frontalWaistRatio), t.waist),   // navel / abdomen
    ZoneBand("Ancas",  0.46f, 0.53f, validMeasure("hip", snap.frontalHipRatio), t.hip),          // pelvis, before legs split
    ZoneBand("Coxas",  0.56f, 0.70f, validMeasure("thigh", snap.frontalThighRatio), t.thigh),    // upper→mid thigh
)

private fun lateralBands(snap: PerformanceSnapshotEntity, t: SilhouetteTargets) = listOf(
    ZoneBand("Peito",   0.27f, 0.36f, validMeasure("chest", snap.lateralChestRatio), t.chest),   // chest, deepest
    ZoneBand("Barriga", 0.43f, 0.51f, validMeasure("belly", snap.lateralBellyRatio), t.belly),   // belly bulge
    ZoneBand("Coxas",   0.58f, 0.72f, validMeasure("ltrhigh", snap.lateralThighRatio), t.lateralThigh),
)

// ─── Verdict colours & copy ─────────────────────────────────────────────────────

private val colorGrow   = Color(0xFF4ADE80)   // green — coach wants this zone larger
private val colorReduce = Color(0xFFF87171)   // red — coach wants this zone smaller
private val colorHold   = Color(0xFF93C5FD)   // blue — on target, hold
private val colorMuted  = Color(0xFF64748B)   // slate — no target / invalid

private fun verdictColor(v: ZoneVerdict): Color = when (v) {
    ZoneVerdict.GROW   -> colorGrow
    ZoneVerdict.REDUCE -> colorReduce
    ZoneVerdict.HOLD   -> colorHold
    ZoneVerdict.NO_TARGET, ZoneVerdict.INVALID -> colorMuted
}

private fun verdictLabel(band: ZoneBand): String = when (band.verdict) {
    ZoneVerdict.GROW   -> "aumentar" + deltaSuffix(band.deltaPct)
    ZoneVerdict.REDUCE -> "reduzir"  + deltaSuffix(band.deltaPct)
    ZoneVerdict.HOLD   -> "manter"
    ZoneVerdict.NO_TARGET -> "sem alvo"
    ZoneVerdict.INVALID   -> "medição inválida"
}

private fun deltaSuffix(deltaPct: Float?): String {
    if (deltaPct == null) return ""
    val mag = abs(deltaPct)
    if (mag < 0.1f) return ""
    return " ${if (deltaPct >= 0) "+" else "−"}${"%.1f".format(mag)}pp"
}

// ─── Zone marker overlay (contour-parallel strokes, not filled ovals) ───────────
//
// For each zone we draw two short vertical strokes that run PARALLEL to the body's
// left and right flank at that zone, in the verdict colour. The stroke hugs the
// silhouette outline (slightly outside it) so it reads as "this is your edge here".
// The textual label (zone name + verdict + Δ) is rendered OUTSIDE the Canvas, in a
// legend list below each pane — so the data is readable as words, not guessed.

// Body half-width per zone (top/mid/bot) AND the body axis X, all in PNG-norm
// coordinates [0,1] relative to the PNG image — NOT the pane. The PNG figure is
// not centred (arms hang right) and ContentScale.Fit letterboxes it inside the
// pane, so markers must be mapped through the rendered PNG rect, not the Canvas
// centre. [axis] is the body's vertical centre line at that zone.
private data class Flank(val axis: Float, val top: Float, val mid: Float, val bot: Float)

// Half-widths are the TORSO/limb flank (conservative, inside the arms), as a
// fraction of the PNG width. Axis measured from the PNG per gender.
// MALE PNG: figure hangs right → axis ≈0.60 frontal, ≈0.47 lateral.
private val MALE_FRONTAL_FLANK = mapOf(
    "Ombros"  to Flank(0.60f, 0.22f, 0.30f, 0.30f),
    "Cintura" to Flank(0.60f, 0.26f, 0.25f, 0.26f),
    "Ancas"   to Flank(0.60f, 0.27f, 0.30f, 0.28f),
    "Coxas"   to Flank(0.60f, 0.24f, 0.20f, 0.17f),
)
private val MALE_LATERAL_FLANK = mapOf(
    "Peito"   to Flank(0.47f, 0.30f, 0.34f, 0.34f),
    "Barriga" to Flank(0.46f, 0.34f, 0.36f, 0.31f),
    "Coxas"   to Flank(0.48f, 0.26f, 0.24f, 0.21f),
)
// FEMALE PNG: near-centred (axis ≈0.51 frontal, ≈0.49 lateral). Narrower waist,
// wider hips — torso flanks conservative (inside arms).
private val FEMALE_FRONTAL_FLANK = mapOf(
    "Ombros"  to Flank(0.51f, 0.20f, 0.26f, 0.26f),
    "Cintura" to Flank(0.51f, 0.22f, 0.20f, 0.23f),   // marked waist
    "Ancas"   to Flank(0.51f, 0.24f, 0.29f, 0.27f),   // wider hips
    "Coxas"   to Flank(0.51f, 0.24f, 0.19f, 0.16f),
)
private val FEMALE_LATERAL_FLANK = mapOf(
    "Peito"   to Flank(0.49f, 0.24f, 0.26f, 0.21f),
    "Barriga" to Flank(0.49f, 0.26f, 0.29f, 0.26f),
    "Coxas"   to Flank(0.49f, 0.22f, 0.17f, 0.18f),
)

private fun flankMapFor(isMale: Boolean, isFrontal: Boolean) = when {
    isMale  && isFrontal -> MALE_FRONTAL_FLANK
    isMale  && !isFrontal -> MALE_LATERAL_FLANK
    !isMale && isFrontal -> FEMALE_FRONTAL_FLANK
    else                  -> FEMALE_LATERAL_FLANK
}

// PNG intrinsic aspect (width/height) per gender+view, for ContentScale.Fit mapping.
private fun pngAspectFor(isMale: Boolean, isFrontal: Boolean): Float = when {
    isMale  && isFrontal -> 300f / 1224f
    isMale  && !isFrontal -> 300f / 2141f
    !isMale && isFrontal -> 300f / 869f
    else                  -> 300f / 1558f
}

private fun DrawScope.drawZoneMarkers(bands: List<ZoneBand>, isFrontal: Boolean, isMale: Boolean) {
    val flankMap = flankMapFor(isMale, isFrontal)

    // Rendered PNG rect inside the pane (ContentScale.Fit). The PNG fills height,
    // leaving horizontal letterbox margins. pngW/pngH are the PNG's intrinsic size.
    val pngAspect = pngAspectFor(isMale, isFrontal)
    val paneAspect = size.width / size.height
    val renderW: Float
    val renderH: Float
    if (pngAspect < paneAspect) {
        // PNG narrower than pane → fills height, letterbox left/right.
        renderH = size.height
        renderW = size.height * pngAspect
    } else {
        renderW = size.width
        renderH = size.width / pngAspect
    }
    val originX = (size.width - renderW) / 2f
    val originY = (size.height - renderH) / 2f

    // Map PNG-norm [0,1] → Canvas px.
    fun px(xNorm: Float) = originX + xNorm * renderW
    fun py(yNorm: Float) = originY + yNorm * renderH

    bands.forEach { band ->
        if (band.verdict == ZoneVerdict.INVALID && band.target == null) return@forEach
        val color = verdictColor(band.verdict)
        val flank = flankMap[band.label] ?: Flank(0.5f, 0.3f, 0.3f, 0.3f)

        val yTop = py(band.yTop)
        val yMid = py(band.midY)
        val yBot = py(band.yBottom)
        // Half-widths scaled by rendered PNG width (not pane width).
        val hTop = flank.top * renderW
        val hMid = flank.mid * renderW
        val hBot = flank.bot * renderW
        val axisX = px(flank.axis)

        val stroke = 3f
        val alpha = if (band.verdict == ZoneVerdict.GROW ||
                        band.verdict == ZoneVerdict.REDUCE ||
                        band.verdict == ZoneVerdict.HOLD) 0.9f else 0.55f

        val right = Path().apply {
            moveTo(axisX + hTop, yTop)
            quadraticBezierTo(axisX + hMid, yMid, axisX + hBot, yBot)
        }
        val left = Path().apply {
            moveTo(axisX - hTop, yTop)
            quadraticBezierTo(axisX - hMid, yMid, axisX - hBot, yBot)
        }
        drawPath(right, color = color.copy(alpha = alpha), style = Stroke(width = stroke))
        drawPath(left,  color = color.copy(alpha = alpha), style = Stroke(width = stroke))

        if (band.verdict == ZoneVerdict.GROW || band.verdict == ZoneVerdict.REDUCE) {
            val inward = band.verdict == ZoneVerdict.REDUCE
            val arrowLen = renderW * 0.045f
            val a = color.copy(alpha = 0.9f)
            drawArrow(Offset(axisX + hMid, yMid), toward = if (inward) -1f else 1f, len = arrowLen, color = a)
            drawArrow(Offset(axisX - hMid, yMid), toward = if (inward) 1f else -1f, len = arrowLen, color = a)
        }
    }
}

/** Small horizontal arrow: [toward] = +1 points right, -1 points left. */
private fun DrawScope.drawArrow(at: Offset, toward: Float, len: Float, color: Color) {
    val tip = Offset(at.x + toward * len, at.y)
    drawLine(color, at, tip, strokeWidth = 3f)
    val head = len * 0.4f
    drawLine(color, tip, Offset(tip.x - toward * head, tip.y - head), strokeWidth = 3f)
    drawLine(color, tip, Offset(tip.x - toward * head, tip.y + head), strokeWidth = 3f)
}

// ─── Main card ────────────────────────────────────────────────────────────────

@Composable
fun SilhouetteGoalCard(
    snap: PerformanceSnapshotEntity,
    latestGoal: PerformanceGoalEntity?,
    isMale: Boolean,
) {
    val targets = remember(latestGoal?.coachTargetJson) {
        parseTargets(latestGoal?.coachTargetJson)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131928),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Silhueta com objetivos",
                color = AccentSoft,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )

            if (!targets.hasAny) {
                Text(
                    "O coach ainda não gerou alvos de silhueta para o teu objetivo. " +
                        "Guarda um objetivo e corre uma avaliação — as recomendações por " +
                        "zona aparecem aqui.",
                    color = Color.White.copy(0.45f),
                    fontSize = 11.sp,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SilhouettePane(
                    modifier  = Modifier.weight(1f),
                    label     = "Frontal",
                    bands     = frontalBands(snap, targets),
                    isMale    = isMale,
                    isFrontal = true,
                )
                SilhouettePane(
                    modifier  = Modifier.weight(1f),
                    label     = "Lateral",
                    bands     = lateralBands(snap, targets),
                    isMale    = isMale,
                    isFrontal = false,
                )
            }

            // Legend — maps colours to meaning (shown only when any target exists).
            if (targets.hasAny) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    LegendDot(colorGrow,   "aumentar")
                    LegendDot(colorReduce, "reduzir")
                    LegendDot(colorHold,   "manter")
                    LegendDot(colorMuted,  "sem alvo")
                }
            }

            Text(
                "Aperta para ampliar · pp = pontos percentuais da altura",
                color = Color.White.copy(alpha = 0.25f),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// ─── Pane ─────────────────────────────────────────────────────────────────────

@Composable
private fun SilhouettePane(
    modifier: Modifier,
    label: String,
    bands: List<ZoneBand>,
    isMale: Boolean,
    isFrontal: Boolean,
) {
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(0.40f), fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.28f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.03f))
                // Pinch-to-zoom WITHOUT stealing vertical scroll from the parent
                // LazyColumn. A single-finger drag is only consumed once we are
                // already zoomed (scale > 1) so the user can pan the zoomed image;
                // at scale == 1 single-finger drags fall through to the list and
                // the page scrolls normally. Multi-finger (pinch) is always ours.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val multiTouch = event.changes.count { it.pressed } >= 2
                            // Only act (and consume) for a pinch, or panning while
                            // already zoomed. Otherwise let the scroll through.
                            if (multiTouch || scale > 1f) {
                                scale = (scale * zoomChange).coerceIn(1f, 4f)
                                val maxX = (size.width * (scale - 1)) / 2f
                                val maxY = (size.height * (scale - 1)) / 2f
                                offset = Offset(
                                    (offset.x + panChange.x * scale).coerceIn(-maxX, maxX),
                                    (offset.y + panChange.y * scale).coerceIn(-maxY, maxY),
                                )
                                if (scale <= 1f) offset = Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val drawableRes = when {
                isFrontal &&  isMale -> R.drawable.silhouette_male_frontal
                isFrontal && !isMale -> R.drawable.silhouette_female_frontal
                !isFrontal && isMale -> R.drawable.silhouette_male_lateral
                else                 -> R.drawable.silhouette_female_lateral
            }
            Image(
                painter = painterResource(drawableRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    ),
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.70f)),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    )
            ) {
                drawZoneMarkers(bands, isFrontal, isMale)
            }
        }

        // Zone verdict list — the data in words, below the pane.
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            bands.forEach { band ->
                // Hide zones that are both unmeasured and un-targeted.
                if (band.verdict == ZoneVerdict.INVALID && band.target == null) return@forEach
                ZoneRow(band)
            }
        }
    }
}

@Composable
private fun ZoneRow(band: ZoneBand) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(7.dp)) {
            drawCircle(color = verdictColor(band.verdict), radius = size.minDimension / 2f)
        }
        Text(
            band.label,
            color = Color.White.copy(0.72f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            verdictLabel(band),
            color = verdictColor(band.verdict).copy(
                alpha = if (band.verdict == ZoneVerdict.INVALID ||
                            band.verdict == ZoneVerdict.NO_TARGET) 0.6f else 0.95f,
            ),
            fontSize = 10.sp,
        )
    }
}

// ─── Legend ───────────────────────────────────────────────────────────────────

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2f)
        }
        Text(label, color = Color.White.copy(0.50f), fontSize = 10.sp)
    }
}
