package com.openfit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.health.HealthConnectAvailability
import com.openfit.health.HealthConnectGateway
import com.openfit.health.TodayRepository
import com.openfit.health.TodaySummary
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
    data object PermissionsRequired : TodayUiState
    data class Failed(val message: String) : TodayUiState
    data class Ready(
        val date: LocalDate,
        val summary: TodaySummary,
        val profile: Profile,
        val missingPermissions: Boolean,
        val refreshing: Boolean = false,
    ) : TodayUiState
}

class TodayViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
) : ViewModel() {

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
        val granted = gateway.grantedPermissions().intersect(HealthConnectGateway.PERMISSIONS)
        if (granted.isEmpty()) return TodayUiState.PermissionsRequired

        val date = LocalDate.now()
        val profile = profileStore.load()
        return TodayUiState.Ready(
            date = date,
            summary = TodayRepository(gateway.client).load(date, granted, profile.age),
            profile = profile,
            missingPermissions = granted != HealthConnectGateway.PERMISSIONS,
        )
    }
}
