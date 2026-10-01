package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.PerformanceGoalEntity
import com.example.data.local.PerformanceSnapshotEntity
import com.example.ui.theme.AccentSoft
import org.json.JSONObject

// ─── Scientific fallback targets by gender ───────────────────────────────────

private data class SilhouetteTargets(
    val shoulder: Float,
    val waist: Float,
    val hip: Float,
    val thigh: Float,
    val chest: Float,
    val belly: Float,
    val lateralThigh: Float,
)

private val MALE_DEFAULTS = SilhouetteTargets(
    shoulder = 0.370f, waist = 0.248f, hip = 0.300f, thigh = 0.200f,
    chest = 0.220f, belly = 0.210f, lateralThigh = 0.185f,
)
private val FEMALE_DEFAULTS = SilhouetteTargets(
    shoulder = 0.330f, waist = 0.228f, hip = 0.330f, thigh = 0.220f,
    chest = 0.240f, belly = 0.200f, lateralThigh = 0.195f,
)

private fun parseTargets(json: String?, isMale: Boolean): SilhouetteTargets {
    val base = if (isMale) MALE_DEFAULTS else FEMALE_DEFAULTS
    if (json == null) return base
    return try {
        val j = JSONObject(json)
        base.copy(
            shoulder     = j.optDouble("shoulder",     base.shoulder.toDouble()).toFloat(),
            waist        = j.optDouble("waist",        base.waist.toDouble()).toFloat(),
            hip          = j.optDouble("hip",          base.hip.toDouble()).toFloat(),
            thigh        = j.optDouble("thigh",        base.thigh.toDouble()).toFloat(),
            chest        = j.optDouble("chest",        base.chest.toDouble()).toFloat(),
            belly        = j.optDouble("belly",        base.belly.toDouble()).toFloat(),
            lateralThigh = j.optDouble("lateralThigh", base.lateralThigh.toDouble()).toFloat(),
        )
    } catch (_: Exception) { base }
}

// ─── Zone definitions (Y fractions matching SilhouetteAnalyzer) ──────────────

private data class ZoneSpec(
    val label: String,
    val yCenter: Float,         // normalised 0..1 center of the zone band
    val currentRatio: Float?,
    val targetRatio: Float,
)

private fun frontalZones(snap: PerformanceSnapshotEntity, t: SilhouetteTargets) = listOf(
    ZoneSpec("Ombros",  0.21f, snap.frontalShoulderRatio, t.shoulder),
    ZoneSpec("Cintura", 0.39f, snap.frontalWaistRatio,    t.waist),
    ZoneSpec("Ancas",   0.57f, snap.frontalHipRatio,      t.hip),
    ZoneSpec("Coxas",   0.72f, snap.frontalThighRatio,    t.thigh),
)

private fun lateralZones(snap: PerformanceSnapshotEntity, t: SilhouetteTargets) = listOf(
    ZoneSpec("Peito",   0.30f, snap.lateralChestRatio,   t.chest),
    ZoneSpec("Barriga", 0.50f, snap.lateralBellyRatio,   t.belly),
    ZoneSpec("Coxa",    0.72f, snap.lateralThighRatio,   t.lateralThigh),
)

// ─── Drawing helpers ──────────────────────────────────────────────────────────

private val colorPositive = Color(0xFF4ADE80)   // green — grow
private val colorNegative = Color(0xFFF87171)   // red — reduce
private val colorNeutral  = Color(0xFFA3E0F7)   // soft blue — on target

private fun gapColor(current: Float?, target: Float): Color = when {
    current == null                  -> colorNeutral
    current < target - 0.005f        -> colorPositive  // need to grow
    current > target + 0.005f        -> colorNegative  // need to reduce
    else                             -> colorNeutral
}

private fun DrawScope.drawSilhouette(path: Path, strokeColor: Color) {
    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawZoneLines(zones: List<ZoneSpec>, isFrontal: Boolean) {
    val halfW = size.width / 2f
    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

    zones.forEach { zone ->
        val current = zone.currentRatio ?: return@forEach
        val target  = zone.targetRatio
        val color   = gapColor(current, target)
        val yPx     = zone.yCenter * size.height

        // Current ratio line — solid
        val halfCur = (current / 2f) * size.height // half-span in pixels
        drawLine(
            color = Color.White.copy(alpha = 0.60f),
            start = Offset(halfW - halfCur, yPx),
            end   = Offset(halfW + halfCur, yPx),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
        // Target ratio line — dashed, coloured
        val halfTgt = (target / 2f) * size.height
        drawLine(
            color = color,
            start = Offset(halfW - halfTgt, yPx + 5f),
            end   = Offset(halfW + halfTgt, yPx + 5f),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
            pathEffect = pathEffect,
        )
    }
}

// ─── Main card ────────────────────────────────────────────────────────────────

@Composable
fun SilhouetteGoalCard(
    snap: PerformanceSnapshotEntity,
    latestGoal: PerformanceGoalEntity?,
    isMale: Boolean,
) {
    val targets = remember(latestGoal?.coachTargetJson, isMale) {
        parseTargets(latestGoal?.coachTargetJson, isMale)
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

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SilhouettePane(
                    modifier = Modifier.weight(1f),
                    label = "Frontal",
                    snap = snap,
                    isMale = isMale,
                    targets = targets,
                    isFrontal = true,
                )
                SilhouettePane(
                    modifier = Modifier.weight(1f),
                    label = "Lateral",
                    snap = snap,
                    isMale = isMale,
                    targets = targets,
                    isFrontal = false,
                )
            }

            // Legend row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                LegendItem(color = Color.White.copy(0.60f), label = "atual", dashed = false)
                LegendItem(color = colorPositive,            label = "alvo (crescer)", dashed = true)
                LegendItem(color = colorNegative,            label = "alvo (reduzir)", dashed = true)
            }

            Text(
                "Aperta para ampliar",
                color = Color.White.copy(alpha = 0.28f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// ─── Individual pane with zoom ────────────────────────────────────────────────

@Composable
private fun SilhouettePane(
    modifier: Modifier,
    label: String,
    snap: PerformanceSnapshotEntity,
    isMale: Boolean,
    targets: SilhouetteTargets,
    isFrontal: Boolean,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(0.45f), fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.45f)
                .background(Color.White.copy(0.03f), RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        val maxX = (size.width * (scale - 1)) / 2f
                        val maxY = (size.height * (scale - 1)) / 2f
                        offset = Offset(
                            x = (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                            y = (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                        )
                    }
                }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
            ) {
                val canvasSize = Size(size.width, size.height)
                val silhouettePath = when {
                    isFrontal && isMale  -> BodySilhouetteDrawable.maleFrontalPath(canvasSize)
                    isFrontal && !isMale -> BodySilhouetteDrawable.femaleFrontalPath(canvasSize)
                    !isFrontal && isMale -> BodySilhouetteDrawable.maleLateralPath(canvasSize)
                    else                 -> BodySilhouetteDrawable.femaleLateralPath(canvasSize)
                }
                drawSilhouette(silhouettePath, Color.White.copy(alpha = 0.55f))

                val zones = if (isFrontal) frontalZones(snap, targets) else lateralZones(snap, targets)
                drawZoneLines(zones, isFrontal)
            }
        }
    }
}

// ─── Legend item ──────────────────────────────────────────────────────────────

@Composable
private fun LegendItem(color: Color, label: String, dashed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(24.dp, 8.dp)) {
            val pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) else null
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f,
                pathEffect = pathEffect,
            )
        }
        Text(label, color = Color.White.copy(0.55f), fontSize = 10.sp)
    }
}
