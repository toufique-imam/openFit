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
import com.openfit.data.RecordLocationEntity
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    zone: ZoneId? = null,
    private val database: OpenFitDatabase = OpenFitDatabase.get(context),
) {
    private val dao = database.healthCache()
    private val app = context.applicationContext
    private val configuredZone = zone
    @Volatile private var filingZone: ZoneId? = null
    private val currentZone: ZoneId get() = filingZone ?: configuredZone ?: ZoneId.systemDefault()
    private val zoneLock = Mutex()
    private val prefs = context.getSharedPreferences("hc_changes", Context.MODE_PRIVATE)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val decoded = LruCache<String, List<SavedRecord>>(MEMORY_DAYS)
    private val dataRevision = AtomicInteger()
    val revision: Int get() = dataRevision.get()

    suspend fun copyCoverage(): List<com.openfit.data.CopyCoverage> { ensureZone(); return dao.copyCoverage() }

    /**
     * The first day with data, as far as the copy goes. Days before it hold nothing; before the import, the last
     * [DEFAULT_WINDOW_DAYS] count as possible.
     */
    var firstDay: LocalDate
        get() = prefs.getString(KEY_FIRST_DAY, null)?.let(LocalDate::parse) ?: LocalDate.now(currentZone).minusDays(DEFAULT_WINDOW_DAYS)
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
    /** [type]'s records starting in [start]..[end]; only those written by [origins] (package names) when given. */
    suspend fun <T : Record> read(type: KClass<T>, start: Instant, end: Instant, origins: Set<String>? = null): List<T> {
        ensureZone()
        if (end <= start) return emptyList()
        val days = days(day(start), day(end.minusMillis(1)))
        if (days.isEmpty()) return emptyList()
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        budget.savedRead()
        val records = recordsByDay(name, days.first(), days.last(), union = false)
        val from = start.toEpochMilli()
        val until = end.toEpochMilli()
        return days.flatMap { day -> records[day].orEmpty() }
            .filter { it.start in from until until && (origins == null || it.origin in origins) }
            .map { it.toRecord(type) }
    }

    suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter, origins: Set<String>? = null): List<T> =
        read(type, range.startTime ?: error("Instant ranges only"), range.endTime ?: error("Instant ranges only"), origins)

    /** Include intervals filed earlier; callers still clip samples/stages to the requested instant range. */
    suspend fun <T : Record> readOverlapping(type: KClass<T>, range: TimeRangeFilter): List<T> {
        ensureZone()
        val start = range.startTime ?: error("Instant ranges only")
        val end = range.endTime ?: error("Instant ranges only")
        if (end <= start) return emptyList()
        ensureRecordIndex()
        budget.savedRead()
        return recordsByDay(SAVED_TYPES.getValue(type), day(start), day(end.minusMillis(1)), union = false, overlapping = true)
            .values.flatten().filter { it.start < end.toEpochMilli() && (it.end ?: it.start) >= start.toEpochMilli() }
            .map { it.toRecord(type) }
    }

    /** [metric]'s total each day in [from]..[until] (count, metres, kcal or litres), for days with data. */
    suspend fun dailyTotals(metric: TotalMetric, from: LocalDate, until: LocalDate): Map<LocalDate, Double> {
        ensureZone()
        return totals(KIND_DAY, metric, days(from, until)).mapNotNull { (day, json) -> json?.let { day to JSON.decodeFromString(DOUBLE, it) } }.toMap()
    }

    /** [metric] per hour of [date] (hour of day → total). */
    suspend fun hourly(metric: TotalMetric, date: LocalDate): Map<Int, Double> = hourlyByDay(metric, date, date)[date].orEmpty()

    suspend fun hourlyByDay(metric: TotalMetric, from: LocalDate, until: LocalDate): Map<LocalDate, Map<Int, Double>> {
        ensureZone()
        return totals(KIND_HOUR, metric, days(from, until)).mapNotNull { (day, json) -> json?.let { day to JSON.decodeFromString(HOURS, it) } }.toMap()
    }

    /** Steps per minute of [date] (epoch minute → steps), minutes with steps only. */
    suspend fun minuteSteps(date: LocalDate): Map<Long, Double> {
        ensureZone()
        return totals(KIND_MINUTE, TotalMetric.Steps, days(date, date))[date]?.let { JSON.decodeFromString(MINUTES, it) }.orEmpty()
    }

    /**
     * A workout's distance and energy over [start, end), added up from the copied records with each moment counted
     * once; null when neither is copied for its days.
     */
    suspend fun sessionTotals(start: Instant, end: Instant): SessionTotals? = sessionTotals(listOf(start to end)).single()

    /** Load the displayed range once, then apportion its amounts to each workout. */
    suspend fun sessionTotals(intervals: List<Pair<Instant, Instant>>): List<SessionTotals?> {
        ensureZone()
        val valid = intervals.filter { (start, end) -> end > start }
        if (valid.isEmpty()) return intervals.map { null }
        val from = day(valid.minOf { it.first }).minusDays(1)
        val until = day(valid.maxOf { it.second }.minusMillis(1))
        val wearables = wearables
        val distance = recordsByDay(TotalMetric.DistanceMeters.key, from, until, union = true)
        val calories = recordsByDay(TotalMetric.CaloriesOutKcal.key, from, until, union = true)
        budget.savedRead()
        return intervals.map { (start, end) ->
            if (end <= start) return@map null
            val dates = generateSequence(day(start).minusDays(1)) { it.plusDays(1) }
                .takeWhile { it <= day(end.minusMillis(1)) }.toList()
            fun total(records: Map<LocalDate, List<SavedRecord>>): Double? =
                merge(dates.flatMap { records[it].orEmpty() }, start.toEpochMilli(), end.toEpochMilli(), wearables)
                    .takeIf { it.isNotEmpty() }?.sumOf { it.value }
            val meters = total(distance)
            val kcal = total(calories)
            if (meters == null && kcal == null) null else SessionTotals(meters, kcal)
        }
    }

    // ---- Syncs: reading Health Connect ----

    /** Reads [first]..[last] of [type] again (or, without [force], the days not saved yet). */
    suspend fun <T : Record> syncRecords(type: KClass<T>, first: LocalDate, last: LocalDate, force: Boolean, refreshedAfter: Long? = null, onDays: (Int) -> Unit = {}) =
        syncDays(type, days(first, last, window = false), force, refreshedAfter, onDays)

    /**
     * Reads [days] of [type] again (or, without [force], those not saved yet): each run of consecutive days as one
     * range, newest run first. [onDays] hears how many days are done as they are saved.
     */
    suspend fun <T : Record> syncDays(type: KClass<T>, days: Collection<LocalDate>, force: Boolean, refreshedAfter: Long? = null, onDays: (Int) -> Unit = {}) {
        ensureZone()
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        val today = LocalDate.now(currentZone)
        val wanted = days.filter { it <= today }.toSortedSet()
        if (wanted.isEmpty()) return
        val missing = if (force) {
            if (refreshedAfter == null) wanted.toList() else {
                val refreshed = dao.daysRefreshedSince(name, wanted.first().toString(), wanted.last().toString(), refreshedAfter).toSet()
                wanted.filter { it.toString() !in refreshed }
            }
        } else {
            val saved = dao.savedDays(name, wanted.first().toString(), wanted.last().toString()).mapTo(HashSet(), LocalDate::parse)
            wanted.filter { it !in saved }
        }
        if (missing.size < wanted.size) onDays(wanted.size - missing.size)
        runs(missing).asReversed().forEach { run -> fetchRun(type, name, run, onDays) }
    }

    /** The first day [type] has data, from one record read oldest first; null without any. */
    suspend fun <T : Record> earliestDay(type: KClass<T>): LocalDate? {
        ensureZone()
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
        ensureZone()
        ensureRecordIndex()
        val types = SAVED_TYPES.keys.filter { HealthConnectGateway.read(it) in granted }.toSet()
        if (types.isEmpty()) return emptyMap()
        val typeKey = types.mapNotNull { SAVED_TYPES[it] }.sorted().joinToString(",")
        var token = prefs.getString(KEY_TOKEN, null)?.takeIf { prefs.getString(KEY_TYPES, null) == typeKey }
        val changed = mutableMapOf<KClass<out Record>, MutableSet<LocalDate>>()
        val pending = prefs.getString(KEY_PENDING, null)?.let { JSON.decodeFromString(PENDING, it) }.orEmpty()
        pending.forEach { (name, dates) ->
            SAVED_TYPES.entries.firstOrNull { it.value == name && it.key in types }?.key?.let { type ->
                changed.getOrPut(type) { mutableSetOf() }.addAll(dates.map(LocalDate::parse))
            }
        }
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
            val ids = response.changes.map { change -> when (change) {
                is UpsertionChange -> change.record.metadata.id
                is DeletionChange -> change.recordId
                else -> ""
            } }
            val previous = ids.chunked(900).flatMap { dao.locations(it) }.groupBy { it.id }
            response.changes.forEach { change ->
                val id = when (change) {
                    is UpsertionChange -> change.record.metadata.id
                    is DeletionChange -> change.recordId
                    else -> ""
                }
                previous[id].orEmpty().forEach { location ->
                    SAVED_TYPES.entries.firstOrNull { it.value == location.type && it.key in types }?.key?.let { type ->
                        changed.getOrPut(type) { mutableSetOf() }.addAll(days(LocalDate.parse(location.day), LocalDate.parse(location.lastDay), window = false))
                    }
                }
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record.toSaved()
                        changed.getOrPut(change.record::class) { mutableSetOf() }.addAll(days(day(Instant.ofEpochMilli(record.start)), day(Instant.ofEpochMilli(record.end ?: record.start)), window = false))
                    }
                    // Indexed IDs identify the original type and all affected days, even years ago.
                    // Only an unknown ID needs the conservative recent-days fallback.
                    is DeletionChange -> if (previous[id].isNullOrEmpty()) types.forEach { type ->
                        changed.getOrPut(type) { mutableSetOf() } += days(LocalDate.now(currentZone).minusDays(DELETION_DAYS), LocalDate.now(currentZone), window = false)
                    }
                }
            }
            token = response.nextChangesToken
            if (!response.hasMore) break
        }
        if (token == null) {
            token = budget.call("changes token") { client().getChangesToken(ChangesTokenRequest(types)) }
            budget.note { "a token for ${types.size} types: the next sync asks what changed since now" }
        }
        // Journal the affected days together with the token. They survive a failed copy or a process restart.
        if (!complete && changed.isNotEmpty()) types.forEach { type ->
            changed.getOrPut(type) { mutableSetOf() }.addAll(days(LocalDate.now(currentZone).minusDays(DELETION_DAYS), LocalDate.now(currentZone), window = false))
        }
        val journal = pending.filterKeys { name -> types.none { SAVED_TYPES[it] == name } } +
            changed.mapKeys { SAVED_TYPES.getValue(it.key) }.mapValues { (_, dates) -> dates.map(LocalDate::toString) }
        check(prefs.edit().putString(KEY_TOKEN, token).putString(KEY_TYPES, typeKey)
            .putString(KEY_PENDING, JSON.encodeToString(PENDING, journal)).commit())
        return if (complete || changed.isNotEmpty()) changed else null
    }

    /** Only called after all affected records and derived values were copied successfully. */
    fun acknowledgeChanges(granted: Set<String>) {
        val pending = prefs.getString(KEY_PENDING, null)?.let { JSON.decodeFromString(PENDING, it) }.orEmpty()
        val processed = SAVED_TYPES.filterKeys { HealthConnectGateway.read(it) in granted }.values.toSet()
        check(prefs.edit().putString(KEY_PENDING, JSON.encodeToString(PENDING, pending.filterKeys { it !in processed })).commit())
    }

    private suspend fun ensureRecordIndex() = withContext(Dispatchers.IO) {
        zoneLock.withLock {
            lock("record_index").withLock {
                if (prefs.getBoolean(KEY_INDEX_READY, false)) return@withLock
                (SAVED_TYPES.values + CLOUD_INTERVAL_TYPES.map { CLOUD + it }).forEach { name ->
                    dao.savedDays(name, "0001-01-01", "9999-12-31").sorted().chunked(30).forEach { dates ->
                        dao.putLocations(dao.days(name, dates.first(), dates.last()).flatMap(::locations))
                    }
                }
                check(prefs.edit().putBoolean(KEY_INDEX_READY, true).commit())
            }
        }
    }

    private fun locations(row: RecordDayEntity): List<RecordLocationEntity> =
        RecordPayload.records(row).mapNotNull { record ->
            val last = day(Instant.ofEpochMilli(record.end ?: record.start)).toString()
            if (row.type.startsWith(CLOUD) && last <= row.day) null
            else RecordLocationEntity(row.type, record.id, row.day, last)
        }

    /** Forgets everything saved, so the next sync reads it all again (Delete everything). */
    suspend fun clear(): Unit = zoneLock.withLock {
        dao.clearDays()
        dao.clearLocations()
        dao.clearAggregates()
        decoded.evictAll()
        prefs.edit().clear().apply()
        dataRevision.incrementAndGet()
    }

    /** Forgets the added-up totals: [syncDays] adds them up again as it saves days. */
    suspend fun clearTotals() = dao.clearAggregates()

    /** Upgrade old payloads in bounded batches. A cancelled pass can resume safely without remote reads. */
    suspend fun compactRecords() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("payload_compacted_v1", false)) return@withContext
        (SAVED_TYPES.values + SAVED_TYPES.values.map { CLOUD + it }).forEach { type ->
            zoneLock.withLock {
                lock(type).withLock {
                    dao.savedDays(type, "0001-01-01", "9999-12-31").sorted().chunked(30).forEach { days ->
                        val changed = dao.days(type, days.first(), days.last()).mapNotNull { row ->
                            RecordPayload.compact(row).takeIf { it !== row }
                        }
                        if (changed.isNotEmpty()) dao.putDays(changed)
                    }
                }
            }
        }
        check(prefs.edit().putBoolean("payload_compacted_v1", true).commit())
    }

    /**
     * Adds up [first]..[last] again from the saved records, with no Health Connect call: after an import, whose amounts
     * may have been added up before its heart rate told which apps are wearables.
     */
    suspend fun addUpAgain(first: LocalDate, last: LocalDate) {
        ensureZone()
        zoneLock.withLock { rebuildTotals(first, last) }
    }

    private suspend fun rebuildTotals(first: LocalDate, last: LocalDate) {
        val days = days(first, last, window = false).toSet()
        TotalMetric.entries.forEach { metric -> lock(metric.key).withLock { addUp(metric, days) } }
    }

    // ---- The cloud history (Google Health API) ----

    /**
     * Saves Fitbit's own history from the Google Health API for [days] (each, empty ones too, so they count as
     * copied), beside Health Connect's copy rather than over it: a later Health Connect read never erases it. Pages
     * then see it where Health Connect has nothing ([recordsByDay]); the amounts are added up again from both.
     */
    suspend fun saveCloud(type: KClass<out Record>, days: Collection<LocalDate>, records: Map<LocalDate, List<SavedRecord>>, sourceZone: ZoneId = currentZone) {
        ensureZone()
        val name = SAVED_TYPES[type] ?: error("Unsupported record ${type.simpleName}")
        val now = System.currentTimeMillis()
        save(type, days.map { day ->
            RecordPayload.row(CLOUD + name, day.toString(), now, records[day].orEmpty().sortedBy { it.start })
        }, sourceZone)
    }

    /** Forgets the cloud history (Health Connect's copy stays), and adds the amounts up again without it. */
    suspend fun clearCloud() {
        ensureZone()
        zoneLock.withLock {
            dao.clearDaysOf(CLOUD)
            dao.clearLocationsOf(CLOUD)
            decoded.evictAll()
            dao.clearAggregates()
            rebuildTotals(firstDay, LocalDate.now(currentZone))
            dataRevision.incrementAndGet()
        }
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
     * records take precedence for overlapping observations, with distinct cloud observations filling gaps.
     */
    private suspend fun recordsByDay(name: String, from: LocalDate, until: LocalDate, union: Boolean, overlapping: Boolean = false): Map<LocalDate, List<SavedRecord>> {
        suspend fun rows(type: String) = if (overlapping) dao.overlappingDays(type, from.toString(), until.toString()) else dao.days(type, from.toString(), until.toString())
        val own = rows(name).associate { LocalDate.parse(it.day) to decode(it) }
        val cloud = rows(CLOUD + name).associate { LocalDate.parse(it.day) to decode(it) }
        if (cloud.isEmpty()) return own
        if (overlapping && !union) return preferredRecords(name, own.values.flatten(), cloud.values.flatten())
            .groupBy { day(Instant.ofEpochMilli(it.start)) }
        return (own.keys + cloud.keys).associateWith { day ->
            val mine = own[day].orEmpty()
            if (union) mine + cloud[day].orEmpty() else preferredRecords(name, mine, cloud[day].orEmpty())
        }
    }

    /**
     * Reads [run] (consecutive days) newest first, a page at a time, saving each day as soon as the pages have passed
     * it: records come back by start, so the days after a page's oldest record are complete.
     */
    private suspend fun <T : Record> fetchRun(type: KClass<T>, name: String, run: List<LocalDate>, onDays: (Int) -> Unit) {
        val first = run.first()
        val queryZone = currentZone
        val range = TimeRangeFilter.between(first.atStartOfDay(queryZone).toInstant(), run.last().plusDays(1).atStartOfDay(queryZone).toInstant())
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
                val day = Instant.ofEpochMilli(saved.start).atZone(queryZone).toLocalDate()
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
                    RecordPayload.row(name, day.toString(), fetchedAt, records)
                }, queryZone)
                onDays(days.size)
                next = complete.minusDays(1)
            }
        } while (pageToken != null)
    }

    /** Saves [rows], then adds up their days again, and each next day (an interval can run past midnight). */
    private suspend fun save(type: KClass<out Record>, rows: List<RecordDayEntity>, sourceZone: ZoneId = currentZone) {
        if (rows.isEmpty()) return
        zoneLock.withLock {
            check((configuredZone ?: ZoneId.systemDefault()) == sourceZone && prefs.getString("filed_zone", null) == sourceZone.id) {
                "Time zone changed during the copy; retry in the new time zone"
            }
            val metric = TotalMetric.entries.firstOrNull { it.record == type }
            lock(SAVED_TYPES.getValue(type)).withLock {
                database.withTransaction {
                    dao.putDays(rows)
                    if (!rows.first().type.startsWith(CLOUD) || rows.first().type.removePrefix(CLOUD) in CLOUD_INTERVAL_TYPES) {
                        dao.clearLocations(rows.first().type, rows.map { it.day })
                        dao.putLocations(rows.flatMap(::locations))
                    }
                }
                dataRevision.incrementAndGet()
                if (metric != null) {
                    val days = rows.map { LocalDate.parse(it.day) }
                    addUp(metric, (days + days.map { it.plusDays(1) }).toSortedSet())
                }
            }
        }
    }

    // ---- Added-up totals ----

    /** [metric]'s saved [kind] totals for [days]: the JSON, or null for a day without data. */
    private suspend fun totals(kind: String, metric: TotalMetric, days: List<LocalDate>): Map<LocalDate, String?> {
        ensureZone()
        if (days.isEmpty()) return emptyMap()
        budget.savedRead()
        return dao.dailyTotals("$kind|${metric.key}|${days.first()}", "$kind|${metric.key}|${days.last()}")
            .associate { LocalDate.parse(it.firstDay) to it.json.takeIf(String::isNotEmpty) }
    }

    /** Adds up [metric] for the saved ones of [days], each from the records filed on it and on the day before. */
    private suspend fun addUp(metric: TotalMetric, days: Set<LocalDate>) {
        val sorted = days.filter { it <= LocalDate.now(currentZone) }.sorted()
        if (sorted.isEmpty()) return
        val byDay = recordsByDay(metric.key, sorted.first().minusDays(1), sorted.last(), union = true)
        val now = System.currentTimeMillis()
        val withMinutes = metric == TotalMetric.Steps
        val wearables = wearables
        val entities = sorted.filter { it in byDay }.flatMap { day ->
            val records = byDay[day.minusDays(1)].orEmpty() + byDay.getValue(day)
            val totals = dayTotals(records, day, currentZone, withMinutes, wearables,
                additive = metric == TotalMetric.CaloriesInKcal || metric == TotalMetric.WaterLiters)
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
        val until = minOf(last, LocalDate.now(currentZone))
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
        return decoded.get(key) ?: RecordPayload.records(row).also { decoded.put(key, it) }
    }

    private fun day(instant: Instant): LocalDate = instant.atZone(currentZone).toLocalDate()

    private fun start(day: LocalDate): Instant = day.atStartOfDay(currentZone).toInstant()

    private fun lock(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    /** Refile the existing copy by timestamp after travel, without downloading its history again. */
    private suspend fun ensureZone() {
        val requested = configuredZone ?: ZoneId.systemDefault()
        if (prefs.getString("filed_zone", null) == requested.id) return
        zoneLock.withLock {
            val previous = prefs.getString("filed_zone", null)
            if (previous == requested.id) return@withLock
            if (previous == null) {
                check(prefs.edit().putString("filed_zone", requested.id).commit())
                return@withLock
            }
            val oldZone = ZoneId.of(previous)
            filingZone = requested
            try {
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        (SAVED_TYPES.values + SAVED_TYPES.values.map { CLOUD + it }).forEach { type ->
                            val staged = "refile:$type"
                            dao.clearType(staged)
                            dao.savedDays(type, "0001-01-01", "9999-12-31").sorted().chunked(30).forEach { chunk ->
                                dao.days(type, chunk.first(), chunk.last()).forEach { row ->
                                    val grouped = RecordPayload.records(row).groupBy { day(Instant.ofEpochMilli(it.start)).toString() }
                                    val emptyDay = LocalDate.parse(row.day).atStartOfDay(oldZone).withZoneSameInstant(requested).toLocalDate().toString()
                                    (grouped.keys + emptyDay).forEach { target ->
                                        val saved = dao.days(staged, target, target).singleOrNull()
                                        val records = saved?.let(RecordPayload::records).orEmpty() + grouped[target].orEmpty()
                                        dao.putDays(listOf(RecordPayload.row(staged, target, minOf(row.fetchedAt, saved?.fetchedAt ?: Long.MAX_VALUE), records.sortedBy { it.start })))
                                    }
                                }
                            }
                            dao.clearType(type)
                            dao.renameType(staged, type)
                        }
                        dao.clearLocations()
                        dao.clearAggregates()
                        database.snapshots().clear()
                    }
                    decoded.evictAll()
                    DayLoadCache(app, database).clear()
                    SlotCache(app, database).clear()
                    firstDay = minOf(firstDay, firstDay.atStartOfDay(oldZone).withZoneSameInstant(requested).toLocalDate())
                    val dates = days(firstDay, LocalDate.now(requested), window = false).toSet()
                    TotalMetric.entries.forEach { addUp(it, dates) }
                    check(prefs.edit().putBoolean(KEY_INDEX_READY, false).putString("filed_zone", requested.id).commit())
                    dataRevision.incrementAndGet()
                }
            } finally { filingZone = null }
        }
    }

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
        private const val KEY_PENDING = "pending"
        private const val KEY_INDEX_READY = "index_ready"
        private const val KEY_TYPES = "types"
        private const val KEY_FIRST_DAY = "first_day"
        private const val KEY_WEARABLES = "wearables"
        /** The cloud history's rows: "cloud:heart_rate", beside Health Connect's "heart_rate". */
        private const val CLOUD = "cloud:"
        private val CLOUD_INTERVAL_TYPES = setOf("heart_rate", "sleep", "exercise")
        /** The origin of the cloud history's records: a wearable's data (Fitbit's own servers). */
        const val CLOUD_ORIGIN = "google-health"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val DOUBLE = Double.serializer()
        private val HOURS = MapSerializer(Int.serializer(), Double.serializer())
        private val MINUTES = MapSerializer(Long.serializer(), Double.serializer())
        private val PENDING = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}
