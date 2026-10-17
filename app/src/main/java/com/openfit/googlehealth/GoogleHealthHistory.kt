package com.openfit.googlehealth

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthSync
import com.openfit.health.SavedRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

sealed interface CloudHistoryState {
    /** Nothing running. [from] is how far back the copied history goes; [failed], why the last copy stopped. */
    data class Idle(val from: LocalDate?, val failed: String? = null) : CloudHistoryState
    data class Copying(val percent: Int) : CloudHistoryState
}

/**
 * Copies Fitbit's own history from the Google Health API into openFit's local copy (Profile → Google Health → Copy
 * Fitbit history), then keeps its last days current on opening the app and pulling to refresh. Health Connect holds
 * only what was written to it on this phone, often weeks; Fitbit's servers hold every day since the account began. The
 * copy goes beside Health Connect's ([com.openfit.health.HealthStore.saveCloud]), so pages read it offline like the
 * rest, and never call the API themselves.
 *
 * Calls are shaped by what the API was measured to allow (17 Oct 2026): a rollUp returns up to 10,000 windows a call
 * (1,440 unless asked) and leaves empty windows out; a list stops at about 1,760 points a page whatever is asked; heart
 * rate and total calories roll up over at most 14 days a call, steps, distance and floors over 90. So minute totals come
 * from rollUps, 10,000 at a time, and a year is about 130 calls: heart rate about 52, total calories 26, sleep and
 * workouts (25 a page) about 12 each, the rest a few. Each type goes newest first in chunks; a chunk saved is not asked
 * again, so a stopped copy carries on where it was.
 */
class GoogleHealthHistory private constructor(context: Context) {

    private val auth = GoogleHealthAuth(context)
    private val gateway = HealthConnectGateway.get(context)
    private val sync = HealthSync.get(context)
    private val prefs = context.getSharedPreferences("google_health_history", Context.MODE_PRIVATE)
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = Mutex()
    private val _state = MutableStateFlow<CloudHistoryState>(idle())
    val state: StateFlow<CloudHistoryState> = _state.asStateFlow()
    private val _updated = MutableStateFlow(prefs.getLong(KEY_LAST_TOP_UP, 0L))

    /** When the copy last changed (a copy or a top-up), epoch milliseconds: Today reloads when it moves. */
    val updated: StateFlow<Long> = _updated.asStateFlow()
    private var job: Job? = null

    suspend fun stopAndJoin() {
        val jobs = scope.coroutineContext[Job]!!.children.toList()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        _state.value = idle()
    }

    fun onDataDeleted() {
        _state.value = idle()
        _updated.value = 0L
    }

    /** Starts the copy, or returns the one going. Google Health must be connected (Profile) first. */
    fun start(): Job = synchronized(this) {
        job?.takeIf { it.isActive } ?: scope.launch { copy() }.also { job = it }
    }

    /** Forgets the copied history (Health Connect's copy stays); Copy Fitbit history then starts afresh. */
    suspend fun forget() = running.withLock {
        gateway.store.clearCloud()
        prefs.edit().clear().apply()
        _state.value = idle()
    }

    /** Starts a top-up of the last days (opening the app) unless one ran within [due]. */
    fun startTopUp(due: Duration = OPEN_TOP_UP): Job = scope.launch { topUp(due) }

