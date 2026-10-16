@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.vitals

import com.openfit.ui.detail.keepAfterFailure
import com.openfit.ui.detail.SavedScreen
import com.openfit.data.SnapshotStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import com.openfit.domain.DurationSerializer
import com.openfit.domain.InstantSerializer
import com.openfit.domain.IntRangeSerializer
import com.openfit.domain.LocalDateSerializer
import com.openfit.domain.LocalTimeSerializer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.HealthMetric
import com.openfit.domain.PersonalRange
import com.openfit.domain.periodDays
import com.openfit.domain.personalRange
import com.openfit.domain.shiftPeriod
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectVitals
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.RangeLineChart
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.dayMonth
import com.openfit.ui.today.weekRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

private val ACCENT = OpenFitColors.Blue.accent
private val OUT_OF_RANGE = OpenFitColors.Pink.accent
private val PERIODS = listOf(ChartPeriod.Week, ChartPeriod.Month, ChartPeriod.Year)

/** A row of Fitbit's list under the chart: a day, week or month, its average and how often it left the range. */
@Serializable
data class MetricListRow(val start: LocalDate, val end: LocalDate, val average: Double, val outOfRange: Int)

sealed interface MetricDetailUiState {
    data object Loading : MetricDetailUiState
    data class Failed(val message: String) : MetricDetailUiState
    @Serializable
    data class Ready(
        val metric: HealthMetric,
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val profile: Profile,
        /** Daily values and personal ranges within the period. */
        val values: Map<LocalDate, Double>,
        val ranges: Map<LocalDate, PersonalRange>,
        val rows: List<MetricListRow>,
    ) : MetricDetailUiState {
        val outOfRange: Int get() = values.count { (day, value) -> ranges[day]?.let { value !in it } == true }
    }
}

class MetricDetailViewModel(private val metric: HealthMetric, gateway: HealthConnectGateway, private val profileStore: ProfileStore, snapshots: SnapshotStore) : ViewModel() {

    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<MetricDetailUiState>(MetricDetailUiState.Loading)
    val state: StateFlow<MetricDetailUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "metric:${metric.name}", MetricDetailUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is MetricDetailUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now(), profile = profileStore.load())
            shownName = name
        }
    }

    /** The selected tab, observed by the screen: it shows even while a period loads or fails. */
    var period by mutableStateOf(ChartPeriod.Week)
        private set
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Week
        anchor = date
        _state.value = MetricDetailUiState.Loading
        load()
    }

    fun selectPeriod(selected: ChartPeriod) {
        if (selected == period) return
        period = selected
        load()
    }

    fun shift(steps: Long) {
        anchor = shiftPeriod(period, anchor, steps).coerceAtMost(LocalDate.now())
        load()
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val name = "$period:$anchor"
            restoreSaved(name)
            var kept = false
            _state.value = try {
                val today = LocalDate.now()
                val days = periodDays(period, anchor).filter { it <= today }
                val daily = vitals.daily(setOf(metric), days.first().minusDays(BASELINE_LEAD_DAYS), days.last())[metric].orEmpty()
                val values = daily.filterKeys { it in days }
                val ranges = days.mapNotNull { day -> personalRange(day, daily)?.let { day to it } }.toMap()
                MetricDetailUiState.Ready(metric, period, anchor, today, profileStore.load(), values, ranges, rows(days, values, ranges))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is MetricDetailUiState.Ready && shownName == name)
                if (kept) current else MetricDetailUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? MetricDetailUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }

    /** Week: each day with a value; Month: each Sunday week; Year: each month. Newest first. */
    private fun rows(days: List<LocalDate>, values: Map<LocalDate, Double>, ranges: Map<LocalDate, PersonalRange>): List<MetricListRow> {
        val groups: List<List<LocalDate>> = when (period) {
            ChartPeriod.Year -> days.groupBy { YearMonth.from(it) }.values.toList()
            ChartPeriod.Month -> days.groupBy { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)) }.values.toList()
            else -> days.map { listOf(it) }
        }
        return groups.mapNotNull { group ->
            val present = group.filter { it in values }
            if (present.isEmpty()) return@mapNotNull null
            MetricListRow(
                start = group.first(),
                end = group.last(),
                average = present.map { values.getValue(it) }.average(),
                outOfRange = present.count { day -> ranges[day]?.let { values.getValue(day) !in it } == true },
            )
        }.sortedByDescending { it.start }
    }

    private companion object {
        const val BASELINE_LEAD_DAYS = 30L
    }
}

@Composable
fun MetricDetailRoute(viewModel: MetricDetailViewModel, metric: HealthMetric, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? MetricDetailUiState.Ready
    var sheet by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(
        title = stringResource(title(metric)),
        accent = ACCENT,
        period = viewModel.period,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            MetricDetailUiState.Loading -> DetailLoading(ACCENT)
            is MetricDetailUiState.Failed -> DetailFailed(current.message)
            is MetricDetailUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                item { Header(current) }
                item { Chart(current) }
                item { Legend() }
                item {
                    LinkSection(
                        title = stringResource(aboutTitle(metric)),
                        action = stringResource(R.string.learn_more),
                        body = stringResource(aboutBody(metric)),
                        accent = OpenFitColors.Mint.accent,
                        maxLines = 3,
                        onAction = { sheet = true },
                    )
                }
                listRows(current)
                item { Disclaimer() }
            }
        }
    }
    if (sheet) InfoSheet(aboutBody(metric)) { sheet = false }
}

