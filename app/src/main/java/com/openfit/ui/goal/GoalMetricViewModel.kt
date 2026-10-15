package com.openfit.ui.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.ChartPeriod
import com.openfit.domain.GoalSummary
import com.openfit.domain.PeriodRow
import com.openfit.domain.dayRows
import com.openfit.domain.goalSummary
import com.openfit.domain.monthRows
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.domain.weekRows
import com.openfit.domain.weekRowsOf
import com.openfit.health.DailyTotal
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectTotals
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.profile.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private const val METERS_PER_MILE = 1_609.344

/** A Fitbit goal screen: which daily total it shows, in which unit, and where its goal comes from. */
enum class GoalMetric(val total: DailyTotal, val goal: (Profile) -> Double, val display: (Double, Profile) -> Double = { value, _ -> value }) {
    Steps(DailyTotal.Steps, { it.dailyStepGoal.toDouble() }),
    Distance(
        DailyTotal.DistanceMeters,
        { distanceFromMeters(it.dailyDistanceGoalMeters, it) },
        { meters, profile -> distanceFromMeters(meters, profile) },
    ),
    Floors(DailyTotal.Floors, { it.dailyFloorsGoal.toDouble() }),
    Energy(DailyTotal.CaloriesOutKcal, { it.dailyCaloriesOutGoal.toDouble() }),
}

private fun distanceFromMeters(meters: Double, profile: Profile): Double =
    if (profile.units == UnitSystem.Imperial) meters / METERS_PER_MILE else meters / 1_000

sealed interface GoalMetricUiState {
    data object Loading : GoalMetricUiState
    data class Failed(val message: String) : GoalMetricUiState
    data class Ready(
        val metric: GoalMetric,
        val profile: Profile,
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val goal: Double,
        val dayValue: Double?,
        val hourly: List<Double?>,
        /** Bars: one per day (Week, Month) or the monthly average day (Year). */
        val bars: List<Double?>,
        val summary: GoalSummary,
        val rows: List<PeriodRow>,
    ) : GoalMetricUiState
}

class GoalMetricViewModel(
    private val metric: GoalMetric,
    gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
) : ViewModel() {

    private val totals = HealthConnectTotals(gateway)

    private val _state = MutableStateFlow<GoalMetricUiState>(GoalMetricUiState.Loading)
    val state: StateFlow<GoalMetricUiState> = _state.asStateFlow()

    private var period = ChartPeriod.Day
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = GoalMetricUiState.Loading
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
            _state.value = try {
                val today = LocalDate.now()
                val all = periodDays(period, anchor)
                val days = all.filter { it <= today }
                val profile = profileStore.load()
                val values = totals.daily(metric.total, days.first(), days.last()).mapValues { metric.display(it.value, profile) }
                val hourly = if (period == ChartPeriod.Day) totals.hourly(metric.total, anchor).mapValues { metric.display(it.value, profile) } else emptyMap()
                val goal = metric.goal(profile)
                GoalMetricUiState.Ready(
                    metric = metric,
                    profile = profile,
                    period = period,
                    anchor = anchor,
                    today = today,
                    goal = goal,
                    dayValue = values[anchor],
                    hourly = (0..23).map { hourly[it] },
                    bars = when (period) {
                        ChartPeriod.Day -> emptyList()
                        ChartPeriod.Year -> (1..12).map { month ->
                            val monthDays = YearMonth.of(anchor.year, month).let { ym -> (1..ym.lengthOfMonth()).map(ym::atDay) }
                            com.openfit.domain.averagePerDay(monthDays, values)
                        }
                        else -> all.map { values[it] }
                    },
                    summary = goalSummary(days, values, goal),
                    rows = when (period) {
                        ChartPeriod.Day -> emptyList()
                        ChartPeriod.Week -> dayRows(days, values)
                        ChartPeriod.Month -> weekRows(YearMonth.from(anchor), values)
                        ChartPeriod.ThreeMonths -> weekRowsOf(days, values)
                        ChartPeriod.Year -> monthRows(anchor.year, values)
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                GoalMetricUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
