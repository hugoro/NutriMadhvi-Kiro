package com.example.ui

import android.annotation.SuppressLint
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.local.PerformanceSnapshotEntity
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.abs

// ─── Joint angle from three ML Kit PoseLandmark points ─────────────────────
private fun angle(a: PoseLandmark, b: PoseLandmark, c: PoseLandmark): Float {
    val ax = a.position.x; val ay = a.position.y
    val bx = b.position.x; val by = b.position.y
    val cx = c.position.x; val cy = c.position.y
    val radians = atan2((cy - by).toDouble(), (cx - bx).toDouble()) -
                  atan2((ay - by).toDouble(), (ax - bx).toDouble())
    var deg = Math.toDegrees(abs(radians)).toFloat()
    if (deg > 180f) deg = 360f - deg
    return deg
}

// Lowered threshold — 0.5 is often not met for partially visible joints
private const val MIN_CONFIDENCE = 0.3f

private fun PoseLandmark.confident() = inFrameLikelihood >= MIN_CONFIDENCE

// ─── Immutable result of one pose capture ───────────────────────────────────
data class PoseAngles(
    val leftShoulderSagittal: Float?,
    val leftShoulderFrontal: Float?,
    val rightShoulderSagittal: Float?,
    val rightShoulderFrontal: Float?,
    val leftHipSagittal: Float?,
    val leftHipFrontal: Float?,
    val rightHipSagittal: Float?,
    val rightHipFrontal: Float?,
    val leftElbowSagittal: Float?,
    val rightElbowSagittal: Float?,
    val leftKneeSagittal: Float?,
    val rightKneeSagittal: Float?,
) {
    fun toEntity(source: String = "manual"): PerformanceSnapshotEntity =
        PerformanceSnapshotEntity(
            source = source,
            leftShoulderSagittalDeg = leftShoulderSagittal,
            leftShoulderFrontalDeg = leftShoulderFrontal,
            rightShoulderSagittalDeg = rightShoulderSagittal,
            rightShoulderFrontalDeg = rightShoulderFrontal,
            leftHipSagittalDeg = leftHipSagittal,
            leftHipFrontalDeg = leftHipFrontal,
            rightHipSagittalDeg = rightHipSagittal,
            rightHipFrontalDeg = rightHipFrontal,
            leftElbowSagittalDeg = leftElbowSagittal,
            rightElbowSagittalDeg = rightElbowSagittal,
            leftKneeSagittalDeg = leftKneeSagittal,
            rightKneeSagittalDeg = rightKneeSagittal,
        )

    val angleRows: List<Triple<String, Float?, Float?>>
        get() = listOf(
            Triple("Ombro E",    leftShoulderSagittal,  leftShoulderFrontal),
            Triple("Ombro D",    rightShoulderSagittal, rightShoulderFrontal),
            Triple("Anca E",     leftHipSagittal,       leftHipFrontal),
            Triple("Anca D",     rightHipSagittal,      rightHipFrontal),
            Triple("Cotovelo E", leftElbowSagittal,     null),
            Triple("Cotovelo D", rightElbowSagittal,    null),
            Triple("Joelho E",   leftKneeSagittal,      null),
            Triple("Joelho D",   rightKneeSagittal,     null),
        )
}

