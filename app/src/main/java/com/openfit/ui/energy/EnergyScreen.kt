@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.energy

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.EnergyBand
import com.openfit.domain.EnergyDay
import com.openfit.domain.EnergyDrain
import com.openfit.domain.SLOTS_PER_DAY
import com.openfit.domain.SLOT_MINUTES
import com.openfit.domain.energyBand
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.health.EnergySource
import com.openfit.health.ExerciseSession
import com.openfit.health.HealthConnectExercises
import com.openfit.health.HealthConnectGateway
import com.openfit.health.SlotCache
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
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
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.weekdayDayMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ACCENT @Composable get() = OpenFitColors.Lavender
private val PERIODS = listOf(ChartPeriod.Day, ChartPeriod.Week)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

sealed interface EnergyUiState {
    data object Loading : EnergyUiState
    data class Failed(val message: String) : EnergyUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val days: Map<LocalDate, EnergyDay>,
        /** Day tab: the day's workouts, to name the drains they caused. */
        val workouts: List<ExerciseSession>,
    ) : EnergyUiState {
        val day: EnergyDay? get() = days[anchor]
    }
}

class EnergyViewModel(gateway: HealthConnectGateway, slotCache: SlotCache, snapshots: SnapshotStore) : ViewModel() {

    private val energy = EnergySource(gateway, slotCache)
    private val exercises = HealthConnectExercises(gateway)

    private val _state = MutableStateFlow<EnergyUiState>(EnergyUiState.Loading)
    val state: StateFlow<EnergyUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "energy", EnergyUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is EnergyUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now())
            shownName = name
        }
    }

    /** The selected tab, observed by the screen: it shows even while a period loads or fails. */
    var period by mutableStateOf(ChartPeriod.Day)
        private set
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = EnergyUiState.Loading
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
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val today = LocalDate.now()
                    val days = periodDays(period, anchor).filter { it <= today }
                    EnergyUiState.Ready(
                        period = period,
                        anchor = anchor,
                        today = today,
                        days = energy.days(days.first(), days.last()).associateBy { it.date },
                        workouts = if (period == ChartPeriod.Day) exercises.sessions(anchor, anchor) else emptyList(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is EnergyUiState.Ready && shownName == name)
                if (kept) current else EnergyUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? EnergyUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }
}

@Composable
fun EnergyRoute(viewModel: EnergyViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? EnergyUiState.Ready
    var sheet by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(
        title = stringResource(R.string.energy_title),
        accent = ACCENT.accent,
        period = viewModel.period,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            EnergyUiState.Loading -> DetailLoading(ACCENT.accent)
            is EnergyUiState.Failed -> DetailFailed(current.message)
            is EnergyUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                        period = current.period,
                    )
                }
                if (current.period == ChartPeriod.Day) dayContent(current) else weekContent(current)
                item {
                    LinkSection(
                        title = stringResource(R.string.energy_about_title),
                        action = stringResource(R.string.learn_more),
                        body = stringResource(R.string.energy_about_body),
                        accent = OpenFitColors.Mint.accent,
                        onAction = { sheet = true },
                    )
                }
            }
        }
    }
    if (sheet) InfoSheet(R.string.energy_sheet) { sheet = false }
}

private fun LazyListScope.dayContent(state: EnergyUiState.Ready) {
    val day = state.day
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            val level = day?.latest
            HeaderValue(level?.roundToInt()?.toString() ?: "–", "%")
            Text(
                when {
                    level == null -> stringResource(R.string.energy_calibrating)
                    else -> stringResource(bandText(energyBand(level)))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
            )
            val wakeSlot = day?.wakeSlot
            val wakeLevel = day?.wakeLevel
            if (wakeSlot != null && wakeLevel != null) {
                val minute = (wakeSlot + 1) * SLOT_MINUTES
                Text(
                    stringResource(R.string.energy_woke, "%02d:%02d".format(minute / 60, minute % 60), wakeLevel.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
            }
            day?.latestSlot?.takeIf { state.anchor == state.today }?.let { slot ->
                val minute = (slot + 1) * SLOT_MINUTES
                Text(
                    stringResource(R.string.energy_updated, "%02d:%02d".format(minute / 60 % 24, minute % 60)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
            }
        }
    }
    if (day == null) return
    item { DayChart(day, Modifier.padding(top = 32.dp)) }
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.energy_charged), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                Text("+${day.charged.roundToInt()}", style = MaterialTheme.typography.headlineMedium, color = ACCENT.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.energy_drained), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                Text("−${day.drained.roundToInt()}", style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
            }
        }
    }
    if (day.drains.isEmpty()) return
    item {
        Text(
            stringResource(R.string.energy_biggest_drains),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.OnSurface,
            modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp),
        )
    }
    itemsIndexed(day.drains) { index, drain ->
        GroupedCard(index, day.drains.size, minHeight = 68.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(drainTitle(drain, state.workouts, state.anchor), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface)
                    Text(drainTime(drain, state.workouts, state.anchor), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                }
                Text("−${drain.points.roundToInt()}", style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
            }
        }
    }
}