    /**
     * Reads the last [TOP_UP_DAYS] days of every type again (a watch syncs late), once the history is copied and Google
     * Health connected: a call per type, less the types that never had data. Skips when it ran within [due], or while a
     * copy runs.
     */
    suspend fun topUp(due: Duration = Duration.ZERO) {
        if (prefs.getString(KEY_COPIED_FROM, null) == null || !auth.enabled) return
        if (prefs.getInt(KEY_RECONCILIATION, 0) < RECONCILIATION_VERSION) {
            copy()
            if (prefs.getInt(KEY_RECONCILIATION, 0) < RECONCILIATION_VERSION) return
        }
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_TOP_UP, 0L) < due.toMillis()) return
        if (!running.tryLock()) return
        try {
            val session = Session("top-up")
            val importZone = zone
            val today = LocalDate.now(importZone)
            val from = today.minusDays(TOP_UP_DAYS - 1)
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= today }.toList()
            // A type the copy holds nothing of (floors without an altimeter, say) is asked about once a week, not every
            // time; once it has data, every time.
            val weekly = System.currentTimeMillis() - prefs.getLong(KEY_EMPTY_CHECKED, 0L) > EMPTY_RECHECK.toMillis()
            TASKS.filter { weekly || gateway.store.cloudHasData(it.type) }.forEach { task ->
                val records = task.fetch(session, from, today, importZone)
                gateway.store.saveCloud(task.type, days, byDay(records, importZone).filterKeys { it in days }, sourceZone = importZone)
            }
            if (weekly) prefs.edit().putLong(KEY_EMPTY_CHECKED, System.currentTimeMillis()).apply()
            note { "top-up $from..$today: ${session.calls} calls" }
            sync.rescoreRecent(from)
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_TOP_UP, now).apply()
            _updated.value = now
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The next opening or pull tries again; what Health Connect has shows meanwhile.
            Log.w(TAG, "Top-up stopped", error)
        } finally {
            running.unlock()
        }
    }

    private suspend fun copy() = running.withLock {
        try {
            val session = Session("copy")
            val importZone = zone
            val today = LocalDate.now(importZone)
            val until = today.minusDays(1)
            _state.value = CloudHistoryState.Copying(0)
            val first = prefs.getString(KEY_FIRST, null)?.takeIf { prefs.getInt(KEY_DISCOVERY_VERSION, 0) == DISCOVERY_VERSION }
                ?.let(LocalDate::parse) ?: session.firstDay(until).also {
                prefs.edit().putString(KEY_FIRST, it.toString()).putInt(KEY_DISCOVERY_VERSION, DISCOVERY_VERSION).apply()
            }
            val store = gateway.store
            // Every chunk of every type, newest first, less those saved by an earlier run.
            val chunks = TASKS.flatMap { task -> task.chunks(first, until).map { task to it } }
            val pending = chunks.filter { (task, chunk) ->
                store.cloudDays(task.type, chunk.start, chunk.endInclusive).size < chunk.days() ||
                    task.name in RECONCILED_TASKS && !prefs.getBoolean(reconciledChunk(task, chunk), false)
            }
            var done = chunks.size - pending.size
            pending.forEach { (task, chunk) ->
                val records = task.fetch(session, chunk.start, chunk.endInclusive, importZone)
                val days = generateSequence(chunk.start) { it.plusDays(1) }.takeWhile { it <= chunk.endInclusive }.toList()
                store.saveCloud(task.type, days, byDay(records, importZone).filterKeys { it in chunk }, sourceZone = importZone)
                if (task.name in RECONCILED_TASKS) prefs.edit().putBoolean(reconciledChunk(task, chunk), true).apply()
                note { "${task.name} ${chunk.start}..${chunk.endInclusive}: ${records.size} records" }
                _state.value = CloudHistoryState.Copying((++done * 100 / chunks.size.coerceAtLeast(1)))
            }
            note { "copy $first..$until: ${session.calls} calls" }
            store.firstDay = minOf(store.firstDay, first)
            prefs.edit()
                .putString(KEY_COPIED_FROM, first.toString())
                .putLong(KEY_LAST_TOP_UP, System.currentTimeMillis())
                .putLong(KEY_EMPTY_CHECKED, System.currentTimeMillis())
                .putInt(KEY_RECONCILIATION, RECONCILIATION_VERSION)
                .apply()
            // Cardio load, zone minutes and Vitality reserve for the days just added.
            sync.rescore(first)
            _updated.value = System.currentTimeMillis()
            _state.value = idle()
        } catch (error: CancellationException) {
            _state.value = idle()
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "History copy stopped", error)
            _state.value = idle(failed = error.message ?: error::class.simpleName)
        }
    }

    private fun idle(failed: String? = null) = CloudHistoryState.Idle(prefs.getString(KEY_COPIED_FROM, null)?.let(LocalDate::parse), failed)

    private inline fun note(message: () -> String) {
        if (debuggable) Log.d(TAG, message())
    }

    /**
     * The API with a fresh access token (it lasts an hour; asked again on a 401), paced under 300 requests a minute,
     * waiting a minute and trying again on a 429. Counts its calls; debug builds log each one.
     */
    private inner class Session(private val purpose: String) {
        private var api: GoogleHealthApi? = null
        private var lastCall = 0L
        var calls = 0
            private set

        suspend fun <T> call(what: String, block: suspend (GoogleHealthApi) -> T): T {
            var attempt = 0
            while (true) {
                val wait = lastCall + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
                calls++
                try {
                    return block(api ?: authorize()).also { note { "  $purpose call $calls: $what (${System.currentTimeMillis() - lastCall} ms)" } }
                } catch (error: GoogleHealthException) {
                    note { "  $purpose call $calls: $what failed ${error.code}" }
                    when {
                        error.code == 401 && attempt == 0 -> api = null
                        error.code == 429 && attempt < MAX_RETRIES -> delay(RATE_WAIT_MS)
                        else -> throw error
                    }
                    attempt++
                }
            }
        }

        private suspend fun authorize(): GoogleHealthApi = when (val authorization = auth.authorize()) {
            is GoogleHealthAuthorization.Authorized -> GoogleHealthApi(authorization.accessToken).also { api = it }
            is GoogleHealthAuthorization.NeedsConsent -> error("Connect Google Health first (Profile).")
        }

        /** Search the supported history window, including long gaps when the tracker was not used. */
        suspend fun firstDay(until: LocalDate): LocalDate {
            var end = until
            var first: LocalDate? = null
            val limit = until.minusYears(MAX_YEARS)
            while (end >= limit) {
                val from = maxOf(end.minusDays(ROLLUP_DAYS - 1), limit)
                val days = call("steps days $from..$end") { api -> parseDailyRollups(api.dailyRollUp("steps", from, end.plusDays(1)), "steps") }
                    .filterValues { (it.number("countSum") ?: 0.0) > 0 }.keys
                if (days.isNotEmpty()) first = days.min()
                end = from.minusDays(1)
            }
            return first ?: until
        }

        /** Every page of a list (or reconcile) call. */
        suspend fun pages(what: String, fetch: suspend (GoogleHealthApi, String?) -> JSONObject): List<JSONObject> {
            val pages = mutableListOf<JSONObject>()
            var token: String? = null
            do {
                val page = call(if (token == null) what else "$what, next page") { api -> fetch(api, token) }
                pages += page
                token = page.optString("nextPageToken").ifEmpty { null }
            } while (token != null)
            return pages
        }
    }

    /** One data type's copy: its Health Connect type, chunk length (the API's range limits) and how to read a chunk. */
    private class Task(
        val name: String,
        val type: KClass<out Record>,
        val chunkDays: Long,
        val fetch: suspend Session.(from: LocalDate, until: LocalDate, zone: ZoneId) -> List<SavedRecord>,
    ) {
        /** [first]..[until] in chunks, newest first. */
        fun chunks(first: LocalDate, until: LocalDate): List<ClosedRange<LocalDate>> {
            val chunks = mutableListOf<ClosedRange<LocalDate>>()
            var end = until
            while (end >= first) {
                val start = maxOf(first, end.minusDays(chunkDays - 1))
                chunks += start..end
                end = start.minusDays(1)
            }
            return chunks
        }
    }

    companion object {
        private const val TAG = "openFit.gh"
        private const val KEY_FIRST = "first"
        private const val KEY_DISCOVERY_VERSION = "discovery_version"
        private const val DISCOVERY_VERSION = 1
        private const val KEY_COPIED_FROM = "copied_from"
        private const val KEY_LAST_TOP_UP = "last_top_up"
        private const val KEY_EMPTY_CHECKED = "empty_checked"
        private const val KEY_RECONCILIATION = "reconciliation_version"
        private const val RECONCILIATION_VERSION = 1
        private val RECONCILED_TASKS = setOf("workouts", "food", "water")
        private fun reconciledChunk(task: Task, chunk: ClosedRange<LocalDate>) =
            "reconciled:$RECONCILIATION_VERSION:${task.name}:${chunk.start}:${chunk.endInclusive}"
        private val EMPTY_RECHECK: Duration = Duration.ofDays(7)
        /** 300 requests a minute per user: one every 220 ms stays under it. */
        private const val MIN_GAP_MS = 220L
        private const val RATE_WAIT_MS = 60_000L
        private const val MAX_RETRIES = 5
        private const val MAX_YEARS = 15L
        private const val ROLLUP_DAYS = 90L
        /** Days read again by a top-up: today, and two before it (a watch syncs late). */
        private const val TOP_UP_DAYS = 3L
        /** Opening the app tops up at most this often; a pull to refresh, at most every [PULL_TOP_UP]. */
        val OPEN_TOP_UP: Duration = Duration.ofMinutes(10)
        val PULL_TOP_UP: Duration = Duration.ofSeconds(30)
        /** The most a rollUp or list returns in one call. */
        private const val PAGE_SIZE = 10_000
        private const val SESSION_PAGE_SIZE = 25
        /** Types read whole: they hold a value or a few a day, so one call (or a page of sessions) covers years. */
        private const val WHOLE = 3_650L

        private fun ClosedRange<LocalDate>.days(): Int = (endInclusive.toEpochDay() - start.toEpochDay() + 1).toInt()

        private fun start(day: LocalDate, zone: ZoneId): Instant = day.atStartOfDay(zone).toInstant()

        /** An RFC 3339 range on [field] (start_time, sample_time.physical_time) from [from] to the end of [until]. */
        private fun between(field: String, from: LocalDate, until: LocalDate, zone: ZoneId) =
            "$field >= \"${start(from, zone)}\" AND $field < \"${start(until.plusDays(1), zone)}\""

        private fun civil(field: String, from: LocalDate, until: LocalDate) = "$field >= \"${from}T00:00:00\" AND $field < \"${until.plusDays(1)}T00:00:00\""

        private fun dates(field: String, from: LocalDate, until: LocalDate) = "$field >= \"$from\" AND $field < \"${until.plusDays(1)}\""

        private suspend fun Session.listed(
            dataType: String,
            field: String,
            filter: String,
            family: String? = GoogleHealthApi.WEARABLES,
            pageSize: Int = PAGE_SIZE,
        ): List<JSONObject> = pages(dataType) { api, token -> api.list(dataType, filter, token, family, pageSize) }.flatMap { it.points(field) }

        /** Use the API's source reconciliation so mirrored sessions/logs do not appear twice. */
        private suspend fun Session.reconciled(dataType: String, field: String, filter: String, pageSize: Int = PAGE_SIZE): List<JSONObject> =
            pages(dataType) { api, token -> api.reconcile(dataType, filter, token, GoogleHealthApi.ALL_SOURCES, pageSize) }.flatMap { it.points(field) }

        /**
         * Every window with data of a rollUp over [from]..[until], 10,000 a call, asking only for [sumKey] of [field]
         * (half the bytes).
         */
        private suspend fun Session.windows(
            dataType: String,
            field: String,
            sumKey: String,
            from: LocalDate,
            until: LocalDate,
            zone: ZoneId,
            window: Duration,
        ): List<Triple<Instant, Instant, JSONObject>> {
            val fields = "rollupDataPoints(startTime,endTime,$field/$sumKey),nextPageToken"
            return pages("$dataType ${window.toMinutes()} min $from..$until") { api, token ->
                api.rollUp(dataType, start(from, zone), start(until.plusDays(1), zone), window, pageToken = token, pageSize = PAGE_SIZE, fields = fields)
            }.flatMap { it.rollupWindows(field) }
        }

        private val TASKS = listOf(
            // A minute's average at a time: the API keeps a reading every few seconds, many times what openFit uses.
            Task("heart rate", HeartRateRecord::class, 14) { from, until, zone ->
                heartRateRecords(windows("heart-rate", "heartRate", "beatsPerMinuteAvg", from, until, zone, Duration.ofMinutes(1)), zone)
            },
            // Minute totals by rollUp: 10,000 minutes a call, where a list stops at 1,760.
            Task("steps", StepsRecord::class, 90) { from, until, zone ->
                windowRecords(windows("steps", "steps", "countSum", from, until, zone, Duration.ofMinutes(1)), "steps", "countSum", zone)
            },
            Task("distance", DistanceRecord::class, 90) { from, until, zone ->
                windowRecords(windows("distance", "distance", "millimetersSum", from, until, zone, Duration.ofMinutes(1)), "distance", "millimetersSum", zone, scale = 0.001)
            },
            // A quarter hour's total, as Fitbit writes them to Health Connect (the API allows 14 days a call).
            Task("energy burned", TotalCaloriesBurnedRecord::class, 14) { from, until, zone ->
                windowRecords(windows("total-calories", "totalCalories", "kcalSum", from, until, zone, Duration.ofMinutes(15)), "calories", "kcalSum", zone)
            },
            Task("floors", FloorsClimbedRecord::class, 90) { from, until, zone ->
                windowRecords(windows("floors", "floors", "countSum", from, until, zone, Duration.ofHours(1)), "floors", "countSum", zone)
            },
            // By end time, a day past the chunk: a night that began on its last evening ends the day after.
            Task("sleep", SleepSessionRecord::class, WHOLE) { from, until, zone ->
                val filter = between("sleep.interval.end_time", from, until.plusDays(1), zone)
                sleepRecords(pages("sleep") { api, token -> api.reconcile("sleep", filter, token, family = null, pageSize = SESSION_PAGE_SIZE) }.flatMap { it.points("sleep") })
            },
            Task("workouts", ExerciseSessionRecord::class, WHOLE) { from, until, _ ->
                exerciseRecords(reconciled("exercise", "exercise", civil("exercise.interval.civil_start_time", from, until), SESSION_PAGE_SIZE))
            },
            Task("resting heart rate", RestingHeartRateRecord::class, WHOLE) { from, until, zone ->
                dailyRecords(
                    listed("daily-resting-heart-rate", "dailyRestingHeartRate", dates("daily_resting_heart_rate.date", from, until)),
                    "rhr", "beatsPerMinute", zone, hour = 7, range = 1.0..300.0,
                )
            },
            Task("HRV", HeartRateVariabilityRmssdRecord::class, WHOLE) { from, until, zone ->
                dailyRecords(
                    listed("daily-heart-rate-variability", "dailyHeartRateVariability", dates("daily_heart_rate_variability.date", from, until)),
                    "hrv", "averageHeartRateVariabilityMilliseconds", zone, hour = 5, range = 1.0..200.0,
                )
            },
            Task("SpO2", OxygenSaturationRecord::class, WHOLE) { from, until, zone ->
                dailyRecords(
                    listed("daily-oxygen-saturation", "dailyOxygenSaturation", dates("daily_oxygen_saturation.date", from, until)),
                    "spo2", "averagePercentage", zone, hour = 5, range = 0.0..100.0,
                )
            },
            Task("breathing rate", RespiratoryRateRecord::class, WHOLE) { from, until, zone ->
                dailyRecords(
                    listed("daily-respiratory-rate", "dailyRespiratoryRate", dates("daily_respiratory_rate.date", from, until)),
                    "breathing", "breathsPerMinute", zone, hour = 5, range = 0.0..1000.0,
                )
            },
            Task("skin temperature", SkinTemperatureRecord::class, WHOLE) { from, until, zone ->
                skinTemperatureRecords(
                    listed("daily-sleep-temperature-derivations", "dailySleepTemperatureDerivations", dates("daily_sleep_temperature_derivations.date", from, until)),
                    zone,
                )
            },
            Task("VO2 max", Vo2MaxRecord::class, WHOLE) { from, until, zone ->
                vo2MaxRecords(listed("daily-vo2-max", "dailyVo2Max", dates("daily_vo2_max.date", from, until)), zone)
            },
            Task("weight", WeightRecord::class, WHOLE) { from, until, zone ->
                sampleRecords(
                    listed("weight", "weight", between("weight.sample_time.physical_time", from, until, zone), GoogleHealthApi.ALL_SOURCES),
                    "weight", "weightGrams", range = 0.0..1000.0, scale = 0.001,
                )
            },
            Task("body fat", BodyFatRecord::class, WHOLE) { from, until, zone ->
                sampleRecords(
                    listed("body-fat", "bodyFat", between("body_fat.sample_time.physical_time", from, until, zone), GoogleHealthApi.ALL_SOURCES),
                    "bodyfat", "percentage", range = 0.0..100.0,
                )
            },
            Task("food", NutritionRecord::class, WHOLE) { from, until, _ ->
                nutritionRecords(reconciled("nutrition-log", "nutritionLog", civil("nutrition_log.interval.civil_start_time", from, until)))
            },
            Task("water", HydrationRecord::class, WHOLE) { from, until, _ ->
                hydrationRecords(reconciled("hydration-log", "hydrationLog", civil("hydration_log.interval.civil_start_time", from, until)))
            },
        )

        @Volatile private var instance: GoogleHealthHistory? = null

        fun get(context: Context): GoogleHealthHistory = instance ?: synchronized(this) {
            instance ?: GoogleHealthHistory(context.applicationContext).also { instance = it }
        }
    }
}
