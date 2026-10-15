package com.openfit.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.openfit.domain.ChartAxis
import com.openfit.domain.LoadRange
import com.openfit.domain.PeriodPoint
import com.openfit.domain.WorkoutLoad
import com.openfit.ui.theme.OpenFitColors
import java.time.Instant
import java.time.ZoneId

/** Fitbit's chart frame: a 288dp plot from 24dp, y labels to its right, 171dp tall. */
private val PLOT_LEFT = 24.dp
private val PLOT_WIDTH = 288.dp
private val PLOT_HEIGHT = 171.dp
private val LABEL_GAP = 14.dp
private val TICK = 4.dp
private val X_LABEL_TOP = 11.dp
private val TICK_TOP = 6.5.dp
private val CHART_HEIGHT = PLOT_HEIGHT + 30.dp
private val LINE_WIDTH = 2.dp
private val DOT_RADIUS = 3.5.dp
private const val FILL_ALPHA = 0.12f

val WorkoutYellow = Color(0xFFE3E987)

private fun DrawScope.dotted(color: Color, from: Offset, to: Offset) {
    val dot = 1.5.dp.toPx()
    drawLine(
        color,
        from,
        to,
        strokeWidth = dot,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 2.5.dp.toPx() + dot)),
    )
}

/** A target range: a translucent box with dotted top and bottom edges. */
private fun DrawScope.rangeBox(left: Float, right: Float, top: Float, bottom: Float, color: Color) {
    drawRect(color.copy(alpha = FILL_ALPHA), Offset(left, top), Size(right - left, bottom - top))
    dotted(color, Offset(left, top), Offset(right, top))
    dotted(color, Offset(left, bottom), Offset(right, bottom))
}

private fun DrawScope.yLabels(measurer: TextMeasurer, style: TextStyle, axisMax: Double, middle: Int, plotTop: Float, plotBottom: Float) {
    val x = (PLOT_LEFT + PLOT_WIDTH + LABEL_GAP).toPx()
    listOf(axisMax.toInt() to plotTop, middle to yFor(middle.toDouble(), axisMax, plotTop, plotBottom), 0 to plotBottom).forEach { (value, y) ->
        val layout = measurer.measure(value.toString(), style)
        drawText(layout, topLeft = Offset(x, y - layout.size.height / 2f))
    }
}

private fun yFor(value: Double, axisMax: Double, plotTop: Float, plotBottom: Float): Float =
    plotBottom - (value / axisMax).coerceIn(0.0, 1.0).toFloat() * (plotBottom - plotTop)

private fun DrawScope.xLabels(measurer: TextMeasurer, style: TextStyle, labels: List<Pair<Float, String>>, plotBottom: Float, centered: Boolean) {
    labels.forEach { (x, label) ->
        drawLine(OpenFitColors.OnSurfaceVariant, Offset(x, plotBottom + TICK_TOP.toPx()), Offset(x, plotBottom + TICK_TOP.toPx() + TICK.toPx()), strokeWidth = 1.dp.toPx())
        val layout = measurer.measure(label, style)
        val left = if (centered) x - layout.size.width / 2f else x - layout.size.width / 2f
        drawText(layout, topLeft = Offset(left.coerceAtLeast(0f), plotBottom + X_LABEL_TOP.toPx()))
    }
}

/**
 * The Day tab chart: cumulative cardio load across the day over the target range, with logged workouts highlighted.
 */
@Composable
fun DayChart(
    cumulative: List<Pair<Long, Double>>,
    dayStartMinute: Long,
    endMinute: Long,
    range: LoadRange?,
    workouts: List<WorkoutLoad>,
    axis: ChartAxis,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    val xLabels = listOf(0 to "12am", 4 to "4am", 8 to "8am", 12 to "12pm", 16 to "4pm", 20 to "8pm", 23 to "11pm")
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = PLOT_LEFT.toPx()
        val width = PLOT_WIDTH.toPx()
        val top = 0f
        val bottom = PLOT_HEIGHT.toPx()
        fun x(minute: Long): Float = left + ((minute - dayStartMinute) / (24f * 60f)).coerceIn(0f, 1f) * width
        fun y(value: Double): Float = yFor(value, axis.max, top, bottom)

        range?.let { rangeBox(left, left + width, y(it.high), y(it.low), OpenFitColors.Mint.accent) }
        workouts.forEach { workout ->
            val from = x(workout.start.epochSecond / 60)
            val to = x(workout.end.epochSecond / 60)
            drawRect(WorkoutYellow.copy(alpha = FILL_ALPHA), Offset(from, top), Size((to - from).coerceAtLeast(1f), bottom - top))
            dotted(WorkoutYellow, Offset(from, top), Offset(from, bottom))
            dotted(WorkoutYellow, Offset(to, top), Offset(to, bottom))
        }

        val path = Path().apply { moveTo(x(dayStartMinute), y(0.0)) }
        var last = Offset(x(dayStartMinute), y(0.0))
        cumulative.forEach { (minute, total) ->
            path.lineTo(x(minute), last.y)
            last = Offset(x(minute + 1), y(total))
            path.lineTo(last.x, last.y)
        }
        last = Offset(x(endMinute), last.y)
        path.lineTo(last.x, last.y)
        drawPath(path, OpenFitColors.Mint.accent, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(OpenFitColors.Mint.accent, DOT_RADIUS.toPx(), last)

        yLabels(measurer, labelStyle, axis.max, axis.middle, top, bottom)
        xLabels(measurer, labelStyle, xLabels.map { (hour, label) -> left + hour / 24f * width to label }, bottom, centered = true)
    }
}

