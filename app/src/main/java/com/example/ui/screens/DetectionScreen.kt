package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.R
import com.example.ui.components.CameraPreview
import com.example.ui.components.StatusCard
import com.example.ui.theme.AuralynBackground
import com.example.ui.theme.AuralynCyan
import com.example.ui.theme.AuralynPrimary
import com.example.ui.theme.AuralynPrimaryVariant
import com.example.ui.theme.AuralynSecondary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.AuralynViewModel
import com.example.ui.viewmodel.VisionFeedMode
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetectionScreen(
    viewModel: AuralynViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDetectionActive by viewModel.isDetectionActive.collectAsState()
    val detections by viewModel.currentDetections.collectAsState()
    val activeDuration by viewModel.activeSessionDurationSeconds.collectAsState()
    val feedMode by viewModel.visionFeedMode.collectAsState()
    val videoUri by viewModel.videoUri.collectAsState()
    val videoTitle by viewModel.videoTitle.collectAsState()
    val isVideoPlaying by viewModel.isVideoPlaying.collectAsState()
    val isSpeaking by viewModel.announcementManager.isSpeaking.collectAsState()
    val voiceNotice by viewModel.voiceNotice.collectAsState()
    val isListening by viewModel.isVoiceListening.collectAsState()
    val isModelReady by viewModel.detectionManager.isModelReady.collectAsState()
    val modelErrorMessage by viewModel.detectionManager.modelErrorMessage.collectAsState()
    val knownPeople by viewModel.knownPeople.collectAsState()
    val isTorchEnabled by viewModel.isTorchEnabled.collectAsState()

    val scrollState = rememberScrollState()

    // Video Recording Launcher via Camera
    val recordVideoFile = remember {
        val dir = File(context.cacheDir, "videos").apply { mkdirs() }
        File(dir, "captured_video.mp4")
    }
    val recordVideoUri = remember(recordVideoFile) {
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", recordVideoFile)
        } catch (e: Exception) {
            null
        }
    }
    val captureVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success && recordVideoUri != null) {
            viewModel.setVideoSource(recordVideoUri, "Recorded Video")
            if (!isDetectionActive) {
                viewModel.startDetection()
            }
        }
    }

    // Pick Video from Files / Gallery Launcher
    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setVideoSource(uri, "Selected Video")
            if (!isDetectionActive) {
                viewModel.startDetection()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuralynBackground)
    ) {
        // Top Bar (Pinned)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_auralyn_logo),
                    contentDescription = "Auralyn Logo",
                    tint = AuralynSecondary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Auralyn",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = AuralynPrimary
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("btn_top_settings")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Scrollable Body
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            // Welcome Header
            Text(
                text = "Object Detection & Assistance",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Capture or select video to detect objects generically with your trained model.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Main Start/Stop Assistance Button (64dp)
            Button(
                onClick = {
                    if (isDetectionActive) {
                        viewModel.stopDetection()
                    } else {
                        viewModel.startDetection()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("btn_start_stop_assistance"),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDetectionActive) Color(0xFFE11D48) else AuralynPrimaryVariant
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    imageVector = if (isDetectionActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isDetectionActive) "Stop Assistance (${formatDuration(activeDuration)})" else "Start Assistance",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (!isModelReady && modelErrorMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                    border = BorderStroke(1.dp, Color(0xFFFDE68A))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Model Notice",
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Model Notice",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF92400E)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = modelErrorMessage ?: "",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Detection Feed Mode Selector (Tab switcher between Video Capture and Live Camera)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "DETECTION INPUT FEED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextTertiary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Video Capture Option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setVisionFeedMode(VisionFeedMode.VIDEO_CAPTURE) }
                                .testTag("btn_mode_video_capture"),
                            color = if (feedMode == VisionFeedMode.VIDEO_CAPTURE) AuralynPrimary else Color.White,
                            shape = RoundedCornerShape(12.dp),
                            border = if (feedMode == VisionFeedMode.VIDEO_CAPTURE) null else BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = if (feedMode == VisionFeedMode.VIDEO_CAPTURE) Color.White else TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Video Capture",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (feedMode == VisionFeedMode.VIDEO_CAPTURE) Color.White else TextPrimary
                                )
                            }
                        }

                        // Live Camera Option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setVisionFeedMode(VisionFeedMode.LIVE_CAMERA) }
                                .testTag("btn_mode_live_camera"),
                            color = if (feedMode == VisionFeedMode.LIVE_CAMERA) AuralynPrimary else Color.White,
                            shape = RoundedCornerShape(12.dp),
                            border = if (feedMode == VisionFeedMode.LIVE_CAMERA) null else BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = if (feedMode == VisionFeedMode.LIVE_CAMERA) Color.White else TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Live Camera",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (feedMode == VisionFeedMode.LIVE_CAMERA) Color.White else TextPrimary
                                )
                            }
                        }
                    }

                    // Video Actions Row (Record, Pick, Sample)
                    if (feedMode == VisionFeedMode.VIDEO_CAPTURE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Record Video Button
                            OutlinedButton(
                                onClick = {
                                    if (recordVideoUri != null) {
                                        captureVideoLauncher.launch(recordVideoUri)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("btn_record_video"),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = AuralynPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Record", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AuralynPrimary)
                            }

                            // Choose Video File Button
                            OutlinedButton(
                                onClick = {
                                    pickVideoLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("btn_choose_video"),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideoLibrary,
                                    contentDescription = null,
                                    tint = AuralynPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Choose", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AuralynPrimary)
                            }

                            // Sample Video Button
                            OutlinedButton(
                                onClick = {
                                    viewModel.setVideoSource(null, "Sample Room Video")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("btn_sample_video"),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = AuralynPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sample", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AuralynPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Active: $videoTitle",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.toggleVideoPlayPause() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play or Pause",
                                        tint = AuralynPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.setVideoSource(videoUri, videoTitle)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Restart Video",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Video / Scene Preview Card (rounded 20dp, height 230dp)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .testTag("card_camera_preview"),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Video / Camera Preview with frame analyzer
                    CameraPreview(
                        isDetectionActive = isDetectionActive,
                        feedMode = feedMode,
                        videoUri = videoUri,
                        isVideoPlaying = isVideoPlaying,
                        detections = detections,
                        onTogglePlayPause = { viewModel.toggleVideoPlayPause() },
                        onFrameProcessed = { bitmap ->
                            viewModel.processFrame(bitmap)
                        },
                        isTorchEnabled = isTorchEnabled,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top Left Status Pill
                    Box(
                        modifier = Modifier
                            .padding(12.dp)
                            .align(Alignment.TopStart)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (isDetectionActive) "Scanning Feed" else "Detection Paused",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Top Right Action: Torch Toggle Button (improves camera frames in dark rooms)
                    Box(
                        modifier = Modifier
                            .padding(12.dp)
                            .align(Alignment.TopEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isTorchEnabled) Color(0xFFF59E0B) else Color(0x99000000))
                            .clickable { viewModel.toggleTorch() }
                            .testTag("btn_toggle_torch"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = if (isTorchEnabled) "Flashlight On (Tap to turn off)" else "Flashlight Off (Tap to turn on)",
                            tint = if (isTorchEnabled) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Bottom Left Detected Object HUD with Proximity
                    Box(
                        modifier = Modifier
                            .padding(12.dp)
                            .align(Alignment.BottomStart)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xE61A1C1E))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        val displayText = when {
                            !isDetectionActive -> "Tap 'Start Assistance' to detect"
                            detections.isEmpty() -> "Scanning video frames..."
                            else -> {
                                val top = detections.maxByOrNull { it.confidence }
                                if (top != null) {
                                    val pct = (top.confidence * 100).toInt()
                                    val isKnown = knownPeople.any { it.name.equals(top.label, ignoreCase = true) }
                                    val prefix = if (isKnown) "👤 " else ""
                                    "$prefix${top.label} ($pct%) • ${top.positionDescription} • ${top.proximityDescription}"
                                } else {
                                    "Object detected"
                                }
                            }
                        }
                        Text(
                            text = displayText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Floating Soundwave Action Button (Cyan FAB)
                    FloatingActionButton(
                        onClick = {
                            if (isDetectionActive) {
                                viewModel.queryCurrentScene()
                            } else {
                                viewModel.toggleVoiceListening()
                            }
                        },
                        modifier = Modifier
                            .padding(12.dp)
                            .size(52.dp)
                            .align(Alignment.BottomEnd)
                            .testTag("fab_voice_query"),
                        shape = CircleShape,
                        containerColor = AuralynCyan,
                        contentColor = AuralynPrimary
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_soundwave),
                            contentDescription = "Voice Assistant Query",
                            tint = AuralynPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Real-Time Detected Objects Chips Section
            if (isDetectionActive && detections.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "DETECTED IN VIEW (${detections.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = TextTertiary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    detections.forEach { detection ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            modifier = Modifier.clickable {
                                viewModel.announcementManager.speak("${detection.label} ${detection.positionDescription}")
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AuralynPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${detection.label.replaceFirstChar { it.uppercase() }} (${(detection.confidence * 100).toInt()}%)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "• ${detection.positionDescription}",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // SYSTEM STATUS Header
            Text(
                text = "SYSTEM STATUS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = TextTertiary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Card 1: Video Capture Status
            StatusCard(
                title = if (feedMode == VisionFeedMode.VIDEO_CAPTURE) "Video Capture Feed" else "Live Camera Feed",
                subtitle = if (isDetectionActive) {
                    if (feedMode == VisionFeedMode.VIDEO_CAPTURE) "Processing frames from $videoTitle" else "Active Camera Streaming"
                } else "Feed Ready",
                icon = if (feedMode == VisionFeedMode.VIDEO_CAPTURE) Icons.Default.Videocam else Icons.Default.CameraAlt,
                iconBgColor = Color(0xFFECEEFC),
                iconTint = AuralynPrimary,
                statusText = "Online",
                isStatusActive = true,
                testTag = "status_camera"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Card 2: AI Detection
            StatusCard(
                title = "AI Detection (YOLO + ML Kit Person)",
                subtitle = when {
                    !isModelReady -> "ML Kit Person active (Loading custom model...)"
                    isDetectionActive && detections.isNotEmpty() -> "${detections.size} object(s) / person(s) in view"
                    isDetectionActive -> "Evaluating frame pixels..."
                    else -> "Ready: Custom Objects + Person"
                },
                icon = Icons.Default.Psychology,
                iconBgColor = if (isModelReady) Color(0xFFE1F7F9) else Color(0xFFECEEFC),
                iconTint = if (isModelReady) AuralynSecondary else AuralynPrimary,
                statusText = "Online",
                isStatusActive = true,
                testTag = "status_ai"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Card 3: Voice Assistant
            StatusCard(
                title = "Voice Assistant",
                subtitle = when {
                    isSpeaking -> "Speaking announcement..."
                    isListening -> "Listening to command..."
                    else -> voiceNotice
                },
                icon = Icons.Default.RecordVoiceOver,
                iconBgColor = Color(0xFFFDF2E9),
                iconTint = Color(0xFFD56B1E),
                statusText = if (isSpeaking || isListening) "Active" else "Online",
                isStatusActive = true,
                testTag = "status_voice"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Card 4: Familiar Faces
            StatusCard(
                title = "Familiar Faces",
                subtitle = if (knownPeople.isNotEmpty()) {
                    "${knownPeople.size} familiar person(s) enrolled • Identifying in real time"
                } else {
                    "No faces enrolled yet • Use Faces tab to add photos"
                },
                icon = Icons.Default.Face,
                iconBgColor = Color(0xFFEEF2FF),
                iconTint = Color(0xFF4F46E5),
                statusText = "${knownPeople.size} Enrolled",
                isStatusActive = knownPeople.isNotEmpty(),
                testTag = "status_familiar_faces"
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
