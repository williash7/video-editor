package com.haessentz.videoeditor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF7A4A22),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF745A48),
    secondaryContainer = Color(0xFFFFDCC6),
    tertiary = Color(0xFF5E6135),
    background = Color(0xFFFFF8F5),
    surface = Color(0xFFFFF8F5),
    surfaceVariant = Color(0xFFF3DED3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF603A10),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE4BFA8),
    secondaryContainer = Color(0xFF5B4332),
    tertiary = Color(0xFFC7CA95),
    background = Color(0xFF1A120D),
    surface = Color(0xFF1A120D),
    surfaceVariant = Color(0xFF52443B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
