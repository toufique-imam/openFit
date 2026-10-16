package com.openfit.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import com.openfit.health.HealthConnectGateway.Companion.read
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass

/** Where an import has got to: [done] of [total] steps, now on [stage]. */
data class ImportProgress(val stage: ImportStage, val done: Int, val total: Int)

enum class ImportStage { Finding, Reading, HeartRate, Scoring }

sealed interface SyncState {
    /** Nothing running; [lastSync] is when Health Connect was last read, [firstDay] how far back the copy goes. */
    data class Idle(val lastSync: Instant?, val firstDay: LocalDate?, val imported: Boolean, val failed: Boolean = false) : SyncState
    data class Importing(val progress: ImportProgress) : SyncState
    /** The import paused for Health Connect's quota; it carries on by itself at [resumeAt]. */
    data class Waiting(val resumeAt: Instant, val progress: ImportProgress?) : SyncState
    data object Syncing : SyncState
}

/**
 * Fills openFit's copy of Health Connect ([HealthStore]), which every page and calculation reads instead of Health
 * Connect itself. Health Connect is read only here:
 *  - [importAll]: everything available, from each type's first record (onboarding, and Sync in the profile page);
 *  - [syncRecent]: the last [RECENT_DAYS] days again, plus the older days the changes feed reports (opening the app,
 *    pulling to refresh, and the hourly background work).
 * One sync runs at a time (a quick one skips while the import runs) in a scope of its own, so leaving a screen does not
 * stop it; screens follow [state]. Both stop when the quota budget runs low and leave what they saved.
 */
