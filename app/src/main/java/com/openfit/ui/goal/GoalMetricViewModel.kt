@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.goal

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
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectSource
import com.openfit.health.HealthConnectTotals
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.profile.UnitSystem
import com.openfit.ui.goals.Goal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private const val METERS_PER_MILE = 1_609.344

/**
 * A Fitbit goal screen: which daily total it shows, in which unit, and where its goal comes from (null while the user
 * has not set it). [total] is null for Active Zone Minutes, which openFit works out from heart rate rather than reading
 * from Health Connect.
 */
enum class GoalMetric(
    val total: DailyTotal?,
    val editableGoal: Goal,
    val goal: (Profile) -> Double?,
    val display: (Double, Profile) -> Double = { value, _ -> value },
) {
    Steps(DailyTotal.Steps, Goal.Steps, { it.dailyStepGoal?.toDouble() }),
    Distance(
        DailyTotal.DistanceMeters,
        Goal.Distance,
        { profile -> profile.dailyDistanceGoalMeters?.let { distanceFromMeters(it, profile) } },
        { meters, profile -> distanceFromMeters(meters, profile) },
    ),
    Floors(DailyTotal.Floors, Goal.Floors, { it.dailyFloorsGoal?.toDouble() }),
    Energy(DailyTotal.CaloriesOutKcal, Goal.Energy, { it.dailyCaloriesOutGoal?.toDouble() }),

    Food(DailyTotal.CaloriesInKcal, Goal.Food, { it.dailyCaloriesInGoal?.toDouble() }),
    Water(DailyTotal.WaterLiters, Goal.Water, { profile -> profile.dailyWaterGoalLiters?.let { water(it, profile) } }, { liters, profile -> water(liters, profile) }),

    /** Fitbit's daily goal is the weekly one (from the user's fitness goal) spread over seven days (150 → 22). */
    ZoneMinutes(null, Goal.Fitness, { ceil(it.fitnessGoal.weeklyZoneMinutes / 7.0) }),
}

private const val FL_OZ_PER_LITER = 33.814

/** Water in millilitres, or fluid ounces for imperial units. */
private fun water(liters: Double, profile: Profile): Double =
    if (profile.units == UnitSystem.Imperial) liters * FL_OZ_PER_LITER else liters * 1_000

private fun distanceFromMeters(meters: Double, profile: Profile): Double =
    if (profile.units == UnitSystem.Imperial) meters / METERS_PER_MILE else meters / 1_000

sealed interface GoalMetricUiState {
    data object Loading : GoalMetricUiState
    data class Failed(val message: String) : GoalMetricUiState
    @Serializable
    data class Ready(
        val metric: GoalMetric,
        val profile: Profile,
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val goal: Double?,
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
    dayLoadCache: DayLoadCache,
    snapshots: SnapshotStore,
) : ViewModel() {

    private val totals = HealthConnectTotals(gateway)
    private val source = HealthConnectSource(gateway, dayLoadCache)

    private val _state = MutableStateFlow<GoalMetricUiState>(GoalMetricUiState.Loading)
    val state: StateFlow<GoalMetricUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "goal:${metric.name}", GoalMetricUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is GoalMetricUiState.Ready) return
        saved.restore(name)?.let { ready ->
            val profile = profileStore.load()
            _state.value = ready.copy(today = LocalDate.now(), profile = profile, goal = metric.goal(profile))
            shownName = name
        }
    }

    private var period = ChartPeriod.Day
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = GoalMetricUiState.Loading
        load()
    }

    fun saveProfile(profile: Profile) {
        profileStore.save(profile)
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
                val all = periodDays(period, anchor)
                val days = all.filter { it <= today }
                val profile = profileStore.load()
                val values = daily(days.first(), days.last(), profile).mapValues { metric.display(it.value, profile) }
                val hourly = if (period == ChartPeriod.Day) hourly(anchor, profile).mapValues { metric.display(it.value, profile) } else emptyMap()
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
                val current = _state.value
                kept = keepAfterFailure(error, current is GoalMetricUiState.Ready && shownName == name)
                if (kept) current else GoalMetricUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? GoalMetricUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }

    private suspend fun daily(from: LocalDate, until: LocalDate, profile: Profile): Map<LocalDate, Double> {
        val total = metric.total ?: return source.history(from, until, profile, backfillDays = BACKFILL_DAYS).zoneMinutes.mapValues { it.value.toDouble() }
        return totals.daily(total, from, until)
    }

    /** Zone Minutes per hour: a point for each moderate minute and two for each vigorous or peak minute. */
    private suspend fun hourly(date: LocalDate, profile: Profile): Map<Int, Double> {
        val total = metric.total ?: return source.heartDay(date, profile).let { day ->
            val zones = day.zones ?: return emptyMap()
            day.byMinute.entries
                .groupBy { Instant.ofEpochSecond(it.key * 60).atZone(ZoneId.systemDefault()).hour }
                .mapValues { (_, minutes) ->
                    minutes.sumOf { (_, bpm) ->
                        when {
                            bpm >= zones.cardioFrom -> 2.0
                            bpm >= zones.fatBurnFrom -> 1.0
                            else -> 0.0
                        }
                    }
                }
        }
        return totals.hourly(total, date)
    }

    private companion object {
        const val BACKFILL_DAYS = 31
    }
}
