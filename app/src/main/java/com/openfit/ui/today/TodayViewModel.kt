package com.openfit.ui.today

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.DailyMetrics
import com.openfit.domain.mergeMetrics
import com.openfit.googlehealth.GoogleHealthApi
import com.openfit.googlehealth.GoogleHealthAuth
import com.openfit.googlehealth.GoogleHealthAuthorization
import com.openfit.googlehealth.GoogleHealthSource
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

sealed interface GoogleHealthStatus {
    data object Off : GoogleHealthStatus
    data object Connected : GoogleHealthStatus
    data class NeedsConsent(val consent: PendingIntent) : GoogleHealthStatus
    data class Failed(val message: String) : GoogleHealthStatus
}

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
        val googleHealth: GoogleHealthStatus,
        val refreshing: Boolean = false,
    ) : TodayUiState
}

class TodayViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    private val googleHealthAuth: GoogleHealthAuth,
) : ViewModel() {

    private val healthConnect = HealthConnectSource(gateway)

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

    suspend fun connectGoogleHealth(): PendingIntent? {
        googleHealthAuth.enabled = true
        val authorization = runCatching { googleHealthAuth.authorize() }.getOrNull()
        if (authorization is GoogleHealthAuthorization.NeedsConsent) return authorization.consent
        refresh()
        return null
    }

    fun disconnectGoogleHealth() {
        googleHealthAuth.enabled = false
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
        val local = healthConnect.load(date, profile)
        val (googleHealth, remote) = loadGoogleHealth(date, profile)
        return TodayUiState.Ready(
            date = date,
            metrics = mergeMetrics(listOfNotNull(remote, local)),
            profile = profile,
            missingPermissions = missing,
            googleHealth = googleHealth,
        )
    }

    private suspend fun loadGoogleHealth(date: LocalDate, profile: Profile): Pair<GoogleHealthStatus, DailyMetrics?> {
        if (!googleHealthAuth.enabled) return GoogleHealthStatus.Off to null
        return try {
            when (val authorization = googleHealthAuth.authorize()) {
                is GoogleHealthAuthorization.NeedsConsent -> GoogleHealthStatus.NeedsConsent(authorization.consent) to null
                is GoogleHealthAuthorization.Authorized ->
                    GoogleHealthStatus.Connected to GoogleHealthSource(GoogleHealthApi(authorization.accessToken)).load(date, profile)
            }
        } catch (error: Exception) {
            GoogleHealthStatus.Failed(error.message ?: error.javaClass.simpleName) to null
        }
    }
}
