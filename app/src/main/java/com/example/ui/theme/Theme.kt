package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = AuralynPrimary,
    onPrimary = Color.White,
    primaryContainer = AuralynSurfaceVariant,
    onPrimaryContainer = AuralynPrimary,
    secondary = AuralynSecondary,
    onSecondary = Color.White,
    secondaryContainer = NavActiveBg,
    onSecondaryContainer = NavActiveText,
    tertiary = AuralynCyan,
    onTertiary = Color.Black,
    background = AuralynBackground,
    onBackground = TextPrimary,
    surface = AuralynSurface,
    onSurface = TextPrimary,
    surfaceVariant = AuralynSurfaceVariant,
    onSurfaceVariant = TextSecondary
)

private val DarkColorScheme = darkColorScheme(
    primary = AuralynCyan,
    onPrimary = Color.Black,
    primaryContainer = AuralynPrimary,
    onPrimaryContainer = Color.White,
    secondary = HighContrastYellow,
    onSecondary = Color.Black,
    tertiary = HighContrastGreen,
    onTertiary = Color.Black,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White
)

@Composable
fun AuralynTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        highContrast || darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
