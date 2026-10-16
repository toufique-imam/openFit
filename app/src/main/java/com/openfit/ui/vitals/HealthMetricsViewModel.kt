package com.openfit.ui.vitals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.HealthMetric
import com.openfit.domain.PersonalRange
import com.openfit.domain.personalRange
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectVitals
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One metric on the Health metrics page: the day's value (null for "No data") and its personal range. */
data class MetricRow(val metric: HealthMetric, val value: Double?, val range: PersonalRange?) {
    val inRange: Boolean? get() = value?.let { v -> range?.let { v in it } }
}

sealed interface HealthMetricsUiState {
    data object Loading : HealthMetricsUiState
    data class Failed(val message: String) : HealthMetricsUiState
    data class Ready(val date: LocalDate, val today: LocalDate, val profile: Profile, val rows: List<MetricRow>) : HealthMetricsUiState {
        /** Metrics with a value and a range, which Fitbit counts in "3 of 3". */
        val ranged: List<MetricRow> get() = rows.filter { it.inRange != null }
    }
}

/** Fitbit's Health metrics page: one day, each metric's value against its personal range. */
class HealthMetricsViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore) : ViewModel() {

    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<HealthMetricsUiState>(HealthMetricsUiState.Loading)
    val state: StateFlow<HealthMetricsUiState> = _state.asStateFlow()

    private var date: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(day: LocalDate) {
        date = day
        _state.value = HealthMetricsUiState.Loading
        load()
    }

    fun shift(days: Long) {
        date = date.plusDays(days).coerceAtMost(LocalDate.now())
        load()
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.value = try {
                // The personal range looks back 30 days.
                val values = vitals.daily(HealthMetric.entries.toSet(), date.minusDays(BASELINE_LEAD_DAYS), date)
                HealthMetricsUiState.Ready(
                    date = date,
                    today = LocalDate.now(),
                    profile = profileStore.load(),
                    rows = FITBIT_ORDER.map { metric ->
                        val daily = values[metric].orEmpty()
                        MetricRow(metric, daily[date], personalRange(date, daily))
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                HealthMetricsUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val BASELINE_LEAD_DAYS = 30L
    }
}
