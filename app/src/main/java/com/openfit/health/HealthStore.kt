package com.openfit.health

import android.content.Context
import android.util.LruCache
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Volume
import com.openfit.R
import com.openfit.data.AggregateEntity
import com.openfit.data.OpenFitDatabase
import com.openfit.data.RecordDayEntity
import com.openfit.ui.Notices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** The totals openFit adds up from Health Connect, saved per day. */
enum class TotalMetric(val metric: AggregateMetric<*>, val record: KClass<out Record>, val key: String, val toNumber: (Any) -> Double) {
    Steps(StepsRecord.COUNT_TOTAL, StepsRecord::class, "steps", { (it as Long).toDouble() }),
    DistanceMeters(DistanceRecord.DISTANCE_TOTAL, DistanceRecord::class, "distance", { (it as Length).inMeters }),
    Floors(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, FloorsClimbedRecord::class, "floors", { it as Double }),
    CaloriesOutKcal(TotalCaloriesBurnedRecord.ENERGY_TOTAL, TotalCaloriesBurnedRecord::class, "calories", { (it as Energy).inKilocalories }),
    CaloriesInKcal(NutritionRecord.ENERGY_TOTAL, NutritionRecord::class, "nutrition", { (it as Energy).inKilocalories }),
    WaterLiters(HydrationRecord.VOLUME_TOTAL, HydrationRecord::class, "hydration", { (it as Volume).inLiters }),
}

/** A workout's distance and energy, added up over its time. */
@kotlinx.serialization.Serializable
data class SessionTotals(val distanceMeters: Double? = null, val caloriesKcal: Double? = null)

/**
 * openFit's copy of Health Connect. Pages and calculations read only from it ([read], [dailyTotals], [hourly],
 * [minuteSteps], [sessionTotals]); a day never read before is filled once on first use, everything else comes from the
 * syncs ([HealthSync]): the import of all available data, and the last days on opening the app or pulling to refresh.
 *  - records are saved per type and local day, filed by start as Health Connect files them;
 *  - totals are saved per metric and day (daily, hourly, per minute for steps), and per workout.
 * Every call goes through [HealthConnectBudget]. When it refuses, or Health Connect fails, the saved data shows (with a
 * note when some days are missing); only a request with nothing saved fails.
 */
