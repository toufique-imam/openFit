@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.exercise

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
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.domain.weekStart
import com.openfit.health.ExerciseSession
import com.openfit.health.HealthConnectExercises
import com.openfit.health.HealthConnectGateway
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** The three charts Fitbit's Exercise screen toggles between. */
enum class ExerciseMode { Days, Duration, Distance }

/** A week in the Month list: how many workouts and how many kinds. */
@Serializable
data class ExerciseWeek(val start: LocalDate, val end: LocalDate, val count: Int, val types: Int)

sealed interface ExerciseUiState {
    data object Loading : ExerciseUiState
    data class Failed(val message: String) : ExerciseUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val profile: Profile,
        val mode: ExerciseMode,
        val days: List<LocalDate>,
        val sessions: Map<LocalDate, List<ExerciseSession>>,
        val weeks: List<ExerciseWeek>,
    ) : ExerciseUiState {
        val exerciseDays: Set<LocalDate> get() = sessions.filterValues { it.isNotEmpty() }.keys
        val count: Int get() = sessions.values.sumOf { it.size }
    }
}

class ExerciseViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore, snapshots: SnapshotStore) : ViewModel() {

    private val exercises = HealthConnectExercises(gateway)
    private val zone = ZoneId.systemDefault()

    private val _state = MutableStateFlow<ExerciseUiState>(ExerciseUiState.Loading)
    val state: StateFlow<ExerciseUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "exercise", ExerciseUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is ExerciseUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now(), profile = profileStore.load(), mode = mode)
            shownName = name
        }
    }

    private var period = ChartPeriod.Week
    private var anchor: LocalDate = LocalDate.now()
    private var mode = ExerciseMode.Days
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Week
        anchor = date
        _state.value = ExerciseUiState.Loading
        load()
    }

    fun saveProfile(profile: com.openfit.profile.Profile) {
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

    fun selectMode(selected: ExerciseMode) {
        mode = selected
        (_state.value as? ExerciseUiState.Ready)?.let { _state.value = it.copy(mode = selected) }
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val name = "$period:$anchor"
            restoreSaved(name)
            var kept = false
            _state.value = try {
                val today = LocalDate.now()
                val days = periodDays(period, anchor)
                val sessions = exercises.sessions(days.first(), minOf(days.last(), today))
                    .groupBy { it.start.atZone(zone).toLocalDate() }
                ExerciseUiState.Ready(
                    period = period,
                    anchor = anchor,
                    today = today,
                    profile = profileStore.load(),
                    mode = mode,
                    days = days,
                    sessions = sessions,
                    weeks = if (period == ChartPeriod.Month) {
                        days.filter { it <= today }.groupBy(::weekStart).map { (start, week) ->
                            val inWeek = week.flatMap { sessions[it].orEmpty() }
                            ExerciseWeek(start, start.plusDays(6), inWeek.size, inWeek.map { it.type }.distinct().size)
                        }.reversed()
                    } else {
                        emptyList()
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is ExerciseUiState.Ready && shownName == name)
                if (kept) current else ExerciseUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? ExerciseUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }
}
