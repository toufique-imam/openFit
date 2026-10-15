package com.openfit.ui.heart

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.HeartZone
import com.openfit.domain.axisWithMiddle
import com.openfit.domain.heartAxis
import com.openfit.domain.zoneOf
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.DotLegend
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.HeartDayChart
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LineDotsChart
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.TitleWithAction
import com.openfit.ui.detail.ZoneColors
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.dayStartMinute
import com.openfit.ui.detail.isCurrentPeriod
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.weekRange
import com.openfit.ui.today.weekdayDayMonth
import com.openfit.ui.today.whole
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val ACCENT = OpenFitColors.Blue.accent
private val TRACK = Color(0xFF292D2C)
private val PERIODS = listOf(ChartPeriod.Day, ChartPeriod.Week, ChartPeriod.Month, ChartPeriod.ThreeMonths, ChartPeriod.Year)

@Composable
fun HeartRoute(viewModel: HeartViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    val ready = state as? HeartUiState.Ready
    DetailScaffold(
        title = stringResource(R.string.heart_title),
        accent = ACCENT,
        period = ready?.period ?: ChartPeriod.Day,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            HeartUiState.Loading -> DetailLoading(ACCENT)
            is HeartUiState.Failed -> DetailFailed(current.message)
            is HeartUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                if (current.period == ChartPeriod.Day) {
                    dayContent(current, onLearnMore = { sheet = it })
                } else {
                    periodContent(current, viewModel::selectMode)
                }
                item {
                    LinkSection(
                        title = stringResource(R.string.heart_about_title),
                        action = stringResource(R.string.learn_more),
                        body = stringResource(R.string.heart_about_body),
                        accent = OpenFitColors.Mint.accent,
                        onAction = { sheet = R.string.heart_about_sheet },
                    )
                }
            }
        }
    }
    sheet?.let { InfoSheet(it) { sheet = null } }
}

