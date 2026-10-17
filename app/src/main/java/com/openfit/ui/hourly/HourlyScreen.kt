package com.openfit.ui.hourly

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ACTIVE_HOUR_STEPS
import com.openfit.domain.ChartPeriod
import com.openfit.domain.averagePerDay
import com.openfit.domain.axisWithMiddle
import com.openfit.domain.periodDays
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.MenuAction
import com.openfit.ui.detail.SetGoalPrompt
import com.openfit.ui.goals.Goal
import com.openfit.ui.goals.GoalEditor
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.isCurrentPeriod
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.detail.periodRows
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.count
import com.openfit.ui.today.minutesLabel
import com.openfit.ui.today.valueText
import com.openfit.ui.today.weekdayDayMonth
import com.openfit.ui.today.whole
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val ACCENT @Composable get() = OpenFitColors.Mint
private val TEAL @Composable get() = OpenFitColors.Blue.accent
private val TRACK @Composable get() = OpenFitColors.OnSurface.copy(alpha = 0.12f)

@Composable
fun HourlyRoute(viewModel: HourlyViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? HourlyUiState.Ready
    var editingGoal by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(
        title = stringResource(R.string.tile_hourly_activity),
        accent = ACCENT.accent,
        period = viewModel.period,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
        menu = listOfNotNull(ready?.let { MenuAction(stringResource(if (it.window == null) R.string.goal_set else R.string.goal_adjust)) { editingGoal = true } }),
    ) {
        when (val current = state) {
            HourlyUiState.Loading -> DetailLoading(ACCENT.accent)
            is HourlyUiState.Failed -> DetailFailed(current.message)
            is HourlyUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                        period = current.period,
                    )
                }
                val window = current.window
                when {
                    window == null -> item { SetGoalPrompt(stringResource(R.string.hourly_set_goal), ACCENT.accent) { editingGoal = true } }
                    current.period == ChartPeriod.Day -> dayContent(current, window)
                    else -> periodContent(current, window, viewModel::selectMode)
                }
            }
        }
    }
    if (editingGoal && ready != null) {
        GoalEditor(
            goal = Goal.HourlyActivity,
            profile = ready.profile,
            onSave = {
                editingGoal = false
                viewModel.saveProfile(it)
            },
            onDismiss = { editingGoal = false },
        )
    }
}

