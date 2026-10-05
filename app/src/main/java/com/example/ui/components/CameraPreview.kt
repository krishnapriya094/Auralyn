package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.logic.DetectionResult
import com.example.ui.theme.AuralynCyan
import com.example.ui.viewmodel.VisionFeedMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

@Composable
fun VideoDetectionPreview(
    isDetectionActive: Boolean,
    feedMode: VisionFeedMode,
    videoUri: Uri?,
    isVideoPlaying: Boolean,
    detections: List<DetectionResult>,
    onTogglePlayPause: () -> Unit,
    onFrameProcessed: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // Continuous Frame Extraction Loop for Video Capture Playback
    LaunchedEffect(isDetectionActive, isVideoPlaying, feedMode, textureViewRef, mediaPlayerRef) {
        if (feedMode == VisionFeedMode.VIDEO_CAPTURE) {
            while (isActive) {
                if (isDetectionActive && isVideoPlaying && textureViewRef != null) {
                    try {
                        val bitmap = textureViewRef?.getBitmap(640, 640)
                        if (bitmap != null) {
                            onFrameProcessed(bitmap)
                        }
                    } catch (e: Exception) {
                        // Frame extraction fallback
                    }
                }
                delay(220) // ~4.5 FPS inference for smooth detection
            }
        }
    }

    Box(modifier = modifier.background(Color(0xFF0F172A))) {
        if (feedMode == VisionFeedMode.VIDEO_CAPTURE) {
            // Native Video Surface View with MediaPlayer
            val effectiveUri = remember(videoUri) {
                getEffectiveVideoUri(context, videoUri)
            }

            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        textureViewRef = this
                        isOpaque = true
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
                                try {
                                    surfaceTexture.setDefaultBufferSize(w.coerceAtLeast(1), h.coerceAtLeast(1))
                                    val mp = MediaPlayer().apply {
                                        setSurface(Surface(surfaceTexture))
                                        setDataSource(ctx, effectiveUri)
                                        isLooping = true
                                        setOnPreparedListener {
                                            if (isVideoPlaying) {
                                                it.start()
                                            }
                                        }
                                        prepareAsync()
                                    }
                                    mediaPlayerRef = mp
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
                                surfaceTexture.setDefaultBufferSize(w.coerceAtLeast(1), h.coerceAtLeast(1))
                            }

                            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                try {
                                    mediaPlayerRef?.stop()
                                    mediaPlayerRef?.release()
                                    mediaPlayerRef = null
                                } catch (e: Exception) {
                                    // Clean up
                                }
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                        }
                    }
                },
                update = { _ ->
                    mediaPlayerRef?.let { mp ->
                        try {
                            if (isVideoPlaying && !mp.isPlaying) {
                                mp.start()
                            } else if (!isVideoPlaying && mp.isPlaying) {
                                mp.pause()
                            }
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Play / Pause Quick Overlay Button
            Box(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopEnd)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickable { onTogglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isVideoPlaying) "Pause Video" else "Play Video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            // Live Camera Stream
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (isDetectionActive) {
                                            val bitmap = imageProxyToBitmap(imageProxy)
                                            if (bitmap != null) {
                                                onFrameProcessed(bitmap)
                                            }
                                        }
                                        imageProxy.close()
                                    }
                                }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            // Camera preview fallback
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bounding Boxes & Scanning Overlay
        if (isDetectionActive) {
            DetectionOverlayCanvas(detections = detections)
        }
    }
}

// Backwards compatibility for callers using CameraPreview
@Composable
fun CameraPreview(
    isDetectionActive: Boolean,
    feedMode: VisionFeedMode = VisionFeedMode.VIDEO_CAPTURE,
    videoUri: Uri? = null,
    isVideoPlaying: Boolean = true,
    detections: List<DetectionResult>,
    onTogglePlayPause: () -> Unit = {},
    onFrameProcessed: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    VideoDetectionPreview(
        isDetectionActive = isDetectionActive,
        feedMode = feedMode,
        videoUri = videoUri,
        isVideoPlaying = isVideoPlaying,
        detections = detections,
        onTogglePlayPause = onTogglePlayPause,
        onFrameProcessed = onFrameProcessed,
        modifier = modifier
    )
}

@Composable
private fun DetectionOverlayCanvas(detections: List<DetectionResult>) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_laser")
    val laserY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "laser_pos"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Subtle Scanning Beam
        val scanY = height * laserY
        drawLine(
            color = AuralynCyan.copy(alpha = 0.5f),
            start = Offset(0f, scanY),
            end = Offset(width, scanY),
            strokeWidth = 3f
        )

        // Draw Bounding Boxes dynamically from model results
        detections.forEach { detection ->
            val box = detection.boundingBox ?: return@forEach
            val left = box.left * width
            val top = box.top * height
            val right = box.right * width
            val bottom = box.bottom * height

            if (right > left && bottom > top) {
                val isStandardObj = detection.label.lowercase() in listOf("chair", "bottle", "table", "book", "box", "lamp", "door", "window", "person")
                val boxColor = when {
                    !isStandardObj -> Color(0xFFE11D48) // Rose highlight for specifically identified familiar person
                    detection.label.equals("person", ignoreCase = true) -> Color(0xFF6366F1) // Indigo for person
                    detection.label.equals("door", ignoreCase = true) || detection.label.equals("window", ignoreCase = true) -> Color(0xFFF59E0B)
                    detection.label.equals("chair", ignoreCase = true) || detection.label.equals("table", ignoreCase = true) -> Color(0xFF10B981)
                    else -> AuralynCyan
                }

                drawRoundRect(
                    color = boxColor,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    style = Stroke(width = 4f)
                )
            }
        }
    }
}

private fun getEffectiveVideoUri(context: Context, customUri: Uri?): Uri {
    if (customUri != null) return customUri
    val cacheFile = File(context.cacheDir, "sample_video.mp4")
    if (!cacheFile.exists() || cacheFile.length() == 0L) {
        try {
            context.assets.open("sample_video.mp4").use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return Uri.fromFile(cacheFile)
}

private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val rawBitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
    } catch (e: Exception) {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
            val imageBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e2: Exception) {
            null
        }
    }
}
