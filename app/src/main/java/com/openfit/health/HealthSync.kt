package com.openfit.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.permission.HealthPermission
import com.openfit.health.HealthConnectGateway.Companion.read
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/** Where an import has got to: [done] of [total] days (of every type), now on [stage]. */
data class ImportProgress(val stage: ImportStage, val done: Int, val total: Int) {
    val percent: Int get() = (done * 100L / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
}

enum class ImportStage { Finding, Reading, Scoring }

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
 *  - [importAll]: the last [WINDOW_DAYS] days (onboarding, and Sync in the profile page), then [startOlder]: the
 *    history before them, up to [MAX_YEARS], in the background;
 *  - [syncRecent]: the last [RECENT_DAYS] days again, plus the older days the changes feed reports (opening the app,
 *    pulling to refresh, and the hourly background work).
 * One sync runs at a time (a quick one skips while the import runs) in a scope of its own, so leaving a screen does not
 * stop it; screens follow [state]. Both stop when the quota budget runs low and leave what they saved.
 */
class HealthSync internal constructor(
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

    suspend fun copyCoverage(): List<com.openfit.data.CopyCoverage> = gateway.store.copyCoverage()

    /** The types openFit reads that Health Connect doesn't allow (refused, or not supported by this Health Connect). */
    suspend fun notAllowedTypes(): List<String> {
        val granted = gateway.grantedPermissions()
        return SAVED_TYPES.filterKeys { read(it) !in granted }.values.toList()
    }

    /** When Health Connect was last read for recent days (or imported). */
    val lastSync: Instant? get() = prefs.getLong(KEY_LAST_SYNC, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    /** Whether the import ran under the current way of copying (an older one copied totals, not raw records). */
    val imported: Boolean get() = prefs.getBoolean(KEY_IMPORTED, false) && prefs.getInt(KEY_VERSION, 0) >= IMPORT_VERSION

    /** Stops what is running (Delete everything). */
    fun stop() = scope.coroutineContext.cancelChildren()

    suspend fun stopAndJoin() {
        val jobs = scope.coroutineContext[Job]!!.children.toList()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    fun onDataDeleted() { _state.value = idle() }

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
        if (!background) olderJob?.cancel()
        importInBackground = background
        scope.launch(if (background) HcPriority.Background else kotlin.coroutines.EmptyCoroutineContext) { importAll() }
            .also { importJob = it }
    }

    /** Starts a recent sync in the background unless one ran within [due] minutes, then carries on with [startOlder]. */
    fun startRecent(due: java.time.Duration = OPEN_SYNC): Job = scope.launch {
        try {
            // Schedule expanded permissions even if an older import currently owns the sync lock.
            val added = imported && newlyGranted().isNotEmpty()
            if (added) startImport(background = true)
            if (!syncRecent(due)) return@launch
            // Newly granted types/history need a full import before continuing older history.
            if (!added) startOlder()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Sync continuation stopped", error)
            _state.value = idle(failed = true)
        }
    }

    /** The granted types the import has not copied yet. */
    private suspend fun newlyGranted(): Set<String> {
        val granted = gateway.grantedPermissions()
        val copied = prefs.getStringSet(KEY_TYPES, null).orEmpty()
        val historyAdded = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted && !prefs.getBoolean(KEY_HISTORY_GRANTED, false)
        return (SAVED_TYPES.filterKeys { read(it) in granted }.values.toSet() - copied) + if (historyAdded) setOf("history") else emptySet()
    }

    private var olderJob: Job? = null
    private val older = Mutex()

    /** Starts copying the history before the import's window, unless it is done or going; screens need not wait. */
    fun startOlder(): Job? = synchronized(this) {
        olderJob?.takeIf { it.isActive }?.let { return it }
        if (!imported || prefs.getBoolean(KEY_OLDER_DONE, false)) return null
        scope.launch(HcPriority.Background) {
            while (isActive) {
                if (importOlder()) break
                val error = olderFailure ?: break
                if (!isQuota(error)) break
                delay(maxOf(gateway.budget.waitFor(RESUME_BATCH, background = true), MIN_WAIT).toMillis())
            }
        }.also { olderJob = it }
    }

    /**
     * Reads everything Health Connect has into the copy, then works out the scores from it. Days already saved are not
     * read again. Returns false when it stopped early (the quota, or Health Connect failing): what it saved stays.
     */
    suspend fun importAll(waitForQuota: Boolean = true): Boolean {
        val background = coroutineContext[HcPriority]?.background == true
        while (true) {
            val error = running.withLock {
                try {
                    withContext(HcSource("import")) { import() }
                    prefs.edit().putBoolean(KEY_IMPORTED, true).putInt(KEY_VERSION, IMPORT_VERSION).putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error
                }
            }
            if (error == null) {
                _state.value = idle()
                startOlder()
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
     * scores worked out from them. Skips when it ran (or was tried) within [due], or while the import runs.
     */
    suspend fun syncRecent(due: java.time.Duration = java.time.Duration.ZERO): Boolean {
        // Counted from the last attempt too: while Health Connect is limiting, a retry every visit would only spend it.
        val since = System.currentTimeMillis() - maxOf(prefs.getLong(KEY_LAST_SYNC, 0), prefs.getLong(KEY_LAST_ATTEMPT, 0))
        if (since < due.toMillis()) return true
        if (!running.tryLock()) return false
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        var failed = false
        return try {
            _state.value = SyncState.Syncing
            withContext(HcSource("recent")) { recent() }
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Recent sync stopped", error)
            failed = true
            false
        } finally {
            _state.value = idle(failed = failed)
            running.unlock()
        }
    }

    /**
     * The last [WINDOW_DAYS] days of every type, and the evening before them (a night, or an interval running past
     * midnight, that began then): newest first, a few types at a time, heart rate first since it takes the most pages.
     */
    private suspend fun import() {
        val granted = gateway.grantedPermissions()
        val store = gateway.store
        val today = LocalDate.now()
        val types = SAVED_TYPES.keys.filter { read(it) in granted }
        val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        val backfillHistory = history && !prefs.getBoolean(KEY_HISTORY_GRANTED, false)
        val rebuild = prefs.getInt(KEY_VERSION, 0) < IMPORT_VERSION || prefs.getBoolean(KEY_REBUILD, false)
        val refreshedAfter = if (rebuild || backfillHistory) {
            prefs.getLong(KEY_REFRESH_STARTED, 0).takeIf { it > 0 } ?: System.currentTimeMillis().also {
                prefs.edit().putLong(KEY_REFRESH_STARTED, it).apply()
            }
        } else null
        if (rebuild && !prefs.getBoolean(KEY_REBUILD, false)) prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(KEY_FIRST_PREFIX) }.forEach(::remove)
            putBoolean(KEY_REBUILD, true)
        }.apply()
        if (backfillHistory && !prefs.getBoolean(KEY_HISTORY_BACKFILL, false)) {
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith(KEY_FIRST_PREFIX) }.forEach(::remove)
                putBoolean(KEY_HISTORY_BACKFILL, true)
            }.apply()
        }
        gateway.budget.note {
            val history = androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
            "import: ${types.size} types granted, past data (history) ${if (history) "granted" else "NOT granted: only 30 days before the first grant"}"
        }
        progress(ImportStage.Finding, 0, 1)
        // A copy from before raw records holds Health Connect's own totals: they are added up again from the records.
        if (prefs.getInt(KEY_TOTALS_VERSION, 0) < IMPORT_VERSION) {
            store.clearTotals()
            prefs.edit().putInt(KEY_TOTALS_VERSION, IMPORT_VERSION).apply()
        }
        // The older history follows again: for a type new since, it is still to copy (the rest costs no call).
        prefs.edit().remove(KEY_OLDER_DONE).apply()

        // The changes feed starts before the reading, so what is written meanwhile is reported to the next sync. What it
        // reports now is read again below: a saved day it names is out of date (an import run again, from Sync).
        val changed = store.changedDays(granted).orEmpty()
        val from = today.minusDays(WINDOW_DAYS)
        val total = types.size * (WINDOW_DAYS.toInt() + 1)
        val done = AtomicInteger()
        progress(ImportStage.Reading, 0, total)
        coroutineScope {
            types.sortedByDescending { it == HeartRateRecord::class }.forEach { type ->
                launch {
                    reads.withPermit {
                        store.syncRecords(type, from, today, force = backfillHistory || rebuild, refreshedAfter = refreshedAfter) { days -> progress(ImportStage.Reading, done.addAndGet(days), total) }
                        changed[type]?.filter { it >= today.minusYears(MAX_YEARS) }?.let { days -> store.syncDays(type, days, force = true) }
                    }
                }
            }
        }
        val earliestChanged = changed.values.flatten().filter { it >= today.minusYears(MAX_YEARS) }.minOrNull() ?: from
        store.firstDay = minOf(store.firstDay, from, earliestChanged)
        prefs.edit().putString(KEY_WINDOW_FROM, from.toString()).putStringSet(KEY_TYPES, types.mapNotNull { SAVED_TYPES[it] }.toSet()).apply()
        progress(ImportStage.Scoring, total, total)
        // Heart rate was read beside the amounts: add them up again now that the wearables' apps are known.
        store.addUpAgain(store.firstDay, today)
        // Pages may have worked days out while their heart rate, resting heart rate or steps were still being copied.
        // Every day the copy holds is worked out again, not just the window: the cache was cleared for all of them,
        // and the cloud history can reach far before it.
        dayLoadCache.clear()
        slotCache.clear()
        score(store.firstDay, today)
        store.acknowledgeChanges(granted)
        prefs.edit().putBoolean(KEY_HISTORY_GRANTED, history).apply()
    }

    /**
     * The history before the import's window, up to [MAX_YEARS]: each type from its first record (one call to find),
     * one type at a time at background priority, so pages and recent syncs keep the quota they need. Stops at the
     * quota and carries on next time from what it saved. Returns whether it is done.
     */
    @Volatile private var olderFailure: Exception? = null

    suspend fun importOlder(): Boolean {
        if (!imported || prefs.getBoolean(KEY_OLDER_DONE, false)) return true
        if (!older.tryLock()) return false
        olderFailure = null
        return try {
            running.withLock { withContext(HcSource("older")) { copyOlder() } }
            // The profile page shows how far back the copy goes.
            if (_state.value is SyncState.Idle) _state.value = idle()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            olderFailure = error
            Log.w(TAG, "Older history stopped", error)
            false
        } finally {
            older.unlock()
        }
    }

    private suspend fun copyOlder() {
        val granted = gateway.grantedPermissions()
        val store = gateway.store
        val today = LocalDate.now()
        val windowFrom = prefs.getString(KEY_WINDOW_FROM, null)?.let(LocalDate::parse) ?: return
        val limit = today.minusYears(MAX_YEARS)
        var first = windowFrom
        var restingFrom: LocalDate? = null
        SAVED_TYPES.filterKeys { read(it) in granted }.forEach { (type, name) ->
            // Found once: a resumed copy does not ask again.
            val key = KEY_FIRST_PREFIX + name
            val earliest = if (prefs.contains(key)) {
                prefs.getString(key, null)?.let(LocalDate::parse)
            } else {
                store.earliestDay(type).also { prefs.edit().putString(key, it?.toString()).apply() }
            }
            if (earliest != null && earliest < windowFrom) {
                val start = maxOf(earliest, limit)
                store.syncRecords(type, start, windowFrom.minusDays(1), force = prefs.getBoolean(KEY_HISTORY_BACKFILL, false) || prefs.getBoolean(KEY_REBUILD, false),
                    refreshedAfter = prefs.getLong(KEY_REFRESH_STARTED, 0).takeIf { it > 0 })
                if (type == RestingHeartRateRecord::class) restingFrom = start
                first = minOf(first, start)
            }
        }
        store.firstDay = minOf(store.firstDay, first)
        prefs.edit().putBoolean(KEY_OLDER_DONE, true).remove(KEY_HISTORY_BACKFILL).remove(KEY_REBUILD).remove(KEY_REFRESH_STARTED).apply()
        if (first < windowFrom) {
            val days = first..windowFrom.minusDays(1)
            dayLoadCache.forget(days)
            restingFrom?.let { dayLoadCache.forgetFrom(it) }
            slotCache.forget(days)
            score(first, today)
        }
        store.compactRecords()
    }

    private suspend fun recent() {
        val granted = gateway.grantedPermissions()
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY !in granted) prefs.edit().putBoolean(KEY_HISTORY_GRANTED, false).apply()
        val store = gateway.store
        val today = LocalDate.now()
        val from = today.minusDays(RECENT_DAYS - 1)
        val types = SAVED_TYPES.keys.filter { read(it) in granted }

        // Older days the feed reports changed (a watch syncing late, history backfilled); a new or expired feed
        // re-reads the last week instead.
        val changed: Map<KClass<out Record>, Set<LocalDate>> = store.changedDays(granted)
            ?: types.associateWith { (0 until FALLBACK_DAYS).map { today.minusDays(it) }.toSet() }
        // Days before the copy's first day count too (an app reconnected to Health Connect writing its history back),
        // within [MAX_YEARS]; the copy then reaches back to them.
        val limit = today.minusYears(MAX_YEARS)
        val older = changed.mapValues { (_, days) -> days.filter { it < from && it >= limit } }
        older.values.flatten().minOrNull()?.let { earliest -> store.firstDay = minOf(store.firstDay, earliest) }

        // One range per type for the recent days (a page each, mostly), and one per run of older changed days.
        coroutineScope {
            types.forEach { type ->
                launch { reads.withPermit { store.syncDays(type, (from..today) + older[type].orEmpty(), force = true) } }
            }
        }
        // Scores worked out from days read again are worked out again.
        val reread = (from..today) + older.values.flatten()
        dayLoadCache.forget(reread)
        if (RestingHeartRateRecord::class in types) {
            dayLoadCache.forgetFrom(minOf(from, older[RestingHeartRateRecord::class]?.minOrNull() ?: from))
        }
        slotCache.forget(reread)
        score(minOf(from, reread.minOrNull() ?: from), today)
        store.acknowledgeChanges(granted)
        store.compactRecords()
    }

