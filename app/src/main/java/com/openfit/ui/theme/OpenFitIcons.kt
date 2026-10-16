package com.openfit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Outlined icons the Material set lacks, drawn for openFit in the style of Today's rings: a dumbbell, a moon with a
 * sparkle, and a sneaker. 24 × 24, tinted like any icon.
 */
object OpenFitIcons {

    /** Two plates each side of a short bar, level; rotate it for the diagonal the exercise ring shows. */
    val Dumbbell: ImageVector by lazy {
        outlined("Dumbbell") {
            // Bar.
            moveTo(8.5f, 10.6f)
            lineTo(15.5f, 10.6f)
            moveTo(8.5f, 13.4f)
            lineTo(15.5f, 13.4f)
            // Inner plates.
            roundRect(5.4f, 5.5f, 8.5f, 18.5f, 1.3f)
            roundRect(15.5f, 5.5f, 18.6f, 18.5f, 1.3f)
            // Outer plates.
            roundRect(2.4f, 8.2f, 5.4f, 15.8f, 1.1f)
            roundRect(18.6f, 8.2f, 21.6f, 15.8f, 1.1f)
        }
    }

    /** A crescent opening to the top right, a four-pointed sparkle in its opening. */
    val MoonSparkle: ImageVector by lazy {
        outlined("MoonSparkle") {
            moveTo(11.5f, 4f)
            arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 20.5f, y1 = 14.5f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 11.5f, y1 = 4f)
            close()
            moveTo(18f, 2.8f)
            quadTo(18.4f, 5.1f, 20.7f, 5.5f)
            quadTo(18.4f, 5.9f, 18f, 8.2f)
            quadTo(17.6f, 5.9f, 15.3f, 5.5f)
            quadTo(17.6f, 5.1f, 18f, 2.8f)
            close()
        }
    }

    /** A low running shoe, toe to the right: the upper with two lace bumps over a rounded sole. */
    val Sneaker: ImageVector by lazy {
        outlined("Sneaker") {
            // Upper: heel, collar, two lace bumps, down to the toe.
            moveTo(3f, 14f)
            lineTo(3f, 7.8f)
            quadTo(3f, 6.6f, 4.2f, 6.6f)
            lineTo(6.6f, 6.6f)
            lineTo(8.6f, 9f)
            lineTo(10f, 8.2f)
            lineTo(11.2f, 10f)
            lineTo(12.6f, 9.4f)
            lineTo(13.8f, 11f)
            quadTo(19.6f, 12f, 20.8f, 14f)
            // Sole.
            roundRect(3f, 14f, 21.2f, 18f, 2f)
        }
    }

    private fun outlined(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

    private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        quadTo(right, top, right, top + radius)
        lineTo(right, bottom - radius)
        quadTo(right, bottom, right - radius, bottom)
        lineTo(left + radius, bottom)
        quadTo(left, bottom, left, bottom - radius)
        lineTo(left, top + radius)
        quadTo(left, top, left + radius, top)
        close()
    }
}
