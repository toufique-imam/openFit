package com.openfit.health

import android.content.Context
import android.util.LruCache
import androidx.health.connect.client.HealthConnectClient
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
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.data.AggregateEntity
import com.openfit.data.OpenFitDatabase
import com.openfit.data.RecordDayEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** The amounts openFit adds up per day, hour and minute from their raw records ([key] names both). */
enum class TotalMetric(val record: KClass<out Record>, val key: String) {
    Steps(StepsRecord::class, "steps"),
    DistanceMeters(DistanceRecord::class, "distance"),
    Floors(FloorsClimbedRecord::class, "floors"),
    CaloriesOutKcal(TotalCaloriesBurnedRecord::class, "calories"),
    CaloriesInKcal(NutritionRecord::class, "nutrition"),
    WaterLiters(HydrationRecord::class, "hydration"),
}

/** A workout's distance and energy, added up over its time. */
@kotlinx.serialization.Serializable
data class SessionTotals(val distanceMeters: Double? = null, val caloriesKcal: Double? = null)

/**
 * openFit's copy of Health Connect. Pages and calculations read only from it ([read], [dailyTotals], [hourly],
 * [minuteSteps], [sessionTotals]) and never reach Health Connect: a day not copied yet reads as no data. Only the syncs
 * ([HealthSync]) read Health Connect, through [syncDays] and [syncRecords], [earliestDay] and [changedDays].
 *  - Every type is copied raw, per type and local day, filed by start as Health Connect files them. A read goes newest
 *    first, in pages of 5,000 over a whole run of days, and saves each day once its records are all in: no call is
 *    spent on a short page that a longer range would have filled.
 *  - The amounts ([TotalMetric]) are added up as their days are saved, per day, hour and (steps) minute, with the time
 *    several apps recorded counted once ([merge]). A workout's totals are added up from the records when asked.
 * Every call goes through [HealthConnectBudget].
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
     * The first day with data, as far as the copy goes. Days before it hold nothing; before the import, the last
     * [DEFAULT_WINDOW_DAYS] count as possible.
     */
    var firstDay: LocalDate
        get() = prefs.getString(KEY_FIRST_DAY, null)?.let(LocalDate::parse) ?: LocalDate.now(zone).minusDays(DEFAULT_WINDOW_DAYS)
        set(value) = prefs.edit().putString(KEY_FIRST_DAY, value.toString()).apply()

    /** The apps that wrote heart rate (a watch's or band's): their amounts come first when apps overlap ([merge]). */
    val wearables: Set<String>
        get() = prefs.getStringSet(KEY_WEARABLES, null).orEmpty() + CLOUD_ORIGIN

    private fun noteWearables(origins: Set<String>) = synchronized(prefs) {
        val known = prefs.getStringSet(KEY_WEARABLES, null).orEmpty()
        if (!known.containsAll(origins)) prefs.edit().putStringSet(KEY_WEARABLES, known + origins).apply()
    }

    // ---- What pages read ----

    /** Records of [type] starting in [start, end), as Health Connect's readRecords would return them. */
    suspend fun <T : Record> read(type: KClass<T>, start: Instant, end: Instant): List<T> {
        if (end <= start) return emptyList()
        val days = days(day(start), day(end.minusMillis(1)))
        if (days.isEmpty()) return emptyList()
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        budget.savedRead()
        val records = recordsByDay(name, days.first(), days.last(), union = false)
        val from = start.toEpochMilli()
        val until = end.toEpochMilli()
        return days.flatMap { day -> records[day].orEmpty() }
            .filter { it.start in from until until }
            .map { it.toRecord(type) }
    }

    suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter): List<T> =
        read(type, range.startTime ?: error("Instant ranges only"), range.endTime ?: error("Instant ranges only"))

    /** [metric]'s total each day in [from]..[until] (count, metres, kcal or litres), for days with data. */
    suspend fun dailyTotals(metric: TotalMetric, from: LocalDate, until: LocalDate): Map<LocalDate, Double> =
        totals(KIND_DAY, metric, days(from, until)).mapNotNull { (day, json) -> json?.let { day to JSON.decodeFromString(DOUBLE, it) } }.toMap()

    /** [metric] per hour of [date] (hour of day → total). */
    suspend fun hourly(metric: TotalMetric, date: LocalDate): Map<Int, Double> = hourlyByDay(metric, date, date)[date].orEmpty()

    suspend fun hourlyByDay(metric: TotalMetric, from: LocalDate, until: LocalDate): Map<LocalDate, Map<Int, Double>> =
        totals(KIND_HOUR, metric, days(from, until)).mapNotNull { (day, json) -> json?.let { day to JSON.decodeFromString(HOURS, it) } }.toMap()

    /** Steps per minute of [date] (epoch minute → steps), minutes with steps only. */
    suspend fun minuteSteps(date: LocalDate): Map<Long, Double> =
        totals(KIND_MINUTE, TotalMetric.Steps, days(date, date))[date]?.let { JSON.decodeFromString(MINUTES, it) }.orEmpty()

    /**
     * A workout's distance and energy over [start, end), added up from the copied records with each moment counted
     * once; null when neither is copied for its days.
     */
    suspend fun sessionTotals(start: Instant, end: Instant): SessionTotals? {
        if (end <= start) return null
        // Records filed the day before can run into the workout.
        val from = day(start).minusDays(1)
        val until = day(end.minusMillis(1))
        val wearables = wearables
        fun total(records: Collection<List<SavedRecord>>): Double? =
            merge(records.flatten(), start.toEpochMilli(), end.toEpochMilli(), wearables).takeIf { it.isNotEmpty() }?.sumOf { it.value }
        val distance = recordsByDay(TotalMetric.DistanceMeters.key, from, until, union = true)
        val calories = recordsByDay(TotalMetric.CaloriesOutKcal.key, from, until, union = true)
        budget.savedRead()
        if (distance.isEmpty() && calories.isEmpty()) return null
        return SessionTotals(distanceMeters = total(distance.values), caloriesKcal = total(calories.values))
    }

    // ---- Syncs: reading Health Connect ----

    /** Reads [first]..[last] of [type] again (or, without [force], the days not saved yet). */
    suspend fun <T : Record> syncRecords(type: KClass<T>, first: LocalDate, last: LocalDate, force: Boolean, onDays: (Int) -> Unit = {}) =
        syncDays(type, days(first, last, window = false), force, onDays)

    /**
     * Reads [days] of [type] again (or, without [force], those not saved yet): each run of consecutive days as one
     * range, newest run first. [onDays] hears how many days are done as they are saved.
     */
    suspend fun <T : Record> syncDays(type: KClass<T>, days: Collection<LocalDate>, force: Boolean, onDays: (Int) -> Unit = {}) {
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        val today = LocalDate.now(zone)
        val wanted = days.filter { it <= today }.toSortedSet()
        if (wanted.isEmpty()) return
        val missing = if (force) {
            wanted.toList()
        } else {
            val saved = dao.savedDays(name, wanted.first().toString(), wanted.last().toString()).mapTo(HashSet(), LocalDate::parse)
            wanted.filter { it !in saved }
        }
        if (missing.size < wanted.size) onDays(wanted.size - missing.size)
        runs(missing).asReversed().forEach { run -> fetchRun(type, name, run, onDays) }
    }

    /** The first day [type] has data, from one record read oldest first; null without any. */
    suspend fun <T : Record> earliestDay(type: KClass<T>): LocalDate? {
        val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now())
        val response = budget.call("first record ${SAVED_TYPES[type]}") { client().readRecords(ReadRecordsRequest(type, range, ascendingOrder = true, pageSize = 1)) }
        return response.records.firstOrNull()?.let { day(it.filedAt) }.also { first ->
            budget.note { "range ${range.startTime}..${range.endTime}, oldest first, 1 record: ${first ?: "none"}" }
        }
    }

    /**
     * Health Connect's changes since the last call, as the local days each record type changed on; null when the feed
     * is new or expired (the caller then re-reads recent days). The next call reports what changes after this one.
     */
    suspend fun changedDays(granted: Set<String>): Map<KClass<out Record>, Set<LocalDate>>? {
        val types = SAVED_TYPES.keys.filter { HealthConnectGateway.read(it) in granted }.toSet()
        if (types.isEmpty()) return emptyMap()
        val typeKey = types.mapNotNull { SAVED_TYPES[it] }.sorted().joinToString(",")
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
        if (token == null) {
            token = budget.call("changes token") { client().getChangesToken(ChangesTokenRequest(types)) }
            budget.note { "a token for ${types.size} types: the next sync asks what changed since now" }
        }
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

    /** Forgets the added-up totals: [syncDays] adds them up again as it saves days. */
    suspend fun clearTotals() = dao.clearAggregates()

    /**
     * Adds up [first]..[last] again from the saved records, with no Health Connect call: after an import, whose amounts
     * may have been added up before its heart rate told which apps are wearables.
     */
    suspend fun addUpAgain(first: LocalDate, last: LocalDate) {
        val days = days(first, last, window = false).toSet()
        TotalMetric.entries.forEach { metric -> lock(metric.key).withLock { addUp(metric, days) } }
    }

    // ---- The cloud history (Google Health API) ----

    /**
     * Saves Fitbit's own history from the Google Health API for [days] (each, empty ones too, so they count as
     * copied), beside Health Connect's copy rather than over it: a later Health Connect read never erases it. Pages
     * then see it where Health Connect has nothing ([recordsByDay]); the amounts are added up again from both.
     */
    suspend fun saveCloud(type: KClass<out Record>, days: Collection<LocalDate>, records: Map<LocalDate, List<SavedRecord>>) {
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        val now = System.currentTimeMillis()
        save(type, days.map { day ->
            RecordDayEntity(CLOUD + name, day.toString(), now, JSON.encodeToString(RECORDS, records[day].orEmpty().sortedBy { it.start }))
        })
    }

    /** Forgets the cloud history (Health Connect's copy stays), and adds the amounts up again without it. */
    suspend fun clearCloud() {
        dao.clearDaysOf(CLOUD)
        decoded.evictAll()
        dao.clearAggregates()
        addUpAgain(firstDay, LocalDate.now(zone))
    }

    /** Whether the cloud history holds any record of [type]. */
    suspend fun cloudHasData(type: KClass<out Record>): Boolean =
        dao.hasRecords(CLOUD + (SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")))

    /** The days of [type] the cloud history has copied within [from]..[until]. */
    suspend fun cloudDays(type: KClass<out Record>, from: LocalDate, until: LocalDate): Set<LocalDate> {
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        return dao.savedDays(CLOUD + name, from.toString(), until.toString()).mapTo(HashSet(), LocalDate::parse)
    }

    // ---- Saved records ----

    /**
     * A type's saved records per day over [from]..[until], from Health Connect's copy and the cloud history beside it.
     * With [union] (the amounts, whose overlapping time [merge] counts once) a day has both; otherwise Health Connect's
     * records when it has any that day, the cloud's when it has none (so heart rate, sleep or a workout is never twice).
     */
    private suspend fun recordsByDay(name: String, from: LocalDate, until: LocalDate, union: Boolean): Map<LocalDate, List<SavedRecord>> {
        val own = dao.days(name, from.toString(), until.toString()).associate { LocalDate.parse(it.day) to decode(it) }
        val cloud = dao.days(CLOUD + name, from.toString(), until.toString()).associate { LocalDate.parse(it.day) to decode(it) }
        if (cloud.isEmpty()) return own
        return (own.keys + cloud.keys).associateWith { day ->
            val mine = own[day].orEmpty()
            if (union) mine + cloud[day].orEmpty() else mine.ifEmpty { cloud[day].orEmpty() }
        }
    }

    /**
     * Reads [run] (consecutive days) newest first, a page at a time, saving each day as soon as the pages have passed
     * it: records come back by start, so the days after a page's oldest record are complete.
     */
    private suspend fun <T : Record> fetchRun(type: KClass<T>, name: String, run: List<LocalDate>, onDays: (Int) -> Unit) {
        val first = run.first()
        val range = TimeRangeFilter.between(start(first), start(run.last().plusDays(1)))
        val fetchedAt = System.currentTimeMillis()
        val pending = HashMap<LocalDate, MutableList<SavedRecord>>()
        var next = run.last()
        var pageToken: String? = null
        var page = 0
        do {
            val token = pageToken
            page++
            // The page token carries the order; Health Connect takes the order only from the first request.
            val response = budget.call("read $name") {
                client().readRecords(ReadRecordsRequest(type, range, ascendingOrder = false, pageSize = PAGE_SIZE, pageToken = token))
            }
            var oldest: LocalDate? = null
            response.records.forEach { record ->
                val saved = record.toSaved()
                val day = day(Instant.ofEpochMilli(saved.start))
                pending.getOrPut(day) { mutableListOf() } += saved
                oldest = day
            }
            if (type == HeartRateRecord::class) noteWearables(response.records.mapTo(HashSet()) { it.metadata.dataOrigin.packageName })
            pageToken = response.pageToken?.takeIf { it.isNotEmpty() }
            budget.note {
                "range ${range.startTime}..${range.endTime} (${run.size} days), page $page, newest first: " +
                    "${response.records.size} records${if (pageToken != null) ", more to come" else ", last page"}"
            }
            val complete = if (pageToken == null) first else oldest?.plusDays(1)
            if (complete != null && complete <= next) {
                val days = generateSequence(next) { it.minusDays(1) }.takeWhile { it >= complete }.toList()
                save(type, days.map { day ->
                    val records = pending.remove(day).orEmpty().sortedBy { it.start }
                    RecordDayEntity(name, day.toString(), fetchedAt, JSON.encodeToString(RECORDS, records))
                })
                onDays(days.size)
                next = complete.minusDays(1)
            }
        } while (pageToken != null)
    }

    /** Saves [rows], then adds up their days again, and each next day (an interval can run past midnight). */
    private suspend fun save(type: KClass<out Record>, rows: List<RecordDayEntity>) {
        if (rows.isEmpty()) return
        val metric = TotalMetric.entries.firstOrNull { it.record == type }
        lock(SAVED_TYPES.getValue(type)).withLock {
            dao.putDays(rows)
            if (metric != null) {
                val days = rows.map { LocalDate.parse(it.day) }
                addUp(metric, (days + days.map { it.plusDays(1) }).toSortedSet())
            }
        }
    }

    // ---- Added-up totals ----

    /** [metric]'s saved [kind] totals for [days]: the JSON, or null for a day without data. */
    private suspend fun totals(kind: String, metric: TotalMetric, days: List<LocalDate>): Map<LocalDate, String?> {
        if (days.isEmpty()) return emptyMap()
        budget.savedRead()
        return dao.dailyTotals("$kind|${metric.key}|", days.first().toString(), days.last().toString())
            .associate { LocalDate.parse(it.firstDay) to it.json.takeIf(String::isNotEmpty) }
    }

    /** Adds up [metric] for the saved ones of [days], each from the records filed on it and on the day before. */
    private suspend fun addUp(metric: TotalMetric, days: Set<LocalDate>) {
        val sorted = days.filter { it <= LocalDate.now(zone) }.sorted()
        if (sorted.isEmpty()) return
        val byDay = recordsByDay(metric.key, sorted.first().minusDays(1), sorted.last(), union = true)
        val now = System.currentTimeMillis()
        val withMinutes = metric == TotalMetric.Steps
        val wearables = wearables
        val entities = sorted.filter { it in byDay }.flatMap { day ->
            val records = byDay[day.minusDays(1)].orEmpty() + byDay.getValue(day)
            val totals = dayTotals(records, day, zone, withMinutes, wearables)
            fun entity(kind: String, json: () -> String) = AggregateEntity(
                key = "$kind|${metric.key}|$day",
                fetchedAt = now,
                rangeEnd = start(day.plusDays(1)).toEpochMilli(),
                firstDay = day.toString(),
                lastDay = day.toString(),
                types = ",${metric.key},",
                // Empty: a day without any record, told apart from one that adds up to nothing.
                json = if (totals.total == null) "" else json(),
            )
            listOfNotNull(
                entity(KIND_DAY) { JSON.encodeToString(DOUBLE, totals.total!!) },
                entity(KIND_HOUR) { JSON.encodeToString(HOURS, totals.hours) },
                if (withMinutes) entity(KIND_MINUTE) { JSON.encodeToString(MINUTES, totals.minutes) } else null,
            )
        }
        dao.putAggregates(entities)
    }

    // ---- Helpers ----

    /**
     * The days [first]..[last], stopping at today (later days hold nothing yet); with [window], also starting at
     * [firstDay] (earlier days hold nothing either).
     */
    private fun days(first: LocalDate, last: LocalDate, window: Boolean = true): List<LocalDate> {
        val from = if (window) maxOf(first, firstDay) else first
        val until = minOf(last, LocalDate.now(zone))
        return generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.toList()
    }

    /** [days] as runs of consecutive days, oldest first. */
    private fun runs(days: List<LocalDate>): List<List<LocalDate>> {
        val runs = mutableListOf<MutableList<LocalDate>>()
        days.sorted().forEach { day ->
            val run = runs.lastOrNull()
            if (run != null && run.last().plusDays(1) == day) run += day else runs += mutableListOf(day)
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
        /** Health Connect's largest page: about six days of heart rate, or three weeks of minute steps. */
        private const val PAGE_SIZE = 5_000
        private const val MEMORY_DAYS = 64
        private const val MAX_CHANGE_PAGES = 20
        private const val DELETION_DAYS = 7L
        private const val DEFAULT_WINDOW_DAYS = 365L
        private const val KIND_DAY = "day"
        private const val KIND_HOUR = "hour"
        private const val KIND_MINUTE = "minute"
        private const val KEY_TOKEN = "token"
        private const val KEY_TYPES = "types"
        private const val KEY_FIRST_DAY = "first_day"
        private const val KEY_WEARABLES = "wearables"
        /** The cloud history's rows: "cloud:heart_rate", beside Health Connect's "heart_rate". */
        private const val CLOUD = "cloud:"
        /** The origin of the cloud history's records: a wearable's data (Fitbit's own servers). */
        const val CLOUD_ORIGIN = "google-health"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val RECORDS = ListSerializer(SavedRecord.serializer())
        private val DOUBLE = Double.serializer()
        private val HOURS = MapSerializer(Int.serializer(), Double.serializer())
        private val MINUTES = MapSerializer(Long.serializer(), Double.serializer())
    }
}