class HealthSync private constructor(
    context: Context,
    private val gateway: HealthConnectGateway,
    private val dayLoadCache: DayLoadCache,
    private val slotCache: SlotCache,
    private val profileStore: ProfileStore,
) {
    private val prefs = context.getSharedPreferences("hc_import", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = Mutex()
    private val _state = MutableStateFlow<SyncState>(idle())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** When Health Connect was last read for recent days (or imported). */
    val lastSync: Instant? get() = prefs.getLong(KEY_LAST_SYNC, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    val imported: Boolean get() = prefs.getBoolean(KEY_IMPORTED, false)

    /** Stops what is running (Delete everything). */
    fun stop() = scope.coroutineContext.cancelChildren()

    private var importJob: Job? = null
    private var importInBackground = false

    /**
     * Starts the import, or returns the one already going; screens follow [state]. [background] imports nobody waits
     * for leave the quota's reserve to the pages; onboarding and Sync run at full priority, and take over a background
     * one.
     */
    fun startImport(background: Boolean = false): Job = synchronized(this) {
        val current = importJob?.takeIf { it.isActive }
        if (current != null && (background || !importInBackground)) return current
        current?.cancel()
        importInBackground = background
        scope.launch(if (background) HcPriority.Background else kotlin.coroutines.EmptyCoroutineContext) { importAll() }
            .also { importJob = it }
    }

    /** Starts a recent sync in the background unless one ran within [due] minutes. */
    fun startRecent(dueMinutes: Long = OPEN_SYNC_MINUTES): Job = scope.launch { syncRecent(dueMinutes) }

    /**
     * Reads everything Health Connect has into the copy, then works out the scores from it. Days already saved are not
     * read again. Returns false when it stopped early (the quota, or Health Connect failing): what it saved stays.
     */
    suspend fun importAll(waitForQuota: Boolean = true): Boolean {
        val background = coroutineContext[HcPriority]?.background == true
        while (true) {
            val error = running.withLock {
                try {
                    import()
                    prefs.edit().putBoolean(KEY_IMPORTED, true).putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error
                }
            }
            if (error == null) {
                _state.value = idle()
                return true
            }
            if (!waitForQuota || !isQuota(error)) {
                Log.w(TAG, "Import stopped", error)
                _state.value = idle(failed = true)
                return false
            }
            // Health Connect's quota: wait (without holding up other syncs) until a batch of reads fits, then carry on
            // from where it stopped; the days saved so far are not read again.
            val wait = maxOf(gateway.budget.waitFor(RESUME_BATCH, background), MIN_WAIT)
            _state.value = SyncState.Waiting(Instant.now().plus(wait), lastProgress)
            delay(wait.toMillis())
        }
    }

    @Volatile private var lastProgress: ImportProgress? = null

    /** Whether [error] (or what it wraps) is Health Connect's quota, or openFit's guard before it. */
    private fun isQuota(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(MAX_CAUSES).any { it is QuotaGuardException || it.message?.contains("quota", ignoreCase = true) == true } ||
            error.suppressed.any(::isQuota)

    /**
     * Reads the last [RECENT_DAYS] days again and the older days Health Connect reports changed, then forgets the
     * scores worked out from them. Skips when it ran within [dueMinutes] (0: always) or while the import runs.
     */
    suspend fun syncRecent(dueMinutes: Long = 0): Boolean {
        val since = System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0)
        if (dueMinutes > 0 && since < dueMinutes * 60_000) return true
        if (!running.tryLock()) return false
        return try {
            _state.value = SyncState.Syncing
            recent()
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Recent sync stopped", error)
            false
        } finally {
            _state.value = idle()
            running.unlock()
        }
    }

    private suspend fun import() {
        val granted = gateway.grantedPermissions()
        val store = gateway.store
        val today = LocalDate.now()
        val types = SAVED_TYPES.keys.filter { read(it) in granted }
        val metrics = TotalMetric.entries.filter { read(it.record) in granted }
        progress(ImportStage.Finding, 0, 1)

        // The changes feed starts before the reading, so what is written meanwhile is reported to the next sync.
        store.changedDays(granted)
        // How far back to read: the earliest first record of any type, found one record (or one yearly total) each.
        val firsts = coroutineScope {
            types.map { async { reads.withPermit { store.earliestDay(it) } } } + metrics.map { async { reads.withPermit { store.earliestDay(it) } } }
        }.awaitAll()
        val firstDay = maxOf(firsts.filterNotNull().minOrNull() ?: today, today.minusYears(MAX_YEARS))
        store.firstDay = firstDay

        val weeks = ((today.toEpochDay() - firstDay.toEpochDay()) / WEEK + 1).toInt()
        val others = types - HeartRateRecord::class
        val total = others.size + metrics.size + 1 + (if (HeartRateRecord::class in types) weeks else 0) + SCORING_STEPS
        var done = 0
        val step = Mutex()
        suspend fun advance(stage: ImportStage) = step.withLock { progress(stage, ++done, total) }

        // Records and totals, a few requests at a time.
        coroutineScope {
            others.forEach { type -> launch { reads.withPermit { store.syncRecords(type, firstDay, today, force = false) }; advance(ImportStage.Reading) } }
            metrics.forEach { metric -> launch { reads.withPermit { store.syncTotals(metric, firstDay, today, force = false) }; advance(ImportStage.Reading) } }
            if (TotalMetric.Steps in metrics) launch { reads.withPermit { store.syncMinutes(firstDay, today, force = false) }; advance(ImportStage.Reading) } else advance(ImportStage.Reading)
        }
        // Heart rate is most of the data: a week at a time, newest first, so recent screens fill first.
        if (HeartRateRecord::class in types) {
            repeat(weeks) { week ->
                val last = today.minusDays(week * WEEK)
                store.syncRecords(HeartRateRecord::class, maxOf(firstDay, last.minusDays(WEEK - 1)), last, force = false)
                advance(ImportStage.HeartRate)
            }
        }
        // Each workout's distance and energy.
        if (ExerciseSessionRecord::class in types) {
            val sessionMetrics = metrics.filter { it == TotalMetric.DistanceMeters || it == TotalMetric.CaloriesOutKcal }.toSet()
            val sessions = store.read(ExerciseSessionRecord::class, firstDay.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(), Instant.now())
            coroutineScope { sessions.forEach { session -> launch { reads.withPermit { store.sessionTotals(session.startTime, session.endTime, sessionMetrics) } } } }
        }
        score(firstDay, today)
        advance(ImportStage.Scoring)
        advance(ImportStage.Scoring)
    }

    private suspend fun recent() {
        val granted = gateway.grantedPermissions()
        val store = gateway.store
        val today = LocalDate.now()
        val from = today.minusDays(RECENT_DAYS - 1)
        val types = SAVED_TYPES.keys.filter { read(it) in granted }
        val metrics = TotalMetric.entries.filter { read(it.record) in granted }

        // Older days the feed reports changed (a watch syncing late, history backfilled); a new or expired feed
        // re-reads the last week instead.
        val changed: Map<KClass<out Record>, Set<LocalDate>> = store.changedDays(granted)
            ?: types.plus(metrics.map { it.record }).associateWith { (0 until FALLBACK_DAYS).map { today.minusDays(it) }.toSet() }
        val older = changed.mapValues { (_, days) -> days.filter { it < from && it >= store.firstDay } }

        coroutineScope {
            types.forEach { type ->
                launch {
                    reads.withPermit {
                        store.syncRecords(type, from, today, force = true)
                        older[type]?.forEach { day -> store.syncRecords(type, day, day, force = true) }
                    }
                }
            }
            metrics.forEach { metric ->
                launch {
                    reads.withPermit {
                        store.syncTotals(metric, from, today, force = true)
                        older[metric.record]?.forEach { day -> store.syncTotals(metric, day, day, force = true) }
                    }
                }
            }
            if (TotalMetric.Steps in metrics) {
                launch {
                    reads.withPermit {
                        store.syncMinutes(from, today, force = true)
                        older[TotalMetric.Steps.record]?.forEach { day -> store.syncMinutes(day, day, force = true) }
                    }
                }
            }
        }
        // Scores worked out from days read again are worked out again.
        val reread = (from..today) + older.values.flatten()
        dayLoadCache.forget(reread)
        slotCache.forget(reread)
        score(minOf(from, reread.minOrNull() ?: from), today)
    }

    /** Cardio load and zone minutes per day, and Energy Bank's slots, from the copy (no Health Connect calls). */
    private suspend fun score(from: LocalDate, today: LocalDate) {
        val profile = profileStore.load()
        if (profile.age != null) {
            HealthConnectSource(gateway, dayLoadCache).history(from, today.minusDays(1), profile, backfillDays = (today.toEpochDay() - from.toEpochDay()).toInt() + 1)
        }
        EnergySource(gateway, slotCache).days(today, today)
    }

    private fun progress(stage: ImportStage, done: Int, total: Int) {
        val progress = ImportProgress(stage, done, total)
        lastProgress = progress
        _state.value = SyncState.Importing(progress)
    }

    private fun idle(failed: Boolean = false) = SyncState.Idle(
        lastSync = lastSync,
        firstDay = if (imported) gateway.store.firstDay else null,
        imported = imported,
        failed = failed,
    )

    private operator fun LocalDate.rangeTo(other: LocalDate): List<LocalDate> =
        generateSequence(this) { it.plusDays(1) }.takeWhile { it <= other }.toList()

    companion object {
        /** Read again on every sync: a watch often syncs a day or two late. */
        private const val RECENT_DAYS = 3L
        /** Opening the app reads recent days at most this often. */
        const val OPEN_SYNC_MINUTES = 2L
        private const val FALLBACK_DAYS = 7L
        private const val MAX_YEARS = 2L
        private const val WEEK = 7L
        private const val SCORING_STEPS = 2
        /** Reads in flight at once (Pulse's figure): enough to overlap, not enough to burst through the quota. */
        private val reads = Semaphore(4)
        private const val TAG = "openFit.sync"
        /** Reads to wait for before carrying on: enough to make progress, a few minutes of Health Connect's refill. */
        private const val RESUME_BATCH = 60
        private val MIN_WAIT: java.time.Duration = java.time.Duration.ofSeconds(30)
        private const val MAX_CAUSES = 8
        private const val KEY_IMPORTED = "imported"
        private const val KEY_LAST_SYNC = "last_sync"

        @Volatile private var instance: HealthSync? = null

        /** The one sync of this process, shared by the app and the background work. */
        fun get(context: Context): HealthSync = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                HealthSync(app, HealthConnectGateway.get(app), DayLoadCache(app), SlotCache(app), ProfileStore(app))
            }.also { instance = it }
        }
    }
}
