@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.heart

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
import com.openfit.domain.HeartDay
import com.openfit.domain.activeMinutes
import com.openfit.domain.averagePerDay
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.domain.weeklyAverages
import com.openfit.domain.zonesEntered
import com.openfit.health.DailyTotal
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSource
import com.openfit.health.HealthConnectTotals
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** The two charts Fitbit's Heart screen toggles between on Week and longer periods. */
enum class HeartMode { Resting, Zones }

/** A row of the list under the chart: a day (Week) or a week/month, with its resting rate and zones. */
@Serializable
data class HeartRow(val start: LocalDate, val end: LocalDate, val restingBpm: Double?, val zones: Int?, val sparkline: List<Double>)

sealed interface HeartUiState {
    data object Loading : HeartUiState
    data class Failed(val message: String) : HeartUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val mode: HeartMode,
        val day: HeartDay?,
        val caloriesKcal: Double?,
        val restingPoints: List<Double?>,
        val zonePoints: List<Double?>,
        val restingAverage: Double?,
        val previousRestingAverage: Double?,
        val zoneAveragePerDay: Double?,
        val zoneTotalMinutes: Int,
        val rows: List<HeartRow>,
    ) : HeartUiState
}

class HeartViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    dayLoadCache: DayLoadCache,
    snapshots: SnapshotStore,
) : ViewModel() {

    private val source = HealthConnectSource(gateway, dayLoadCache)
    private val totals = HealthConnectTotals(gateway)

    private val _state = MutableStateFlow<HeartUiState>(HeartUiState.Loading)
    val state: StateFlow<HeartUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "heart", HeartUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is HeartUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now(), mode = mode)
            shownName = name
        }
    }

    /** The selected tab, observed by the screen: it shows even while a period loads or fails. */
    var period by mutableStateOf(ChartPeriod.Day)
        private set
    private var anchor: LocalDate = LocalDate.now()
    private var mode = HeartMode.Resting
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = HeartUiState.Loading
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

    fun selectMode(selected: HeartMode) {
        mode = selected
        (_state.value as? HeartUiState.Ready)?.let { _state.value = it.copy(mode = selected) }
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val name = "$period:$anchor"
            restoreSaved(name)
            var kept = false
            _state.value = try {
                val profile = profileStore.load()
                val today = LocalDate.now()
                val all = periodDays(period, anchor)
                val days = all.filter { it <= today }
                if (period == ChartPeriod.Day) {
                    ready(today, all, history = DailyHistory(), previous = DailyHistory()).copy(
                        day = source.heartDay(anchor, profile),
                        caloriesKcal = totals.daily(DailyTotal.CaloriesOutKcal, anchor, anchor)[anchor],
                    )
                } else {
                    val previousStart = shiftPeriod(period, all.first(), -1)
                    val history = source.history(days.first(), days.last(), profile, backfillDays = BACKFILL_DAYS)
                    // Comparing a year with the one before would read a second year of data; Fitbit's year view has no comparison either.
                    val previous = if (period == ChartPeriod.Year) DailyHistory() else source.history(previousStart, all.first().minusDays(1), profile, backfillDays = 0)
                    val sparklines = if (period == ChartPeriod.Week) days.associateWith { sparkline(source.heartDay(it, profile)) } else emptyMap()
                    ready(today, all, history, previous, sparklines)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is HeartUiState.Ready && shownName == name)
                if (kept) current else HeartUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? HeartUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }

    private fun ready(
        today: LocalDate,
        all: List<LocalDate>,
        history: DailyHistory,
        previous: DailyHistory,
        sparklines: Map<LocalDate, List<Double>> = emptyMap(),
    ): HeartUiState.Ready {
        // Fitbit shows today's resting rate as "No data" until the day is over, so leave the preliminary value out.
        val resting = history.restingBpm.filterKeys { it < today }.mapValues { it.value.toDouble() }
        val active = history.heartZoneMinutes.mapValues { it.value.activeMinutes.toDouble() }
        val days = all.filter { it <= today }
        fun points(values: Map<LocalDate, Double>): List<Double?> = when (period) {
            ChartPeriod.Day -> emptyList()
            ChartPeriod.Year -> (1..12).map { month -> YearMonth.of(anchor.year, month).let { ym -> averagePerDay((1..ym.lengthOfMonth()).map(ym::atDay), values) } }
            ChartPeriod.ThreeMonths -> weeklyAverages(all, values)
            else -> all.map { values[it] }
        }
        val rows = when (period) {
            ChartPeriod.Day -> emptyList()
            ChartPeriod.Week -> days.sortedDescending().map { day ->
                HeartRow(day, day, resting[day], history.heartZoneMinutes[day]?.let(::zonesEntered), sparklines[day].orEmpty())
            }
            ChartPeriod.Month, ChartPeriod.ThreeMonths -> days.chunkedByWeek().map { week ->
                HeartRow(week.first(), week.last(), averagePerDay(week, resting), null, emptyList())
            }.reversed()
            ChartPeriod.Year -> (12 downTo 1).mapNotNull { month ->
                val ym = YearMonth.of(anchor.year, month)
                averagePerDay((1..ym.lengthOfMonth()).map(ym::atDay), resting)?.let { HeartRow(ym.atDay(1), ym.atEndOfMonth(), it, null, emptyList()) }
            }
        }.filter { it.restingBpm != null || it.zones != null }
        return HeartUiState.Ready(
            period = period,
            anchor = anchor,
            today = today,
            mode = mode,
            day = null,
            caloriesKcal = null,
            restingPoints = points(resting),
            zonePoints = points(active),
            restingAverage = averagePerDay(days, resting),
            previousRestingAverage = previous.restingBpm.values.takeIf { it.isNotEmpty() }?.average(),
            zoneAveragePerDay = averagePerDay(days, active),
            zoneTotalMinutes = days.sumOf { active[it]?.toInt() ?: 0 },
            rows = rows,
        )
    }

    /** Weeks within the days shown (the first and last may be partial). */
    private fun List<LocalDate>.chunkedByWeek(): List<List<LocalDate>> =
        groupBy { com.openfit.domain.weekStart(it) }.values.toList()

    private fun sparkline(day: HeartDay): List<Double> =
        day.byMinute.toSortedMap().values.chunked(SPARKLINE_MINUTES) { it.average() }

    private companion object {
        const val BACKFILL_DAYS = 31
        const val SPARKLINE_MINUTES = 15
    }
}