    /**
     * Works the scores out again from [from] on, after days were added before the copy's (Fitbit's history from the
     * Google Health API): no Health Connect call.
     */
    suspend fun rescore(from: LocalDate) {
        dayLoadCache.clear()
        slotCache.clear()
        score(from, LocalDate.now())
        if (_state.value is SyncState.Idle) _state.value = idle()
    }

    /** Works the scores of [from]..today out again (the cloud history's last days were read again). */
    suspend fun rescoreRecent(from: LocalDate) {
        val days = from..LocalDate.now()
        dayLoadCache.forget(days)
        slotCache.forget(days)
        score(from, LocalDate.now())
    }

    /** Cardio load and zone minutes per day, and Vitality reserve's slots, from the copy (no Health Connect calls). */
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
        /**
         * 2: every type copied raw, totals added up from the records (1 copied Health Connect's totals). A copy from an
         * older version imports again. 3: reread existing copies to repair missed changes and rebuild corrected totals.
         */
        private const val IMPORT_VERSION = 3
        /**
         * The import's window: a year, so every Year view is complete from the start. About 130 Health Connect calls
         * with a watch's full data (a sixth of the quota), well under a minute; older history follows in the background.
         */
        private const val WINDOW_DAYS = 365L
        /** Read again on every sync: a watch often syncs a day or two late. */
        private const val RECENT_DAYS = 3L
        /** Opening the app reads recent days at most this often. */
        val OPEN_SYNC: java.time.Duration = java.time.Duration.ofMinutes(2)
        /** Pulls to refresh closer together than this just reload from the copy. */
        val PULL_SYNC: java.time.Duration = java.time.Duration.ofSeconds(30)
        private const val FALLBACK_DAYS = 7L
        private const val MAX_YEARS = 2L
        /** Bounds concurrent reads while allowing independent record types to make progress. */
        private val reads = Semaphore(4)
        private const val TAG = "openFit.sync"
        /** Reads to wait for before carrying on: enough to make progress, a few minutes of Health Connect's refill. */
        private const val RESUME_BATCH = 60
        private val MIN_WAIT: java.time.Duration = java.time.Duration.ofSeconds(30)
        private const val MAX_CAUSES = 8
        private const val KEY_IMPORTED = "imported"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_ATTEMPT = "last_attempt"
        private const val KEY_VERSION = "version"
        private const val KEY_TOTALS_VERSION = "totals_version"
        private const val KEY_WINDOW_FROM = "window_from"
        private const val KEY_OLDER_DONE = "older_done"
        private const val KEY_TYPES = "types"
        private const val KEY_HISTORY_GRANTED = "history_granted"
        private const val KEY_HISTORY_BACKFILL = "history_backfill"
        private const val KEY_REBUILD = "rebuild"
        private const val KEY_REFRESH_STARTED = "refresh_started"
        private const val KEY_FIRST_PREFIX = "first:"

        @Volatile private var instance: HealthSync? = null

        /** The one sync of this process, shared by the app and the background work. */
        fun get(context: Context): HealthSync = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                HealthSync(app, HealthConnectGateway.get(app), DayLoadCache(app), SlotCache(app), ProfileStore(app))
            }.also { instance = it }
        }
    }
}
