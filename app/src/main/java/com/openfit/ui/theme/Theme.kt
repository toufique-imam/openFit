package com.openfit.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val TRACK_ALPHA = 0.16f

data class MetricColor(val accent: Color) {
    val track: Color get() = accent.copy(alpha = TRACK_ALPHA)
}

/** Light, dark or the system's choice; [Black] is dark with true-black surfaces for OLED screens. */
enum class Appearance { System, Light, Dark, Black }

/** The colour theme: surfaces and the main accent. [Dynamic] takes them from the wallpaper (Android 12+). */
enum class ColorTheme { Ocean, Forest, Rose, Graphite, Dynamic }

/** A theme's surfaces, text colours and main accent in one brightness. */
class ThemeSurfaces(
    val background: Color,
    val card: Color,
    val cardHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val primaryContainer: Color,
    val primary: Color,
    val onPrimary: Color,
    val lowest: Color,
    val high: Color,
    val highest: Color,
    val outlineVariant: Color,
)

private fun surfaces(
    background: Long, card: Long, cardHigh: Long, onSurface: Long, onSurfaceVariant: Long, outline: Long,
    primaryContainer: Long, primary: Long, onPrimary: Long, lowest: Long, high: Long, highest: Long, outlineVariant: Long,
) = ThemeSurfaces(
    Color(background), Color(card), Color(cardHigh), Color(onSurface), Color(onSurfaceVariant), Color(outline),
    Color(primaryContainer), Color(primary), Color(onPrimary), Color(lowest), Color(high), Color(highest), Color(outlineVariant),
)

private val OceanDark = surfaces(
    0xFF111827, 0xFF1B2536, 0xFF253247, 0xFFF1F5FB, 0xFFBDCADB, 0xFF92A3BA,
    0xFF314978, 0xFFA8C7FF, 0xFF10264D, 0xFF0B1220, 0xFF293A52, 0xFF30415A, 0xFF41536E,
)
private val OceanLight = surfaces(
    0xFFF4F7FC, 0xFFFFFFFF, 0xFFE6EDF8, 0xFF17243A, 0xFF475971, 0xFF64758B,
    0xFFD9E5FF, 0xFF285DA8, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDCE6F5, 0xFFD2DEEE, 0xFFC2CFDF,
)
private val ForestDark = surfaces(
    0xFF0F1A15, 0xFF18261F, 0xFF22342B, 0xFFEEF5F0, 0xFFBACBC0, 0xFF8FA597,
    0xFF2C4E3A, 0xFF96DBAE, 0xFF0B2E1A, 0xFF09120D, 0xFF273A30, 0xFF2E4237, 0xFF3D5547,
)
private val ForestLight = surfaces(
    0xFFF3F8F4, 0xFFFFFFFF, 0xFFE3EFE6, 0xFF15281C, 0xFF44594B, 0xFF62786A,
    0xFFD3ECDB, 0xFF1D6A3A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDAE8DE, 0xFFD0E1D5, 0xFFC0D3C6,
)
private val RoseDark = surfaces(
    0xFF1B1217, 0xFF281C23, 0xFF35262F, 0xFFF8EEF2, 0xFFD8C0CA, 0xFFAA919C,
    0xFF5A2E45, 0xFFFFB1CC, 0xFF4A1230, 0xFF120B0F, 0xFF3B2A34, 0xFF43303B, 0xFF574350,
)
private val RoseLight = surfaces(
    0xFFFCF4F7, 0xFFFFFFFF, 0xFFF4E3EA, 0xFF2E1622, 0xFF6A4C59, 0xFF876A77,
    0xFFFFDDE8, 0xFF9C3463, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFEFDAE2, 0xFFE8D0DA, 0xFFDCC2CD,
)
private val GraphiteDark = surfaces(
    0xFF141414, 0xFF1F1F1F, 0xFF2A2A2A, 0xFFF2F2F2, 0xFFC6C6C6, 0xFF9B9B9B,
    0xFF37414D, 0xFFB4CBEA, 0xFF14263D, 0xFF0B0B0B, 0xFF2F2F2F, 0xFF363636, 0xFF474747,
)
private val GraphiteLight = surfaces(
    0xFFF5F5F5, 0xFFFFFFFF, 0xFFE9E9E9, 0xFF1C1C1C, 0xFF4E4E4E, 0xFF6E6E6E,
    0xFFDFE4EA, 0xFF2F5D92, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFE1E1E1, 0xFFD8D8D8, 0xFFC7C7C7,
)