private fun LazyListScope.dayContent(state: HourlyUiState.Ready, window: IntRange) {
    val met = window.count { (state.hourly[it] ?: 0.0) >= ACTIVE_HOUR_STEPS }
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            HeaderValue(valueText { count(met, window.count()) }, stringResource(R.string.hourly_hours_with_steps))
        }
    }
    item { HourPills(state, window, Modifier.padding(top = 32.dp)) }
    val glance = state.glance ?: return
    item {
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
            Text(stringResource(R.string.hourly_glance_title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
            val total = (glance.activeMinutes + glance.inactiveMinutes).coerceAtLeast(1)
            val activeShare = (glance.activeMinutes * 100.0 / total).roundToInt()
            GlanceBar(stringResource(R.string.hourly_active_time, activeShare, minutesLabel(glance.activeMinutes.toLong())), activeShare / 100f, ACCENT.accent)
            GlanceBar(stringResource(R.string.hourly_inactive_time, 100 - activeShare, minutesLabel(glance.inactiveMinutes.toLong())), (100 - activeShare) / 100f, TEAL)
        }
    }
    item {
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
            Text(stringResource(R.string.hourly_longest_title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
            Text(
                stringResource(R.string.hourly_longest_body),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(dayName(state), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                Text(minutesLabel(glance.longestInactiveMinutes.toLong()), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
            }
        }
    }
}

/**
 * A 28 x 44dp pill per hour of the goal window (narrower when the window is long), filled with a check when the hour
 * reached 250 steps; every other hour labelled (every fourth on long windows).
 */
@Composable
private fun HourPills(state: HourlyUiState.Ready, window: IntRange, modifier: Modifier = Modifier) {
    val arrangement = Arrangement.spacedBy(if (window.count() > MAX_WIDE_PILLS) 3.dp else 6.7.dp, Alignment.CenterHorizontally)
    val labelEvery = if (window.count() > MAX_WIDE_PILLS) 4 else 2
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
            window.forEach { hour ->
                val met = (state.hourly[hour] ?: 0.0) >= ACTIVE_HOUR_STEPS
                Box(
                    Modifier.weight(1f).widthIn(max = 28.dp).height(44.dp).clip(CircleShape).background(if (met) ACCENT.accent else ACCENT.track),
                    contentAlignment = Alignment.Center,
                ) {
                    if (met) Icon(Icons.Filled.Check, contentDescription = null, tint = OpenFitColors.Background, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = arrangement) {
            window.forEach { hour ->
                Text(
                    if ((hour - window.first) % labelEvery == 0) hourLabel(hour) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenFitColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.weight(1f).widthIn(max = 28.dp).wrapContentWidth(unbounded = true),
                )
            }
        }
    }
}

private const val MAX_WIDE_PILLS = 9

@Composable
private fun GlanceBar(label: String, fraction: Float, color: Color) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
        val track0 = TRACK
        Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp)) {
            val track = 4.dp.toPx()
            drawRoundRect(track0, Offset(0f, (size.height - track) / 2), Size(size.width, track), CornerRadius(track / 2))
            if (fraction > 0f) drawRoundRect(color, size = Size(maxOf(size.width * fraction, size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

private fun LazyListScope.periodContent(state: HourlyUiState.Ready, window: IntRange, onMode: (HourlyMode) -> Unit) {
    val current = isCurrentPeriod(state.period, state.anchor, state.today)
    val days = periodDays(state.period, state.anchor).filter { it <= state.today }
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            if (state.mode == HourlyMode.Hours) {
                val average = days.mapNotNull { state.hoursByDay[it] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                HeaderValue(valueText { count(average.roundToInt(), window.count()) }, stringResource(R.string.hourly_hours_per_day))
                Text(hoursDescription(state, current), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            } else {
                val average = averagePerDay(days, state.inactiveByDay) ?: 0.0
                HeaderValue(whole(average), stringResource(R.string.hourly_inactive_per_day))
                Text(
                    stringResource(R.string.hourly_inactive_total, minutesLabel(days.sumOf { state.inactiveByDay[it] ?: 0.0 }.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
            }
        }
    }
    item {
        Column(Modifier.padding(top = 32.dp)) {
            val labels = chartXLabels(state.period, state.anchor)
            if (state.mode == HourlyMode.Hours) {
                val goal = window.count().toDouble()
                val top = maxOf(10.0, goal)
                BarChart(
                    values = bars(state, state.hoursByDay),
                    color = ACCENT.accent,
                    axisMax = top,
                    axisLabels = listOfNotNull(
                        AxisLabel(0.0, "0h"),
                        AxisLabel(top / 2, "${(top / 2).roundToInt()}h").takeIf { top / 2 != goal },
                        AxisLabel(goal, "${goal.toInt()}h", highlight = true),
                        AxisLabel(top, "${top.toInt()}h").takeIf { top != goal },
                    ),
                    xLabels = labels,
                    goal = goal,
                )
            } else {
                val values = bars(state, state.inactiveByDay)
                val axis = axisWithMiddle(maxOf(60.0, ceil((values.maxOf { it ?: 0.0 }) * 1.1 / 10) * 10))
                BarChart(
                    values = values,
                    color = TEAL,
                    axisMax = axis.max,
                    axisLabels = listOf(0.0, axis.middle.toDouble(), axis.max).map { AxisLabel(it, minutesLabel(it.toLong())) },
                    xLabels = labels,
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(stringResource(R.string.hourly_chip_hours), state.mode == HourlyMode.Hours) { onMode(HourlyMode.Hours) }
                if (state.period != ChartPeriod.Year) {
                    ModeChip(stringResource(R.string.hourly_chip_inactive), state.mode == HourlyMode.Inactive) { onMode(HourlyMode.Inactive) }
                }
            }
        }
    }
    periodRows(state.period, state.anchor, state.today, state.rows) { "${it.load.roundToInt()}h" }
}

private fun bars(state: HourlyUiState.Ready, values: Map<java.time.LocalDate, Double>): List<Double?> = when (state.period) {
    ChartPeriod.Year -> (1..12).map { month -> YearMonth.of(state.anchor.year, month).let { ym -> averagePerDay((1..ym.lengthOfMonth()).map(ym::atDay), values) } }
    else -> periodDays(state.period, state.anchor).map { values[it] }
}

@Composable
private fun hoursDescription(state: HourlyUiState.Ready, current: Boolean): String {
    val days = periodDays(state.period, state.anchor).filter { it <= state.today }
    val total = days.sumOf { state.hoursByDay[it] ?: 0.0 }.roundToInt().toString()
    return when (state.period) {
        ChartPeriod.Week -> stringResource(R.string.hourly_week_total, total)
        ChartPeriod.Month -> stringResource(R.string.hourly_month_total, total)
        else -> {
            val best = (1..12).maxByOrNull { month ->
                YearMonth.of(state.anchor.year, month).let { ym -> (1..ym.lengthOfMonth()).sumOf { state.hoursByDay[ym.atDay(it)] ?: 0.0 } }
            } ?: 1
            val bestTotal = YearMonth.of(state.anchor.year, best).let { ym -> (1..ym.lengthOfMonth()).sumOf { state.hoursByDay[ym.atDay(it)] ?: 0.0 } }
            stringResource(
                if (current) R.string.hourly_year_best else R.string.hourly_year_best_past,
                java.time.Month.of(best).getDisplayName(TextStyle.FULL_STANDALONE, com.openfit.ui.currentLocale()),
                bestTotal.roundToInt().toString(),
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = ACCENT.accent) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OpenFitColors.PrimaryContainer,
            selectedLabelColor = ACCENT.accent,
            labelColor = OpenFitColors.OnSurface,
        ),
    )
}

@Composable
private fun dayName(state: HourlyUiState.Ready): String = when (state.anchor) {
    state.today -> stringResource(R.string.today)
    state.today.minusDays(1) -> stringResource(R.string.yesterday)
    else -> state.anchor.weekdayDayMonth()
}

private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12am"
    hour < 12 -> "${hour}am"
    hour == 12 -> "12pm"
    else -> "${hour - 12}pm"
}
