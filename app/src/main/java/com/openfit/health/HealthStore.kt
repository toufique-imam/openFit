package com.openfit.health

import android.content.Context
import android.util.LruCache
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
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
import com.openfit.data.AggregateEntity
import com.openfit.data.OpenFitDatabase
import com.openfit.data.RecordDayEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** Sums from an aggregate request, read like Health Connect's [AggregationResult]. */
class AggregateValues internal constructor(internal val values: Map<String, Double>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(metric: AggregateMetric<T>): T? {
        val codec = METRICS[metric] ?: error("Unsupported metric $metric")
        return values[codec.name]?.let { codec.decode(it) as T }
    }
}

class DurationBucket(val startTime: Instant, val endTime: Instant, val result: AggregateValues)

class PeriodBucket(val startTime: LocalDateTime, val endTime: LocalDateTime, val result: AggregateValues)

/**
 * openFit's copy of Health Connect, and the only way to it. Screens read through here; Health Connect is asked only for
 * what is not saved yet or may still change:
 *  - records are saved per type and local day (filed by start, as Health Connect files them). A day read well after it
 *    ended ([SETTLE]) is final; a recent one is read again at most every [LIVE_TTL].
 *  - an aggregate answer is saved per request, final or not by the same rule.
 *  - [syncChanges] follows Health Connect's changes feed and forgets the saved days that changed later (a watch syncing
 *    late, an app backfilling history).
 * Every call goes through [HealthConnectBudget]. When it refuses, or Health Connect fails, saved data stands in; only
 * data never read before fails.
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

    /** Records of [type] starting in [start, end), as Health Connect's readRecords would return them. */
    suspend fun <T : Record> read(type: KClass<T>, start: Instant, end: Instant): List<T> {
        if (end <= start) return emptyList()
        val days = days(start, end)
        if (days.isEmpty()) return emptyList()
        val rows = ensure(type, days)
        val from = start.toEpochMilli()
        val until = end.toEpochMilli()
        return days.flatMap { day -> rows[day]?.let(::decode).orEmpty() }
            .filter { it.start in from until until }
            .map { it.toRecord(type) }
    }

    /**
     * Saves [first]..[last] of [type] from Health Connect where nothing current is saved, without reading it back (the
     * import); with [force], reads them again whatever is saved.
     */
    suspend fun <T : Record> save(type: KClass<T>, first: LocalDate, last: LocalDate, force: Boolean = false) {
        val days = days(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())
        if (days.isNotEmpty()) ensure(type, days, force)
    }

    /** Forgets saved aggregate answers covering [from] or later, so they are asked again. */
    suspend fun forgetAggregatesFrom(from: LocalDate) = dao.forgetAggregatesFrom(from.toString())

    /** Local days from [start] to the one before [end], stopping at today: later days hold nothing yet. */
    private fun days(start: Instant, end: Instant): List<LocalDate> {
        val first = day(start)
        val last = minOf(day(end.minusMillis(1)), LocalDate.now(zone))
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
    }

    /** The saved rows for [days], reading the stale ones from Health Connect first. */
    private suspend fun <T : Record> ensure(type: KClass<T>, days: List<LocalDate>, force: Boolean = false): Map<LocalDate, RecordDayEntity> {
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        val first = days.first()
        val last = days.last()
        return lock("records:$name").withLock {
            val saved = dao.days(name, first.toString(), last.toString()).associateBy { LocalDate.parse(it.day) }
            val stale = if (force) days else days.filter { day -> saved[day]?.let { isFresh(it.fetchedAt, dayEnd(day)) } != true }
            if (stale.isEmpty()) {
                budget.savedRead()
                saved
            } else {
                try {
                    saved + fetchDays(type, name, stale)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Days read before stand in for the ones Health Connect would not give now.
                    if (stale.any { it !in saved }) throw error
                    budget.savedRead()
                    saved
                }
            }
        }
    }

    suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter): List<T> =
        read(type, range.startTime ?: error("Instant ranges only"), range.endTime ?: error("Instant ranges only"))

    suspend fun aggregate(metrics: Set<AggregateMetric<*>>, start: Instant, end: Instant): AggregateValues {
        val until = settledEnd(end)
        val key = "total|${names(metrics)}|${start.toEpochMilli()}|${until.toEpochMilli()}"
        val values = cached(key, metrics, start, until, MAP) {
            encode(metrics, client().aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, until))))
        }
        return AggregateValues(values)
    }

    suspend fun aggregateByDuration(metrics: Set<AggregateMetric<*>>, start: Instant, end: Instant, slicer: Duration): List<DurationBucket> {
        val until = settledEnd(end)
        val key = "duration|${names(metrics)}|${start.toEpochMilli()}|${until.toEpochMilli()}|${slicer.seconds}"
        val buckets = cached(key, metrics, start, until, ListSerializer(SavedBucket.serializer())) {
            client().aggregateGroupByDuration(AggregateGroupByDurationRequest(metrics, TimeRangeFilter.between(start, until), slicer))
                .map { SavedBucket(it.startTime.toEpochMilli().toString(), it.endTime.toEpochMilli().toString(), encode(metrics, it.result)) }
        }
        return buckets.map { DurationBucket(Instant.ofEpochMilli(it.start.toLong()), Instant.ofEpochMilli(it.end.toLong()), AggregateValues(it.values)) }
    }

    suspend fun aggregateByPeriod(metrics: Set<AggregateMetric<*>>, start: LocalDateTime, end: LocalDateTime, slicer: Period): List<PeriodBucket> {
        val key = "period|${names(metrics)}|$start|$end|$slicer"
        val buckets = cached(key, metrics, start.atZone(zone).toInstant(), end.atZone(zone).toInstant(), ListSerializer(SavedBucket.serializer())) {
            client().aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, TimeRangeFilter.between(start, end), slicer))
                .map { SavedBucket(it.startTime.toString(), it.endTime.toString(), encode(metrics, it.result)) }
        }
        return buckets.map { PeriodBucket(LocalDateTime.parse(it.start), LocalDateTime.parse(it.end), AggregateValues(it.values)) }
    }

    /**
     * Follows Health Connect's changes feed (at most every [CHANGES_EVERY]) and forgets saved days and aggregates that
     * changed since: Fitbit's app may write a day hours late, or backfill older days. Background priority, and quiet on
     * failure: the saved days are still the best there is.
     */
    suspend fun syncChanges(granted: Set<String>) = withContext(HcPriority.Background) {
        val types = CHANGE_TYPES.keys.filter { HealthConnectGateway.read(it) in granted }.toSet()
        if (types.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_SYNCED_AT, 0) < CHANGES_EVERY.toMillis() || !budget.hasRoom()) return@withContext
        val typeKey = types.mapNotNull { CHANGE_TYPES[it] }.sorted().joinToString(",")
        // Taken before the walk, so a change written during it is not counted as seen.
        val walkStart = now
        try {
            var token = prefs.getString(KEY_TOKEN, null)?.takeIf { prefs.getString(KEY_TYPES, null) == typeKey }
            if (token != null) {
                var pages = 0
                var deleted = false
                while (pages++ < MAX_CHANGE_PAGES) {
                    val current = token ?: break
                    val response = budget.call("changes") { client().getChanges(current) }
                    if (response.changesTokenExpired) {
                        token = null
                        forgetRecent()
                        break
                    }
                    response.changes.forEach { change ->
                        when (change) {
                            is UpsertionChange -> forget(change.record)
                            // A deletion names only the record's id: forget the recent days, where deletions happen.
                            is DeletionChange -> deleted = true
                        }
                    }
                    token = response.nextChangesToken
                    if (!response.hasMore) break
                }
                if (deleted) forgetRecent()
            }
            val editor = prefs.edit()
            if (token == null) {
                token = budget.call("changes token") { client().getChangesToken(ChangesTokenRequest(types)) }
                // A new token reports only what changes from now on.
                editor.putLong(KEY_SINCE, walkStart)
            }
            editor.putString(KEY_TOKEN, token).putString(KEY_TYPES, typeKey).putLong(KEY_SYNCED_AT, now).putLong(KEY_WALKED_AT, walkStart).apply()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /** Forgets everything saved, so the next reads go to Health Connect (Delete everything). */
    suspend fun clear() {
        dao.clearDays()
        dao.clearAggregates()
        decoded.evictAll()
        prefs.edit().clear().apply()
    }

    private suspend fun forget(record: Record) {
        val day = day(record.filedAt).toString()
        SAVED_TYPES[record::class]?.let { dao.forgetDay(it, day) }
        CHANGE_TYPES[record::class]?.let { dao.forgetAggregates(it, day) }
    }

    private suspend fun forgetRecent() {
        val from = LocalDate.now(zone).minusDays(RECENT_DAYS).toString()
        dao.forgetDaysFrom(from)
        dao.forgetAggregatesFrom(from)
    }

    /** Reads [days] from Health Connect, one request per run of consecutive days, and saves them (empty days too). */
    private suspend fun <T : Record> fetchDays(type: KClass<T>, name: String, days: List<LocalDate>): Map<LocalDate, RecordDayEntity> {
        // Runs of consecutive days, each at most [maxRun] long: a few days of heart rate already fill several pages.
        val maxRun = if (type == HeartRateRecord::class) HEART_RATE_RUN_DAYS else RUN_DAYS
        val runs = mutableListOf<MutableList<LocalDate>>()
        days.sorted().forEach { day ->
            val run = runs.lastOrNull()
            if (run != null && run.last().plusDays(1) == day && run.size < maxRun) run += day else runs += mutableListOf(day)
        }
        val result = mutableMapOf<LocalDate, RecordDayEntity>()
        runs.forEach { run ->
            val fetchedAt = System.currentTimeMillis()
            val range = TimeRangeFilter.between(run.first().atStartOfDay(zone).toInstant(), run.last().plusDays(1).atStartOfDay(zone).toInstant())
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

    private suspend fun <R> cached(
        key: String,
        metrics: Set<AggregateMetric<*>>,
        start: Instant,
        end: Instant,
        serializer: KSerializer<R>,
        fetch: suspend () -> R,
    ): R = lock("aggregate:$key").withLock {
        val saved = dao.aggregate(key)
        if (saved != null && isFresh(saved.fetchedAt, end.toEpochMilli())) {
            budget.savedRead()
            return@withLock JSON.decodeFromString(serializer, saved.json)
        }
        try {
            val value = budget.call("aggregate") { fetch() }
            dao.putAggregate(
                AggregateEntity(
                    key = key,
                    fetchedAt = System.currentTimeMillis(),
                    rangeEnd = end.toEpochMilli(),
                    firstDay = day(start).toString(),
                    lastDay = day(end.minusMillis(1)).toString(),
                    types = metrics.mapNotNull { METRICS[it]?.type }.distinct().joinToString(",", prefix = ",", postfix = ","),
                    json = JSON.encodeToString(serializer, value),
                ),
            )
            value
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (saved == null) throw error
            budget.savedRead()
            JSON.decodeFromString(serializer, saved.json)
        }
    }

    /**
     * Final once read [SETTLE] after its end. Before that, good for [LIVE_TTL], or for as long as the changes feed has
     * been followed since it was read: a change would have made [syncChanges] forget it.
     */
    private fun isFresh(fetchedAt: Long, end: Long): Boolean {
        if (fetchedAt >= end + SETTLE.toMillis()) return true
        if (System.currentTimeMillis() - fetchedAt < LIVE_TTL.toMillis()) return true
        val since = prefs.getLong(KEY_SINCE, Long.MAX_VALUE)
        return fetchedAt >= since && prefs.getLong(KEY_WALKED_AT, 0) >= fetchedAt
    }

    /**
     * A range that runs to now asks to the end of today instead, so the request is the same all day and its saved answer
     * can be found again (Health Connect has nothing after now anyway).
     */
    private fun settledEnd(end: Instant): Instant {
        val now = Instant.now()
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        return if (end > now.minusSeconds(NOW_SLACK_SECONDS) && end < endOfToday) endOfToday else end
    }

    private fun decode(row: RecordDayEntity): List<SavedRecord> {
        val key = "${row.type}:${row.day}:${row.fetchedAt}"
        return decoded.get(key) ?: JSON.decodeFromString(RECORDS, row.json).also { decoded.put(key, it) }
    }

    private fun encode(metrics: Set<AggregateMetric<*>>, result: AggregationResult): Map<String, Double> =
        metrics.mapNotNull { metric -> METRICS[metric]?.let { codec -> result[metric]?.let { codec.name to codec.encode(it) } } }.toMap()

    private fun names(metrics: Set<AggregateMetric<*>>) = metrics.map { METRICS[it]?.name ?: error("Unsupported metric $it") }.sorted().joinToString(",")

    private fun day(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    private fun dayEnd(day: LocalDate): Long = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun lock(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    @Serializable
    private class SavedBucket(val start: String, val end: String, val values: Map<String, Double>)

    companion object {
        /** A day read this long after it ended is final (a watch syncs within hours; the changes feed covers later). */
        val SETTLE: Duration = Duration.ofHours(36)
        /** How long a recent day or answer is good for before Health Connect is asked again. */
        val LIVE_TTL: Duration = Duration.ofMinutes(15)
        /** One call when nothing changed, so it can run with every Today refresh. */
        val CHANGES_EVERY: Duration = Duration.ofMinutes(5)
        /** Health Connect's largest page: fewer calls for a day of heart rate or a month of HRV. */
        private const val PAGE_SIZE = 5_000
        private const val HEART_RATE_RUN_DAYS = 2
        private const val RUN_DAYS = 31
        private const val MEMORY_DAYS = 64
        private const val MAX_CHANGE_PAGES = 20
        private const val RECENT_DAYS = 7L
        private const val NOW_SLACK_SECONDS = 120L
        private const val KEY_TOKEN = "token"
        private const val KEY_TYPES = "types"
        private const val KEY_SYNCED_AT = "synced_at"
        /** When the current token started: saved data read before it is not covered by the feed. */
        private const val KEY_SINCE = "token_since"
        /** When the last walk of the feed started: everything read before it, and still saved, is unchanged. */
        private const val KEY_WALKED_AT = "walked_at"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val RECORDS = ListSerializer(SavedRecord.serializer())
        private val MAP = MapSerializer(String.serializer(), Double.serializer())

        /** Record types whose changes matter: the saved ones, and the ones openFit only adds up. */
        private val CHANGE_TYPES: Map<KClass<out Record>, String> = SAVED_TYPES + mapOf(
            StepsRecord::class to "steps",
            DistanceRecord::class to "distance",
            FloorsClimbedRecord::class to "floors",
            TotalCaloriesBurnedRecord::class to "calories",
            NutritionRecord::class to "nutrition",
            HydrationRecord::class to "hydration",
        )
    }
}

private class MetricCodec(val name: String, val type: String, val encode: (Any) -> Double, val decode: (Double) -> Any)

/** The aggregate metrics openFit asks for, stored as numbers in fixed units. */
private val METRICS: Map<AggregateMetric<*>, MetricCodec> = mapOf(
    StepsRecord.COUNT_TOTAL to MetricCodec("steps", "steps", { (it as Long).toDouble() }, { it.toLong() }),
    DistanceRecord.DISTANCE_TOTAL to MetricCodec("distance_m", "distance", { (it as Length).inMeters }, { Length.meters(it) }),
    FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL to MetricCodec("floors", "floors", { it as Double }, { it }),
    TotalCaloriesBurnedRecord.ENERGY_TOTAL to MetricCodec("calories_out_kcal", "calories", { (it as Energy).inKilocalories }, { Energy.kilocalories(it) }),
    NutritionRecord.ENERGY_TOTAL to MetricCodec("calories_in_kcal", "nutrition", { (it as Energy).inKilocalories }, { Energy.kilocalories(it) }),
    HydrationRecord.VOLUME_TOTAL to MetricCodec("water_l", "hydration", { (it as Volume).inLiters }, { Volume.liters(it) }),
)
