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
    // QA 2026-10 (second pass): stable internal key (flank-map lookup) so the
    // display label can be translated without breaking marker placement.
    val zoneKey: String,
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

// Y bands calibrated to the v43.13+ realistic A-pose demo_muscle_* renders
// (v43.18, 2026-10-23: the goal card moved from the old line-art silhouettes
// to the SAME A-pose figures used by the demo cards in Plano — the user QA
// flagged the two tabs showing two different generations of figures).
// v43.20 (2026-10-23): the MALE figures were regenerated to the user-confirmed
// female standard (A-pose with a 40-50px arm-torso gap, dark brown hair,
// bright white briefs — the old male lateral read as bald/clothless); the
// male frontal + lateral bands/flanks below were re-measured on the new
// PNGs. The female assets are unchanged.
// All coordinates are FULL-PNG fractions (the assets are 768×1344; the body
// spans differ per render because of head/hair padding: male frontal
// rows 34..1309, female frontal 34..1308 (v43.24 full-body single render —
// head to sole, Gemini inpainted feet, cleanly keyed), male lateral
// 34..1309, female lateral 115..1286).
// Bands were re-measured on the real PNGs (per-row ink segments, α>60)
// and are GENDER-AWARE — no shared dY shift.
private fun frontalBands(snap: PerformanceSnapshotEntity, t: SilhouetteTargets, isMale: Boolean, lang: String): List<ZoneBand> {
    return if (isMale) {
        listOf(
            ZoneBand("shoulder", tr("zone_shoulder", lang), 0.298f, 0.379f, validMeasure("shoulder", snap.frontalShoulderRatio), t.shoulder),
            ZoneBand("waist",    tr("zone_waist", lang),    0.402f, 0.491f, validMeasure("waist", snap.frontalWaistRatio), t.waist),
            ZoneBand("hip",      tr("zone_hip", lang),      0.491f, 0.550f, validMeasure("hip", snap.frontalHipRatio), t.hip),
            ZoneBand("thigh",    tr("zone_thigh", lang),    0.558f, 0.707f, validMeasure("thigh", snap.frontalThighRatio), t.thigh),
        )
    } else {
        // v43.24: re-measured on the full-body single render (body rows
        // 34..1308): crotch f0.51, knee f0.73, ankle f0.91 — the legs are
        // proportionally longer than the v43.23 composite (crotch f0.69), so
        // the thigh band is shorter and the hip band sits lower.
        listOf(
            ZoneBand("shoulder", tr("zone_shoulder", lang), 0.222f, 0.306f, validMeasure("shoulder", snap.frontalShoulderRatio), t.shoulder),
            ZoneBand("waist",    tr("zone_waist", lang),    0.426f, 0.479f, validMeasure("waist", snap.frontalWaistRatio), t.waist),
            ZoneBand("hip",      tr("zone_hip", lang),      0.568f, 0.642f, validMeasure("hip", snap.frontalHipRatio), t.hip),
            ZoneBand("thigh",    tr("zone_thigh", lang),    0.653f, 0.737f, validMeasure("thigh", snap.frontalThighRatio), t.thigh),
        )
    }
}

private fun lateralBands(snap: PerformanceSnapshotEntity, t: SilhouetteTargets, isMale: Boolean, lang: String): List<ZoneBand> {
    return if (isMale) {
        listOf(
            ZoneBand("chest",   tr("zone_chest", lang),   0.313f, 0.402f, validMeasure("chest", snap.lateralChestRatio), t.chest),
            ZoneBand("belly",   tr("zone_belly", lang),   0.402f, 0.513f, validMeasure("belly", snap.lateralBellyRatio), t.belly),
            ZoneBand("ltrhigh", tr("zone_lateralThigh", lang), 0.558f, 0.737f, validMeasure("ltrhigh", snap.lateralThighRatio), t.lateralThigh),
        )
    } else {
        listOf(
            ZoneBand("chest",   tr("zone_chest", lang),   0.347f, 0.417f, validMeasure("chest", snap.lateralChestRatio), t.chest),
            ZoneBand("belly",   tr("zone_belly", lang),   0.449f, 0.537f, validMeasure("belly", snap.lateralBellyRatio), t.belly),
            ZoneBand("ltrhigh", tr("zone_lateralThigh", lang), 0.589f, 0.715f, validMeasure("ltrhigh", snap.lateralThighRatio), t.lateralThigh),
        )
    }
}

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

private fun verdictLabel(band: ZoneBand, lang: String): String = when (band.verdict) {
    ZoneVerdict.GROW   -> tr("verdict_grow", lang) + deltaSuffix(band.deltaPct, lang)
    ZoneVerdict.REDUCE -> tr("verdict_reduce", lang) + deltaSuffix(band.deltaPct, lang)
    ZoneVerdict.HOLD   -> tr("verdict_hold", lang)
    ZoneVerdict.NO_TARGET -> tr("no_target", lang)
    ZoneVerdict.INVALID   -> tr("verdict_invalid", lang)
}

