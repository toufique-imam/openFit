package com.openfit.ui.today

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.data.SnapshotStore
import com.openfit.ui.Notices
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
import com.openfit.health.BudgetUsage
import com.openfit.health.HcPriority
import com.openfit.health.HealthSync
import com.openfit.health.HealthConnectSource
import com.openfit.health.HealthConnectSleep
import com.openfit.health.HealthConnectVitals
import com.openfit.domain.sleepScore
import com.openfit.domain.EnergyDay
import com.openfit.domain.StressDay
import com.openfit.domain.FitnessLevel
import com.openfit.domain.fitnessLevel
import com.openfit.domain.ESTIMATE_DAYS
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /** The day's Energy Bank; loaded beside the rest, since its first run reads weeks of heart rate. */
        val energy: EnergyDay? = null,
        val energyLoading: Boolean = true,
        val stress: StressDay? = null,
        /** VO2 max (measured, or estimated from resting heart rate) against people of the same age and sex. */
        val fitness: FitnessLevel? = null,
        /** Pulse Age over the 28 days before the date; loaded beside the rest. */
        val pulseAge: PulseAge? = null,
        val pulseAgeLoading: Boolean = true,
        /** The pull-to-refresh spinner: only for a refresh the user asked for. */
        val refreshing: Boolean = false,
        /** A quiet reload of what is on screen (a cached snapshot, or values a few minutes old). */
        val updating: Boolean = false,
        /** The date being loaded while this one, with no snapshot of the new date, stays on screen. */
        val pendingDate: LocalDate? = null,
    ) : TodayUiState
}

/**
 * Today's state. A date shows its last snapshot ([SnapshotStore]) at once and reloads from Health Connect only when the
 * snapshot is stale: today's after a minute, a past day's until it was taken well after the day ended.
 */
class TodayViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    private val googleHealthAuth: GoogleHealthAuth,
    dayLoadCache: DayLoadCache,
    slotCache: SlotCache,
    private val snapshots: SnapshotStore,
    private val sync: HealthSync,
) : ViewModel() {

    private val healthConnect = HealthConnectSource(gateway, dayLoadCache)
    private val energySource = EnergySource(gateway, slotCache)
    private val pulseAgeSource = PulseAgeSource(gateway, dayLoadCache)
    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** A past date the user picked; null follows today across midnight. */
    private var selectedDate: LocalDate? = null
    private var showing: Job? = null
    private var showingDate: LocalDate? = null
    /** When the values on screen were computed, and with which profile. */
    private var shownAt: Instant? = null
    private var shownProfileKey: String? = null
    /** False while the values on screen come from a snapshot of an older format. */
    private var shownCurrent = false
    /** After Health Connect rate-limits a read, quiet reloads and prefetches wait until then. */
    private var backoffUntil: Instant = Instant.EPOCH
    /** Loads in flight by date; touched on the main thread only. */
    private val fetches = mutableMapOf<LocalDate, Fetch>()
    private var missingPermissions: Set<String> = emptySet()
    private var googleHealth: GoogleHealthStatus = GoogleHealthStatus.Off

    private enum class Reload { IfStale, Always, Pull }

    init {
        viewModelScope.launch(Dispatchers.Default) { snapshots.prune(LocalDate.now().minusDays(SNAPSHOT_DAYS)) }
    }

    private fun currentDate(): LocalDate = selectedDate ?: LocalDate.now()

    /** On resume or on returning to Today: reloads only what is stale (or follows the date past midnight). */
    fun refreshIfStale() {
        val date = currentDate()
        if (showing?.isActive == true && showingDate == date) return
        show(date, Reload.IfStale)
    }

    /**
     * Opening the app: Today shows what is saved at once, then the last days are read from Health Connect (at most
     * every few minutes) and Today reloads from the copy if anything was read.
     */
    fun onResume() {
        refreshIfStale()
        viewModelScope.launch {
            sync.startRecent().join()
            refreshIfStale()
        }
    }

    /** Pull to refresh (or permissions or data sources changed): reads the last days now, then reloads. */
    fun refresh() {
        gateway.invalidatePermissions()
        _state.update { if (it is TodayUiState.Ready) it.copy(refreshing = true) else it }
        viewModelScope.launch {
            sync.syncRecent()
            show(currentDate(), Reload.Pull)
        }
    }

    fun showDate(date: LocalDate) {
        val today = LocalDate.now()
        selectedDate = date.takeIf { it < today }
        show(currentDate(), Reload.IfStale)
    }

    /** How much of Health Connect's read quota openFit has used, for the profile page. */
    fun healthConnectUsage(): BudgetUsage = gateway.budget.usage()

    /** The saved profile, for screens opened before Today has loaded. */
    fun profile(): Profile = profileStore.load()

    fun saveProfile(profile: Profile) {
        profileStore.save(profile)
        show(currentDate(), Reload.Always)
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

    private fun show(date: LocalDate, reload: Reload) {
        showing?.cancel()
        // A run of taps through the days should not queue a load per day: drop loads that have not reached the screen.
        fetches.entries.removeAll { (day, fetch) -> (day != date && !fetch.main.isCompleted).also { if (it) fetch.cancel() } }
        showingDate = date
        showing = viewModelScope.launch {
            val profile = profileStore.load()
            val profileKey = profile.snapshotKey()
            val onScreen = _state.value as? TodayUiState.Ready
            if (onScreen?.date != date) {
                val cached = withContext(Dispatchers.IO) { snapshots.get(TodaySnapshot.KIND, date, TodaySnapshot.VERSION, TodaySnapshot.serializer()) }
                if (cached != null) {
                    _state.value = cached.value.toReady(profile, missingPermissions, googleHealth)
                    shownAt = cached.savedAt
                    shownProfileKey = cached.profileKey
                    shownCurrent = cached.current
                } else if (onScreen != null) {
                    _state.value = onScreen.copy(pendingDate = date, refreshing = false, updating = false)
                }
            } else if (onScreen.profile != profile) {
                // New goals show at once; values that depend on the profile follow from the reload.
                _state.value = onScreen.copy(profile = profile)
            }

            val shown = _state.value as? TodayUiState.Ready
            val backingOff = Instant.now() < backoffUntil
            if (reload == Reload.IfStale && shown?.date == date && shown.pendingDate == null && (backingOff || isFresh(shown, profileKey))) {
                prefetch(date.minusDays(1), profile)
                return@launch
            }
            _state.update {
                when {
                    it !is TodayUiState.Ready -> it
                    reload == Reload.Pull -> it.copy(refreshing = true)
                    it.date == date -> it.copy(updating = true)
                    else -> it
                }
            }
            // Pulse Age covers the 28 days before the date, so a reload of the same day keeps the one on screen.
            val keptAge = (_state.value as? TodayUiState.Ready)
                ?.takeIf { it.date == date && it.pendingDate == null && !it.pulseAgeLoading && shownCurrent && shownProfileKey == profileKey }
                ?.let { Kept(it.pulseAge) }
            val fetch = fetches[date]?.takeIf { reload != Reload.Pull && it.profileKey == profileKey } ?: startFetch(date, profile, keptAge)
            val main = fetch.main.await()
            if (main !is TodayUiState.Ready) {
                val failed = main as? TodayUiState.Failed
                val rateLimited = failed?.message?.contains("quota", ignoreCase = true) == true
                if (rateLimited) backoffUntil = Instant.now() + BACKOFF
                val kept = _state.value as? TodayUiState.Ready
                if (failed != null && kept != null) {
                    // A failed reload keeps what is on screen (the saved day, or the day before a new one was picked);
                    // losing access to Health Connect does not.
                    if (kept.pendingDate != null) selectedDate = kept.date.takeIf { it < LocalDate.now() }
                    _state.value = kept.copy(refreshing = false, updating = false, pendingDate = null)
                    Notices.post(if (rateLimited) R.string.hc_busy_saved else R.string.hc_failed_saved)
                } else {
                    _state.value = main
                }
                return@launch
            }
            missingPermissions = main.missingPermissions
            googleHealth = main.googleHealth
            importIfNeeded()
            _state.update { current ->
                // Until the new Energy Bank and Pulse Age arrive, the ones on screen for this date stay.
                val previous = (current as? TodayUiState.Ready)?.takeIf { it.date == date && it.pendingDate == null }
                main.copy(
                    energy = previous?.energy,
                    energyLoading = previous?.energyLoading ?: true,
                    stress = previous?.stress,
                    pulseAge = previous?.pulseAge,
                    pulseAgeLoading = previous?.pulseAgeLoading ?: true,
                    updating = true,
                )
            }
            val (energy, stress) = fetch.energy.await()
            _state.update { if (it is TodayUiState.Ready && it.date == date) it.copy(energy = energy, stress = stress, energyLoading = false) else it }
            val age = fetch.pulseAge.await()
            _state.update { if (it is TodayUiState.Ready && it.date == date) it.copy(pulseAge = age, pulseAgeLoading = false, updating = false) else it }
            shownAt = fetch.startedAt
            shownProfileKey = profileKey
            shownCurrent = true
            prefetch(date.minusDays(1), profile)
        }
    }

    private var importing: Job? = null

    /** The first time, reads the history into openFit's copy in the background, so every screen opens from it later. */
    private fun importIfNeeded() {
        if (importing?.isActive == true || sync.imported) return
        importing = sync.startImport(background = true)
    }

    private fun isFresh(shown: TodayUiState.Ready, profileKey: String): Boolean {
        val at = shownAt ?: return false
        // A sync since read new data into the copy.
        if (sync.lastSync?.isAfter(at) == true) return false
        if (!shownCurrent || shownProfileKey != profileKey || shown.energyLoading || shown.pulseAgeLoading) return false
        val now = Instant.now()
        if (shown.date >= LocalDate.now()) return Duration.between(at, now) < TODAY_FRESH
        // Watches sync late, so a past day settles only once it has been over for a while.
        val settled = shown.date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant() + SETTLE_AFTER
        return at >= settled || Duration.between(at, now) < PAST_FRESH
    }

    /** Loads [date] in the background when it has no snapshot, so stepping back a day shows at once. */
    private fun prefetch(date: LocalDate, profile: Profile) {
        if (date in fetches || Instant.now() < backoffUntil) return
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { snapshots.get(TodaySnapshot.KIND, date, TodaySnapshot.VERSION, TodaySnapshot.serializer()) }
            // Nobody is waiting for it: a background read, which leaves the quota's reserve to what the user opens.
            if (cached == null && date !in fetches && gateway.budget.hasRoom()) startFetch(date, profile, background = true)
        }
    }

    /** One load of a date: the day itself, then Energy Bank and Pulse Age beside it; saved as a snapshot when all three are in. */
    /** A value already worked out for the date, to reuse instead of reading it again. */
    private class Kept<T>(val value: T)

    private inner class Fetch(val date: LocalDate, profile: Profile, keptAge: Kept<PulseAge?>? = null, background: Boolean = false) {
        private val context = if (background) Dispatchers.Default + HcPriority.Background else Dispatchers.Default
        val profileKey = profile.snapshotKey()
        val startedAt: Instant = Instant.now()
        val main: Deferred<TodayUiState> = viewModelScope.async(context) { loadMain(date, profile) }
        val energy: Deferred<Pair<EnergyDay?, StressDay?>> = viewModelScope.async(context) { loadEnergy(date) }
        val pulseAge: Deferred<PulseAge?> = keptAge?.let { CompletableDeferred(it.value) }
            ?: viewModelScope.async(context) { loadPulseAge(date, profile) }
        private val save: Job = viewModelScope.launch(Dispatchers.Default) {
            val ready = main.await() as? TodayUiState.Ready
            if (ready == null) {
                energy.cancel()
                pulseAge.cancel()
            } else {
                val (energyDay, stress) = energy.await()
                val complete = ready.copy(energy = energyDay, stress = stress, energyLoading = false, pulseAge = pulseAge.await(), pulseAgeLoading = false)
                snapshots.put(TodaySnapshot.KIND, date, TodaySnapshot.VERSION, TodaySnapshot.serializer(), complete.toSnapshot(), profileKey)
            }
            withContext(Dispatchers.Main) { if (fetches[date] === this@Fetch) fetches.remove(date) }
        }

        fun cancel() {
            main.cancel()
            energy.cancel()
            pulseAge.cancel()
            save.cancel()
        }
    }

    private fun startFetch(date: LocalDate, profile: Profile, keptAge: Kept<PulseAge?>? = null, background: Boolean = false): Fetch {
        fetches.remove(date)?.cancel()
        return Fetch(date, profile, keptAge, background).also { fetches[date] = it }
    }

    private suspend fun loadEnergy(date: LocalDate): Pair<EnergyDay?, StressDay?> {
        val day = try {
            energySource.days(date, date).singleOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        // The slots are cached by now, so stress costs little extra.
        val stress = try {
            energySource.stress(date, date).singleOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return day to stress
    }

    private suspend fun loadPulseAge(date: LocalDate, profile: Profile): PulseAge? = try {
        pulseAgeSource.pulseAge(date.minusDays(1), profile)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun loadMain(date: LocalDate, profile: Profile): TodayUiState = try {
        loadDay(date, profile)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        TodayUiState.Failed(error.message ?: error.javaClass.simpleName)
    }

    private suspend fun loadDay(date: LocalDate, profile: Profile): TodayUiState = coroutineScope {
        when (gateway.availability()) {
            HealthConnectAvailability.Unavailable -> return@coroutineScope TodayUiState.Unavailable(updateRequired = false)
            HealthConnectAvailability.UpdateRequired -> return@coroutineScope TodayUiState.Unavailable(updateRequired = true)
            HealthConnectAvailability.Available -> Unit
        }
        val requestable = gateway.requestablePermissions()
        val dataTypes = requestable - HealthConnectGateway.OPTIONAL_PERMISSIONS
        val granted = gateway.grantedPermissions()
        val missing = dataTypes - granted
        if (missing == dataTypes) return@coroutineScope TodayUiState.PermissionsRequired(requestable)

        val today = LocalDate.now()
        val week = weekOf(date)
        // The smaller reads run beside the day and its history.
        val latestExercise = async { runCatching { HealthConnectExercises(gateway).sessions(date, date).maxByOrNull { it.start } }.getOrNull() }
        val mindfulDays = async { runCatching { HealthConnectMindfulness(gateway).mindfulDays(week.first(), week.last()) }.getOrDefault(emptySet()) }
        val sleepScore = async { runCatching { HealthConnectSleep(gateway).nights(date, date)[date]?.let(::sleepScore) }.getOrNull() }
        val healthMetrics = async {
            runCatching { metricReadings(date, vitals.daily(HealthMetric.entries.toSet(), date.minusDays(HEALTH_METRIC_DAYS), date)) }.getOrDefault(emptyList())
        }
        var (googleHealth, remote) = googleHealthSource()
        val loaded = try {
            load(listOfNotNull(remote, healthConnect), date, week, profile)
        } catch (error: GoogleHealthException) {
            googleHealth = GoogleHealthStatus.Failed(error.message.orEmpty())
            load(listOf(healthConnect), date, week, profile)
        }
        val metrics = loaded.metrics
        val history = loaded.history.withToday(date, metrics)
        val readiness = readiness(date, history)
        val zoneMinutes = loaded.history.zoneMinutes.mapValues { it.value.toDouble() }
        val zoneWeeks = completedWeeks(zoneMinutes, week.first())
        val dayIndex = week.indexOf(date)
        TodayUiState.Ready(
            date = date,
            isToday = date == today,
            metrics = metrics,
            week = week,
            exerciseDays = loaded.exerciseDays,
            latestExercise = latestExercise.await(),
            mindfulDays = mindfulDays.await(),
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
            fitness = profile.age?.let { age ->
                profile.sex?.let { sex ->
                    // The same 30 days to the date as the VO2 max page.
                    val resting = history.restingBpm.filterKeys { it > date.minusDays(ESTIMATE_DAYS.toLong()) }.values
                    fitnessLevel(age, sex, metrics.vo2Max?.value, resting.takeIf { it.isNotEmpty() }?.average())
                }
            },
            sleepScore = sleepScore.await(),
            healthMetrics = healthMetrics.await(),
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
        /** Today reloads by itself at most this often; pull to refresh reloads at once. */
        val TODAY_FRESH: Duration = Duration.ofMinutes(5)
        val BACKOFF: Duration = Duration.ofMinutes(5)
        val PAST_FRESH: Duration = Duration.ofMinutes(10)
        /** A past day's snapshot taken this long after the day ended is final. */
        val SETTLE_AFTER: Duration = Duration.ofHours(12)
        const val SNAPSHOT_DAYS = 120L
        const val HISTORY_WEEKS = 4L
        const val HEALTH_METRIC_DAYS = 30L
    }
}
