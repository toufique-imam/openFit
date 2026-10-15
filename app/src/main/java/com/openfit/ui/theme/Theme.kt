package com.openfit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.openfit.R

private const val TRACK_ALPHA = 0.16f

data class MetricColor(val accent: Color) {
    val track: Color get() = accent.copy(alpha = TRACK_ALPHA)
}

object OpenFitColors {
    val Background = Color(0xFF101413)
    val Card = Color(0xFF191C1B)
    val CardHigh = Color(0xFF1D201F)
    val OnSurface = Color(0xFFE0E3E1)
    val OnSurfaceVariant = Color(0xFFBFC9C4)
    val Outline = Color(0xFFA3ADA9)
    val PrimaryContainer = Color(0xFF334B45)

    val Mint = MetricColor(Color(0xFF78F8DD))
    val Lavender = MetricColor(Color(0xFFE5CCFF))
    val Blue = MetricColor(Color(0xFF94D6FF))
    val Pink = MetricColor(Color(0xFFFFD8E6))
}

@OptIn(ExperimentalTextApi::class)
private fun googleSansFlex(size: Int, lineHeight: Int, weight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = FontFamily(
        Font(
            R.font.google_sans_flex,
            FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.Setting("opsz", size.toFloat()),
            ),
        ),
    ),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    fontFeatureSettings = "ss02",
)

private val OpenFitTypography = Typography(
    displayLarge = googleSansFlex(57, 64, 400),
    displayMedium = googleSansFlex(45, 52, 400),
    displaySmall = googleSansFlex(36, 44, 400),
    headlineLarge = googleSansFlex(32, 40, 400),
    headlineMedium = googleSansFlex(28, 36, 400),
    headlineSmall = googleSansFlex(24, 32, 400),
    titleLarge = googleSansFlex(22, 28, 400),
    titleMedium = googleSansFlex(16, 24, 500),
    titleSmall = googleSansFlex(14, 20, 500),
    bodyLarge = googleSansFlex(16, 24, 400),
    bodyMedium = googleSansFlex(14, 20, 400),
    bodySmall = googleSansFlex(12, 16, 400, letterSpacing = 0.1),
    labelLarge = googleSansFlex(14, 20, 500),
    labelMedium = googleSansFlex(12, 16, 500),
    labelSmall = googleSansFlex(11, 16, 500, letterSpacing = 0.1),
)

val SectionHeaderStyle = googleSansFlex(18, 24, 400)

private val DarkScheme = darkColorScheme(
    primary = OpenFitColors.Mint.accent,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144),
    onPrimaryContainer = OpenFitColors.OnSurface,
    secondaryContainer = OpenFitColors.PrimaryContainer,
    background = OpenFitColors.Background,
    onBackground = OpenFitColors.OnSurface,
    surface = OpenFitColors.Background,
    onSurface = OpenFitColors.OnSurface,
    surfaceVariant = Color(0xFF323634),
    onSurfaceVariant = OpenFitColors.OnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = OpenFitColors.Card,
    surfaceContainer = OpenFitColors.CardHigh,
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323634),
    outline = OpenFitColors.Outline,
    outlineVariant = Color(0xFF3F4946),
)

@Composable
fun OpenFitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = OpenFitTypography, content = content)
}
