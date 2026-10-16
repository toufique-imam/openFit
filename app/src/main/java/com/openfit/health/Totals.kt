package com.openfit.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/** An amount recorded over [start, end) (epoch milliseconds); a moment when they are equal. */
data class Piece(val start: Long, val end: Long, val value: Double)

/**
 * [records] added up as Health Connect adds up several apps' records: each moment counted once, from the highest-ranked
 * app that recorded it, and the time it did not record filled from the next. Health Connect ranks apps by the user's
 * priority list, which it does not share with apps, so openFit ranks the [wearables] (the apps that write heart rate:
 * a watch's or band's) first, then the rest by how much each recorded within [from, until). A phone app that copies the
 * watch's data, sometimes a little more, so comes after it, and still fills the hours the watch was off: on 38 days of
 * Fitbit and Google Fit records this matched Health Connect's totals to 0.01% (amount alone: off by up to 1.6%).
 * Records are cut to [from, until), their amounts in proportion to the time kept; an app's records that overlap each
 * other (one written twice) count once.
 */
fun merge(records: List<SavedRecord>, from: Long, until: Long, wearables: Set<String> = emptySet()): List<Piece> {
    val clipped = records.mapNotNull { record -> clip(record, from, until)?.let { record.origin to it } }
    val amount = clipped.groupBy({ it.first }, { it.second.value }).mapValues { (_, values) -> values.sum() }
    val ordered = clipped.sortedWith(
        compareBy<Pair<String, Piece>> { it.first !in wearables }
            .thenByDescending { amount.getValue(it.first) }
            .thenBy { it.first }
            .thenBy { it.second.start },
    )
    val coverage = Coverage()
    val pieces = mutableListOf<Piece>()
    ordered.forEach { (_, piece) -> coverage.take(piece, pieces) }
    return pieces
}

/** The part of [record] within [from, until), its amount in proportion; null outside it or without an amount. */
private fun clip(record: SavedRecord, from: Long, until: Long): Piece? {
    val value = record.value ?: return null
    val start = record.start
    val end = record.end ?: start
    if (end <= start) return if (start in from until until) Piece(start, start, value) else null
    val clippedStart = maxOf(start, from)
    val clippedEnd = minOf(end, until)
    if (clippedEnd <= clippedStart) return null
    return Piece(clippedStart, clippedEnd, value * (clippedEnd - clippedStart) / (end - start))
}

/** The time already counted: each new piece adds only the part not counted yet. */
private class Coverage {
    private val spans = TreeMap<Long, Long>()
    private val moments = HashSet<Long>()

    fun take(piece: Piece, out: MutableList<Piece>) {
        if (piece.end <= piece.start) {
            val around = spans.floorEntry(piece.start)
            if ((around == null || around.value <= piece.start) && moments.add(piece.start)) out += piece
            return
        }
        val length = (piece.end - piece.start).toDouble()
        var cursor = piece.start
        var span = spans.floorEntry(piece.start)?.takeIf { it.value > piece.start } ?: spans.ceilingEntry(piece.start)
        while (span != null && span.key < piece.end) {
            if (span.key > cursor) out += Piece(cursor, span.key, piece.value * (span.key - cursor) / length)
            cursor = maxOf(cursor, span.value)
            span = spans.higherEntry(span.key)
        }
        if (cursor < piece.end) out += Piece(cursor, piece.end, piece.value * (piece.end - cursor) / length)
        add(piece.start, piece.end)
    }

    private fun add(start: Long, end: Long) {
        var from = start
        var until = end
        spans.floorEntry(from)?.takeIf { it.value >= from }?.let {
            from = it.key
            until = maxOf(until, it.value)
        }
        while (true) {
            val next = spans.ceilingEntry(from) ?: break
            if (next.key > until) break
            until = maxOf(until, next.value)
            spans.remove(next.key)
        }
        spans[from] = until
    }
}

/** [pieces] summed per bucket, each piece shared out in proportion to its time in each; [bucket] gives a moment's. */
fun spread(pieces: List<Piece>, bucket: (Long) -> Pair<Long, Long>): Map<Long, Double> {
    val sums = HashMap<Long, Double>()
    pieces.forEach { piece ->
        if (piece.end <= piece.start) {
            val (start, _) = bucket(piece.start)
            sums[start] = (sums[start] ?: 0.0) + piece.value
            return@forEach
        }
        val length = (piece.end - piece.start).toDouble()
        var cursor = piece.start
        while (cursor < piece.end) {
            val (start, end) = bucket(cursor)
            val until = minOf(end, piece.end)
            sums[start] = (sums[start] ?: 0.0) + piece.value * (until - cursor) / length
            cursor = until
        }
    }
    return sums
}

/** A day's totals of one metric: in all (null without any record), per hour of the day, and per epoch minute. */
data class DayTotals(val total: Double?, val hours: Map<Int, Double>, val minutes: Map<Long, Double>)

/**
 * [day]'s totals from [records]: those filed on it and on the day before, since an interval can run past midnight.
 * Minutes are worked out only when [withMinutes]; [wearables] as for [merge].
 */
fun dayTotals(records: List<SavedRecord>, day: LocalDate, zone: ZoneId, withMinutes: Boolean, wearables: Set<String> = emptySet()): DayTotals {
    val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val pieces = merge(records, from, until, wearables)
    if (pieces.isEmpty()) return DayTotals(null, emptyMap(), emptyMap())
    val hours = spread(pieces) { time ->
        val start = Instant.ofEpochMilli(time).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        start.toInstant().toEpochMilli() to start.plusHours(1).toInstant().toEpochMilli()
    }.entries.groupBy({ Instant.ofEpochMilli(it.key).atZone(zone).hour }, { it.value }).mapValues { (_, values) -> values.sum() }
    val minutes = if (!withMinutes) emptyMap() else spread(pieces) { time ->
        val start = time - Math.floorMod(time, MINUTE_MS)
        start to start + MINUTE_MS
    }.mapKeys { it.key / MINUTE_MS }
    return DayTotals(pieces.sumOf { it.value }, hours.filterValues { it > 0 }, minutes.filterValues { it > 0 })
}

private const val MINUTE_MS = 60_000L
