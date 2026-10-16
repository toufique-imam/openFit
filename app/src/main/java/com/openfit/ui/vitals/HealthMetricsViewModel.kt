package com.openfit.ui.vitals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.ChartPeriod
import com.openfit.domain.HealthMetric
import com.openfit.domain.PersonalRange
import com.openfit.domain.periodDays
import com.openfit.domain.personalRange
import com.openfit.domain.shiftPeriod
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

/** One metric over the period: its value and personal range for each day. */
data class MetricSeries(val metric: HealthMetric, val values: Map<LocalDate, Double>, val ranges: Map<LocalDate, PersonalRange>)

sealed interface HealthMetricsUiState {
    data object Loading : HealthMetricsUiState
    data class Failed(val message: String) : HealthMetricsUiState
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val profile: Profile,
        val series: List<MetricSeries>,
    ) : HealthMetricsUiState
}

class HealthMetricsViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore) : ViewModel() {

    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<HealthMetricsUiState>(HealthMetricsUiState.Loading)
    val state: StateFlow<HealthMetricsUiState> = _state.asStateFlow()

    private var period = ChartPeriod.Week
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Week
        anchor = date
        _state.value = HealthMetricsUiState.Loading
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
                val days = periodDays(period, anchor).filter { it <= today }
                // Each day's personal range looks back 30 days.
                val values = vitals.daily(HealthMetric.entries.toSet(), days.first().minusDays(BASELINE_LEAD_DAYS), days.last())
                HealthMetricsUiState.Ready(
                    period = period,
                    anchor = anchor,
                    today = today,
                    profile = profileStore.load(),
                    series = HealthMetric.entries.map { metric ->
                        val daily = values[metric].orEmpty()
                        MetricSeries(
                            metric = metric,
                            values = daily.filterKeys { it in days },
                            ranges = days.mapNotNull { day -> personalRange(day, daily)?.let { day to it } }.toMap(),
                        )
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