private fun LazyListScope.dayContent(state: HeartUiState.Ready, onLearnMore: (Int) -> Unit) {
    val day = state.day ?: return
    val range = day.range
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            if (range == null) {
                HeaderValue("–", stringResource(R.string.unit_bpm))
                Text(stringResource(R.string.heart_no_data), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                return@Column
            }
            HeaderValue("${range.first}–${range.last}", stringResource(R.string.unit_bpm))
            val zone = day.zones?.zoneOf(range.last.toDouble())
            Text(
                if (zone == null || zone == HeartZone.Light) {
                    stringResource(R.string.heart_high, range.last.toString())
                } else {
                    stringResource(R.string.heart_high_in_zone, range.last.toString(), stringResource(zoneName(zone, lower = true)))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
            )
        }
    }
    if (range != null) {
        item {
            val (axisMin, axisMax) = heartAxis(range)
            Column(Modifier.padding(top = 32.dp)) {
                HeartDayChart(day.byMinute, dayStartMinute(state.anchor), day.zones, axisMin, axisMax)
                DotLegend(
                    listOf(
                        ZoneColors.Light to stringResource(R.string.zone_light),
                        ZoneColors.Moderate to stringResource(R.string.zone_moderate),
                        ZoneColors.Vigorous to stringResource(R.string.zone_vigorous),
                        ZoneColors.Peak to stringResource(R.string.zone_peak),
                    ),
                )
            }
        }
    }
    item {
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
            TitleWithAction(stringResource(R.string.heart_rhr_title), stringResource(R.string.learn_more), OpenFitColors.Mint.accent) {
                onLearnMore(R.string.heart_rhr_sheet)
            }
            ValueRow(dayName(state), day.restingBpm?.let { valueBpm(it.toString()) })
        }
    }
    val minutes = day.minutes
    if (minutes != null) {
        item {
            Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
                TitleWithAction(stringResource(R.string.heart_zones_title), stringResource(R.string.learn_more), OpenFitColors.Mint.accent) {
                    onLearnMore(R.string.heart_zones_sheet)
                }
                Text(
                    stringResource(R.string.heart_zones_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                val total = minutes.fatBurn + minutes.cardio + minutes.peak
                ValueRow(stringResource(R.string.heart_total_time), quantity(whole(total), "min"))
                listOf(
                    Triple(HeartZone.Peak, minutes.peak, ZoneColors.Peak),
                    Triple(HeartZone.Vigorous, minutes.cardio, ZoneColors.Vigorous),
                    Triple(HeartZone.Moderate, minutes.fatBurn, ZoneColors.Moderate),
                ).forEach { (zone, value, color) ->
                    val share = if (total > 0) (value * 100.0 / total).roundToInt() else 0
                    ZoneBar(stringResource(R.string.heart_zone_share, stringResource(zoneName(zone, lower = false)), share, "${value}m"), share / 100f, color)
                }
                state.caloriesKcal?.let { ValueRow(stringResource(R.string.heart_energy), quantity(whole(it), stringResource(R.string.unit_cal)), Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: androidx.compose.ui.text.AnnotatedString?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
        Text(value ?: buildAnnotatedString { append(stringResource(R.string.no_data)) }, style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
    }
}

/** "61 bpm" with the unit a size smaller, as Fitbit sets values in rows. */
@Composable
private fun valueBpm(value: String) = quantity(value, stringResource(R.string.unit_bpm))

private fun quantity(value: String, unit: String) = buildAnnotatedString {
    append(value)
    withStyle(SpanStyle(fontSize = 22.sp)) { append(" $unit") }
}

@Composable
private fun ZoneBar(label: String, fraction: Float, color: Color) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
        Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp)) {
            val track = 4.dp.toPx()
            drawRoundRect(TRACK, Offset(0f, (size.height - track) / 2), Size(size.width, track), CornerRadius(track / 2))
            if (fraction > 0f) drawRoundRect(color, size = Size(maxOf(size.width * fraction, size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

private fun LazyListScope.periodContent(state: HeartUiState.Ready, onMode: (HeartMode) -> Unit) {
    val current = isCurrentPeriod(state.period, state.anchor, state.today)
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            if (state.mode == HeartMode.Resting) {
                HeaderValue(state.restingAverage?.let { whole(it) } ?: "–", stringResource(R.string.heart_bpm_avg))
                val average = state.restingAverage
                val previous = state.previousRestingAverage
                if (average != null && previous != null) {
                    val difference = average.roundToInt() - previous.roundToInt()
                    Text(
                        when {
                            difference < 0 -> stringResource(R.string.heart_rhr_lower, abs(difference))
                            difference > 0 -> stringResource(R.string.heart_rhr_higher, difference)
                            else -> stringResource(R.string.heart_rhr_same)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenFitColors.OnSurfaceVariant,
                    )
                }
            } else {
                HeaderValue("${whole(state.zoneAveragePerDay ?: 0.0)}min", stringResource(R.string.heart_per_day_avg))
                Text(
                    stringResource(R.string.heart_zones_total, longDuration(state.zoneTotalMinutes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
            }
        }
    }
    item {
        Column(Modifier.padding(top = 32.dp)) {
            val labels = chartXLabels(state.period, state.anchor)
            if (state.mode == HeartMode.Resting) {
                val values = state.restingPoints.filterNotNull()
                val low = floor(values.minOrNull() ?: 50.0)
                val high = ceil(values.maxOrNull() ?: 70.0).let { if (it <= low) low + 2 else it }
                val middle = ((low + high) / 2).roundToInt().toDouble()
                LineDotsChart(
                    values = state.restingPoints,
                    color = ACCENT,
                    axisMin = low,
                    axisMax = high,
                    axisLabels = listOf(low, middle, high).map { AxisLabel(it, it.toInt().toString()) },
                    xLabels = labels,
                )
            } else {
                val highest = state.zonePoints.maxOf { it ?: 0.0 }
                val axis = axisWithMiddle(maxOf(30.0, ceil(highest * 1.05 / 10) * 10))
                BarChart(
                    values = state.zonePoints,
                    color = ACCENT,
                    axisMax = axis.max,
                    axisLabels = listOf(0.0, axis.middle.toDouble(), axis.max).map { AxisLabel(it, shortMinutes(it.toInt())) },
                    xLabels = labels,
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(stringResource(R.string.heart_chip_resting), state.mode == HeartMode.Resting, Modifier) { onMode(HeartMode.Resting) }
                ModeChip(stringResource(R.string.heart_chip_zones), state.mode == HeartMode.Zones, Modifier) { onMode(HeartMode.Zones) }
            }
        }
    }
    if (state.rows.isNotEmpty()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp)) {
                Text(
                    if (current) periodLabel(state.period, state.anchor, state.today) else listTitle(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenFitColors.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.heart_list_rhr), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
            }
        }
        itemsIndexed(state.rows) { index, row ->
            GroupedCard(index, state.rows.size, minHeight = 68.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rowLabel(state, row), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            row.restingBpm?.let { valueBpm(whole(it)) } ?: buildAnnotatedString { append(stringResource(R.string.no_data)) },
                            style = MaterialTheme.typography.headlineMedium,
                            color = OpenFitColors.OnSurface,
                        )
                        row.zones?.let {
                            Text(pluralStringResource(R.plurals.heart_zones_count, it, it), style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurfaceVariant)
                        }
                        if (row.sparkline.size > 1) Sparkline(row.sparkline, Modifier.padding(top = 8.dp).width(112.dp).height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = ACCENT) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF263238),
            selectedLabelColor = ACCENT,
            labelColor = OpenFitColors.OnSurface,
        ),
        modifier = modifier,
    )
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        val low = values.min()
        val high = values.max().let { if (it <= low) low + 1 else it }
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * size.width / (values.size - 1)
            val y = size.height - ((value - low) / (high - low)).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ZoneColors.Vigorous, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun dayName(state: HeartUiState.Ready): String = when (state.anchor) {
    state.today -> stringResource(R.string.today)
    state.today.minusDays(1) -> stringResource(R.string.yesterday)
    else -> state.anchor.weekdayDayMonth()
}

@Composable
private fun rowLabel(state: HeartUiState.Ready, row: HeartRow): String = when (state.period) {
    ChartPeriod.Week -> when (row.start) {
        state.today -> stringResource(R.string.today)
        state.today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> row.start.weekdayDayMonth()
    }
    ChartPeriod.Year -> if (YearMonth.from(row.start) == YearMonth.from(state.today)) {
        stringResource(R.string.this_month)
    } else {
        row.start.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
    }
    else -> if (state.today in row.start..row.end) stringResource(R.string.this_week_label) else weekRange(row.start, row.end)
}

@Composable
private fun listTitle(state: HeartUiState.Ready): String = when (state.period) {
    ChartPeriod.Month -> state.anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
    ChartPeriod.Year -> state.anchor.year.toString()
    else -> periodLabel(state.period, state.anchor, state.today)
}

@StringRes
private fun zoneName(zone: HeartZone, lower: Boolean): Int = when (zone) {
    HeartZone.Light -> if (lower) R.string.zone_light_lower else R.string.zone_light
    HeartZone.Moderate -> if (lower) R.string.zone_moderate_lower else R.string.zone_moderate
    HeartZone.Vigorous -> if (lower) R.string.zone_vigorous_lower else R.string.zone_vigorous
    HeartZone.Peak -> if (lower) R.string.zone_peak_lower else R.string.zone_peak
}

/** "8 hours 4 minutes", as in Fitbit's zones takeaway. */
@Composable
private fun longDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.duration_hours, it, it) },
        rest.takeIf { it > 0 || hours == 0 }?.let { pluralStringResource(R.plurals.duration_minutes, it, it) },
    ).joinToString(" ")
}

/** Axis labels: "50 min", "1 h 40 m". */
private fun shortMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} m"
}
