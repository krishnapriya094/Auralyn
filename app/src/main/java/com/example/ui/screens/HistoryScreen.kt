package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SessionEntity
import com.example.data.model.DetectionEventDto
import com.example.data.model.DetectionSessionDto
import com.example.ui.components.GoogleSignInCard
import com.example.ui.theme.AuralynBackground
import com.example.ui.theme.AuralynPrimary
import com.example.ui.theme.AuralynSecondary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AuralynViewModel

enum class HistoryTab {
    SESSIONS,
    LIVE_OBJECTS
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    viewModel: AuralynViewModel,
    modifier: Modifier = Modifier
) {
    val localSessions by viewModel.historySessions.collectAsState()
    val cloudSessions by viewModel.firestoreSessions.collectAsState()
    val cloudEvents by viewModel.firestoreEvents.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isAuthenticating by viewModel.isAuthenticating.collectAsState()
    val authError by viewModel.authError.collectAsState()

    var activeTab by remember { mutableStateOf(HistoryTab.SESSIONS) }
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuralynBackground)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Detection History",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuralynPrimary
                )
                Text(
                    text = if (currentUser != null) "Synced with Firebase Firestore" else "Local mode • Sign in to sync",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Button(
                onClick = { showClearDialog = true },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006C74)),
                modifier = Modifier.testTag("btn_clear_history")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear History",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Clear",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(color = Color(0xFFE5E5E5), thickness = 1.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Google Sign-In Card
            item {
                GoogleSignInCard(
                    currentUser = currentUser,
                    isAuthenticating = isAuthenticating,
                    authError = authError,
                    onSignInClick = { activity -> viewModel.signInWithGoogle(activity) },
                    onSignOutClick = { viewModel.signOut() }
                )
            }

            // Tab selector: Sessions vs Live Objects
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = activeTab == HistoryTab.SESSIONS,
                        onClick = { activeTab = HistoryTab.SESSIONS },
                        label = {
                            val count = if (currentUser != null && cloudSessions.isNotEmpty()) cloudSessions.size else localSessions.size
                            Text("Sessions ($count)", fontWeight = FontWeight.SemiBold)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AuralynPrimary,
                            selectedLabelColor = Color.White
                        )
                    )

                    FilterChip(
                        selected = activeTab == HistoryTab.LIVE_OBJECTS,
                        onClick = { activeTab = HistoryTab.LIVE_OBJECTS },
                        label = {
                            Text("Live Cloud Objects (${cloudEvents.size})", fontWeight = FontWeight.SemiBold)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AuralynSecondary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // Tab 1: Sessions
            if (activeTab == HistoryTab.SESSIONS) {
                if (currentUser != null && cloudSessions.isNotEmpty()) {
                    items(cloudSessions, key = { it.id.ifEmpty { it.timestamp.toString() } }) { cloudSession ->
                        CloudSessionItemCard(cloudSession)
                    }
                } else if (localSessions.isNotEmpty()) {
                    items(localSessions, key = { it.id }) { session ->
                        LocalSessionItemCard(session, isSynced = currentUser != null)
                    }
                } else {
                    item {
                        EmptyHistoryView(
                            title = "No sessions recorded",
                            subtitle = "Start scanning from the Home screen. Finished sessions will be saved and pushed to Firestore."
                        )
                    }
                }
            }

            // Tab 2: Individual Live Detected Objects (Firebase Firestore)
            if (activeTab == HistoryTab.LIVE_OBJECTS) {
                if (currentUser == null) {
                    item {
                        EmptyHistoryView(
                            title = "Sign in to view real-time detections",
                            subtitle = "As every object is detected by the camera, it is pushed directly to your personal Firebase Firestore collection."
                        )
                    }
                } else if (cloudEvents.isEmpty()) {
                    item {
                        EmptyHistoryView(
                            title = "No detection events yet",
                            subtitle = "Start scanning with your camera or video feed. Every detected object will appear here live from Firestore."
                        )
                    }
                } else {
                    items(cloudEvents, key = { it.id }) { event ->
                        DetectionEventCard(event)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("Are you sure you want to clear your detection sessions and cloud events?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear All", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptyHistoryView(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = Color(0xFF8E9196),
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudSessionItemCard(session: DetectionSessionDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(AuralynPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = session.timeLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = session.durationFormatted,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = "Cloud Saved",
                        tint = Color(0xFF15803D),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Cloud",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF15803D)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val objects = session.detectedObjectsList
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                objects.forEach { obj ->
                    val isPerson = obj.equals("person", ignoreCase = true)
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = obj,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isPerson) Color(0xFF4338CA) else TextPrimary
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isPerson) Color(0xFFEEF2FF) else Color(0xFFF0F0F5)
                        ),
                        border = null,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalSessionItemCard(session: SessionEntity, isSynced: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4355B9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = session.timeLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = session.durationFormatted,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (isSynced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Synced",
                            tint = Color(0xFF15803D),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Synced",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF15803D)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFE0F2E9))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = session.status,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F7A4A)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val objects = session.detectedObjectsList
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                objects.forEach { obj ->
                    val isPerson = obj.equals("person", ignoreCase = true)
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = obj,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isPerson) Color(0xFF4338CA) else TextPrimary
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isPerson) Color(0xFFEEF2FF) else Color(0xFFF0F0F5)
                        ),
                        border = null,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectionEventCard(event: DetectionEventDto) {
    val isPerson = event.label.equals("person", ignoreCase = true)
    val pct = (event.confidence * 100).toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isPerson) Color(0xFFEEF2FF) else Color(0xFFE6FFFA)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPerson) Icons.Default.Person else Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = if (isPerson) Color(0xFF4F46E5) else Color(0xFF0D9488),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = event.label.replaceFirstChar { it.uppercase() },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Confidence: $pct% • Position: ${event.location}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = "Stored in Firestore",
                        tint = Color(0xFF059669),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Firestore",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669)
                    )
                }
            }
        }
    }
}
