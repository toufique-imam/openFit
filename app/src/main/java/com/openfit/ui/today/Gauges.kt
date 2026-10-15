package com.openfit.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import kotlin.math.cos
import kotlin.math.sin

private const val GAUGE_START = 126f
private const val GAUGE_SWEEP = 288f
private const val SEGMENT_GAP = 0.06f * 360f
private const val FOCUS_GAP = 7.5f
private const val FOCUS_SWEEP = 180f - 2 * FOCUS_GAP
private val GAUGE_ICON = 24.dp

data class FocusMetric(val label: String, val icon: ImageVector, val value: AnnotatedString, val progress: Float?, val color: MetricColor)

@Composable
fun FocusRing(top: FocusMetric, bottom: FocusMetric, modifier: Modifier = Modifier, size: Dp = 240.dp, stroke: Dp = 10.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            focusArc(top.color, startAngle = 180f + FOCUS_GAP, direction = 1f, progress = top.progress, width = width)
            focusArc(bottom.color, startAngle = 180f - FOCUS_GAP, direction = -1f, progress = bottom.progress, width = width)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FocusLabel(top)
            Spacer(Modifier.height(18.dp))
            FocusLabel(bottom)
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

private fun DrawScope.focusArc(color: MetricColor, startAngle: Float, direction: Float, progress: Float?, width: Float) {
    val style = Stroke(width, cap = StrokeCap.Round)
    val inset = width / 2
    val arcSize = Size(size.width - width, size.height - width)
    val topLeft = Offset(inset, inset)
    drawArc(color.track, startAngle, direction * FOCUS_SWEEP, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
    if (progress == null) return
    val sweep = FOCUS_SWEEP * progress.coerceIn(0f, 1f)
    if (sweep > 0f) {
        drawArc(color.accent, startAngle, direction * sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
    } else {
        drawCircle(color.accent, radius = width / 2, center = pointOnArc(startAngle, inset = width / 2))
    }
}

@Composable
fun ArcGauge(
    icon: ImageVector,
    color: MetricColor,
    progress: Float,
    modifier: Modifier = Modifier,
    from: Float = 0f,
    size: Dp = 66.dp,
    stroke: Dp = 6.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val style = Stroke(width, cap = StrokeCap.Round)
            drawArc(color.track, GAUGE_START, GAUGE_SWEEP, useCenter = false, style = style)
            val start = from.coerceIn(0f, 1f)
            val end = progress.coerceIn(start, 1f)
            if (end > start) {
                drawArc(
                    color.accent,
                    GAUGE_START + GAUGE_SWEEP * start,
                    GAUGE_SWEEP * (end - start),
                    useCenter = false,
                    style = style,
                )
            } else {
                drawCircle(color.accent, radius = width / 2, center = pointOnArc(GAUGE_START + GAUGE_SWEEP * start, inset = 0f))
            }
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
