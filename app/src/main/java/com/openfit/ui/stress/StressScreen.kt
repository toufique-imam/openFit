@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.stress

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.SLOTS_PER_DAY
import com.openfit.domain.SLOT_MINUTES
import com.openfit.domain.StressBand
import com.openfit.domain.StressDay
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.domain.stressBand
import com.openfit.health.EnergySource
import com.openfit.health.HealthConnectGateway
import com.openfit.health.SlotCache
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.detail.periodRows
import com.openfit.domain.PeriodRow
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.minutesLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

private val ACCENT = OpenFitColors.Lavender
private val PERIODS = listOf(ChartPeriod.Day, ChartPeriod.Week, ChartPeriod.Month)

/** Pulse's band colours: calm blue, medium mint, high amber. */
fun stressColor(band: StressBand): Color = when (band) {
    StressBand.Low -> OpenFitColors.Blue.accent
    StressBand.Medium -> OpenFitColors.Mint.accent
    StressBand.High -> Color(0xFFF2A65A)
}

fun stressBandName(band: StressBand): Int = when (band) {
    StressBand.Low -> R.string.stress_low
    StressBand.Medium -> R.string.stress_medium
    StressBand.High -> R.string.stress_high
}

sealed interface StressUiState {
    data object Loading : StressUiState
    data class Failed(val message: String) : StressUiState
    @Serializable
    data class Ready(val period: ChartPeriod, val anchor: LocalDate, val today: LocalDate, val days: Map<LocalDate, StressDay>) : StressUiState {
        val day: StressDay? get() = days[anchor]
    }
}

class StressViewModel(gateway: HealthConnectGateway, slotCache: SlotCache, snapshots: SnapshotStore) : ViewModel() {

    private val source = EnergySource(gateway, slotCache)

    private val _state = MutableStateFlow<StressUiState>(StressUiState.Loading)
    val state: StateFlow<StressUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "stress", StressUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is StressUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now())
            shownName = name
        }
    }

    private var period = ChartPeriod.Day
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = StressUiState.Loading
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
                StressUiState.Ready(period, anchor, today, source.stress(days.first(), days.last()).associateBy { it.date })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is StressUiState.Ready && shownName == name)
                if (kept) current else StressUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? StressUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }
}

@Composable
fun StressRoute(viewModel: StressViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? StressUiState.Ready
    var sheet by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(
        title = stringResource(R.string.stress_title),
        accent = ACCENT.accent,
        period = ready?.period ?: ChartPeriod.Day,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            StressUiState.Loading -> DetailLoading(ACCENT.accent)
            is StressUiState.Failed -> DetailFailed(current.message)
            is StressUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                if (current.period == ChartPeriod.Day) dayContent(current) else periodContent(current)
                item {
                    LinkSection(
                        title = stringResource(R.string.stress_about_title),
                        action = stringResource(R.string.learn_more),
                        body = stringResource(R.string.stress_about_body),
                        accent = OpenFitColors.Mint.accent,
                        onAction = { sheet = true },
                    )
                }
            }
        }
    }
    if (sheet) InfoSheet(R.string.stress_sheet) { sheet = false }
}

private fun LazyListScope.dayContent(state: StressUiState.Ready) {
    val day = state.day
    val isToday = state.anchor == state.today
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            // Today shows the latest reading, past days the day's average, as Pulse does.
            val value = if (isToday) day?.latest else day?.average
            HeaderValue(value?.toString() ?: "–", value?.let { stringResource(stressBandName(stressBand(it))) } ?: "")
            Text(
                when {
                    day == null -> stringResource(R.string.stress_calibrating)
                    isToday -> day.latestSlot?.let { slot ->
                        val minute = (slot + 1) * SLOT_MINUTES
                        stringResource(R.string.energy_updated, "%02d:%02d".format(minute / 60 % 24, minute % 60))
                    }.orEmpty()
                    else -> stringResource(R.string.stress_day_average)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
            )
        }
    }
    if (day == null) return
    item { DayChart(day, Modifier.padding(top = 32.dp)) }
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
            StressBand.entries.forEach { band ->
                Column(Modifier.weight(1f)) {
                    Text(minutesLabel(day.minutes(band).toLong()), style = MaterialTheme.typography.headlineSmall, color = stressColor(band))
                    Text(stringResource(stressBandName(band)), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                }
            }
        }
    }
}

private fun LazyListScope.periodContent(state: StressUiState.Ready) {
    val days = periodDays(state.period, state.anchor)
    val averages = days.map { state.days[it]?.average?.toDouble() }
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            val average = averages.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()
            HeaderValue(average?.toString() ?: "–", stringResource(R.string.stress_average_label))
            average?.let { Text(stringResource(stressBandName(stressBand(it))), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant) }
        }
    }
    item {
        BarChart(
            values = averages,
            color = ACCENT.accent,
            axisMax = 100.0,
            axisLabels = listOf(0, 30, 60, 100).map { AxisLabel(it.toDouble(), it.toString()) },
            xLabels = chartXLabels(state.period, state.anchor),
            guides = listOf(30.0, 60.0),
            modifier = Modifier.padding(top = 32.dp),
        )
    }
    val rows = days.filter { it <= state.today }.mapNotNull { day -> state.days[day]?.average?.let { PeriodRow(day, day, it.toDouble()) } }.sortedByDescending { it.start }
    if (state.period == ChartPeriod.Week) periodRows(state.period, state.anchor, state.today, rows)
}

/** The day's stress per six minutes as bars coloured by band, with sleep shaded behind. */
@Composable
private fun DayChart(day: StressDay, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    val sleepShade = ACCENT.track
    Canvas(modifier.fillMaxWidth().height(212.dp)) {
        val left = 24.dp.toPx()
        val width = 288.dp.toPx()
        val plot = 180.dp.toPx()
        val slot = width / SLOTS_PER_DAY
        fun y(stress: Int) = plot - stress / 100f * plot
        day.asleep.forEachIndexed { i, asleep -> if (asleep) drawRect(sleepShade, Offset(left + i * slot, 0f), Size(slot + 0.5f, plot)) }
        listOf(0, 30, 60, 100).forEach { level ->
            drawLine(OpenFitColors.OnSurfaceVariant.copy(alpha = 0.3f), Offset(left, y(level)), Offset(left + width, y(level)), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure(level.toString(), labelStyle)
            drawText(layout, topLeft = Offset(left + width + 10.dp.toPx(), y(level) - layout.size.height / 2f))
        }
        day.slots.forEachIndexed { i, stress ->
            stress ?: return@forEachIndexed
            drawRect(stressColor(stressBand(stress)), Offset(left + i * slot, y(stress)), Size(maxOf(slot - 0.5f, 1f), plot - y(stress)))
        }
        listOf(0 to "12am", 6 to "6am", 12 to "12pm", 18 to "6pm").forEach { (hour, label) ->
            val layout = measurer.measure(label, labelStyle)
            drawText(layout, topLeft = Offset(left + hour * 60 / SLOT_MINUTES * slot, plot + 8.dp.toPx()))
        }
    }
}