private fun LazyListScope.weekContent(state: EnergyUiState.Ready) {
    val days = periodDays(state.period, state.anchor)
    val peaks = days.map { state.days[it]?.peak }
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            val average = peaks.filterNotNull().takeIf { it.isNotEmpty() }?.average()
            HeaderValue(average?.roundToInt()?.toString() ?: "–", stringResource(R.string.energy_peak_avg))
            Text(stringResource(R.string.energy_peak_body), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
    item {
        BarChart(
            values = peaks,
            color = ACCENT.accent,
            axisMax = 100.0,
            axisLabels = listOf(0, 50, 100).map { AxisLabel(it.toDouble(), it.toString()) },
            xLabels = chartXLabels(state.period, state.anchor),
            modifier = Modifier.padding(top = 32.dp),
        )
    }
    val rows = days.filter { it <= state.today }.sortedDescending()
    item {
        Text(
            stringResource(R.string.energy_peak_list),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.OnSurface,
            modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp),
        )
    }
    itemsIndexed(rows) { index, date ->
        GroupedCard(index, rows.size, minHeight = 68.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(date.weekdayDayMonth(), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                Text(
                    state.days[date]?.peak?.roundToInt()?.let { "$it%" } ?: stringResource(R.string.no_data),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OpenFitColors.OnSurface,
                )
            }
        }
    }
}

/** The day's level as a line over 24 hours, broken where the device was not worn. */
@Composable
private fun DayChart(day: EnergyDay, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    val palette0 = OpenFitColors
    val accent0 = ACCENT
    Canvas(modifier.fillMaxWidth().height(212.dp)) {
        val left = 24.dp.toPx()
        val width = 288.dp.toPx()
        val plot = 180.dp.toPx()
        fun x(slot: Int) = left + slot * width / SLOTS_PER_DAY
        fun y(level: Double) = plot - (level / 100).toFloat() * plot
        listOf(0.0, 50.0, 100.0).forEach { level ->
            drawLine(palette0.OnSurfaceVariant.copy(alpha = 0.3f), Offset(left, y(level)), Offset(left + width, y(level)), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure(level.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(left + width + 10.dp.toPx(), y(level) - layout.size.height / 2f))
        }
        var path: Path? = null
        day.levels.forEachIndexed { slot, level ->
            if (level == null) {
                path?.let { drawPath(it, accent0.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                path = null
            } else {
                val point = Offset(x(slot), y(level))
                path = (path ?: Path().apply { moveTo(point.x, point.y) }).apply { lineTo(point.x, point.y) }
            }
        }
        path?.let { drawPath(it, accent0.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
        listOf(0 to "12am", 6 to "6am", 12 to "12pm", 18 to "6pm").forEach { (hour, label) ->
            val layout = measurer.measure(label, labelStyle)
            drawText(layout, topLeft = Offset(x(hour * 60 / SLOT_MINUTES), plot + 8.dp.toPx()))
        }
    }
}

private fun bandText(band: EnergyBand): Int = when (band) {
    EnergyBand.Plenty -> R.string.energy_band_plenty
    EnergyBand.PaceYourself -> R.string.energy_band_pace
    EnergyBand.RunningLow -> R.string.energy_band_low
}

private fun workoutAt(drain: EnergyDrain.Workout, workouts: List<ExerciseSession>, date: LocalDate): ExerciseSession? {
    val zone = ZoneId.systemDefault()
    val slotStart = date.atStartOfDay(zone).toInstant().plusSeconds(drain.slot * SLOT_MINUTES * 60L)
    return workouts.minByOrNull { kotlin.math.abs(it.start.epochSecond - slotStart.epochSecond) }
}

@Composable
private fun drainTitle(drain: EnergyDrain, workouts: List<ExerciseSession>, date: LocalDate): String = when (drain) {
    is EnergyDrain.Workout -> workoutAt(drain, workouts, date)?.name ?: stringResource(R.string.energy_drain_workout)
    is EnergyDrain.Stress -> stringResource(R.string.energy_drain_stress)
}

private fun drainTime(drain: EnergyDrain, workouts: List<ExerciseSession>, date: LocalDate): String = when (drain) {
    is EnergyDrain.Workout -> {
        val zone = ZoneId.systemDefault()
        workoutAt(drain, workouts, date)?.let { "${it.start.atZone(zone).format(CLOCK)}–${it.end.atZone(zone).format(CLOCK)}" }
            ?: "%02d:%02d".format(drain.slot * SLOT_MINUTES / 60, drain.slot * SLOT_MINUTES % 60)
    }
    is EnergyDrain.Stress -> "%02d:00–%02d:00".format(drain.hour, (drain.hour + 1) % 24)
}