@Composable
private fun Header(state: MetricDetailUiState.Ready) {
    Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
        val metric = state.metric
        if (state.values.isEmpty()) {
            HeaderValue(stringResource(R.string.no_data), "")
            Text(stringResource(R.string.hm_unavailable, stringResource(title(metric))), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            return
        }
        val average = display(metric, state.values.values.average(), state.profile)
        HeaderValue(format(metric, average), stringResource(R.string.hm_average_unit, stringResource(unit(metric, state.profile))))
        Text(
            when {
                state.ranges.isEmpty() -> stringResource(R.string.hm_range_unavailable)
                state.outOfRange == 0 -> stringResource(R.string.hm_all_in_range)
                else -> pluralStringResource(R.plurals.hm_out_of_range_times, state.outOfRange, state.outOfRange)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OpenFitColors.OnSurfaceVariant,
        )
    }
}

/** Week and Month: a point per day; Year: per month. Each slot's range is the average of its days' ranges. */
@Composable
private fun Chart(state: MetricDetailUiState.Ready) {
    val metric = state.metric
    val profile = state.profile
    val slots: List<List<LocalDate>> = when (state.period) {
        ChartPeriod.Year -> (1..12).map { month -> YearMonth.of(state.anchor.year, month).let { ym -> (1..ym.lengthOfMonth()).map(ym::atDay) } }
        else -> periodDays(state.period, state.anchor).map { listOf(it) }
    }
    fun average(days: List<LocalDate>, value: (LocalDate) -> Double?): Double? = days.mapNotNull(value).takeIf { it.isNotEmpty() }?.average()
    val points = slots.map { days -> average(days) { state.values[it] }?.let { display(metric, it, profile) } }
    val ranges = slots.map { days ->
        val low = average(days) { state.ranges[it]?.low }
        val high = average(days) { state.ranges[it]?.high }
        if (low != null && high != null) display(metric, low, profile)..display(metric, high, profile) else null
    }
    val all = points.filterNotNull() + ranges.filterNotNull().flatMap { listOf(it.start, it.endInclusive) }
    val (low, mid, high) = axis(metric, all)
    RangeLineChart(
        values = points,
        ranges = ranges,
        color = ACCENT,
        outColor = OUT_OF_RANGE,
        axisMin = low,
        axisMax = high,
        axisLabels = listOf(low, mid, high).map { AxisLabel(it, format(metric, it)) },
        xLabels = chartXLabels(state.period, state.anchor),
        modifier = Modifier.padding(top = 32.dp),
    )
}

/** Three labels around the data: Fitbit's blood-oxygen chart reads 90 / 95 / 100 with nothing to show. */
private fun axis(metric: HealthMetric, values: List<Double>): Triple<Double, Double, Double> {
    if (values.isEmpty()) {
        return when (metric) {
            HealthMetric.BloodOxygen -> Triple(90.0, 95.0, 100.0)
            HealthMetric.SkinTemperature -> Triple(-2.0, 0.0, 2.0)
            HealthMetric.BreathingRate -> Triple(10.0, 15.0, 20.0)
            else -> Triple(40.0, 60.0, 80.0)
        }
    }
    val step = when (metric) {
        HealthMetric.SkinTemperature -> 0.5
        HealthMetric.BreathingRate, HealthMetric.BloodOxygen -> 1.0
        else -> 2.0
    }
    val center = Math.round((values.min() + values.max()) / 2 / step) * step
    val half = ceil((maxOf(values.max() - center, center - values.min()) + step) / step) * step
    val top = if (metric == HealthMetric.BloodOxygen) minOf(center + half, 100.0) else center + half
    return Triple(floor(center - half), center, top)
}

@Composable
private fun Legend() {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                drawRect(ACCENT.copy(alpha = 0.16f), Offset(0f, 2.dp.toPx()), Size(size.width, size.height - 4.dp.toPx()))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hm_legend_personal_range), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            Spacer(Modifier.width(24.dp))
            Canvas(Modifier.size(8.dp)) { drawCircle(ACCENT) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hm_legend_in_range), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(8.dp)) { drawCircle(OUT_OF_RANGE) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hm_legend_out_of_range), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

private fun LazyListScope.listRows(state: MetricDetailUiState.Ready) {
    if (state.rows.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp)) {
            Text(periodLabel(state.period, state.anchor, state.today), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.hm_list_average, stringResource(title(state.metric))), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
        }
    }
    itemsIndexed(state.rows) { index, row ->
        GroupedCard(index, state.rows.size, minHeight = 80.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rowLabel(state, row), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${format(state.metric, display(state.metric, row.average, state.profile))} ${stringResource(unit(state.metric, state.profile))}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = OpenFitColors.OnSurface,
                    )
                    if (row.outOfRange > 0) {
                        Text(pluralStringResource(R.plurals.hm_out_of_range_times, row.outOfRange, row.outOfRange), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "Today", "Yesterday", "14 Oct"; "This week", "4–10 Oct"; "This month", "September". */
@Composable
private fun rowLabel(state: MetricDetailUiState.Ready, row: MetricListRow): String = when (state.period) {
    ChartPeriod.Year -> if (YearMonth.from(row.start) == YearMonth.from(state.today)) {
        stringResource(R.string.this_month)
    } else {
        row.start.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
    }
    ChartPeriod.Month -> if (state.today in row.start..row.end) stringResource(R.string.this_week) else weekRange(row.start, row.end)
    else -> when (row.start) {
        state.today -> stringResource(R.string.today)
        state.today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> row.start.dayMonth()
    }
}

@Composable
private fun Disclaimer() {
    Column(Modifier.padding(top = SectionGap)) {
        HorizontalDivider(Modifier.padding(horizontal = PagePadding), color = OpenFitColors.OnSurfaceVariant.copy(alpha = 0.3f))
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = OpenFitColors.OnSurface)
            Text(stringResource(R.string.hm_disclaimer), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}
