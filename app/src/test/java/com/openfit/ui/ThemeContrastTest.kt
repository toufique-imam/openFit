package com.openfit.ui

import androidx.compose.ui.graphics.Color
import com.openfit.ui.theme.OpenFitPalette
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
    @Test fun `body text and metric accents meet normal text contrast in both themes`() {
        listOf(false, true).forEach { light ->
            val palette = OpenFitPalette(light)
            listOf(palette.Background, palette.Card, palette.CardHigh, palette.PrimaryContainer).forEach { background ->
                listOf(palette.OnSurface, palette.OnSurfaceVariant, palette.Mint.accent, palette.Lavender.accent, palette.Blue.accent, palette.Pink.accent).forEach { foreground ->
                    val a = luminance(background)
                    val b = luminance(foreground)
                    val ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
                    assertTrue("light=$light, foreground=$foreground, background=$background: $ratio", ratio >= 4.5)
                }
            }
        }
    }
}