/** A theme's colours for [light] or dark; [black] swaps a dark theme's surfaces for true black. */
fun themeSurfaces(theme: ColorTheme, light: Boolean, black: Boolean = false): ThemeSurfaces {
    val base = when (theme) {
        ColorTheme.Ocean, ColorTheme.Dynamic -> if (light) OceanLight else OceanDark
        ColorTheme.Forest -> if (light) ForestLight else ForestDark
        ColorTheme.Rose -> if (light) RoseLight else RoseDark
        ColorTheme.Graphite -> if (light) GraphiteLight else GraphiteDark
    }
    return if (black && !light) base.blackened() else base
}

/** True-black surfaces, keeping the theme's text, accent and containers. */
private fun ThemeSurfaces.blackened() = ThemeSurfaces(
    background = Color.Black, card = Color(0xFF121212), cardHigh = Color(0xFF1E1E1E), onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant, outline = outline, primaryContainer = primaryContainer, primary = primary,
    onPrimary = onPrimary, lowest = Color.Black, high = Color(0xFF242424), highest = Color(0xFF2C2C2C), outlineVariant = Color(0xFF3A3A3A),
)

/** The wallpaper's colours (Android 12+) in the same roles. */
private fun ColorScheme.surfaces() = ThemeSurfaces(
    background = background, card = surfaceContainerLow, cardHigh = surfaceContainer, onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant, outline = outline, primaryContainer = primaryContainer, primary = primary,
    onPrimary = onPrimary, lowest = surfaceContainerLowest, high = surfaceContainerHigh, highest = surfaceContainerHighest,
    outlineVariant = outlineVariant,
)

class OpenFitPalette(val light: Boolean, surfaces: ThemeSurfaces = themeSurfaces(ColorTheme.Ocean, light)) {
    val Background = surfaces.background
    val Card = surfaces.card
    val CardHigh = surfaces.cardHigh
    val OnSurface = surfaces.onSurface
    val OnSurfaceVariant = surfaces.onSurfaceVariant
    val Outline = surfaces.outline
    val PrimaryContainer = surfaces.primaryContainer

    /** The theme's main accent (blue in the default theme). */
    val Mint = MetricColor(surfaces.primary)
    val Lavender = MetricColor(Color(if (light) 0xFF7044AC else 0xFFD1B9FF))
    val Blue = MetricColor(Color(if (light) 0xFF006D68 else 0xFF7FD6CE))
    val Pink = MetricColor(Color(if (light) 0xFFA54313 else 0xFFFFBB96))
}

private val LocalPalette = staticCompositionLocalOf { OpenFitPalette(light = false) }
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

private fun colorScheme(light: Boolean, s: ThemeSurfaces): ColorScheme {
    val scheme = if (light) lightColorScheme() else darkColorScheme()
    return scheme.copy(
        primary = s.primary,
        onPrimary = s.onPrimary,
        primaryContainer = s.primaryContainer,
        onPrimaryContainer = s.onSurface,
        secondaryContainer = s.primaryContainer,
        onSecondaryContainer = s.onSurface,
        background = s.background,
        onBackground = s.onSurface,
        surface = s.background,
        onSurface = s.onSurface,
        surfaceVariant = if (light) s.cardHigh else s.highest,
        onSurfaceVariant = s.onSurfaceVariant,
        surfaceContainerLowest = s.lowest,
        surfaceContainerLow = s.card,
        surfaceContainer = s.cardHigh,
        surfaceContainerHigh = s.high,
        surfaceContainerHighest = s.highest,
        outline = s.outline,
        outlineVariant = s.outlineVariant,
    )
}

/** Whether [Appearance] is dark here, following the system for [Appearance.System]. */
@Composable
fun Appearance.isDark(): Boolean = when (this) {
    Appearance.System -> isSystemInDarkTheme()
    Appearance.Light -> false
    Appearance.Dark, Appearance.Black -> true
}

/** Whether this phone can take colours from the wallpaper. */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun OpenFitTheme(
    appearance: Appearance = Appearance.System,
    theme: ColorTheme = ColorTheme.Ocean,
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark()
    val context = LocalContext.current
    val surfaces = remember(appearance, theme, dark) {
        val dynamic = if (theme == ColorTheme.Dynamic && dynamicColorAvailable) {
            (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).surfaces()
        } else {
            null
        }
        when {
            dynamic != null && appearance == Appearance.Black -> dynamic.blackened()
            dynamic != null -> dynamic
            else -> themeSurfaces(theme, light = !dark, black = appearance == Appearance.Black)
        }
    }
    val palette = remember(surfaces, dark) { OpenFitPalette(light = !dark, surfaces) }
    val scheme = remember(surfaces, dark) { colorScheme(light = !dark, surfaces) }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = OpenFitTypography, content = content)
    }
}
