@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.sleep

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.ChartPeriod
import com.openfit.domain.SleepNight
import com.openfit.domain.SleepStage
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.domain.sleepScore
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSleep
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sqrt

/** Day tab: how the stage rows are drawn. */
enum class StageMode { Stages, Benchmark, Average }

/** Week and longer: which chart is shown. */
enum class SleepChart { Duration, Score, Schedule, Stage }

/** The user's sleep schedule (bedtime and wake time), as minutes after 6pm the evening before (so midnight is 360). */
@Serializable
data class SleepSchedule(val startMinute: Int, val endMinute: Int)

sealed interface SleepUiState {
    data object Loading : SleepUiState
    data class Failed(val message: String) : SleepUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val profile: Profile,
        val nights: Map<LocalDate, SleepNight>,
        val scores: Map<LocalDate, Int>,
        val schedule: SleepSchedule?,
        /** Day tab: the 30-day average share of each stage, and the night's blood-oxygen spread (null without data). */
        val averagePercent: Map<SleepStage, Double>,
        val oxygenVariation: Double?,
        val stageMode: StageMode,
        val chart: SleepChart,
        val chartStage: SleepStage,
    ) : SleepUiState {
        val night: SleepNight? get() = nights[anchor]
    }
}

class SleepViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore, snapshots: SnapshotStore) : ViewModel() {

    private val sleep = HealthConnectSleep(gateway)

    private val _state = MutableStateFlow<SleepUiState>(SleepUiState.Loading)
    val state: StateFlow<SleepUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "sleep", SleepUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is SleepUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now(), profile = profileStore.load(), stageMode = stageMode, chart = chart, chartStage = chartStage)
            shownName = name
        }
    }

    /** The selected tab, observed by the screen: it shows even while a period loads or fails. */
    var period by mutableStateOf(ChartPeriod.Day)
        private set
    private var anchor: LocalDate = LocalDate.now()
    private var stageMode = StageMode.Stages
    private var chart = SleepChart.Duration
    private var chartStage = SleepStage.Deep
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = SleepUiState.Loading
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

    fun saveProfile(profile: Profile) {
        profileStore.save(profile)
        load()
    }

    fun selectStageMode(mode: StageMode) = update { it.copy(stageMode = mode.also { stageMode = it }) }

    fun selectChart(selected: SleepChart, stage: SleepStage = chartStage) =
        update { it.copy(chart = selected.also { chart = it }, chartStage = stage.also { chartStage = it }) }

    private fun update(change: (SleepUiState.Ready) -> SleepUiState.Ready) {
        (_state.value as? SleepUiState.Ready)?.let { _state.value = change(it) }
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
                // The 30-day averages look back a month before the period.
                val history = sleep.nights(days.first().minusDays(AVERAGE_DAYS), days.last())
                val reference = if (period == ChartPeriod.Day) anchor else days.last()
                val recent = history.filterKeys { it < reference && it >= reference.minusDays(AVERAGE_DAYS) }
                val night = history[anchor]
                val profile = profileStore.load()
                SleepUiState.Ready(
                    period = period,
                    anchor = anchor,
                    today = today,
                    profile = profile,
                    nights = history.filterKeys { it in days },
                    scores = history.filterKeys { it in days }.mapNotNull { (day, n) -> sleepScore(n)?.let { day to it } }.toMap(),
                    schedule = schedule(profile),
                    averagePercent = SleepStage.entries.associateWith { stage ->
                        recent.values.filter { it.segments.size > 1 }.map { it.percent(stage) }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                    },
                    oxygenVariation = if (period == ChartPeriod.Day && night != null) spread(sleep.oxygen(night.start, night.end)) else null,
                    stageMode = stageMode,
                    chart = chart,
                    chartStage = chartStage,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is SleepUiState.Ready && shownName == name)
                if (kept) current else SleepUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? SleepUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }

    /** The schedule the user set as a goal; null until they set one. */
    private fun schedule(profile: Profile): SleepSchedule? {
        val bedtime = profile.bedtime ?: return null
        val wake = profile.wakeTime ?: return null
        return SleepSchedule(minutesAfterSixPm(bedtime), minutesAfterSixPm(wake))
    }

    private fun spread(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    companion object {
        const val AVERAGE_DAYS = 30L

        fun minutesAfterSixPm(time: LocalTime): Int = ((time.hour * 60 + time.minute) - 18 * 60).mod(24 * 60)
    }
}
