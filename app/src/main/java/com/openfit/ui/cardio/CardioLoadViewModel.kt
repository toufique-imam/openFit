package com.openfit.ui.cardio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.CardioLoadDay
import com.openfit.domain.ChartPeriod
import com.openfit.domain.ChartAxis
import com.openfit.domain.DailyHistory
import com.openfit.domain.FitnessGoal
import com.openfit.domain.LoadRange
import com.openfit.domain.PeriodPoint
import com.openfit.domain.PeriodRow
import com.openfit.domain.TargetStatus
import com.openfit.domain.TrainingStatus
import com.openfit.domain.acuteChronicRatio
import com.openfit.domain.averagePerDay
import com.openfit.domain.chartAxis
import com.openfit.domain.dailyCardioRange
import com.openfit.domain.dailyPoints
import com.openfit.domain.dayRows
import com.openfit.domain.daysInTarget
import com.openfit.domain.monthRows
import com.openfit.domain.monthStatus
import com.openfit.domain.monthlyPoints
import com.openfit.domain.periodDays
import com.openfit.domain.readiness
import com.openfit.domain.shiftPeriod
import com.openfit.domain.targetStatus
import com.openfit.domain.trainingStatus
import com.openfit.domain.weekRows
import com.openfit.domain.weekRowsOf
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSource
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CardioDayView(
    val load: Double?,
    val range: LoadRange?,
    val status: TargetStatus?,
    val detail: CardioLoadDay?,
)

data class CardioPeriodView(
    val points: List<PeriodPoint>,
    val axis: ChartAxis,
    val averagePerDay: Double?,
    val daysInTarget: Int,
    val rows: List<PeriodRow>,
    val monthStatus: TrainingStatus?,
    val monthlyAverages: ClosedFloatingPointRange<Double>?,
)

/** "Your past seven days of training": null [ratio] until there is enough history. */
data class TrainingView(val ratio: Double?) {
    val status: TrainingStatus? get() = ratio?.let(::trainingStatus)
}

sealed interface CardioLoadUiState {
    data object Loading : CardioLoadUiState
    data class Failed(val message: String) : CardioLoadUiState
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val profile: Profile,
        val day: CardioDayView?,
        val chart: CardioPeriodView?,
        val training: TrainingView,
        val backfilling: Boolean,
    ) : CardioLoadUiState {
        val isCurrent: Boolean get() = periodDays(period, anchor).contains(today)
        val canGoForward: Boolean get() = periodDays(period, anchor).last() < today
    }
}

class CardioLoadViewModel(
    gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    dayLoadCache: DayLoadCache,
) : ViewModel() {

    private val source = HealthConnectSource(gateway, dayLoadCache)

    private val _state = MutableStateFlow<CardioLoadUiState>(CardioLoadUiState.Loading)
    val state: StateFlow<CardioLoadUiState> = _state.asStateFlow()

    private var period = ChartPeriod.Day
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = CardioLoadUiState.Loading
        load()
    }

    fun selectPeriod(selected: ChartPeriod) {
        if (selected == period) return
        period = selected
        load()
    }

    fun shift(steps: Long) {
        val today = LocalDate.now()
        anchor = shiftPeriod(period, anchor, steps).coerceAtMost(today)
        load()
    }

    fun setGoal(goal: FitnessGoal) {
        profileStore.save(profileStore.load().copy(fitnessGoal = goal))
        load()
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val profile = profileStore.load()
                val today = LocalDate.now()
                val days = periodDays(period, anchor).filter { it <= today }
                val from = days.first().minusDays(HISTORY_LEAD_DAYS)
                val until = days.last()
                val detail = if (period == ChartPeriod.Day) source.cardioLoadDay(anchor, profile) else null
                var history = source.history(from, until, profile, backfillDays = BACKFILL_DAYS)
                publish(profile, today, days, history, detail, backfilling = history.pendingDays > 0)
                var rounds = 1
                while (history.pendingDays > 0 && rounds < MAX_BACKFILL_ROUNDS) {
                    delay(BACKFILL_PAUSE_MS)
                    val loads = try {
                        source.loadHistory(from, until, profile, backfillDays = BACKFILL_DAYS)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Most likely Health Connect rate limiting: keep what is shown and try again on the next visit.
                        null
                    }
                    rounds++
                    if (loads == null) {
                        publish(profile, today, days, history, detail, backfilling = false)
                        break
                    }
                    history = history.copy(cardioLoad = loads.cardioLoad, zoneMinutes = loads.zoneMinutes, pendingDays = loads.pendingDays)
                    publish(profile, today, days, history, detail, backfilling = history.pendingDays > 0 && rounds < MAX_BACKFILL_ROUNDS)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = CardioLoadUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun publish(
        profile: Profile,
        today: LocalDate,
        days: List<LocalDate>,
        history: DailyHistory,
        detail: CardioLoadDay?,
        backfilling: Boolean,
    ) {
        val loads = history.cardioLoad + listOfNotNull(detail?.let { it.date to it.total })
        // Ranges depend on the previous 28 days, so wait until none of them is still being calculated.
        val ranges = if (history.pendingDays > 0) {
            emptyMap()
        } else {
            days.mapNotNull { day -> dailyCardioRange(day, profile.fitnessGoal, loads, readiness(day, history))?.let { day to it } }.toMap()
        }
        val end = days.last()
        _state.value = CardioLoadUiState.Ready(
            period = period,
            anchor = anchor,
            today = today,
            profile = profile,
            day = if (period == ChartPeriod.Day) {
                val load = loads[anchor]
                val range = ranges[anchor]
                CardioDayView(load, range, if (load != null && range != null) targetStatus(load, range) else null, detail)
            } else {
                null
            },
            chart = if (period == ChartPeriod.Day) null else periodView(days, loads, ranges, end),
            training = TrainingView(acuteChronicRatio(end, loads)),
            backfilling = backfilling,
        )
    }

    private fun periodView(days: List<LocalDate>, loads: Map<LocalDate, Double>, ranges: Map<LocalDate, LoadRange>, end: LocalDate): CardioPeriodView {
        val allDays = periodDays(period, anchor)
        val points = when (period) {
            ChartPeriod.Year -> monthlyPoints(anchor.year, loads.filterKeys { it <= end }, ranges)
            else -> dailyPoints(allDays, loads.filterKeys { it <= end }, ranges)
        }
        val highest = points.maxOfOrNull { point -> maxOf(point.load ?: 0.0, point.range?.high ?: 0.0) } ?: 0.0
        val monthly = if (period == ChartPeriod.Year) points.mapNotNull { it.load } else emptyList()
        return CardioPeriodView(
            points = points,
            axis = chartAxis(highest),
            averagePerDay = averagePerDay(days, loads),
            daysInTarget = daysInTarget(points),
            rows = when (period) {
                ChartPeriod.Week -> dayRows(days, loads)
                ChartPeriod.Month -> weekRows(YearMonth.from(anchor), loads.filterKeys { it <= end })
                ChartPeriod.ThreeMonths -> weekRowsOf(days, loads)
                ChartPeriod.Year -> monthRows(anchor.year, loads.filterKeys { it <= end })
                ChartPeriod.Day -> emptyList()
            },
            monthStatus = if (period == ChartPeriod.Month) monthStatus(YearMonth.from(anchor), loads, end) else null,
            monthlyAverages = monthly.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
        )
    }

    private companion object {
        const val HISTORY_LEAD_DAYS = 35L
        const val BACKFILL_DAYS = 31
        const val MAX_BACKFILL_ROUNDS = 14
        const val BACKFILL_PAUSE_MS = 300L
    }
}
