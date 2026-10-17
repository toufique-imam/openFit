@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.vo2

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.FitnessLevel
import com.openfit.domain.HealthMetric
import com.openfit.domain.Sex
import com.openfit.domain.ESTIMATE_DAYS
import com.openfit.domain.dailyVo2Max
import com.openfit.domain.fitnessBandEdges
import com.openfit.domain.fitnessPercentile
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectVitals
import com.openfit.profile.ProfileStore
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
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
import com.openfit.ui.today.fitnessBandName
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.ordinal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.floor

private val ACCENT @Composable get() = OpenFitColors.Blue.accent
private val PERIODS = listOf(ChartPeriod.Week, ChartPeriod.Month, ChartPeriod.Year)

sealed interface Vo2MaxUiState {
    data object Loading : Vo2MaxUiState
    data object NeedsProfile : Vo2MaxUiState
    data class Failed(val message: String) : Vo2MaxUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val age: Int,
        val sex: Sex,
        /** Each day's VO2 max and whether it was estimated from resting heart rate. */
        val values: Map<LocalDate, Pair<Double, Boolean>>,
    ) : Vo2MaxUiState {
        /** The period's latest value against the FRIEND reference for the user's age and sex. */
        val latest: FitnessLevel?
            get() = values.maxByOrNull { it.key }?.value?.let { (vo2, estimated) -> FitnessLevel(vo2, estimated, fitnessPercentile(vo2, age, sex)) }
    }
}

class Vo2MaxViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore, snapshots: SnapshotStore) : ViewModel() {

    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<Vo2MaxUiState>(Vo2MaxUiState.Loading)
    val state: StateFlow<Vo2MaxUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "vo2", Vo2MaxUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is Vo2MaxUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now())
            shownName = name
        }
    }

    /** The selected tab, observed by the screen: it shows even while a period loads or fails. */
    var period by mutableStateOf(ChartPeriod.Month)
        private set
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Month
        anchor = date
        _state.value = Vo2MaxUiState.Loading
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
                val profile = profileStore.load()
                val age = profile.age
                val sex = profile.sex
                if (age == null || sex == null) {
                    Vo2MaxUiState.NeedsProfile
                } else {
                    val today = LocalDate.now()
                    val days = periodDays(period, anchor).filter { it <= today }
                    val resting = vitals.daily(setOf(HealthMetric.RestingHeartRate), days.first().minusDays(ESTIMATE_DAYS.toLong()), days.last())[HealthMetric.RestingHeartRate].orEmpty()
                    val measured = vitals.vo2Max(days.first(), days.last())
                    Vo2MaxUiState.Ready(period, anchor, today, age, sex, dailyVo2Max(age, days, measured, resting))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is Vo2MaxUiState.Ready && shownName == name)
                if (kept) current else Vo2MaxUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? Vo2MaxUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }
}

@Composable
fun Vo2MaxRoute(viewModel: Vo2MaxViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? Vo2MaxUiState.Ready
    var sheet by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(
        title = stringResource(R.string.vo2_title),
        accent = ACCENT,
        period = viewModel.period,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            Vo2MaxUiState.Loading -> DetailLoading(ACCENT)
            Vo2MaxUiState.NeedsProfile -> DetailFailed(stringResource(R.string.vo2_needs_profile))
            is Vo2MaxUiState.Failed -> DetailFailed(current.message)
            is Vo2MaxUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                        period = current.period,
                    )
                }
                item { Header(current) }
                item { BandScale(current) }
                item { Chart(current) }
                item {
                    LinkSection(
                        title = stringResource(R.string.vo2_about),
                        action = stringResource(R.string.learn_more),
                        body = stringResource(R.string.vo2_about_body),
                        accent = OpenFitColors.Mint.accent,
                        maxLines = 3,
                        onAction = { sheet = true },
                    )
                }
            }
        }
    }
    if (sheet) InfoSheet(R.string.vo2_about_body) { sheet = false }
}