// QA 2026-10 (second pass): locale-aware decimal separator for the Δpp value.
private fun deltaSuffix(deltaPct: Float?, lang: String): String {
    if (deltaPct == null) return ""
    val mag = abs(deltaPct)
    if (mag < 0.1f) return ""
    return " ${if (deltaPct >= 0) "+" else "−"}" +
        String.format(localeForLanguage(lang), "%.1f", mag) + "pp"
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
// v43.18 (2026-10-23): the card now renders the SAME realistic A-pose figures
// as the Plano demo cards (demo_muscle_*), so the flank geometry below was
// re-measured on those PNGs (per-row α>60 ink segments; see SilhouetteAsset
// tests). One zone may have SEVERAL columns (the male frontal thighs are two
// separate columns). Edges are ABSOLUTE x in full-PNG-normalised coords at
// the band top/mid/bottom rows, following the torso/limb contour (arms
// excluded), so each stroke pair hugs the body outline.
private data class FlankCol(
    val lTop: Float, val lMid: Float, val lBot: Float,
    val rTop: Float, val rMid: Float, val rBot: Float,
)

private data class Flank(val cols: List<FlankCol>)

private val MALE_FRONTAL_FLANK = mapOf(
    "shoulder" to Flank(listOf(FlankCol(0.363f, 0.371f, 0.378f, 0.634f, 0.625f, 0.620f))),
    "waist"    to Flank(listOf(FlankCol(0.370f, 0.357f, 0.344f, 0.628f, 0.639f, 0.652f))),
    "hip"      to Flank(listOf(FlankCol(0.344f, 0.337f, 0.332f, 0.652f, 0.659f, 0.665f))),
    "thigh"    to Flank(listOf(
        FlankCol(0.332f, 0.349f, 0.357f, 0.490f, 0.469f, 0.449f),
        FlankCol(0.512f, 0.533f, 0.549f, 0.667f, 0.659f, 0.641f),
    )),
)
private val MALE_LATERAL_FLANK = mapOf(
    "chest"   to Flank(listOf(FlankCol(0.368f, 0.383f, 0.397f, 0.600f, 0.596f, 0.600f))),
    // The left (back) edge bulges out around f0.42 (lumbar curve) — the mid
    // sample sits on the bulge so the Bezier tracks it within ~8px.
    "belly"   to Flank(listOf(FlankCol(0.397f, 0.406f, 0.387f, 0.600f, 0.603f, 0.620f))),
    // The left (front) edge dips slightly below f0.65 before the knee;
    // a single quadratic follows it within ~9px over the band.
    "ltrhigh" to Flank(listOf(FlankCol(0.414f, 0.415f, 0.396f, 0.608f, 0.572f, 0.521f))),
)
private val FEMALE_FRONTAL_FLANK = mapOf(
    // v43.24: re-measured on the full-body single render (body rows
    // 34..1308; figure centred on axis x=384). Shoulder follows the
    // deltoid slope; waist/hip hug the torso/leg edges (arms excluded —
    // the A-pose hands drop to ~row 805); thigh hugs the OUTER leg edges.
    "shoulder" to Flank(listOf(FlankCol(0.314f, 0.306f, 0.296f, 0.688f, 0.694f, 0.704f))),
    "waist"    to Flank(listOf(FlankCol(0.368f, 0.357f, 0.346f, 0.633f, 0.643f, 0.654f))),
    "hip"      to Flank(listOf(FlankCol(0.335f, 0.341f, 0.354f, 0.665f, 0.659f, 0.646f))),
    // One stroke pair on the OUTER edges of both thighs (the female
    // design keeps a single column, unlike the male's two per-thigh
    // columns).
    "thigh"    to Flank(listOf(FlankCol(0.358f, 0.375f, 0.376f, 0.642f, 0.624f, 0.622f))),
)
private val FEMALE_LATERAL_FLANK = mapOf(
    // Top started at f0.30 — above that the back edge recedes fast
    // (0.632 → 0.615 between f0.28 and f0.30).
    "chest"   to Flank(listOf(FlankCol(0.423f, 0.426f, 0.438f, 0.615f, 0.609f, 0.599f))),
    "belly"   to Flank(listOf(FlankCol(0.435f, 0.395f, 0.398f, 0.600f, 0.591f, 0.586f))),
    "ltrhigh" to Flank(listOf(FlankCol(0.424f, 0.422f, 0.428f, 0.589f, 0.560f, 0.529f))),
)

private fun flankMapFor(isMale: Boolean, isFrontal: Boolean) = when {
    isMale  && isFrontal -> MALE_FRONTAL_FLANK
    isMale  && !isFrontal -> MALE_LATERAL_FLANK
    !isMale && isFrontal -> FEMALE_FRONTAL_FLANK
    else                  -> FEMALE_LATERAL_FLANK
}

// PNG intrinsic aspect (width/height) per gender+view, for ContentScale.Fit mapping.
// v43.18: all four demo_muscle_* assets share the same 768×1344 canvas.
private fun pngAspectFor(isMale: Boolean, isFrontal: Boolean): Float = 768f / 1344f

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
        val flank = flankMap[band.zoneKey] ?: return@forEach

        val yTop = py(band.yTop)
        val yMid = py(band.midY)
        val yBot = py(band.yBottom)

        val stroke = 3f
        val alpha = if (band.verdict == ZoneVerdict.GROW ||
                        band.verdict == ZoneVerdict.REDUCE ||
                        band.verdict == ZoneVerdict.HOLD) 0.9f else 0.55f

        // One stroke pair per body column (the male frontal thighs are two).
        flank.cols.forEach { col ->
            val right = Path().apply {
                moveTo(px(col.rTop), yTop)
                quadraticBezierTo(px(col.rMid), yMid, px(col.rBot), yBot)
            }
            val left = Path().apply {
                moveTo(px(col.lTop), yTop)
                quadraticBezierTo(px(col.lMid), yMid, px(col.lBot), yBot)
            }
            drawPath(right, color = color.copy(alpha = alpha), style = Stroke(width = stroke))
            drawPath(left,  color = color.copy(alpha = alpha), style = Stroke(width = stroke))

            if (band.verdict == ZoneVerdict.GROW || band.verdict == ZoneVerdict.REDUCE) {
                val inward = band.verdict == ZoneVerdict.REDUCE
                val arrowLen = renderW * 0.045f
                val a = color.copy(alpha = 0.9f)
                drawArrow(Offset(px(col.rMid), yMid), toward = if (inward) -1f else 1f, len = arrowLen, color = a)
                drawArrow(Offset(px(col.lMid), yMid), toward = if (inward) 1f else -1f, len = arrowLen, color = a)
            }
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
    lang: String,
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
                tr("goal_silhouette_title", lang),
                color = AccentSoft,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )

            if (!targets.hasAny) {
                Text(
                    tr("goal_silhouette_no_targets", lang),
                    color = Color.White.copy(0.6f),
                    fontSize = 11.sp,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SilhouettePane(
                    modifier  = Modifier.weight(1f),
                    label     = tr("view_frontal", lang),
                    bands     = frontalBands(snap, targets, isMale, lang),
                    isMale    = isMale,
                    isFrontal = true,
                    lang      = lang,
                )
                SilhouettePane(
                    modifier  = Modifier.weight(1f),
                    label     = tr("view_lateral", lang),
                    bands     = lateralBands(snap, targets, isMale, lang),
                    isMale    = isMale,
                    isFrontal = false,
                    lang      = lang,
                )
            }

            // v43.18: the schematic-figure disclaimer is gone — the card now
            // renders the same realistic A-pose figures as the Plano demo
            // cards (demo_muscle_*), so the old line-art contour-gap caveat no
            // longer applies.

            // Legend — maps colours to meaning (shown only when any target exists).
            if (targets.hasAny) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    LegendDot(colorGrow,   tr("verdict_grow", lang))
                    LegendDot(colorReduce, tr("verdict_reduce", lang))
                    LegendDot(colorHold,   tr("verdict_hold", lang))
                    LegendDot(colorMuted,  tr("no_target", lang))
                }
            }

            Text(
                tr("perf_zoom_hint", lang) + " · " + tr("perf_pp_legend", lang),
                color = Color.White.copy(alpha = 0.5f),
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
    lang: String,
) {
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(0.6f), fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                // QA 2026-10: fixed height instead of aspectRatio(0.28f). At a
                // ~148dp pane width the old ratio produced a ~1585px-tall pane
                // that pushed the whole silhouette card (and the zone rows)
                // below the fold. 340dp keeps the card on one screen; the
                // zone markers map through the pane size, so they adapt.
                .height(340.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.06f))
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
            // v43.18 (2026-10-23): same realistic A-pose figures as the Plano
            // demo cards (user QA: the two tabs must show the same generation
            // of figures). No white tint — the renders are realistic.
            val drawableRes = when {
                isFrontal &&  isMale -> R.drawable.demo_muscle_male_frontal
                isFrontal && !isMale -> R.drawable.demo_muscle_female_frontal
                !isFrontal && isMale -> R.drawable.demo_muscle_male_lateral
                else                 -> R.drawable.demo_muscle_female_lateral
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
                ZoneRow(band, lang)
            }
        }
    }
}

@Composable
private fun ZoneRow(band: ZoneBand, lang: String) {
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
            color = Color.White.copy(0.8f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            verdictLabel(band, lang),
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
        Text(label, color = Color.White.copy(0.65f), fontSize = 10.sp)
    }
}