private fun extractAngles(landmarks: Map<Int, PoseLandmark>): PoseAngles {
    fun lm(type: Int) = landmarks[type]
    fun angle3(a: Int, b: Int, c: Int): Float? {
        val la = lm(a); val lb = lm(b); val lc = lm(c)
        return if (la != null && lb != null && lc != null &&
                   la.confident() && lb.confident() && lc.confident())
            angle(la, lb, lc) else null
    }
    return PoseAngles(
        leftShoulderSagittal  = angle3(PL.LEFT_HIP, PL.LEFT_SHOULDER, PL.LEFT_ELBOW),
        leftShoulderFrontal   = angle3(PL.RIGHT_SHOULDER, PL.LEFT_SHOULDER, PL.LEFT_ELBOW),
        rightShoulderSagittal = angle3(PL.RIGHT_HIP, PL.RIGHT_SHOULDER, PL.RIGHT_ELBOW),
        rightShoulderFrontal  = angle3(PL.LEFT_SHOULDER, PL.RIGHT_SHOULDER, PL.RIGHT_ELBOW),
        leftHipSagittal       = angle3(PL.LEFT_SHOULDER, PL.LEFT_HIP, PL.LEFT_KNEE),
        leftHipFrontal        = angle3(PL.RIGHT_HIP, PL.LEFT_HIP, PL.LEFT_KNEE),
        rightHipSagittal      = angle3(PL.RIGHT_SHOULDER, PL.RIGHT_HIP, PL.RIGHT_KNEE),
        rightHipFrontal       = angle3(PL.LEFT_HIP, PL.RIGHT_HIP, PL.RIGHT_KNEE),
        leftElbowSagittal     = angle3(PL.LEFT_SHOULDER, PL.LEFT_ELBOW, PL.LEFT_WRIST),
        rightElbowSagittal    = angle3(PL.RIGHT_SHOULDER, PL.RIGHT_ELBOW, PL.RIGHT_WRIST),
        leftKneeSagittal      = angle3(PL.LEFT_HIP, PL.LEFT_KNEE, PL.LEFT_ANKLE),
        rightKneeSagittal     = angle3(PL.RIGHT_HIP, PL.RIGHT_KNEE, PL.RIGHT_ANKLE),
    )
}

private typealias PL = PoseLandmark

// ─── Skeleton overlay ────────────────────────────────────────────────────────
private val SKELETON_CONNECTIONS = listOf(
    PL.NOSE to PL.LEFT_EYE, PL.NOSE to PL.RIGHT_EYE,
    PL.LEFT_SHOULDER to PL.RIGHT_SHOULDER,
    PL.LEFT_SHOULDER to PL.LEFT_ELBOW, PL.LEFT_ELBOW to PL.LEFT_WRIST,
    PL.RIGHT_SHOULDER to PL.RIGHT_ELBOW, PL.RIGHT_ELBOW to PL.RIGHT_WRIST,
    PL.LEFT_SHOULDER to PL.LEFT_HIP, PL.RIGHT_SHOULDER to PL.RIGHT_HIP,
    PL.LEFT_HIP to PL.RIGHT_HIP,
    PL.LEFT_HIP to PL.LEFT_KNEE, PL.LEFT_KNEE to PL.LEFT_ANKLE,
    PL.RIGHT_HIP to PL.RIGHT_KNEE, PL.RIGHT_KNEE to PL.RIGHT_ANKLE,
)