@Composable
private fun Header(state: Vo2MaxUiState.Ready) {
    Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
        val latest = state.latest
        if (latest == null) {
            HeaderValue(stringResource(R.string.no_data), "")
            Text(stringResource(R.string.vo2_no_data), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            return
        }
        HeaderValue(oneDecimal(latest.vo2Max), stringResource(R.string.vo2_unit))
        Text(
            stringResource(R.string.vo2_band_line, stringResource(fitnessBandName(latest.band)), ordinal(latest.percentile.toInt())),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenFitColors.OnSurface,
        )
        if (latest.estimated) {
            Text(stringResource(R.string.vo2_estimated_note), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The five bands for the user's age and sex, a marker at the latest value, and the edges between bands. */
@Composable
private fun BandScale(state: Vo2MaxUiState.Ready) {
    val edges = fitnessBandEdges(state.age, state.sex)
    val low = floor(edges.first() - (edges[1] - edges.first()))
    val high = ceil(edges.last() + (edges.last() - edges[edges.size - 2]))
    val value = state.latest?.vo2Max
    val bands = listOf(R.string.fitness_poor, R.string.fitness_fair, R.string.fitness_good, R.string.fitness_excellent, R.string.fitness_superior)
    Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 28.dp)) {
        val palette0 = OpenFitColors
        val accent0 = ACCENT
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val bar = 8.dp.toPx()
            val gap = 3.dp.toPx()
            val top = (size.height - bar) / 2
            val bounds = listOf(low) + edges + high
            fun x(v: Double) = ((v - low) / (high - low)).toFloat().coerceIn(0f, 1f) * size.width
            bands.indices.forEach { index ->
                val start = x(bounds[index]) + if (index > 0) gap / 2 else 0f
                val end = x(bounds[index + 1]) - if (index < bands.lastIndex) gap / 2 else 0f
                drawRoundRect(
                    accent0.copy(alpha = 0.2f + 0.2f * index),
                    Offset(start, top),
                    Size(end - start, bar),
                    CornerRadius(bar / 2),
                )
            }
            value?.let { drawCircle(palette0.OnSurface, radius = 7.dp.toPx(), center = Offset(x(it), size.height / 2)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            bands.forEach { band ->
                Text(stringResource(band), style = MaterialTheme.typography.labelMedium, color = OpenFitColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Text(
            stringResource(R.string.vo2_band_edges, edges.joinToString(" · ") { oneDecimal(it) }),
            style = MaterialTheme.typography.bodySmall,
            color = OpenFitColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Week and Month: a point per day; Year: each month's average. */
@Composable
private fun Chart(state: Vo2MaxUiState.Ready) {
    val slots: List<List<LocalDate>> = when (state.period) {
        ChartPeriod.Year -> (1..12).map { month -> YearMonth.of(state.anchor.year, month).let { ym -> (1..ym.lengthOfMonth()).map(ym::atDay) } }
        else -> periodDays(state.period, state.anchor).map { listOf(it) }
    }
    val points = slots.map { days -> days.mapNotNull { state.values[it]?.first }.takeIf { it.isNotEmpty() }?.average() }
    val present = points.filterNotNull()
    val center = if (present.isEmpty()) 45.0 else Math.round((present.min() + present.max()) / 2).toDouble()
    val half = if (present.isEmpty()) 5.0 else ceil(maxOf(present.max() - center, center - present.min()) + 1)
    val labels = listOf(center - half, center, center + half)
    RangeLineChart(
        values = points,
        ranges = points.map { null },
        color = ACCENT,
        outColor = ACCENT,
        axisMin = labels.first(),
        axisMax = labels.last(),
        axisLabels = labels.map { AxisLabel(it, oneDecimal(it)) },
        xLabels = chartXLabels(state.period, state.anchor),
        modifier = Modifier.padding(top = SectionGap),
    )
}
