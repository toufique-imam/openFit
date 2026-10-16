@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.readiness

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
import com.openfit.domain.DailyHistory
import com.openfit.domain.PeriodPoint
import com.openfit.domain.PeriodRow
import com.openfit.domain.Readiness
import com.openfit.domain.ReadinessBreakdown
import com.openfit.domain.ReadinessSummary
import com.openfit.domain.dailyPoints
import com.openfit.domain.dayRows
import com.openfit.domain.monthRows
import com.openfit.domain.monthlyPoints
import com.openfit.domain.periodDays
import com.openfit.domain.readiness
import com.openfit.domain.readinessBreakdown
import com.openfit.domain.readinessSummary
import com.openfit.domain.shiftPeriod
import com.openfit.domain.weekRows
import com.openfit.domain.weekRowsOf
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSource
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

sealed interface ReadinessUiState {
    data object Loading : ReadinessUiState
    data class Failed(val message: String) : ReadinessUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val readiness: Readiness?,
        val breakdown: ReadinessBreakdown?,
        val points: List<PeriodPoint>,
        val rows: List<PeriodRow>,
        val summary: ReadinessSummary?,
    ) : ReadinessUiState
}

class ReadinessViewModel(
    gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    dayLoadCache: DayLoadCache,
    snapshots: SnapshotStore,
) : ViewModel() {

    private val source = HealthConnectSource(gateway, dayLoadCache)

    private val _state = MutableStateFlow<ReadinessUiState>(ReadinessUiState.Loading)
    val state: StateFlow<ReadinessUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "readiness", ReadinessUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is ReadinessUiState.Ready) return
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
        _state.value = ReadinessUiState.Loading
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
                // Readiness compares each day with the 30 days before it; no cardio backfill is needed here.
                val history = source.history(days.first().minusDays(BASELINE_LEAD_DAYS), days.last(), profileStore.load(), backfillDays = 0)
                ready(today, days, history)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is ReadinessUiState.Ready && shownName == name)
                if (kept) current else ReadinessUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? ReadinessUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }

    private fun ready(today: LocalDate, days: List<LocalDate>, history: DailyHistory): ReadinessUiState.Ready {
        val scores = days.mapNotNull { day -> readiness(day, history)?.let { day to it.score.toDouble() } }.toMap()
        val end = days.last()
        return ReadinessUiState.Ready(
            period = period,
            anchor = anchor,
            today = today,
            readiness = if (period == ChartPeriod.Day) readiness(anchor, history) else null,
            breakdown = if (period == ChartPeriod.Day) readinessBreakdown(anchor, history) else null,
            points = when (period) {
                ChartPeriod.Day -> emptyList()
                ChartPeriod.Year -> monthlyPoints(anchor.year, scores, emptyMap())
                else -> dailyPoints(periodDays(period, anchor), scores, emptyMap())
            },
            rows = when (period) {
                ChartPeriod.Day -> emptyList()
                ChartPeriod.Week -> dayRows(days, scores)
                ChartPeriod.Month -> weekRows(YearMonth.from(anchor), scores.filterKeys { it <= end })
                ChartPeriod.ThreeMonths -> weekRowsOf(days, scores)
                ChartPeriod.Year -> monthRows(anchor.year, scores)
            },
            summary = readinessSummary(scores.values.map { it.toInt() }),
        )
    }

    private companion object {
        const val BASELINE_LEAD_DAYS = 31L
    }
}
