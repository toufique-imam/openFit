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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.openfit.domain.ChartAxis
import com.openfit.domain.HeartRateZones
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
private val CHART_HEIGHT = 212.dp
private val LINE_WIDTH = 1.5.dp
private val DOT_RADIUS = 3.dp
private val DOT_RING = 1.5.dp
private const val FILL_ALPHA = 0.12f

val WorkoutYellow = Color(0xFFE3E987)

/** Fitbit's dotted line: 2dp round dots every 5dp. */
private fun DrawScope.dotted(color: Color, from: Offset, to: Offset) {
    drawLine(
        color,
        from,
        to,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx())),
    )
}

/** A curve through [points] with Catmull-Rom tangents scaled by 1/6, as Fitbit smooths its day chart. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    for (i in 0 until points.lastIndex) {
        val p0 = points[maxOf(i - 1, 0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[minOf(i + 2, points.lastIndex)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
            p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
            p2.x, p2.y,
        )
    }
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
        val windows = workouts.map { x(it.start.epochSecond / 60) to x(it.end.epochSecond / 60) }
        windows.forEach { (from, to) ->
            drawRect(WorkoutYellow.copy(alpha = FILL_ALPHA), Offset(from, top), Size((to - from).coerceAtLeast(1f), bottom - top))
            dotted(WorkoutYellow, Offset(from, top), Offset(from, bottom))
            dotted(WorkoutYellow, Offset(to, top), Offset(to, bottom))
        }

        // Running total per minute; flat stretches get an extra point so the smoothing keeps them flat.
        val points = mutableListOf(Offset(x(dayStartMinute), y(0.0)))
        var previousMinute = dayStartMinute
        var previousTotal = 0.0
        cumulative.forEach { (minute, total) ->
            if (minute > previousMinute + 1) points += Offset(x(minute), y(previousTotal))
            points += Offset(x(minute + 1), y(total))
            previousMinute = minute + 1
            previousTotal = total
        }
        if (endMinute > previousMinute) points += Offset(x(endMinute), y(previousTotal))
        val path = smoothPath(points)
        val stroke = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path, OpenFitColors.Mint.accent, style = stroke)
        // Inside logged workouts the line turns yellow.
        windows.forEach { (from, to) -> clipRect(left = from, right = to) { drawPath(path, WorkoutYellow, style = stroke) } }
        val last = points.last()
        drawCircle(OpenFitColors.Background, (DOT_RADIUS + DOT_RING).toPx(), last)
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
        points.forEachIndexed { index, point ->
            val load = point.load ?: return@forEachIndexed
            val center = Offset(x(index), y(load))
            // Dots inside their day's target range get a background ring, as in Fitbit's charts.
            if (point.range != null && load in point.range) drawCircle(OpenFitColors.Background, (DOT_RADIUS + DOT_RING).toPx(), center)
            drawCircle(OpenFitColors.Mint.accent, DOT_RADIUS.toPx(), center)
        }

        yLabels(measurer, labelStyle, axis.max, axis.middle, top, bottom)
        xLabels(measurer, labelStyle, labels.map { (index, label) -> x(index) to label }, bottom, centered = true)
    }
}

data class AxisLabel(val value: Double, val text: String, val highlight: Boolean = false)

/**
 * Fitbit's bar charts: one rounded bar per slot (centered in it), labels on the right. Readiness adds dotted guides;
 * goal metrics add a solid goal line and mark the bars that reach it.
 */
