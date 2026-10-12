@file:OptIn(ExperimentalTextApi::class)

package com.haessentz.videoeditor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haessentz.videoeditor.R

// Warm ivory and gold, after the "העסענץ החסידי" brand (gold lettering on dark).
private val Light = lightColorScheme(
    primary = Color(0xFF8A5A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E2BC),
    onPrimaryContainer = Color(0xFF2C1A00),
    secondary = Color(0xFF6B5B4B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE1D2),
    onSecondaryContainer = Color(0xFF241A10),
    tertiary = Color(0xFF3F6656),
    tertiaryContainer = Color(0xFFD3EADF),
    onTertiaryContainer = Color(0xFF0C2017),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF0E8DC),
    onSurfaceVariant = Color(0xFF5A5045),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFCF7),
    surfaceContainer = Color(0xFFF7F1E8),
    surfaceContainerHigh = Color(0xFFF2EBE0),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFFB3A595),
    outlineVariant = Color(0xFFE3D9CC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE8B865),
    onPrimary = Color(0xFF3A2600),
    primaryContainer = Color(0xFF5A3F12),
    onPrimaryContainer = Color(0xFFFFE0AE),
    secondary = Color(0xFFD7C3AE),
    onSecondary = Color(0xFF3A2E21),
    secondaryContainer = Color(0xFF3B3127),
    onSecondaryContainer = Color(0xFFF2E1CC),
    tertiary = Color(0xFFA5CFBC),
    tertiaryContainer = Color(0xFF26473A),
    onTertiaryContainer = Color(0xFFC1EBD8),
    background = Color(0xFF14110D),
    onBackground = Color(0xFFEDE3D8),
    surface = Color(0xFF14110D),
    onSurface = Color(0xFFEDE3D8),
    surfaceVariant = Color(0xFF2A241D),
    onSurfaceVariant = Color(0xFFCFC3B5),
    surfaceContainerLowest = Color(0xFF0F0C09),
    surfaceContainerLow = Color(0xFF1A1612),
    surfaceContainer = Color(0xFF1F1A15),
    surfaceContainerHigh = Color(0xFF29231D),
    surfaceContainerHighest = Color(0xFF231E18),
    outline = Color(0xFF8D8172),
    outlineVariant = Color(0xFF3D352C),
)

private fun rubik(weight: Int) = Font(
    R.font.rubik, FontWeight(weight), FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/** Rubik reads well in both Hebrew and Russian. */
private val Rubik = FontFamily(rubik(400), rubik(500), rubik(600), rubik(700))

private fun Typography.withFont(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.f().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.f().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f().copy(fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography().withFont(Rubik),
        shapes = AppShapes,
        content = content,
    )
}