@Composable
private fun SkeletonOverlay(
    landmarks: Map<Int, PoseLandmark>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        fun lm(type: Int): Offset? {
            val l = landmarks[type] ?: return null
            if (!l.confident()) return null
            return Offset(l.position.x * scaleX, l.position.y * scaleY)
        }

        SKELETON_CONNECTIONS.forEach { (a, b) ->
            val pa = lm(a); val pb = lm(b)
            if (pa != null && pb != null) {
                drawLine(Color(0xFF4ADE80), pa, pb, 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        landmarks.values.filter { it.confident() }.forEach { lm ->
            drawCircle(
                Color(0xFF3B82F6), 5.dp.toPx(),
                Offset(lm.position.x * scaleX, lm.position.y * scaleY),
            )
        }
    }
}

// ─── Countdown pill ──────────────────────────────────────────────────────────
@Composable
private fun CountdownPill(seconds: Int) {
    if (seconds <= 0) return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AccentPrimary.copy(alpha = 0.85f),
        modifier = Modifier.padding(8.dp),
    ) {
        Text(
            text = "$seconds",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

// ─── Angles result table ─────────────────────────────────────────────────────
@Composable
private fun AngleTable(angles: PoseAngles) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.92f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Articulação", color = AccentSoft, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.6f))
                Text("Sagital", color = AccentSoft, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Frontal", color = AccentSoft, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.padding(vertical = 4.dp))
            angles.angleRows.forEach { (name, sag, fron) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(name, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1.6f))
                    Text(sag?.let { "${it.toInt()}°" } ?: "—",
                        color = if (sag != null) Color.White else Color.White.copy(alpha = 0.35f),
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(fron?.let { "${it.toInt()}°" } ?: "—",
                        color = if (fron != null) Color.White else Color.White.copy(alpha = 0.35f),
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── Main screen composable ──────────────────────────────────────────────────
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MobilityAssessmentScreen(
    source: String = "manual",
    onConfirm: (PerformanceSnapshotEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Mutable state updated from ML Kit callbacks (main executor, safe)
    var landmarks by remember { mutableStateOf<Map<Int, PoseLandmark>>(emptyMap()) }
    var imageWidth by remember { mutableIntStateOf(640) }
    var imageHeight by remember { mutableIntStateOf(480) }

    var countdown by remember { mutableIntStateOf(3) }
    var capturedAngles by remember { mutableStateOf<PoseAngles?>(null) }

    // Front/back toggle
    var useFrontCamera by remember { mutableStateOf(false) }

    // Shared ML Kit detector — reused across camera switches
    val poseDetector = remember {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    // Countdown coroutine — restartable
    fun startCountdown() {
        scope.launch {
            countdown = 3
            delay(800)
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            capturedAngles = extractAngles(landmarks)
        }
    }

    LaunchedEffect(Unit) { startCountdown() }

    // Dispose detector on leave
    DisposableEffect(Unit) {
        onDispose { poseDetector.close() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview — keyed on lens so AndroidView recreates on flip
        key(useFrontCamera) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val previewUseCase = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analysisUseCase = ImageAnalysis.Builder()
                            .setTargetResolution(Size(640, 480))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysisUseCase.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage, imageProxy.imageInfo.rotationDegrees
                                )
                                imageWidth = imageProxy.width
                                imageHeight = imageProxy.height
                                poseDetector.process(inputImage)
                                    .addOnSuccessListener { pose ->
                                        landmarks = pose.allPoseLandmarks
                                            .associateBy { it.landmarkType }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }

                        val cameraSelector = if (useFrontCamera)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else
                            CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector,
                                previewUseCase, analysisUseCase,
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("Mobility", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        // Skeleton overlay (only while live)
        if (capturedAngles == null) {
            SkeletonOverlay(
                landmarks = landmarks,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color.White)
            }
            Text(
                "Avaliação de Mobilidade",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            // Flip camera button
            IconButton(onClick = {
                useFrontCamera = !useFrontCamera
                if (capturedAngles == null) {
                    // Reset countdown for new camera
                    scope.launch {
                        delay(600)
                        countdown = 3
                        delay(800)
                        while (countdown > 0) { delay(1000); countdown-- }
                        capturedAngles = extractAngles(landmarks)
                    }
                }
            }) {
                Icon(Icons.Rounded.FlipCameraAndroid, contentDescription = "Trocar câmara",
                    tint = Color.White)
            }
        }

        // Bottom overlay — countdown or result
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val captured = capturedAngles
            if (captured == null) {
                CountdownPill(seconds = countdown)
                Text(
                    text = "Fica em posição...",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                )
            } else {
                AngleTable(captured)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            capturedAngles = null
                            landmarks = emptyMap()
                            scope.launch {
                                countdown = 3
                                delay(800)
                                while (countdown > 0) { delay(1000); countdown-- }
                                capturedAngles = extractAngles(landmarks)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Repetir")
                    }
                    Button(
                        onClick = { onConfirm(captured.toEntity(source)) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Guardar")
                    }
                }
            }
        }
    }
}
