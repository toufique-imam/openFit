package com.openfit.ui.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.HealthMetric
import com.openfit.domain.averagePerDay
import com.openfit.domain.periodDays
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.RangeDotsChart
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.ZoneColors
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.whole
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.floor

private val ACCENT = OpenFitColors.Blue.accent
private val OUT_OF_RANGE = ZoneColors.Vigorous
private val PERIODS = listOf(ChartPeriod.Week, ChartPeriod.Month, ChartPeriod.ThreeMonths, ChartPeriod.Year)

@Composable
fun HealthMetricsRoute(viewModel: HealthMetricsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? HealthMetricsUiState.Ready
    DetailScaffold(
        title = stringResource(R.string.hm_title),
        accent = ACCENT,
        period = ready?.period ?: ChartPeriod.Week,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            HealthMetricsUiState.Loading -> DetailLoading(ACCENT)
            is HealthMetricsUiState.Failed -> DetailFailed(current.message)
            is HealthMetricsUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                current.series.filter { it.values.isNotEmpty() }.forEach { series -> metricSection(current, series) }
                item { Legend() }
            }
        }
    }
}

private fun LazyListScope.metricSection(state: HealthMetricsUiState.Ready, series: MetricSeries) {
    item(key = series.metric.name) {
        val profile = state.profile
        val days = periodDays(state.period, state.anchor)
        val display = series.values.mapValues { display(series.metric, it.value, profile) }
        Column(Modifier.padding(top = 24.dp)) {
            Column(Modifier.padding(horizontal = PagePadding)) {
                Text(stringResource(title(series.metric)), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                val average = averagePerDay(days, display) ?: display.values.average()
                HeaderValue(format(series.metric, average), stringResource(unit(series.metric, profile)))
                Text(takeaway(state, series), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            }
            val points = bucket(state, display)
            val ranges = bucket(state, series.ranges.mapValues { display(series.metric, it.value.low, profile) }).zip(
                bucket(state, series.ranges.mapValues { display(series.metric, it.value.high, profile) }),
            ) { low, high -> if (low != null && high != null) low..high else null }
            val all = points.filterNotNull() + ranges.filterNotNull().flatMap { listOf(it.start, it.endInclusive) }
            val step = axisStep(series.metric)
            val low = floor((all.minOrNull() ?: 0.0) / step) * step - step
            val high = ceil((all.maxOrNull() ?: 1.0) / step) * step + step
            RangeDotsChart(
                values = points,
                ranges = ranges,
                color = ACCENT,
                outColor = OUT_OF_RANGE,
                axisMin = low,
                axisMax = high,
                axisLabels = listOf(low, (low + high) / 2, high).map { AxisLabel(it, format(series.metric, it)) },
                xLabels = chartXLabels(state.period, state.anchor),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** Week and Month: a dot per day; 3 months: per week; Year: per month (averages). */
private fun bucket(state: HealthMetricsUiState.Ready, values: Map<java.time.LocalDate, Double>): List<Double?> = when (state.period) {
    ChartPeriod.Year -> (1..12).map { month -> YearMonth.of(state.anchor.year, month).let { ym -> averagePerDay((1..ym.lengthOfMonth()).map(ym::atDay), values) } }
    ChartPeriod.ThreeMonths -> periodDays(state.period, state.anchor).chunked(7).map { week -> week.mapNotNull { values[it] }.takeIf { it.isNotEmpty() }?.average() }
    else -> periodDays(state.period, state.anchor).map { values[it] }
}

@Composable
private fun takeaway(state: HealthMetricsUiState.Ready, series: MetricSeries): String {
    val name = stringResource(title(series.metric)).lowercase()
    if (series.values.isEmpty()) return stringResource(R.string.hm_no_data_period, name)
    if (series.ranges.isEmpty()) return stringResource(R.string.hm_range_unavailable)
    val outside = series.values.filter { (day, value) -> series.ranges[day]?.let { value !in it } == true }
    if (outside.isEmpty()) return stringResource(R.string.hm_all_in_range)
    val latest = series.ranges.maxBy { it.key }.value
    return stringResource(
        R.string.hm_range_this_period,
        format(series.metric, display(series.metric, latest.low, state.profile)),
        format(series.metric, display(series.metric, latest.high, state.profile)),
        stringResource(unit(series.metric, state.profile)),
    )
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(top = SectionGap, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(ACCENT to R.string.hm_legend_in_range, OUT_OF_RANGE to R.string.hm_legend_out_of_range).forEach { (color, label) ->
            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            Spacer(Modifier.width(20.dp))
        }
        Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
            drawRect(ACCENT.copy(alpha = 0.16f), Offset(0f, 2.dp.toPx()), Size(size.width, size.height - 4.dp.toPx()))
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.hm_legend_personal_range), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
    }
}

fun title(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_breathing_rate
    HealthMetric.RestingHeartRate -> R.string.hm_resting_heart_rate
    HealthMetric.HeartRateVariability -> R.string.hm_hrv
    HealthMetric.BloodOxygen -> R.string.hm_blood_oxygen
    HealthMetric.SkinTemperature -> R.string.hm_skin_temperature
}

fun abbreviation(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_breathing_rate_abbr
    HealthMetric.RestingHeartRate -> R.string.hm_resting_heart_rate_abbr
    HealthMetric.HeartRateVariability -> R.string.hm_hrv_abbr
    HealthMetric.BloodOxygen -> R.string.hm_blood_oxygen_abbr
    HealthMetric.SkinTemperature -> R.string.hm_skin_temperature_abbr
}

private fun unit(metric: HealthMetric, profile: Profile): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_unit_brpm
    HealthMetric.RestingHeartRate -> R.string.unit_bpm
    HealthMetric.HeartRateVariability -> R.string.unit_ms
    HealthMetric.BloodOxygen -> R.string.unit_percent
    HealthMetric.SkinTemperature -> if (profile.units == UnitSystem.Imperial) R.string.hm_unit_fahrenheit else R.string.hm_unit_celsius
}

/** Skin temperature is a variation, so Fahrenheit only scales it (no offset). */
private fun display(metric: HealthMetric, value: Double, profile: Profile): Double =
    if (metric == HealthMetric.SkinTemperature && profile.units == UnitSystem.Imperial) value * 9 / 5 else value

private fun format(metric: HealthMetric, value: Double): String = when (metric) {
    HealthMetric.RestingHeartRate, HealthMetric.HeartRateVariability -> whole(value)
    HealthMetric.SkinTemperature -> (if (value > 0) "+" else "") + oneDecimal(value)
    else -> oneDecimal(value)
}

private fun axisStep(metric: HealthMetric): Double = when (metric) {
    HealthMetric.SkinTemperature -> 0.5
    HealthMetric.BreathingRate, HealthMetric.BloodOxygen -> 1.0
    else -> 5.0
}