/** Week, Month and Year charts: one point per slot, joined by a line, each over its own target-range box. */
@Composable
fun PeriodChart(
    points: List<PeriodPoint>,
    axis: ChartAxis,
    labels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = PLOT_LEFT.toPx()
        val slot = PLOT_WIDTH.toPx() / points.size.coerceAtLeast(1)
        val top = 0f
        val bottom = PLOT_HEIGHT.toPx()
        fun x(index: Int): Float = left + index * slot
        fun y(value: Double): Float = yFor(value, axis.max, top, bottom)

        points.forEachIndexed { index, point ->
            point.range?.let { rangeBox(x(index) - slot / 2, x(index) + slot / 2, y(it.high), y(it.low), OpenFitColors.Mint.accent) }
        }
        val drawn = points.mapIndexedNotNull { index, point -> point.load?.let { Offset(x(index), y(it)) } }
        if (drawn.size > 1) {
            val path = Path().apply {
                moveTo(drawn.first().x, drawn.first().y)
                drawn.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, OpenFitColors.Mint.accent, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawn.forEach { point ->
            drawCircle(OpenFitColors.Background, DOT_RADIUS.toPx() + 1.5.dp.toPx(), point)
            drawCircle(OpenFitColors.Mint.accent, DOT_RADIUS.toPx(), point)
        }

        yLabels(measurer, labelStyle, axis.max, axis.middle, top, bottom)
        xLabels(measurer, labelStyle, labels.map { (index, label) -> x(index) to label }, bottom, centered = true)
    }
}

/** Readiness-style bar chart: rounded bars per slot on a fixed 0–100 scale with dotted guides at 30 and 65. */
@Composable
fun BarChart(
    points: List<PeriodPoint>,
    color: Color,
    labels: List<Pair<Int, String>>,
    guides: List<Int>,
    axisMax: Double,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = PLOT_LEFT.toPx()
        val width = PLOT_WIDTH.toPx()
        val slot = width / points.size.coerceAtLeast(1)
        val top = 0f
        val bottom = PLOT_HEIGHT.toPx()
        val barWidth = (slot * 0.4f).coerceIn(3.dp.toPx(), 16.dp.toPx())
        fun y(value: Double): Float = yFor(value, axisMax, top, bottom)

        guides.forEachIndexed { index, guide ->
            // The upper guide is dotted three times as densely as the lower one, as in Fitbit's charts.
            val step = if (index == guides.lastIndex) width / 21 else width / 7
            var x = left
            while (x <= left + width + 0.5f) {
                drawCircle(OpenFitColors.OnSurfaceVariant, 1.dp.toPx(), Offset(x, y(guide.toDouble())))
                x += step
            }
        }
        points.forEachIndexed { index, point ->
            val value = point.load ?: return@forEachIndexed
            // Bars sit in the middle of their slot, unlike line-chart points which sit at its start.
            val x = left + (index + 0.5f) * slot
            val barTop = minOf(y(value), bottom - barWidth)
            drawRoundRect(color, Offset(x - barWidth / 2, barTop), Size(barWidth, bottom - barTop), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2))
        }
        val x = (PLOT_LEFT + PLOT_WIDTH + LABEL_GAP).toPx()
        (listOf(0) + guides + axisMax.toInt()).forEach { value ->
            val layout = measurer.measure(value.toString(), labelStyle)
            drawText(layout, topLeft = Offset(x, y(value.toDouble()) - layout.size.height / 2f))
        }
        xLabels(measurer, labelStyle, labels.map { (index, label) -> left + (index + 0.5f) * slot to label }, bottom, centered = true)
    }
}

/** Legend for dotted guides: one dot for the lower range, two for the upper, as Fitbit draws them. */
@Composable
fun GuideLegend(items: List<Pair<Int, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, (dots, label) ->
            if (index > 0) Spacer(Modifier.width(32.dp))
            Canvas(Modifier.size(width = 12.dp, height = 12.dp)) {
                val gap = 4.dp.toPx()
                val start = size.width / 2 - gap * (dots - 1) / 2
                repeat(dots) { drawCircle(OpenFitColors.OnSurfaceVariant, 1.dp.toPx(), Offset(start + it * gap, size.height / 2)) }
            }
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface)
        }
    }
}

@Composable
fun ChartLegend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, (color, label) ->
            if (index > 0) Spacer(Modifier.width(32.dp))
            Canvas(Modifier.size(width = 14.dp, height = 12.dp)) {
                drawRect(color.copy(alpha = 0.16f))
                dotted(color, Offset(0f, 0f), Offset(0f, size.height))
                dotted(color, Offset(size.width, 0f), Offset(size.width, size.height))
            }
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface)
        }
    }
}

fun epochMinute(instant: Instant): Long = instant.epochSecond / 60

fun dayStartMinute(date: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = epochMinute(date.atStartOfDay(zone).toInstant())
