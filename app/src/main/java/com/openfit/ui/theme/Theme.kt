package com.openfit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val TRACK_ALPHA = 0.16f

data class MetricColor(val accent: Color) {
    val track: Color get() = accent.copy(alpha = TRACK_ALPHA)
}

class OpenFitPalette(val light: Boolean) {
    val Background = Color(if (light) 0xFFF4F7FC else 0xFF111827)
    val Card = Color(if (light) 0xFFFFFFFF else 0xFF1B2536)
    val CardHigh = Color(if (light) 0xFFE6EDF8 else 0xFF253247)
    val OnSurface = Color(if (light) 0xFF17243A else 0xFFF1F5FB)
    val OnSurfaceVariant = Color(if (light) 0xFF475971 else 0xFFBDCADB)
    val Outline = Color(if (light) 0xFF64758B else 0xFF92A3BA)
    val PrimaryContainer = Color(if (light) 0xFFD9E5FF else 0xFF314978)

    val Mint = MetricColor(Color(if (light) 0xFF285DA8 else 0xFFA8C7FF))
    val Lavender = MetricColor(Color(if (light) 0xFF7044AC else 0xFFD1B9FF))
    val Blue = MetricColor(Color(if (light) 0xFF006D68 else 0xFF7FD6CE))
    val Pink = MetricColor(Color(if (light) 0xFFA54313 else 0xFFFFBB96))
}

private val DarkPalette = OpenFitPalette(light = false)
private val LightPalette = OpenFitPalette(light = true)
private val LocalPalette = staticCompositionLocalOf { DarkPalette }
val OpenFitColors: OpenFitPalette @Composable get() = LocalPalette.current

private fun openFitType(size: Int, lineHeight: Int, weight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

private val OpenFitTypography = Typography(
    displayLarge = openFitType(57, 64, 400),
    displayMedium = openFitType(45, 52, 400),
    displaySmall = openFitType(36, 44, 400),
    headlineLarge = openFitType(32, 40, 400),
    headlineMedium = openFitType(28, 36, 400),
    headlineSmall = openFitType(24, 32, 400),
    titleLarge = openFitType(22, 28, 400),
    titleMedium = openFitType(16, 24, 500),
    titleSmall = openFitType(14, 20, 500),
    bodyLarge = openFitType(16, 24, 400),
    bodyMedium = openFitType(14, 20, 400),
    bodySmall = openFitType(12, 16, 400, letterSpacing = 0.1),
    labelLarge = openFitType(14, 20, 500),
    labelMedium = openFitType(12, 16, 500),
    labelSmall = openFitType(11, 16, 500, letterSpacing = 0.1),
)

val SectionHeaderStyle = openFitType(18, 24, 400)

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.Mint.accent,
    onPrimary = Color(0xFF10264D),
    primaryContainer = Color(0xFF314978),
    onPrimaryContainer = DarkPalette.OnSurface,
    secondaryContainer = DarkPalette.PrimaryContainer,
    background = DarkPalette.Background,
    onBackground = DarkPalette.OnSurface,
    surface = DarkPalette.Background,
    onSurface = DarkPalette.OnSurface,
    surfaceVariant = Color(0xFF30415A),
    onSurfaceVariant = DarkPalette.OnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0B1220),
    surfaceContainerLow = DarkPalette.Card,
    surfaceContainer = DarkPalette.CardHigh,
    surfaceContainerHigh = Color(0xFF293A52),
    surfaceContainerHighest = Color(0xFF30415A),
    outline = DarkPalette.Outline,
    outlineVariant = Color(0xFF41536E),
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.Mint.accent,
    onPrimary = Color.White,
    primaryContainer = LightPalette.PrimaryContainer,
    onPrimaryContainer = LightPalette.OnSurface,
    secondaryContainer = LightPalette.PrimaryContainer,
    onSecondaryContainer = LightPalette.OnSurface,
    background = LightPalette.Background,
    onBackground = LightPalette.OnSurface,
    surface = LightPalette.Background,
    onSurface = LightPalette.OnSurface,
    surfaceVariant = LightPalette.CardHigh,
    onSurfaceVariant = LightPalette.OnSurfaceVariant,
    surfaceContainerLowest = LightPalette.Card,
    surfaceContainerLow = LightPalette.Card,
    surfaceContainer = LightPalette.CardHigh,
    surfaceContainerHigh = Color(0xFFDCE6F5),
    surfaceContainerHighest = Color(0xFFD2DEEE),
    outline = LightPalette.Outline,
    outlineVariant = Color(0xFFC2CFDF),
)

@Composable
fun OpenFitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, typography = OpenFitTypography, content = content)
    }
}
