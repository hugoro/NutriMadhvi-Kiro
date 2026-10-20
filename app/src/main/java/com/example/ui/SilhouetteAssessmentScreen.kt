package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ─── Capture step ────────────────────────────────────────────────────────────

private enum class CaptureStep {
    FRONTAL,   // step 1 — face camera front-on
    LATERAL,   // step 2 — turn to side
    PROCESSING // analysing silhouette + generating report
}

// ─── Pose full-body guard ─────────────────────────────────────────────────────

private fun hasFullBodyPose(landmarks: Map<Int, PoseLandmark>): Boolean {
    val required = listOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
    )
    return required.all { (landmarks[it]?.inFrameLikelihood ?: 0f) >= 0.4f }
}

// ─── Photo save ───────────────────────────────────────────────────────────────

private fun saveBitmapToFilesDir(context: Context, bitmap: Bitmap): String {
    val dir = File(context.filesDir, "performance_photos").also { it.mkdirs() }
    val file = File(dir, "${UUID.randomUUID()}.jpg")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return file.absolutePath
}

// ─── Main composable ──────────────────────────────────────────────────────────

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun SilhouetteAssessmentScreen(
    source: String = "manual",
    onConfirm: (PerformanceSnapshotEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(CaptureStep.FRONTAL) }
    var countdown by remember { mutableIntStateOf(5) }
    var bodyReady by remember { mutableStateOf(false) }

    var landmarks by remember { mutableStateOf<Map<Int, PoseLandmark>>(emptyMap()) }
    var imageWidth by remember { mutableIntStateOf(640) }
    var imageHeight by remember { mutableIntStateOf(480) }

    // Captured bitmaps — kept until entity is built
    var frontalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lateralBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Pose landmarks at the moment of each capture (used to normalise the
    // silhouette zones by the person's on-screen height when full-body).
    var frontalLandmarks by remember { mutableStateOf<Map<Int, PoseLandmark>?>(null) }
    var lateralLandmarks by remember { mutableStateOf<Map<Int, PoseLandmark>?>(null) }

    var useFrontCamera by remember { mutableStateOf(false) }

    val poseDetector = remember {
        val opts = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(opts)
    }
    DisposableEffect(Unit) { onDispose { poseDetector.close() } }

    // Latest captured frame (for segmentation)
    val latestBitmap = remember { mutableStateOf<Bitmap?>(null) }

    // ── Countdown + capture logic ─────────────────────────────────────────────
    fun startCountdown(onCaptured: (Bitmap) -> Unit) {
        scope.launch {
            countdown = 5
            bodyReady = false
            delay(500)
            // Wait until full body is in frame (max 10 s)
            val deadline = System.currentTimeMillis() + 10_000
            while (!hasFullBodyPose(landmarks) && System.currentTimeMillis() < deadline) {
                delay(200)
            }
            bodyReady = hasFullBodyPose(landmarks)
            // Countdown
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            val bmp = latestBitmap.value
            if (bmp != null) onCaptured(bmp)
        }
    }

    LaunchedEffect(step) {
        if (step == CaptureStep.FRONTAL) {
            startCountdown { bmp ->
                frontalBitmap = bmp
                frontalLandmarks = if (hasFullBodyPose(landmarks)) landmarks else null
                step = CaptureStep.LATERAL
            }
        } else if (step == CaptureStep.LATERAL) {
            startCountdown { bmp ->
                lateralBitmap = bmp
                lateralLandmarks = if (hasFullBodyPose(landmarks)) landmarks else null
                step = CaptureStep.PROCESSING
                // Run segmentation off-main-thread
                scope.launch(Dispatchers.Default) {
                    val frontal = frontalBitmap
                    val lateral = lateralBitmap
                    val frontalPath = frontal?.let {
                        withContext(Dispatchers.IO) { saveBitmapToFilesDir(context, it) }
                    }
                    val lateralPath = lateral?.let {
                        withContext(Dispatchers.IO) { saveBitmapToFilesDir(context, it) }
                    }
                    val frontalAnalysis = runCatching {
                        frontal?.let { SilhouetteAnalyzer.analysis(it, isFrontal = true, landmarks = frontalLandmarks) }
                    }.getOrNull()
                    val lateralAnalysis = runCatching {
                        lateral?.let { SilhouetteAnalyzer.analysis(it, isFrontal = false, landmarks = lateralLandmarks) }
                    }.getOrNull()
                    val frontalRatios = frontalAnalysis?.ratios
                    val lateralRatios = lateralAnalysis?.ratios
                    frontal?.recycle()
                    lateral?.recycle()

                    val entity = PerformanceSnapshotEntity(
                        source = source,
                        frontalImagePath = frontalPath,
                        lateralImagePath = lateralPath,
                        frontalShoulderRatio = frontalRatios?.shoulderRatio,
                        frontalWaistRatio    = frontalRatios?.waistRatio,
                        frontalHipRatio      = frontalRatios?.hipRatio,
                        frontalThighRatio    = frontalRatios?.thighRatio,
                        lateralChestRatio    = lateralRatios?.chestRatio,
                        lateralBellyRatio    = lateralRatios?.bellyRatio,
                        lateralThighRatio    = lateralRatios?.lateralThighRatio,
                        // v39 legacy marker (kept for compatibility): the primary
                        // side's convention. v40 replaced it with the per-side
                        // markers below, because frontal and lateral can
                        // legitimately use DIFFERENT conventions in one capture
                        // (e.g. full-body pose detected on one shot only) — one
                        // marker lying about both sides would let the trend
                        // chart compare not-same-unit values.
                        silhouetteNormalization = frontalAnalysis?.normalization
                            ?: lateralAnalysis?.normalization,
                        frontalNormalization = frontalAnalysis?.normalization,
                        lateralNormalization = lateralAnalysis?.normalization,
                    )
                    withContext(Dispatchers.Main) { onConfirm(entity) }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera preview (hidden during processing) ──────────────────────────
        if (step != CaptureStep.PROCESSING) {
            key(useFrontCamera, step) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(640, 480))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .build()
                            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                                val media = proxy.image
                                if (media != null) {
                                    imageWidth = proxy.width
                                    imageHeight = proxy.height
                                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                    poseDetector.process(input)
                                        .addOnSuccessListener { pose ->
                                            landmarks = pose.allPoseLandmarks.associateBy { it.landmarkType }
                                        }
                                        .addOnCompleteListener {
                                            // Capture a JPEG bitmap for segmentation
                                            try {
                                                latestBitmap.value = proxy.toBitmap()
                                                    .let { rotateBitmap(it, proxy.imageInfo.rotationDegrees.toFloat()) }
                                            } catch (_: Exception) {}
                                            proxy.close()
                                        }
                                } else proxy.close()
                            }
                            val selector = if (useFrontCamera)
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            else CameraSelector.DEFAULT_BACK_CAMERA
                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                            } catch (e: Exception) {
                                android.util.Log.e("Silhouette", "Camera bind failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            }
        }

        // ── Top bar ────────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
            Text(
                text = when (step) {
                    CaptureStep.FRONTAL -> "Foto Frontal (1/2)"
                    CaptureStep.LATERAL -> "Foto Lateral (2/2)"
                    CaptureStep.PROCESSING -> "A analisar..."
                },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            if (step != CaptureStep.PROCESSING) {
                IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                    Icon(Icons.Rounded.FlipCameraAndroid, null, tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // ── Overlay content ────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                CaptureStep.FRONTAL, CaptureStep.LATERAL -> {
                    // Instruction card
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (step == CaptureStep.FRONTAL)
                                    "Fica de frente para a câmara\nbraços relaxados ao lado do corpo"
                                else
                                    "Vira de perfil\nlado esquerdo para a câmara",
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                            // Body-ready indicator
                            AnimatedVisibility(visible = bodyReady, enter = fadeIn(), exit = fadeOut()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.Check, null,
                                        tint = Color(0xFF4ADE80), modifier = Modifier.size(16.dp))
                                    Text("Corpo detetado",
                                        color = Color(0xFF4ADE80), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    // Countdown pill
                    if (countdown > 0) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = AccentPrimary.copy(alpha = 0.90f),
                        ) {
                            Text(
                                "$countdown",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 44.sp,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                            )
                        }
                    }
                    // Retake button (only after countdown finishes and we're still showing)
                    OutlinedButton(
                        onClick = {
                            step = if (step == CaptureStep.FRONTAL) {
                                frontalBitmap = null
                                CaptureStep.FRONTAL
                            } else {
                                lateralBitmap = null
                                CaptureStep.LATERAL
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Repetir")
                    }
                }

                CaptureStep.PROCESSING -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1A1F2E),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(color = AccentSoft, modifier = Modifier.size(48.dp))
                            Text(
                                "A analisar silhueta e composição corporal...",
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "O relatório de coach estará disponível em breve.",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Bitmap rotation helper ───────────────────────────────────────────────────

private fun rotateBitmap(bmp: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bmp
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        .also { if (it !== bmp) bmp.recycle() }
}
