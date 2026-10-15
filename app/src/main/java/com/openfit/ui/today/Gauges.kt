package com.openfit.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfit.domain.HourStatus
import com.openfit.ui.theme.MetricColor
import com.openfit.ui.theme.OpenFitColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

private const val GAUGE_START = 126f
private const val GAUGE_SWEEP = 288f
private const val SEGMENT_GAP = 0.06f * 360f
private const val FOCUS_GAP = 7.5f
private const val FOCUS_SWEEP = 180f - 2 * FOCUS_GAP
private val GAUGE_ICON = 24.dp

private const val MIN_SWEEP = 0.1f

data class FocusMetric(
    val label: String,
    val icon: ImageVector,
    val value: AnnotatedString,
    val progress: Float?,
    val color: MetricColor,
    val range: ClosedFloatingPointRange<Float>? = null,
)

@Composable
fun FocusRing(
    top: FocusMetric,
    bottom: FocusMetric,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    stroke: Dp = 10.dp,
    onTopClick: (() -> Unit)? = null,
    onBottomClick: (() -> Unit)? = null,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val band = Band(outline = 2.dp.toPx(), halo = 2.dp.toPx(), background = OpenFitColors.Background)
            gauge(top.color, ArcFrame(180f + FOCUS_GAP, FOCUS_SWEEP, inset = width / 2), width, top.progress, top.range, band)
            gauge(bottom.color, ArcFrame(180f - FOCUS_GAP, -FOCUS_SWEEP, inset = width / 2), width, bottom.progress, bottom.range, band)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FocusLabel(top)
            Spacer(Modifier.height(18.dp))
            FocusLabel(bottom)
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(TopHalf).then(if (onTopClick != null) Modifier.clickable(onClick = onTopClick) else Modifier))
            Box(Modifier.weight(1f).fillMaxWidth().clip(BottomHalf).then(if (onBottomClick != null) Modifier.clickable(onClick = onBottomClick) else Modifier))
        }
    }
}

@Composable
private fun FocusLabel(metric: FocusMetric) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(metric.icon, contentDescription = null, tint = metric.color.accent, modifier = Modifier.size(16.dp))
        Text(metric.label, style = MaterialTheme.typography.labelLarge, color = OpenFitColors.OnSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        metric.value,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp, lineHeight = 18.sp),
        color = OpenFitColors.OnSurface,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

private val TopHalf = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50)
private val BottomHalf = RoundedCornerShape(bottomStartPercent = 50, bottomEndPercent = 50)

private class ArcFrame(val start: Float, val sweep: Float, val inset: Float) {
    fun angle(fraction: Float): Float = start + sweep * fraction
}

/** A target range drawn as an outlined capsule on the arc, with the track cut away [halo] around it. */
private class Band(val outline: Float, val halo: Float, val background: Color)

private fun DrawScope.gauge(
    color: MetricColor,
    frame: ArcFrame,
    width: Float,
    progress: Float?,
    range: ClosedFloatingPointRange<Float>?,
    band: Band,
    from: Float = 0f,
) {
    arc(color.track, frame, 0f, 1f, width)
    if (progress != null) {
        val start = from.coerceIn(0f, 1f)
        val end = progress.coerceIn(start, 1f)
        if (end > start) {
            arc(color.accent, frame, start, end, width)
        } else {
            drawCircle(color.accent, radius = width / 2, center = pointOnArc(frame.angle(start), frame.inset))
        }
    }
    if (range == null) return
    val low = range.start.coerceIn(0f, 1f)
    val high = range.endInclusive.coerceIn(low, 1f)
    val inner = width - 2 * band.outline
    arc(band.background, frame, low, high, width + 2 * band.halo)
    arc(color.accent, frame, low, high, width)
    arc(band.background, frame, low, high, inner)
    arc(color.track, frame, low, high, inner)
    val filled = (progress ?: 0f).coerceAtMost(high)
    if (filled > low) arc(color.accent, frame, low, filled, inner)
}

private fun DrawScope.arc(color: Color, frame: ArcFrame, from: Float, to: Float, width: Float) {
    val sweep = frame.sweep * (to - from)
    drawArc(
        color,
        frame.angle(from),
        if (abs(sweep) < MIN_SWEEP) MIN_SWEEP * frame.sweep.sign else sweep,
        useCenter = false,
        topLeft = Offset(frame.inset, frame.inset),
        size = Size(size.width - 2 * frame.inset, size.height - 2 * frame.inset),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

@Composable
fun ArcGauge(
    icon: ImageVector,
    color: MetricColor,
    progress: Float,
    modifier: Modifier = Modifier,
    from: Float = 0f,
    range: ClosedFloatingPointRange<Float>? = null,
    background: Color = OpenFitColors.Card,
    size: Dp = 66.dp,
    stroke: Dp = 6.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val band = Band(outline = 1.dp.toPx(), halo = 1.dp.toPx(), background = background)
            gauge(color, ArcFrame(GAUGE_START, GAUGE_SWEEP, inset = 0f), stroke.toPx(), progress, range, band, from)
        }
        Icon(icon, contentDescription = null, tint = color.accent, modifier = Modifier.size(GAUGE_ICON))
        if (from == 0f && progress >= 1f) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = color.accent,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(12.dp),
            )
        }
    }
}

@Composable
fun SegmentedGauge(
    icon: ImageVector,
    color: MetricColor,
    segments: Int,
    filled: Int,
    modifier: Modifier = Modifier,
    size: Dp = 66.dp,
    stroke: Dp = 6.dp,
    iconRotation: Float = 0f,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val style = Stroke(width, cap = StrokeCap.Round)
            val count = segments.coerceAtLeast(1)
            val segmentSweep = (GAUGE_SWEEP - SEGMENT_GAP * (count - 1)) / count
            repeat(count) { index ->
                drawArc(
                    if (index < filled) color.accent else color.track,
                    GAUGE_START + index * (segmentSweep + SEGMENT_GAP),
                    segmentSweep,
                    useCenter = false,
                    style = style,
                )
            }
        }
        Icon(icon, contentDescription = null, tint = color.accent, modifier = Modifier.size(GAUGE_ICON).rotate(iconRotation))
    }
}

@Composable
fun WeekBars(labels: List<String>, done: List<Boolean>, color: MetricColor, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            done.forEach { met ->
                Box(
                    Modifier.size(width = 20.dp, height = 46.dp).clip(CircleShape).background(if (met) color.accent else color.track),
                    contentAlignment = Alignment.Center,
                ) {
                    if (met) Icon(Icons.Filled.Check, contentDescription = null, tint = OpenFitColors.Background, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.width(20.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun HourlyBars(hours: List<HourStatus>, startLabel: String, endLabel: String, color: MetricColor, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hours.forEach { status ->
                Box(
                    Modifier
                        .size(width = 15.dp, height = 46.dp)
                        .clip(CircleShape)
                        .background(if (status == HourStatus.Met) color.accent else color.track),
                )
            }
        }
        Row(Modifier.width((15 * hours.size + 4 * (hours.size - 1)).dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(startLabel, style = MaterialTheme.typography.labelMedium, color = OpenFitColors.OnSurfaceVariant)
            Text(endLabel, style = MaterialTheme.typography.labelMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

private fun DrawScope.pointOnArc(angle: Float, inset: Float): Offset {
    val radius = size.minDimension / 2 - inset
    val radians = Math.toRadians(angle.toDouble())
    return Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
}
