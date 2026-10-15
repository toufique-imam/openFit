package com.openfit.googlehealth

import com.openfit.domain.Exercise
import com.openfit.domain.Reading
import com.openfit.domain.SleepSummary
import com.openfit.domain.ZoneMinutes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun JSONObject.firstRollup(field: String): JSONObject? =
    optJSONArray("rollupDataPoints")?.objects()?.firstNotNullOfOrNull { it.optJSONObject(field) }

fun JSONObject.points(field: String): List<JSONObject> =
    (optJSONArray("dataPoints") ?: optJSONArray("reconciledDataPoints"))
        ?.objects()
        ?.mapNotNull { it.optJSONObject(field) }
        .orEmpty()

fun parseHourly(response: JSONObject, field: String, value: String, zone: ZoneId): Map<Int, Long> =
    response.optJSONArray("rollupDataPoints")
        ?.objects()
        ?.mapNotNull { point ->
            val start = point.instant("startTime") ?: return@mapNotNull null
            val amount = point.optJSONObject(field)?.number(value) ?: return@mapNotNull null
            start.atZone(zone).hour to amount.toLong()
        }
        ?.groupBy({ it.first }, { it.second })
        ?.mapValues { (_, amounts) -> amounts.sum() }
        .orEmpty()

fun parseHeartRateRange(rollup: JSONObject): IntRange? {
    val min = rollup.number("beatsPerMinuteMin") ?: return null
    val max = rollup.number("beatsPerMinuteMax") ?: return null
    return min.toInt()..max.toInt()
}

// Rollup sums are zone-minute points, so cardio and peak minutes are already counted twice.
fun parseZoneMinutes(rollup: JSONObject): ZoneMinutes = ZoneMinutes(
    fatBurn = rollup.number("sumInFatBurnHeartZone")?.toInt() ?: 0,
    cardio = (rollup.number("sumInCardioHeartZone")?.toInt() ?: 0) / 2,
    peak = (rollup.number("sumInPeakHeartZone")?.toInt() ?: 0) / 2,
)

fun parseDailyRollups(response: JSONObject, field: String): Map<LocalDate, JSONObject> =
    response.optJSONArray("rollupDataPoints")
        ?.objects()
        ?.mapNotNull { point ->
            val date = civilDate(point.optJSONObject("civilStartTime")?.optJSONObject("date")) ?: return@mapNotNull null
            point.optJSONObject(field)?.let { date to it }
        }
        ?.toMap()
        .orEmpty()

fun dailyValues(points: List<JSONObject>, value: (JSONObject) -> Double?): Map<LocalDate, Double> =
    points
        .mapNotNull { point -> civilDate(point.optJSONObject("date"))?.let { date -> value(point)?.let { date to it } } }
        .toMap()

fun sleepMinutesByEndDate(sessions: List<JSONObject>, zone: ZoneId): Map<LocalDate, Int> =
    sessions
        .mapNotNull { session ->
            val end = session.optJSONObject("interval")?.instant("endTime") ?: return@mapNotNull null
            val minutes = session.optJSONObject("summary")?.number("minutesAsleep") ?: return@mapNotNull null
            end.atZone(zone).toLocalDate() to minutes.toInt()
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, minutes) -> minutes.sum() }

fun latestDaily(points: List<JSONObject>, zone: ZoneId, value: (JSONObject) -> Double?): Reading<Double>? =
    points
        .mapNotNull { point -> civilDate(point.optJSONObject("date"))?.let { date -> value(point)?.let { Reading(it, endOf(date, zone)) } } }
        .maxByOrNull { it.time }

fun latestSample(points: List<JSONObject>, value: (JSONObject) -> Double?): Reading<Double>? =
    points
        .mapNotNull { point ->
            val time = point.optJSONObject("sampleTime")?.instant("physicalTime") ?: return@mapNotNull null
            value(point)?.let { Reading(it, time) }
        }
        .maxByOrNull { it.time }

fun parseSleep(sessions: List<JSONObject>): SleepSummary? {
    val parsed = sessions.mapNotNull { session ->
        val end = session.optJSONObject("interval")?.instant("endTime") ?: return@mapNotNull null
        val minutes = session.optJSONObject("summary")?.number("minutesAsleep") ?: return@mapNotNull null
        Duration.ofMinutes(minutes.toLong()) to end
    }
    if (parsed.isEmpty()) return null
    return SleepSummary(parsed.fold(Duration.ZERO) { total, (asleep, _) -> total + asleep }, parsed.maxOf { it.second })
}

fun parseExercises(sessions: List<JSONObject>): List<Exercise> = sessions.mapNotNull { session ->
    val interval = session.optJSONObject("interval") ?: return@mapNotNull null
    val start = interval.instant("startTime") ?: return@mapNotNull null
    val end = interval.instant("endTime") ?: return@mapNotNull null
    val name = session.optString("displayName").ifBlank { readableEnum(session.optString("exerciseType")) }
    Exercise(name, start, end)
}

fun JSONObject.number(key: String): Double? = when (val value = opt(key)) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

private fun JSONObject.instant(key: String): Instant? =
    optString(key).takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun civilDate(date: JSONObject?): LocalDate? {
    if (date == null || !date.has("year")) return null
    return LocalDate.of(date.getInt("year"), date.getInt("month"), date.getInt("day"))
}

private fun endOf(date: LocalDate, zone: ZoneId): Instant =
    minOf(date.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1), Instant.now())

private fun readableEnum(value: String): String =
    value.ifBlank { "EXERCISE" }.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
