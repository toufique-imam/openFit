package com.openfit.ui.today

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.DailyMetrics
import com.openfit.domain.DailyHistory
import com.openfit.domain.LoadRange
import com.openfit.domain.LoadTarget
import com.openfit.domain.Readiness
import com.openfit.domain.HealthMetric
import com.openfit.domain.MetricReading
import com.openfit.domain.metricReadings
import com.openfit.domain.mergeHistory
import com.openfit.domain.mergeMetrics
import com.openfit.domain.completedWeeks
import com.openfit.domain.dailyCardioRange
import com.openfit.domain.dailyTarget
import com.openfit.domain.readiness
import com.openfit.domain.weeklyZoneTarget
import com.openfit.googlehealth.GoogleHealthApi
import com.openfit.googlehealth.GoogleHealthAuth
import com.openfit.googlehealth.GoogleHealthAuthorization
import com.openfit.googlehealth.GoogleHealthException
import com.openfit.googlehealth.GoogleHealthSource
import com.openfit.health.DailyMetricsSource
import com.openfit.health.HealthConnectAvailability
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSource
import com.openfit.health.HealthConnectSleep
import com.openfit.health.HealthConnectVitals
import com.openfit.domain.sleepScore
import com.openfit.domain.EnergyDay
import com.openfit.health.EnergySource
import com.openfit.health.ExerciseSession
import com.openfit.health.HealthConnectExercises
import com.openfit.health.HealthConnectMindfulness
import com.openfit.health.PulseAgeSource
import com.openfit.domain.PulseAge
import com.openfit.health.SlotCache
import com.openfit.health.DayLoadCache
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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
        val isToday: Boolean,
        val metrics: DailyMetrics,
        val week: List<LocalDate>,
        val exerciseDays: Set<LocalDate>,
        /** The date's last workout, shown under Exercise days as on Fitbit. */
        val latestExercise: ExerciseSession? = null,
        val mindfulDays: Set<LocalDate> = emptySet(),
        val readiness: Readiness?,
        val cardioRange: LoadRange?,
        val zoneTarget: LoadTarget,
        val profile: Profile,
        val missingPermissions: Set<String>,
        val googleHealth: GoogleHealthStatus,
        val healthMetrics: List<MetricReading> = emptyList(),
        val sleepScore: Int? = null,
        /** The day's Energy Bank; loaded after the rest, since its first run reads weeks of heart rate. */
        val energy: EnergyDay? = null,
        val energyLoading: Boolean = true,
        /** Pulse Age over the 28 days before the date; loaded after the rest. */
        val pulseAge: PulseAge? = null,
        val pulseAgeLoading: Boolean = true,
        val refreshing: Boolean = false,
    ) : TodayUiState
}

class TodayViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    private val googleHealthAuth: GoogleHealthAuth,
    dayLoadCache: DayLoadCache,
    slotCache: SlotCache,
) : ViewModel() {

    private val healthConnect = HealthConnectSource(gateway, dayLoadCache)
    private val energySource = EnergySource(gateway, slotCache)
    private var energyJob: Job? = null
    private val pulseAgeSource = PulseAgeSource(gateway, dayLoadCache)
    private var pulseAgeJob: Job? = null
    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private var selectedDate: LocalDate? = null
    private var loading: Job? = null
    private var loadedAt: Instant? = null

    fun refreshIfStale() {
        val last = loadedAt
        if (_state.value !is TodayUiState.Ready || last == null || Duration.between(last, Instant.now()) > STALE_AFTER) refresh()
    }

    fun refresh() {
        _state.update { if (it is TodayUiState.Ready) it.copy(refreshing = true) else it }
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.value = try {
                load().also { if (it is TodayUiState.Ready) loadedAt = Instant.now() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                TodayUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? TodayUiState.Ready)?.let {
                loadEnergy(it.date)
                loadPulseAge(it.date, it.profile)
            }
        }
    }

    private fun loadPulseAge(date: LocalDate, profile: Profile) {
        pulseAgeJob?.cancel()
        pulseAgeJob = viewModelScope.launch {
            val age = try {
                pulseAgeSource.pulseAge(date.minusDays(1), profile)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            _state.update { if (it is TodayUiState.Ready && it.date == date) it.copy(pulseAge = age, pulseAgeLoading = false) else it }
        }
    }

    private fun loadEnergy(date: LocalDate) {
        energyJob?.cancel()
        energyJob = viewModelScope.launch {
            val day = try {
                energySource.days(date, date).singleOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            _state.update { if (it is TodayUiState.Ready && it.date == date) it.copy(energy = day, energyLoading = false) else it }
        }
    }

    fun showDate(date: LocalDate) {
        val today = LocalDate.now()
        selectedDate = date.takeIf { it < today }
        refresh()
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
        val dataTypes = requestable - HealthConnectGateway.OPTIONAL_PERMISSIONS
        val missing = dataTypes - gateway.grantedPermissions()
        if (missing == dataTypes) return TodayUiState.PermissionsRequired(requestable)

        val today = LocalDate.now()
        val date = selectedDate ?: today
        val week = weekOf(date)
        val profile = profileStore.load()
        var (googleHealth, remote) = googleHealthSource()
        val loaded = try {
            load(listOfNotNull(remote, healthConnect), date, week, profile)
        } catch (error: GoogleHealthException) {
            googleHealth = GoogleHealthStatus.Failed(error.message.orEmpty())
            load(listOf(healthConnect), date, week, profile)
        }
        val metrics = loaded.metrics
        val readiness = readiness(date, loaded.history.withToday(date, metrics))
        val zoneMinutes = loaded.history.zoneMinutes.mapValues { it.value.toDouble() }
        val zoneWeeks = completedWeeks(zoneMinutes, week.first())
        val dayIndex = week.indexOf(date)
        return TodayUiState.Ready(
            date = date,
            isToday = date == today,
            metrics = metrics,
            week = week,
            exerciseDays = loaded.exerciseDays,
            latestExercise = runCatching { HealthConnectExercises(gateway).sessions(date, date).maxByOrNull { it.start } }.getOrNull(),
            mindfulDays = runCatching { HealthConnectMindfulness(gateway).mindfulDays(week.first(), week.last()) }.getOrDefault(emptySet()),
            readiness = readiness,
            cardioRange = dailyCardioRange(date, profile.fitnessGoal, loaded.history.cardioLoad, readiness),
            zoneTarget = dailyTarget(
                weekly = weeklyZoneTarget(profile.fitnessGoal, zoneWeeks.filterNotNull()),
                doneBeforeToday = week.take(dayIndex).sumOf { zoneMinutes[it] ?: 0.0 },
                doneToday = metrics.zoneMinutes?.total?.toDouble() ?: 0.0,
                dayIndex = dayIndex,
                readiness = readiness,
            ),
            profile = profile,
            missingPermissions = missing,
            googleHealth = googleHealth,
            sleepScore = runCatching { HealthConnectSleep(gateway).nights(date, date)[date]?.let(::sleepScore) }.getOrNull(),
            healthMetrics = runCatching {
                metricReadings(date, vitals.daily(HealthMetric.entries.toSet(), date.minusDays(HEALTH_METRIC_DAYS), date))
            }.getOrDefault(emptyList()),
        )
    }

    private suspend fun load(
        sources: List<DailyMetricsSource>,
        date: LocalDate,
        week: List<LocalDate>,
        profile: Profile,
    ): Loaded = coroutineScope {
        val metrics = sources.map { async { it.load(date, profile) } }
        val exerciseDays = sources.map { async { it.exerciseDays(week.first(), week.last()) } }
        val history = sources.map { async { it.history(week.first().minusWeeks(HISTORY_WEEKS), date.minusDays(1), profile) } }
        Loaded(
            metrics = mergeMetrics(metrics.awaitAll()),
            exerciseDays = exerciseDays.awaitAll().flatten().toSet(),
            history = mergeHistory(history.awaitAll()),
        )
    }

    private fun DailyHistory.withToday(date: LocalDate, metrics: DailyMetrics): DailyHistory {
        val zone = ZoneId.systemDefault()
        fun Instant.isOn(day: LocalDate) = atZone(zone).toLocalDate() == day
        return copy(
            restingBpm = restingBpm + listOfNotNull(metrics.restingBpm?.takeIf { it.time.isOn(date) }?.let { date to it.value }),
            hrvMs = hrvMs + listOfNotNull(metrics.hrvMs?.takeIf { it.time.isOn(date) }?.let { date to it.value }),
            sleepMinutes = sleepMinutes + listOfNotNull(metrics.sleep?.let { date to it.asleep.toMinutes().toInt() }),
        )
    }

    private class Loaded(val metrics: DailyMetrics, val exerciseDays: Set<LocalDate>, val history: DailyHistory)

    private suspend fun googleHealthSource(): Pair<GoogleHealthStatus, DailyMetricsSource?> {
        if (!googleHealthAuth.enabled) return GoogleHealthStatus.Off to null
        return try {
            when (val authorization = googleHealthAuth.authorize()) {
                is GoogleHealthAuthorization.NeedsConsent -> GoogleHealthStatus.NeedsConsent(authorization.consent) to null
                is GoogleHealthAuthorization.Authorized ->
                    GoogleHealthStatus.Connected to GoogleHealthSource(GoogleHealthApi(authorization.accessToken))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GoogleHealthStatus.Failed(error.message ?: error.javaClass.simpleName) to null
        }
    }

    private fun weekOf(date: LocalDate): List<LocalDate> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        return (0L until 7L).map(start::plusDays)
    }

    private companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(1)
        const val HISTORY_WEEKS = 4L
        const val HEALTH_METRIC_DAYS = 30L
    }
}
