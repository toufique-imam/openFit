package com.openfit.ui.goal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material3.MaterialTheme
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
import com.openfit.ui.today.whole
import kotlin.math.abs

private val ACCENT = OpenFitColors.Mint

private val HOUR_LABELS = listOf(0 to "12am", 4 to "4am", 8 to "8am", 12 to "12pm", 16 to "4pm", 20 to "8pm", 23 to "11pm")

@Composable
fun GoalMetricRoute(viewModel: GoalMetricViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? GoalMetricUiState.Ready
    DetailScaffold(
        title = stringResource(R.string.tile_steps),
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
        DayHeaderRow(
            value = whole(value),
            status = stringResource(R.string.steps_of_goal, whole(state.goal)),
            description = when {
                remaining > 0 -> stringResource(if (isToday) R.string.steps_away_today else R.string.steps_away_past, whole(remaining))
                remaining < 0 -> stringResource(R.string.steps_exceeded, whole(abs(remaining)))
                else -> stringResource(R.string.steps_met)
            },
        ) {
            ArcGauge(Icons.AutoMirrored.Outlined.DirectionsWalk, ACCENT, (value / state.goal).toFloat(), background = OpenFitColors.Background)
        }
    }
    item {
        val axis = hourlyAxis(state.hourly.maxOf { it ?: 0.0 })
        BarChart(
            values = state.hourly,
            color = ACCENT.accent,
            axisMax = axis.max,
            axisLabels = listOf(0.0, axis.middle.toDouble(), axis.max).map { AxisLabel(it, compact(it)) },
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
            HeaderValue(summary.averagePerDay?.let(::whole) ?: "0", stringResource(R.string.steps_per_day_avg))
            val total = whole(summary.total)
            Text(
                when {
                    state.period == ChartPeriod.Year ->
                        stringResource(if (current) R.string.steps_year_current else R.string.steps_year_past, total)
                    summary.goalDays > 0 -> pluralStringResource(
                        if (current) R.plurals.steps_goal_days_current else R.plurals.steps_goal_days_past,
                        summary.goalDays,
                        summary.goalDays,
                        total,
                    )
                    !current -> stringResource(R.string.steps_no_goal_past, total)
                    state.period == ChartPeriod.Week -> stringResource(R.string.steps_no_goal_week, total)
                    else -> stringResource(R.string.steps_no_goal_month, total)
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
    periodRows(state.period, state.anchor, state.today, state.rows)
}

/** Axis labels the way Fitbit writes them: 500, 1.6k, 10k. */
private fun compact(value: Double): String = when {
    value < 1000 -> whole(value)
    value % 1000 == 0.0 -> "${(value / 1000).toInt()}k"
    else -> "${"%.1f".format(value / 1000).trimEnd('0').trimEnd('.')}k"
}
