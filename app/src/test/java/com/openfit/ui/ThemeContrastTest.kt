package com.openfit.ui

import androidx.compose.ui.graphics.Color
import com.openfit.ui.theme.ColorTheme
import com.openfit.ui.theme.OpenFitPalette
import com.openfit.ui.theme.themeSurfaces
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeContrastTest {
    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = if (channel <= 0.04045f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test fun `body text and metric accents meet normal text contrast in every theme`() {
        // Dynamic colours come from the wallpaper at run time; Material's own schemes meet contrast for them.
        ColorTheme.entries.filter { it != ColorTheme.Dynamic }.forEach { theme ->
            listOf(true to false, false to false, false to true).forEach { (light, black) ->
                val palette = OpenFitPalette(light, themeSurfaces(theme, light, black))
                listOf(palette.Background, palette.Card, palette.CardHigh, palette.PrimaryContainer).forEach { background ->
                    listOf(palette.OnSurface, palette.OnSurfaceVariant, palette.Mint.accent, palette.Lavender.accent, palette.Blue.accent, palette.Pink.accent).forEach { foreground ->
                        val ratio = contrast(background, foreground)
                        assertTrue("$theme light=$light black=$black, foreground=$foreground, background=$background: $ratio", ratio >= 4.5)
                    }
                }
            }
        }
    }

    @Test fun `text on the main accent is readable in every theme`() {
        ColorTheme.entries.filter { it != ColorTheme.Dynamic }.forEach { theme ->
            listOf(true, false).forEach { light ->
                val surfaces = themeSurfaces(theme, light)
                val ratio = contrast(surfaces.primary, surfaces.onPrimary)
                assertTrue("$theme light=$light: $ratio", ratio >= 4.5)
            }
        }
    }
}
