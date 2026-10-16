package com.openfit.ui.goal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material3.MaterialTheme
import com.openfit.profile.UnitSystem
import com.openfit.profile.Profile
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.goalAxis
import com.openfit.domain.hourlyAxis
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DayHeaderRow
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.isCurrentPeriod
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.detail.periodRows
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.ArcGauge
import com.openfit.ui.today.twoDecimals
import com.openfit.ui.today.whole
import kotlin.math.abs

private val ACCENT = OpenFitColors.Mint

private val HOUR_LABELS = listOf(0 to "12am", 4 to "4am", 8 to "8am", 12 to "12pm", 16 to "4pm", 20 to "8pm", 23 to "11pm")

@Composable
fun GoalMetricRoute(viewModel: GoalMetricViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? GoalMetricUiState.Ready
    DetailScaffold(
        title = stringResource(ready?.metric?.title ?: R.string.tile_steps),
        accent = ACCENT.accent,
        period = ready?.period ?: ChartPeriod.Day,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            GoalMetricUiState.Loading -> DetailLoading(ACCENT.accent)
            is GoalMetricUiState.Failed -> DetailFailed(current.message)
            is GoalMetricUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                if (current.period == ChartPeriod.Day) dayContent(current) else periodContent(current)
            }
        }
    }
}

private fun LazyListScope.dayContent(state: GoalMetricUiState.Ready) {
    val value = state.dayValue ?: 0.0
    val isToday = state.anchor == state.today
    item {
        val remaining = state.goal - value
        val unit = stringResource(state.metric.unit(state.profile))
        val format = state.metric.format
        DayHeaderRow(
            value = format(value),
            status = stringResource(R.string.goal_of, format(state.goal), unit),
            description = when {
                format(remaining) == format(0.0) -> stringResource(R.string.goal_met)
                remaining > 0 -> stringResource(if (isToday) R.string.goal_away_today else R.string.goal_away_past, format(remaining), unit)
                else -> stringResource(R.string.goal_exceeded, format(abs(remaining)), unit)
            },
        ) {
            ArcGauge(state.metric.icon, ACCENT, (value / state.goal).toFloat(), background = OpenFitColors.Background)
        }
    }
    item {
        val axis = hourlyAxis(state.hourly.maxOf { it ?: 0.0 })
        BarChart(
            values = state.hourly,
            color = ACCENT.accent,
            axisMax = axis.max,
            axisLabels = listOf(0.0, axis.max / 2, axis.max).map { AxisLabel(it, compact(it)) },
            xLabels = HOUR_LABELS,
            modifier = Modifier.padding(top = 32.dp),
        )
    }
}

private fun LazyListScope.periodContent(state: GoalMetricUiState.Ready) {
    val summary = state.summary
    val current = isCurrentPeriod(state.period, state.anchor, state.today)
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            val format = state.metric.format
            HeaderValue(
                summary.averagePerDay?.let(format) ?: "0",
                stringResource(R.string.goal_per_day_avg, stringResource(state.metric.unit(state.profile))),
            )
            val phrase = stringResource(state.metric.phrase, format(summary.total), stringResource(state.metric.longUnit(state.profile)))
            Text(
                when {
                    state.period == ChartPeriod.Year -> stringResource(if (current) R.string.goal_year_current else R.string.goal_year_past, phrase)
                    summary.goalDays > 0 -> pluralStringResource(
                        if (current) R.plurals.goal_days_current else R.plurals.goal_days_past,
                        summary.goalDays,
                        summary.goalDays,
                        phrase,
                    )
                    !current -> stringResource(R.string.goal_none_past, phrase)
                    state.period == ChartPeriod.Week -> stringResource(R.string.goal_none_week, phrase)
                    else -> stringResource(R.string.goal_none_month, phrase)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
    item {
        val axis = goalAxis(state.goal, state.bars.maxOf { it ?: 0.0 })
        BarChart(
            values = state.bars,
            color = ACCENT.accent,
            axisMax = axis.max,
            axisLabels = axis.labels.map { AxisLabel(it, compact(it), highlight = it == state.goal) },
            xLabels = chartXLabels(state.period, state.anchor),
            goal = state.goal,
            modifier = Modifier.padding(top = 32.dp),
        )
    }
    periodRows(state.period, state.anchor, state.today, state.rows) { state.metric.format(it.load) }
}

/** Axis labels the way Fitbit writes them: 2.5, 500, 1.6k, 10k. */
private fun compact(value: Double): String = when {
    value < 1000 -> twoDecimals(value)
    value % 1000 == 0.0 -> "${(value / 1000).toInt()}k"
    else -> "${"%.1f".format(value / 1000).trimEnd('0').trimEnd('.')}k"
}

private val GoalMetric.title: Int
    get() = when (this) {
        GoalMetric.Steps -> R.string.tile_steps
        GoalMetric.Distance -> R.string.tile_distance
        GoalMetric.Floors -> R.string.tile_floors
        GoalMetric.Energy -> R.string.tile_energy_burned
        GoalMetric.ZoneMinutes -> R.string.azm_title
    }

private val GoalMetric.icon: ImageVector
    get() = when (this) {
        GoalMetric.Steps -> Icons.AutoMirrored.Outlined.DirectionsWalk
        GoalMetric.Distance -> Icons.Outlined.LocationOn
        GoalMetric.Floors -> Icons.Outlined.Stairs
        GoalMetric.Energy -> Icons.Outlined.LocalFireDepartment
        GoalMetric.ZoneMinutes -> Icons.Outlined.KeyboardDoubleArrowUp
    }

/** The short unit after numbers ("of 10,000 steps", "km per day (avg)"). */
private fun GoalMetric.unit(profile: Profile): Int = when (this) {
    GoalMetric.Steps -> R.string.unit_steps
    GoalMetric.Distance -> if (profile.units == UnitSystem.Imperial) R.string.unit_mi else R.string.unit_km
    GoalMetric.Floors -> R.string.unit_floors
    GoalMetric.Energy -> R.string.unit_cal
    GoalMetric.ZoneMinutes -> R.string.unit_zone_minutes
}

/** The unit inside takeaway sentences ("burned a total of 14,000 calories"). */
private fun GoalMetric.longUnit(profile: Profile): Int = if (this == GoalMetric.Energy) R.string.unit_calories else unit(profile)

private val GoalMetric.phrase: Int
    get() = when (this) {
        GoalMetric.Steps -> R.string.phrase_steps
        GoalMetric.Distance -> R.string.phrase_distance
        GoalMetric.Floors -> R.string.phrase_floors
        GoalMetric.Energy -> R.string.phrase_energy
        GoalMetric.ZoneMinutes -> R.string.phrase_zone_minutes
    }

private val GoalMetric.format: (Double) -> String
    get() = if (this == GoalMetric.Distance) ::twoDecimals else { value -> whole(value) }
