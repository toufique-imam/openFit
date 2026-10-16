@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.hourly

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
import com.openfit.domain.ActivityGlance
import com.openfit.domain.ChartPeriod
import com.openfit.domain.PeriodRow
import com.openfit.domain.activityGlance
import com.openfit.domain.dayRows
import com.openfit.domain.hoursMet
import com.openfit.domain.monthRows
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.domain.weekRows
import com.openfit.health.DailyTotal
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectTotals
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Hourly activity's two charts on Week and longer: hours with 250+ steps, or inactive time. */
enum class HourlyMode { Hours, Inactive }

sealed interface HourlyUiState {
    data object Loading : HourlyUiState
    data class Failed(val message: String) : HourlyUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val mode: HourlyMode,
        val profile: Profile,
        /** The user's hourly goal window; null until it is set, when the screen offers to set it. */
        val window: IntRange?,
        /** Day: steps per hour, and the hour now (null on past days). */
        val hourly: Map<Int, Double>,
        val currentHour: Int?,
        val glance: ActivityGlance?,
        /** Week and longer: hours met per day and inactive minutes per day (Week and Month only). */
        val hoursByDay: Map<LocalDate, Double>,
        val inactiveByDay: Map<LocalDate, Double>,
        val rows: List<PeriodRow>,
    ) : HourlyUiState
}

class HourlyViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore, snapshots: SnapshotStore) : ViewModel() {

    private val totals = HealthConnectTotals(gateway)
    private val zone = ZoneId.systemDefault()

    private val _state = MutableStateFlow<HourlyUiState>(HourlyUiState.Loading)
    val state: StateFlow<HourlyUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "hourly", HourlyUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is HourlyUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now(), profile = profileStore.load(), mode = mode)
            shownName = name
        }
    }

    private var period = ChartPeriod.Day
    private var anchor: LocalDate = LocalDate.now()
    private var mode = HourlyMode.Hours
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Day
        anchor = date
        _state.value = HourlyUiState.Loading
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

    fun selectMode(selected: HourlyMode) {
        mode = selected
        (_state.value as? HourlyUiState.Ready)?.let { _state.value = it.copy(mode = selected) }
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
                val profile = profileStore.load()
                val window = profile.activeHours
                if (window == null) {
                    HourlyUiState.Ready(
                        period = period,
                        anchor = anchor,
                        today = today,
                        mode = mode,
                        profile = profile,
                        window = null,
                        hourly = emptyMap(),
                        currentHour = null,
                        glance = null,
                        hoursByDay = emptyMap(),
                        inactiveByDay = emptyMap(),
                        rows = emptyList(),
                    )
                } else if (period == ChartPeriod.Day) {
                    HourlyUiState.Ready(
                        period = period,
                        anchor = anchor,
                        today = today,
                        mode = mode,
                        profile = profile,
                        window = window,
                        hourly = totals.hourly(DailyTotal.Steps, anchor),
                        currentHour = if (anchor == today) LocalDateTime.now().hour else null,
                        glance = glance(anchor, window),
                        hoursByDay = emptyMap(),
                        inactiveByDay = emptyMap(),
                        rows = emptyList(),
                    )
                } else {
                    val hours = totals.hourlyByDay(DailyTotal.Steps, days.first(), days.last()).mapValues { hoursMet(it.value, window).toDouble() }
                    val inactive = if (period == ChartPeriod.Week || period == ChartPeriod.Month) {
                        days.associateWith { day -> glance(day, window).inactiveMinutes.toDouble() }
                    } else {
                        emptyMap()
                    }
                    HourlyUiState.Ready(
                        period = period,
                        anchor = anchor,
                        today = today,
                        mode = mode,
                        profile = profile,
                        window = window,
                        hourly = emptyMap(),
                        currentHour = null,
                        glance = null,
                        hoursByDay = days.associateWith { hours[it] ?: 0.0 },
                        inactiveByDay = inactive,
                        rows = when (period) {
                            ChartPeriod.Week -> dayRows(days, days.associateWith { hours[it] ?: 0.0 })
                            ChartPeriod.Month -> weekRows(YearMonth.from(anchor), hours)
                            ChartPeriod.Year -> monthRows(anchor.year, hours)
                            else -> emptyList()
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is HourlyUiState.Ready && shownName == name)
                if (kept) current else HourlyUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? HourlyUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }

    private suspend fun glance(day: LocalDate, window: IntRange): ActivityGlance {
        val minutes = totals.minutely(DailyTotal.Steps, day, window.first, window.last)
        val from = day.atTime(window.first, 0).atZone(zone).toEpochSecond() / 60
        val until = minOf(day.atTime(window.last, 59).atZone(zone).toEpochSecond() / 60 + 1, System.currentTimeMillis() / 60_000)
        return activityGlance(minutes, from, until)
    }
}
