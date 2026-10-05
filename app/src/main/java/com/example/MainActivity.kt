package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.screens.DetectionScreen
import com.example.ui.screens.FamiliarFacesScreen
import com.example.ui.screens.HelpScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.PreferencesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.AuralynTheme
import com.example.ui.theme.NavActiveBg
import com.example.ui.theme.NavActiveText
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.AuralynViewModel
import com.example.ui.viewmodel.ScreenDestination

class MainActivity : ComponentActivity() {

    private val viewModel: AuralynViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicitly set 8-bit RGBA_8888 pixel format and standard color mode
        // to prevent HWUI from probing 101010-2 HDR configs and EGL swap preserve warnings
        window.setFormat(android.graphics.PixelFormat.RGBA_8888)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        }
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsState()
            val currentScreen by viewModel.currentScreen.collectAsState()

            AuralynTheme(highContrast = settings.highContrast) {
                // Request permissions at startup
                PermissionRequester()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // Only show bottom navigation on primary tabs
                        if (currentScreen == ScreenDestination.WELCOME ||
                            currentScreen == ScreenDestination.DETECTION ||
                            currentScreen == ScreenDestination.HISTORY ||
                            currentScreen == ScreenDestination.FAMILIAR_FACES ||
                            currentScreen == ScreenDestination.SETTINGS
                        ) {
                            AuralynBottomNavigation(
                                currentScreen = currentScreen,
                                onScreenSelected = { viewModel.navigateTo(it) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Crossfade(targetState = currentScreen, label = "screenTransition") { screen ->
                            when (screen) {
                                ScreenDestination.WELCOME -> {
                                    WelcomeScreen(
                                        onStartAssistance = {
                                            viewModel.startDetection()
                                            viewModel.navigateTo(ScreenDestination.DETECTION)
                                        },
                                        onOpenSettings = {
                                            viewModel.navigateTo(ScreenDestination.SETTINGS)
                                        },
                                        onOpenAbout = {
                                            viewModel.navigateTo(ScreenDestination.HELP)
                                        }
                                    )
                                }

                                ScreenDestination.DETECTION -> {
                                    BackHandler {
                                        viewModel.navigateTo(ScreenDestination.WELCOME)
                                    }
                                    DetectionScreen(
                                        viewModel = viewModel,
                                        onOpenSettings = {
                                            viewModel.navigateTo(ScreenDestination.SETTINGS)
                                        }
                                    )
                                }

                                ScreenDestination.HISTORY -> {
                                    BackHandler {
                                        viewModel.navigateTo(ScreenDestination.WELCOME)
                                    }
                                    HistoryScreen(viewModel = viewModel)
                                }

                                ScreenDestination.FAMILIAR_FACES -> {
                                    BackHandler {
                                        viewModel.navigateTo(ScreenDestination.WELCOME)
                                    }
                                    FamiliarFacesScreen(
                                        viewModel = viewModel,
                                        onNavigateBack = {
                                            viewModel.navigateTo(ScreenDestination.WELCOME)
                                        }
                                    )
                                }

                                ScreenDestination.SETTINGS -> {
                                    BackHandler {
                                        viewModel.navigateTo(ScreenDestination.WELCOME)
                                    }
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onNavigateBack = {
                                            viewModel.navigateTo(ScreenDestination.WELCOME)
                                        },
                                        onOpenPreferences = {
                                            viewModel.navigateTo(ScreenDestination.PREFERENCES)
                                        }
                                    )
                                }

                                ScreenDestination.PREFERENCES -> {
                                    BackHandler {
                                        viewModel.navigateTo(ScreenDestination.SETTINGS)
                                    }
                                    PreferencesScreen(
                                        viewModel = viewModel,
                                        onNavigateBack = {
                                            viewModel.navigateTo(ScreenDestination.SETTINGS)
                                        }
                                    )
                                }

                                ScreenDestination.HELP -> {
                                    BackHandler {
                                        viewModel.navigateTo(ScreenDestination.WELCOME)
                                    }
                                    HelpScreen(
                                        onNavigateBack = {
                                            viewModel.navigateTo(ScreenDestination.WELCOME)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRequester() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    LaunchedEffect(Unit) {
        val hasCamera = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera || !hasMic) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }
}

@Composable
private fun AuralynBottomNavigation(
    currentScreen: ScreenDestination,
    onScreenSelected: (ScreenDestination) -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab 1: Home
            BottomNavItem(
                label = "Home",
                icon = Icons.Default.Home,
                isSelected = currentScreen == ScreenDestination.WELCOME,
                onClick = { onScreenSelected(ScreenDestination.WELCOME) },
                modifier = Modifier.weight(1f),
                testTag = "nav_home"
            )

            // Tab 2: Live Detection
            BottomNavItem(
                label = "Assist",
                icon = Icons.Default.CameraAlt,
                isSelected = currentScreen == ScreenDestination.DETECTION,
                onClick = { onScreenSelected(ScreenDestination.DETECTION) },
                modifier = Modifier.weight(1f),
                testTag = "nav_assist"
            )

            // Tab 3: History
            BottomNavItem(
                label = "History",
                icon = Icons.Default.History,
                isSelected = currentScreen == ScreenDestination.HISTORY,
                onClick = { onScreenSelected(ScreenDestination.HISTORY) },
                modifier = Modifier.weight(1f),
                testTag = "nav_history"
            )

            // Tab 4: Familiar Faces
            BottomNavItem(
                label = "Faces",
                icon = Icons.Default.Face,
                isSelected = currentScreen == ScreenDestination.FAMILIAR_FACES,
                onClick = { onScreenSelected(ScreenDestination.FAMILIAR_FACES) },
                modifier = Modifier.weight(1f),
                testTag = "nav_faces"
            )

            // Tab 5: Settings
            BottomNavItem(
                label = "Settings",
                icon = Icons.Default.Settings,
                isSelected = currentScreen == ScreenDestination.SETTINGS,
                onClick = { onScreenSelected(ScreenDestination.SETTINGS) },
                modifier = Modifier.weight(1f),
                testTag = "nav_settings"
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(NavActiveBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = NavActiveText,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NavActiveText
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = TextTertiary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextTertiary
                )
            }
        }
    }
}