class HealthStore(
    context: Context,
    private val client: () -> HealthConnectClient,
    val budget: HealthConnectBudget,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val dao = OpenFitDatabase.get(context).healthCache()
    private val prefs = context.getSharedPreferences("hc_changes", Context.MODE_PRIVATE)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val decoded = LruCache<String, List<SavedRecord>>(MEMORY_DAYS)

    /**
     * The first day with data, found by the import. Days before it hold nothing, so reading them costs no call; before
     * the import, the last [DEFAULT_WINDOW_DAYS] count as possible.
     */
    var firstDay: LocalDate
        get() = prefs.getString(KEY_FIRST_DAY, null)?.let(LocalDate::parse) ?: LocalDate.now(zone).minusDays(DEFAULT_WINDOW_DAYS)
        set(value) = prefs.edit().putString(KEY_FIRST_DAY, value.toString()).apply()

    // ---- What pages read ----

    /** Records of [type] starting in [start, end), as Health Connect's readRecords would return them. */
    suspend fun <T : Record> read(type: KClass<T>, start: Instant, end: Instant): List<T> {
        if (end <= start) return emptyList()
        val days = days(day(start), day(end.minusMillis(1)))
        if (days.isEmpty()) return emptyList()
        val rows = records(type, days, force = false)
        val from = start.toEpochMilli()
        val until = end.toEpochMilli()
        return days.flatMap { day -> rows[day]?.let(::decode).orEmpty() }
            .filter { it.start in from until until }
            .map { it.toRecord(type) }
    }

    suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter): List<T> =
        read(type, range.startTime ?: error("Instant ranges only"), range.endTime ?: error("Instant ranges only"))

    /** [metric]'s total each day in [from]..[until] (count, metres, kcal or litres), for days with data. */
    suspend fun dailyTotals(metric: TotalMetric, from: LocalDate, until: LocalDate): Map<LocalDate, Double> =
        series(KIND_DAY, metric, days(from, until), force = false, DOUBLE).mapNotNull { (day, value) -> value?.let { day to it } }.toMap()

    /** [metric] per hour of [date] (hour of day → total). */
    suspend fun hourly(metric: TotalMetric, date: LocalDate): Map<Int, Double> = hourlyByDay(metric, date, date)[date].orEmpty()

    suspend fun hourlyByDay(metric: TotalMetric, from: LocalDate, until: LocalDate): Map<LocalDate, Map<Int, Double>> =
        series(KIND_HOUR, metric, days(from, until), force = false, HOURS).mapNotNull { (day, value) -> value?.let { day to it } }.toMap()

    /** Steps per minute of [date] (epoch minute → steps), minutes with steps only. */
    suspend fun minuteSteps(date: LocalDate): Map<Long, Double> =
        series(KIND_MINUTE, TotalMetric.Steps, days(date, date), force = false, MINUTES)[date].orEmpty()

    /** A workout's distance and energy over [start, end); null when neither could be read. */
    suspend fun sessionTotals(start: Instant, end: Instant, metrics: Set<TotalMetric>): SessionTotals? {
        val key = "$KIND_SESSION|${start.toEpochMilli()}|${end.toEpochMilli()}"
        return lock(key).withLock { sessionTotals(key, start, end, metrics) }
    }

    private suspend fun sessionTotals(key: String, start: Instant, end: Instant, metrics: Set<TotalMetric>): SessionTotals? {
        dao.aggregate(key)?.let { saved ->
            budget.savedRead()
            return JSON.decodeFromString(SessionTotals.serializer(), saved.json)
        }
        if (metrics.isEmpty()) return null
        return try {
            val result = budget.call("workout totals") { client().aggregate(AggregateRequest(metrics.map { it.metric }.toSet(), TimeRangeFilter.between(start, end))) }
            val totals = SessionTotals(
                distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                caloriesKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            )
            val day = day(start).toString()
            dao.putAggregate(AggregateEntity(key, System.currentTimeMillis(), end.toEpochMilli(), day, day, ",distance,calories,", JSON.encodeToString(SessionTotals.serializer(), totals)))
            totals
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    // ---- Syncs: reading Health Connect ----

    /** Reads [first]..[last] of [type] again (or, without [force], the days not saved yet). */
    suspend fun <T : Record> syncRecords(type: KClass<T>, first: LocalDate, last: LocalDate, force: Boolean) {
        val days = days(first, last, window = false)
        if (days.isNotEmpty()) records(type, days, force, strict = true)
    }

    /** Reads [metric]'s daily and hourly totals for [first]..[last] again (or, without [force], the missing days). */
    suspend fun syncTotals(metric: TotalMetric, first: LocalDate, last: LocalDate, force: Boolean) {
        val days = days(first, last, window = false)
        if (days.isEmpty()) return
        series(KIND_DAY, metric, days, force, DOUBLE, strict = true)
        series(KIND_HOUR, metric, days, force, HOURS, strict = true)
    }

    /** Reads steps per minute (for cardio load's moving minutes) for [first]..[last]. */
    suspend fun syncMinutes(first: LocalDate, last: LocalDate, force: Boolean) {
        val days = days(first, last, window = false)
        if (days.isNotEmpty()) series(KIND_MINUTE, TotalMetric.Steps, days, force, MINUTES, strict = true)
    }

    /** The first day [type] has data, from one record read oldest first; null without any. */
    suspend fun <T : Record> earliestDay(type: KClass<T>): LocalDate? {
        val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now())
        val response = budget.call("first record") { client().readRecords(ReadRecordsRequest(type, range, ascendingOrder = true, pageSize = 1)) }
        return response.records.firstOrNull()?.let { day(it.filedAt) }
    }

    /** The first day [metric] has data, from yearly totals; null without any. */
    suspend fun earliestDay(metric: TotalMetric): LocalDate? {
        val start = LocalDate.now(zone).minusYears(MAX_HISTORY_YEARS)
        val request = AggregateGroupByPeriodRequest(setOf(metric.metric), TimeRangeFilter.between(start.atStartOfDay(), LocalDate.now(zone).plusDays(1).atStartOfDay()), Period.ofMonths(1))
        val months = budget.call("first total") { client().aggregateGroupByPeriod(request) }
        return months.firstOrNull { it.result[metric.metric] != null }?.startTime?.toLocalDate()
    }

    /**
     * Health Connect's changes since the last call, as the local days each record type changed on; null when the feed
     * is new or expired (the caller then re-reads recent days). The next call reports what changes after this one.
     */
    suspend fun changedDays(granted: Set<String>): Map<KClass<out Record>, Set<LocalDate>>? {
        val types = CHANGE_TYPES.filter { HealthConnectGateway.read(it) in granted }.toSet()
        if (types.isEmpty()) return emptyMap()
        val typeKey = types.mapNotNull { TYPE_NAMES[it] }.sorted().joinToString(",")
        var token = prefs.getString(KEY_TOKEN, null)?.takeIf { prefs.getString(KEY_TYPES, null) == typeKey }
        val changed = mutableMapOf<KClass<out Record>, MutableSet<LocalDate>>()
        var complete = token != null
        var pages = 0
        while (token != null && pages++ < MAX_CHANGE_PAGES) {
            val current = token
            val response = budget.call("changes") { client().getChanges(current) }
            if (response.changesTokenExpired) {
                token = null
                complete = false
                break
            }
            response.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> changed.getOrPut(change.record::class) { mutableSetOf() } += day(change.record.filedAt)
                    // A deletion names only the record: count it as a change to the recent days of every type.
                    is DeletionChange -> types.forEach { type -> changed.getOrPut(type) { mutableSetOf() } += days(LocalDate.now(zone).minusDays(DELETION_DAYS), LocalDate.now(zone), window = false) }
                }
            }
            token = response.nextChangesToken
            if (!response.hasMore) break
        }
        if (token == null) token = budget.call("changes token") { client().getChangesToken(ChangesTokenRequest(types)) }
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_TYPES, typeKey).apply()
        return if (complete) changed else null
    }

    /** Forgets everything saved, so the next sync reads it all again (Delete everything). */
    suspend fun clear() {
        dao.clearDays()
        dao.clearAggregates()
        decoded.evictAll()
        prefs.edit().clear().apply()
    }

    // ---- Saved records ----

    /**
     * The saved rows of [type] for [days]. Without [force], only days never read go to Health Connect; with it, all of
     * [days] do. [strict] (a sync) fails when Health Connect does; a page instead keeps what is saved.
     */
    private suspend fun <T : Record> records(type: KClass<T>, days: List<LocalDate>, force: Boolean, strict: Boolean = false): Map<LocalDate, RecordDayEntity> {
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        return lock("records:$name").withLock {
            val saved = dao.days(name, days.first().toString(), days.last().toString()).associateBy { LocalDate.parse(it.day) }
            val missing = if (force) days else days.filter { it !in saved }
            if (missing.isEmpty()) {
                budget.savedRead()
                return@withLock saved
            }
            try {
                saved + fetchDays(type, name, missing)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (strict || saved.isEmpty()) throw error
                Notices.postOnce(R.string.hc_partial)
                saved
            }
        }
    }

    /** Reads [days] from Health Connect in runs of consecutive days, and saves each day (empty ones too). */
    private suspend fun <T : Record> fetchDays(type: KClass<T>, name: String, days: List<LocalDate>): Map<LocalDate, RecordDayEntity> {
        // A few days of heart rate already fill several pages.
        val result = mutableMapOf<LocalDate, RecordDayEntity>()
        runs(days, if (type == HeartRateRecord::class) HEART_RATE_RUN_DAYS else RUN_DAYS).forEach { run ->
            val fetchedAt = System.currentTimeMillis()
            val range = TimeRangeFilter.between(start(run.first()), start(run.last().plusDays(1)))
            val records = mutableListOf<SavedRecord>()
            var pageToken: String? = null
            do {
                val response = budget.call("read $name") {
                    client().readRecords(ReadRecordsRequest(type, range, pageSize = PAGE_SIZE, pageToken = pageToken))
                }
                response.records.forEach { records += it.toSaved() }
                pageToken = response.pageToken
            } while (!pageToken.isNullOrEmpty())
            val byDay = records.groupBy { day(Instant.ofEpochMilli(it.start)) }
            val rows = run.map { day -> RecordDayEntity(name, day.toString(), fetchedAt, JSON.encodeToString(RECORDS, byDay[day].orEmpty())) }
            dao.putDays(rows)
            rows.forEach { result[LocalDate.parse(it.day)] = it }
        }
        return result
    }

    // ---- Saved totals ----

    /**
     * [metric]'s saved [kind] values for [days] ("" saved for a day without data, so it is not asked again), reading
     * the missing days (or all, with [force]) from Health Connect, one request per run.
     */
    private suspend fun <V> series(
        kind: String,
        metric: TotalMetric,
        days: List<LocalDate>,
        force: Boolean,
        serializer: KSerializer<V>,
        strict: Boolean = false,
    ): Map<LocalDate, V?> {
        if (days.isEmpty()) return emptyMap()
        val prefix = "$kind|${metric.key}|"
        return lock("series:$prefix").withLock {
            val saved = dao.dailyTotals(prefix, days.first().toString(), days.last().toString()).associateBy { LocalDate.parse(it.firstDay) }
            val missing = if (force) days else days.filter { it !in saved }
            val rows = if (missing.isEmpty()) {
                budget.savedRead()
                saved
            } else {
                try {
                    saved + fetchSeries(kind, metric, prefix, missing, serializer)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (strict || saved.isEmpty()) throw error
                    Notices.postOnce(R.string.hc_partial)
                    saved
                }
            }
            rows.mapValues { (_, row) -> row.json.takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString(serializer, it) } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <V> fetchSeries(kind: String, metric: TotalMetric, prefix: String, days: List<LocalDate>, serializer: KSerializer<V>): Map<LocalDate, AggregateEntity> {
        val result = mutableMapOf<LocalDate, AggregateEntity>()
        val maxRun = when (kind) {
            KIND_DAY -> DAY_TOTALS_RUN_DAYS
            KIND_HOUR -> RUN_DAYS
            else -> MINUTE_RUN_DAYS
        }
        runs(days, maxRun).forEach { run ->
            val first = run.first()
            val end = run.last().plusDays(1)
            val values: Map<LocalDate, Any> = when (kind) {
                KIND_DAY -> {
                    val request = AggregateGroupByPeriodRequest(setOf(metric.metric), TimeRangeFilter.between(first.atStartOfDay(), end.atStartOfDay()), Period.ofDays(1))
                    budget.call("daily totals") { client().aggregateGroupByPeriod(request) }
                        .mapNotNull { bucket -> bucket.result[metric.metric]?.let { bucket.startTime.toLocalDate() to metric.toNumber(it) } }
                        .toMap()
                }
                else -> {
                    val slice = if (kind == KIND_HOUR) Duration.ofHours(1) else Duration.ofMinutes(1)
                    val request = AggregateGroupByDurationRequest(setOf(metric.metric), TimeRangeFilter.between(start(first), start(end)), slice)
                    val buckets = budget.call(if (kind == KIND_HOUR) "hourly totals" else "minute steps") { client().aggregateGroupByDuration(request) }
                    buckets.mapNotNull { bucket -> bucket.result[metric.metric]?.let { bucket.startTime to metric.toNumber(it) } }
                        .filter { (_, value) -> value > 0 }
                        .groupBy { (time, _) -> day(time) }
                        .mapValues { (_, entries) ->
                            if (kind == KIND_HOUR) {
                                entries.associate { (time, value) -> time.atZone(zone).hour to value }
                            } else {
                                entries.associate { (time, value) -> time.epochSecond / 60 to value }
                            }
                        }
                }
            }
            val fetchedAt = System.currentTimeMillis()
            val rows = run.map { day ->
                AggregateEntity(
                    key = prefix + day,
                    fetchedAt = fetchedAt,
                    rangeEnd = start(day.plusDays(1)).toEpochMilli(),
                    firstDay = day.toString(),
                    lastDay = day.toString(),
                    types = ",${metric.key},",
                    json = values[day]?.let { JSON.encodeToString(serializer, it as V) } ?: "",
                )
            }
            dao.putAggregates(rows)
            rows.forEach { result[LocalDate.parse(it.firstDay)] = it }
        }
        return result
    }

    // ---- Helpers ----

    /**
     * The days [first]..[last], stopping at today (later days hold nothing yet); with [window], also starting at
     * [firstDay] (earlier days hold nothing either), so a page asking about them costs no call.
     */
    private fun days(first: LocalDate, last: LocalDate, window: Boolean = true): List<LocalDate> {
        val from = if (window) maxOf(first, firstDay) else first
        val until = minOf(last, LocalDate.now(zone))
        return generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.toList()
    }

    private fun runs(days: List<LocalDate>, maxRun: Int): List<List<LocalDate>> {
        val runs = mutableListOf<MutableList<LocalDate>>()
        days.sorted().forEach { day ->
            val run = runs.lastOrNull()
            if (run != null && run.last().plusDays(1) == day && run.size < maxRun) run += day else runs += mutableListOf(day)
        }
        return runs
    }

    private fun decode(row: RecordDayEntity): List<SavedRecord> {
        val key = "${row.type}:${row.day}:${row.fetchedAt}"
        return decoded.get(key) ?: JSON.decodeFromString(RECORDS, row.json).also { decoded.put(key, it) }
    }

    private fun day(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    private fun start(day: LocalDate): Instant = day.atStartOfDay(zone).toInstant()

    private fun lock(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    companion object {
        /** Health Connect's largest page: fewer calls for a day of heart rate or a month of HRV. */
        private const val PAGE_SIZE = 5_000
        private const val HEART_RATE_RUN_DAYS = 2
        private const val RUN_DAYS = 31
        private const val DAY_TOTALS_RUN_DAYS = 92
        /** Three days of minutes is 4,320 buckets in one answer. */
        private const val MINUTE_RUN_DAYS = 3
        private const val MEMORY_DAYS = 64
        private const val MAX_CHANGE_PAGES = 20
        private const val DELETION_DAYS = 7L
        private const val DEFAULT_WINDOW_DAYS = 180L
        private const val MAX_HISTORY_YEARS = 5L
        private const val KIND_DAY = "day"
        private const val KIND_HOUR = "hour"
        private const val KIND_MINUTE = "minute"
        private const val KIND_SESSION = "session"
        private const val KEY_TOKEN = "token"
        private const val KEY_TYPES = "types"
        private const val KEY_FIRST_DAY = "first_day"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val RECORDS = ListSerializer(SavedRecord.serializer())
        private val DOUBLE = Double.serializer()
        private val HOURS = MapSerializer(Int.serializer(), Double.serializer())
        private val MINUTES = MapSerializer(Long.serializer(), Double.serializer())

        /** Record types whose changes matter: the saved ones, and the ones openFit only adds up. */
        val CHANGE_TYPES: Set<KClass<out Record>> = SAVED_TYPES.keys + TotalMetric.entries.map { it.record }
        private val TYPE_NAMES: Map<KClass<out Record>, String> = SAVED_TYPES + TotalMetric.entries.associate { it.record to it.key }
    }
}