@Composable
fun BarChart(
    values: List<Double?>,
    color: Color,
    axisMax: Double,
    axisLabels: List<AxisLabel>,
    xLabels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
    guides: List<Double> = emptyList(),
    goal: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = PLOT_LEFT.toPx()
        val width = PLOT_WIDTH.toPx()
        val slot = width / values.size.coerceAtLeast(1)
        val top = 0f
        val bottom = PLOT_HEIGHT.toPx()
        val barWidth = when {
            values.size <= 12 -> 16.dp
            values.size <= 24 -> 6.dp
            else -> 3.5.dp
        }.toPx()
        fun y(value: Double): Float = yFor(value, axisMax, top, bottom)

        guides.forEachIndexed { index, guide ->
            // The upper guide is dotted three times as densely as the lower one, as in Fitbit's charts.
            val step = if (index == guides.lastIndex) width / 21 else width / 7
            var x = left
            while (x <= left + width + 0.5f) {
                drawCircle(OpenFitColors.OnSurfaceVariant, 1.dp.toPx(), Offset(x, y(guide)))
                x += step
            }
        }
        goal?.let { drawLine(color, Offset(left, y(it)), Offset(left + width, y(it)), strokeWidth = 2.dp.toPx()) }
        values.forEachIndexed { index, value ->
            if (value == null || value <= 0.0) return@forEachIndexed
            val x = left + (index + 0.5f) * slot
            val barTop = minOf(y(value), bottom - barWidth)
            drawRoundRect(color, Offset(x - barWidth / 2, barTop), Size(barWidth, bottom - barTop), cornerRadius = CornerRadius(barWidth / 2))
            if (goal != null && value >= goal) {
                val center = Offset(x, barTop + barWidth / 2)
                drawCircle(OpenFitColors.Background, barWidth / 2 * 0.75f, center)
                if (barWidth >= 12.dp.toPx()) {
                    val r = barWidth * 0.18f
                    val check = Path().apply {
                        moveTo(center.x - r, center.y)
                        lineTo(center.x - r * 0.25f, center.y + r * 0.75f)
                        lineTo(center.x + r * 1.1f, center.y - r * 0.7f)
                    }
                    drawPath(check, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        val x = (PLOT_LEFT + PLOT_WIDTH + LABEL_GAP).toPx()
        axisLabels.forEach { label ->
            val style = if (label.highlight) labelStyle.copy(color = color) else labelStyle
            val layout = measurer.measure(label.text, style)
            drawText(layout, topLeft = Offset(x, y(label.value) - layout.size.height / 2f))
        }
        xLabels(measurer, labelStyle, xLabels.map { (index, label) -> left + (index + 0.5f) * slot to label }, bottom, centered = true)
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
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

/** Fitbit's heart-rate zone colors. */
object ZoneColors {
    val Light = Color(0xFF95D6FF)
    val Moderate = Color(0xFFFCF485)
    val Vigorous = Color(0xFFFCBA66)
    val Peak = Color(0xFFFF8989)
}

/**
 * The Heart day chart: per-minute heart rate over shaded zone bands with dotted thresholds. The line takes each
 * zone's color while it is inside that zone; gaps of over five minutes break it.
 */
@Composable
fun HeartDayChart(
    byMinute: Map<Long, Double>,
    dayStartMinute: Long,
    zones: HeartRateZones?,
    axisMin: Double,
    axisMax: Double,
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
        fun y(bpm: Double): Float = bottom - ((bpm - axisMin) / (axisMax - axisMin)).coerceIn(0.0, 1.0).toFloat() * (bottom - top)

        val bands = zones?.let {
            listOf(
                Triple(it.fatBurnFrom.toDouble(), it.cardioFrom.toDouble(), ZoneColors.Moderate),
                Triple(it.cardioFrom.toDouble(), it.peakFrom.toDouble(), ZoneColors.Vigorous),
                Triple(it.peakFrom.toDouble(), axisMax, ZoneColors.Peak),
            )
        }.orEmpty().filter { (from, _, _) -> from < axisMax }
        bands.forEach { (from, to, color) ->
            val bandTop = y(minOf(to, axisMax))
            val bandBottom = y(maxOf(from, axisMin))
            drawRect(color.copy(alpha = FILL_ALPHA), Offset(left, bandTop), Size(width, bandBottom - bandTop))
            if (from > axisMin) dotted(color, Offset(left, y(from)), Offset(left + width, y(from)))
        }

        val path = Path()
        var previous: Long? = null
        byMinute.toSortedMap().forEach { (minute, bpm) ->
            if (previous == null || minute - previous!! > MAX_GAP_MINUTES) path.moveTo(x(minute), y(bpm)) else path.lineTo(x(minute), y(bpm))
            previous = minute
        }
        val stroke = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path, ZoneColors.Light, style = stroke)
        bands.forEach { (from, to, color) ->
            clipRect(top = y(minOf(to, axisMax)), bottom = y(maxOf(from, axisMin))) { drawPath(path, color, style = stroke) }
        }

        val labelX = (PLOT_LEFT + PLOT_WIDTH + LABEL_GAP).toPx()
        val values = listOf(axisMin) + bands.map { it.first }.filter { it > axisMin } + axisMax
        values.forEach { value ->
            val layout = measurer.measure(value.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(labelX, y(value) - layout.size.height / 2f))
        }
        xLabels(measurer, labelStyle, xLabels.map { (hour, label) -> left + hour / 24f * width to label }, bottom, centered = true)
    }
}

private const val MAX_GAP_MINUTES = 5

/** A line through one value per slot with a dot on each, on an axis that need not start at zero (resting heart rate). */
@Composable
fun LineDotsChart(
    values: List<Double?>,
    color: Color,
    axisMin: Double,
    axisMax: Double,
    axisLabels: List<AxisLabel>,
    xLabels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = PLOT_LEFT.toPx()
        val slot = PLOT_WIDTH.toPx() / values.size.coerceAtLeast(1)
        val bottom = PLOT_HEIGHT.toPx()
        fun x(index: Int): Float = left + index * slot
        fun y(value: Double): Float = bottom - ((value - axisMin) / (axisMax - axisMin)).coerceIn(0.0, 1.0).toFloat() * bottom
        val points = values.mapIndexedNotNull { index, value -> value?.let { Offset(x(index), y(it)) } }
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEach { drawCircle(color, DOT_RADIUS.toPx(), it) }
        val labelX = (PLOT_LEFT + PLOT_WIDTH + LABEL_GAP).toPx()
        axisLabels.forEach { label ->
            val layout = measurer.measure(label.text, labelStyle)
            drawText(layout, topLeft = Offset(labelX, y(label.value) - layout.size.height / 2f))
        }
        xLabels(measurer, labelStyle, xLabels.map { (index, label) -> x(index) to label }, bottom, centered = true)
    }
}

/**
 * Health-metric charts: a dot per day on an axis that need not start at zero, inside that day's personal-range box;
 * dots outside their range take [outColor].
 */
@Composable
fun RangeDotsChart(
    values: List<Double?>,
    ranges: List<ClosedFloatingPointRange<Double>?>,
    color: Color,
    outColor: Color,
    axisMin: Double,
    axisMax: Double,
    axisLabels: List<AxisLabel>,
    xLabels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = PLOT_LEFT.toPx()
        val slot = PLOT_WIDTH.toPx() / values.size.coerceAtLeast(1)
        val bottom = PLOT_HEIGHT.toPx()
        fun x(index: Int): Float = left + (index + 0.5f) * slot
        fun y(value: Double): Float = bottom - ((value - axisMin) / (axisMax - axisMin)).coerceIn(0.0, 1.0).toFloat() * bottom
        ranges.forEachIndexed { index, range ->
            range ?: return@forEachIndexed
            rangeBox(x(index) - slot / 2, x(index) + slot / 2, y(range.endInclusive), y(range.start), color)
        }
        values.forEachIndexed { index, value ->
            value ?: return@forEachIndexed
            val inRange = ranges.getOrNull(index)?.let { value in it } ?: true
            drawCircle(OpenFitColors.Background, (DOT_RADIUS + DOT_RING).toPx(), Offset(x(index), y(value)))
            drawCircle(if (inRange) color else outColor, DOT_RADIUS.toPx(), Offset(x(index), y(value)))
        }
        val labelX = (PLOT_LEFT + PLOT_WIDTH + LABEL_GAP).toPx()
        axisLabels.forEach { label ->
            val layout = measurer.measure(label.text, labelStyle)
            drawText(layout, topLeft = Offset(labelX, y(label.value) - layout.size.height / 2f))
        }
        xLabels(measurer, labelStyle, xLabels.map { (index, label) -> x(index) to label }, bottom, centered = true)
    }
}

/** Legend whose markers are dots in each item's color: one dot for the first item, two for the second, and so on. */
@Composable
fun DotLegend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, (color, label) ->
            if (index > 0) Spacer(Modifier.width(20.dp))
            val dots = index + 1
            Canvas(Modifier.size(width = (4 * dots + 2).dp, height = 10.dp)) {
                repeat(dots) { drawCircle(color, 1.dp.toPx(), Offset((1 + 4 * it + 2).dp.toPx(), size.height / 2)) }
            }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

@Composable
fun ChartLegend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, (color, label) ->
            if (index > 0) Spacer(Modifier.width(24.dp))
            // Fitbit's swatch: a 16 x 10dp box tinted at 16 %, inset 1.5 x 2dp, with a 1dp dot at each corner.
            Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                val insetX = 1.5.dp.toPx()
                val insetY = 2.dp.toPx()
                drawRect(color.copy(alpha = 0.16f), Offset(insetX, insetY), Size(size.width - 2 * insetX, size.height - 2 * insetY))
                listOf(Offset(insetX, insetY), Offset(size.width - insetX, insetY), Offset(insetX, size.height - insetY), Offset(size.width - insetX, size.height - insetY))
                    .forEach { drawCircle(color, 1.dp.toPx(), it) }
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

fun epochMinute(instant: Instant): Long = instant.epochSecond / 60

fun dayStartMinute(date: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = epochMinute(date.atStartOfDay(zone).toInstant())
