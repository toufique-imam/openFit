package com.openfit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.DailyMetrics
import com.openfit.domain.mergeMetrics
import com.openfit.health.DailyMetricsSource
import com.openfit.health.HealthConnectAvailability
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSource
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface TodayUiState {
    data object Loading : TodayUiState
    data class Unavailable(val updateRequired: Boolean) : TodayUiState
    data class PermissionsRequired(val permissions: Set<String>) : TodayUiState
    data class Failed(val message: String) : TodayUiState
    data class Ready(
        val date: LocalDate,
        val metrics: DailyMetrics,
        val profile: Profile,
        val missingPermissions: Set<String>,
        val refreshing: Boolean = false,
    ) : TodayUiState
}

class TodayViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
) : ViewModel() {

    private val sources: List<DailyMetricsSource> = listOf(HealthConnectSource(gateway))

    private val _state = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    fun refresh() {
        _state.update { if (it is TodayUiState.Ready) it.copy(refreshing = true) else it }
        viewModelScope.launch {
            _state.value = runCatching { load() }
                .getOrElse { TodayUiState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun saveProfile(profile: Profile) {
        profileStore.save(profile)
        refresh()
    }

    private suspend fun load(): TodayUiState {
        when (gateway.availability()) {
            HealthConnectAvailability.Unavailable -> return TodayUiState.Unavailable(updateRequired = false)
            HealthConnectAvailability.UpdateRequired -> return TodayUiState.Unavailable(updateRequired = true)
            HealthConnectAvailability.Available -> Unit
        }
        val requestable = gateway.requestablePermissions()
        val missing = requestable - gateway.grantedPermissions()
        if (missing == requestable) return TodayUiState.PermissionsRequired(requestable)

        val date = LocalDate.now()
        val profile = profileStore.load()
        return TodayUiState.Ready(
            date = date,
            metrics = mergeMetrics(sources.map { it.load(date, profile) }),
            profile = profile,
            missingPermissions = missing,
        )
    }
}
