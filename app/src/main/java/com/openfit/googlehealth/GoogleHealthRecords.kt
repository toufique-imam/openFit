package com.openfit.googlehealth

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import com.openfit.health.HealthStore
import com.openfit.health.SavedRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Google Health API data points as openFit saves Health Connect records ([SavedRecord]), so pages read Fitbit's cloud
// history exactly as they read Health Connect's copy. Every record's origin is [HealthStore.CLOUD_ORIGIN].

private const val ORIGIN = HealthStore.CLOUD_ORIGIN

/** A rollUp answer's windows that hold [field]: (start, end, the field's object). */
fun JSONObject.rollupWindows(field: String): List<Triple<Instant, Instant, JSONObject>> =
    optJSONArray("rollupDataPoints")?.objects()?.mapNotNull { point ->
        val start = point.instantOf("startTime") ?: return@mapNotNull null
        val end = point.instantOf("endTime") ?: return@mapNotNull null
        point.optJSONObject(field)?.let { Triple(start, end, it) }
    }.orEmpty()

/**
 * Heart rate from one-minute rollUp windows: the minute's average as a sample in its middle, an hour of minutes per
 * record (24 records a day, not 1,440).
 */
fun heartRateRecords(windows: List<Triple<Instant, Instant, JSONObject>>, zone: ZoneId): List<SavedRecord> =
    windows.mapNotNull { (start, end, rate) ->
        val bpm = rate.number("beatsPerMinuteAvg") ?: rate.number("beatsPerMinuteAverage") ?: rate.number("averageBeatsPerMinute")
            ?: run {
                val min = rate.number("beatsPerMinuteMin") ?: return@run null
                val max = rate.number("beatsPerMinuteMax") ?: return@run null
                (min + max) / 2
            }
            ?: return@mapNotNull null
        // Health Connect's range: a value outside it would fail when the record is read back.
        if (bpm !in 1.0..300.0) return@mapNotNull null
        Instant.ofEpochMilli((start.toEpochMilli() + end.toEpochMilli()) / 2) to bpm
    }
        .groupBy { (time, _) -> time.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant() }
        .map { (hour, samples) ->
            val sorted = samples.sortedBy { it.first }
            val offset = zone.rules.getOffset(hour).totalSeconds
            SavedRecord(
                id = "gh-hr-${hour.toEpochMilli()}",
                origin = ORIGIN,
                start = sorted.first().first.toEpochMilli(),
                startOffset = offset,
                // Health Connect wants every sample before the end.
                end = sorted.last().first.toEpochMilli() + 1_000,
                endOffset = offset,
                times = LongArray(sorted.size) { sorted[it].first.toEpochMilli() },
                values = DoubleArray(sorted.size) { sorted[it].second },
            )
        }

/** Interval amounts (steps' count, distance's millimetres): [scale] turns the value into openFit's unit. */
fun intervalRecords(points: List<JSONObject>, field: String, valueKey: String, scale: Double = 1.0): List<SavedRecord> =
    points.mapNotNull { point ->
        val interval = point.optJSONObject("interval") ?: return@mapNotNull null
        val value = point.number(valueKey) ?: return@mapNotNull null
        interval.record("gh-$field", value * scale)
    }

/**
 * RollUp windows' sums (steps' countSum, distance's millimetersSum, total calories' kcalSum), the windows with something
 * in them; [scale] turns the sum into openFit's unit.
 */
fun windowRecords(windows: List<Triple<Instant, Instant, JSONObject>>, field: String, sumKey: String, zone: ZoneId, scale: Double = 1.0): List<SavedRecord> =
    windows.mapNotNull { (start, end, sums) ->
        val value = sums.number(sumKey)?.takeIf { it > 0 }?.times(scale) ?: return@mapNotNull null
        SavedRecord(
            id = "gh-$field-${start.toEpochMilli()}",
            origin = ORIGIN,
            start = start.toEpochMilli(),
            startOffset = zone.rules.getOffset(start).totalSeconds,
            end = end.toEpochMilli(),
            endOffset = zone.rules.getOffset(end).totalSeconds,
            value = value,
        )
    }

/**
 * One value a day (resting heart rate, HRV, SpO₂, breathing rate, VO₂ max), as a reading at [hour] of its date; values
 * outside Health Connect's [range] for the type are left out (reading the record back would fail).
 */
fun dailyRecords(
    points: List<JSONObject>,
    field: String,
    valueKey: String,
    zone: ZoneId,
    hour: Int,
    range: ClosedFloatingPointRange<Double>,
    kind: Int? = null,
): List<SavedRecord> =
    points.mapNotNull { point ->
        val date = civilDate(point.optJSONObject("date")) ?: return@mapNotNull null
        val value = point.number(valueKey)?.takeIf { it in range } ?: return@mapNotNull null
        val time = date.atTime(hour, 0).atZone(zone)
        SavedRecord(id = "gh-$field-$date", origin = ORIGIN, start = time.toInstant().toEpochMilli(), startOffset = time.offset.totalSeconds, value = value, kind = kind)
    }

/** VO₂ max a day, estimated by the watch as Fitbit's cardio fitness score. */
fun vo2MaxRecords(points: List<JSONObject>, zone: ZoneId): List<SavedRecord> =
    dailyRecords(points, "vo2", "vo2Max", zone, hour = 12, range = 0.0..100.0, kind = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER)

/** A night's skin temperature against its baseline: the baseline, and the night's difference from it at 3am. */
fun skinTemperatureRecords(points: List<JSONObject>, zone: ZoneId): List<SavedRecord> =
    points.mapNotNull { point ->
        val date = civilDate(point.optJSONObject("date")) ?: return@mapNotNull null
        val nightly = point.number("nightlyTemperatureCelsius") ?: return@mapNotNull null
        val baseline = point.number("baselineTemperatureCelsius") ?: return@mapNotNull null
        val start = date.atStartOfDay(zone)
        SavedRecord(
            id = "gh-skin-$date",
            origin = ORIGIN,
            start = start.toInstant().toEpochMilli(),
            startOffset = start.offset.totalSeconds,
            end = start.plusHours(6).toInstant().toEpochMilli(),
            endOffset = start.plusHours(6).offset.totalSeconds,
            value = baseline,
            kind = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
            times = longArrayOf(start.plusHours(3).toInstant().toEpochMilli()),
            values = doubleArrayOf(nightly - baseline),
        )
    }

/** Readings at a moment (weight in grams, body fat): [scale] turns the value into openFit's unit, kept within [range]. */
fun sampleRecords(points: List<JSONObject>, field: String, valueKey: String, range: ClosedFloatingPointRange<Double>, scale: Double = 1.0): List<SavedRecord> =
    points.mapNotNull { point ->
        val sample = point.optJSONObject("sampleTime") ?: return@mapNotNull null
        val time = sample.instantOf("physicalTime") ?: return@mapNotNull null
        val value = point.number(valueKey)?.times(scale)?.takeIf { it in range } ?: return@mapNotNull null
        SavedRecord(id = "gh-$field-${time.toEpochMilli()}", origin = ORIGIN, start = time.toEpochMilli(), startOffset = sample.offsetSeconds("utcOffset"), value = value)
    }

/** Food logged: each entry's energy (kcal). */
fun nutritionRecords(points: List<JSONObject>): List<SavedRecord> = points.mapNotNull { point ->
    val interval = point.optJSONObject("interval") ?: return@mapNotNull null
    val energy = point.optJSONObject("energy") ?: return@mapNotNull null
    val kcal = energy.number("kcal") ?: energy.number("kilocalories") ?: return@mapNotNull null
    interval.record("gh-food", kcal)
}

/** Water logged: each entry's litres. */
fun hydrationRecords(points: List<JSONObject>): List<SavedRecord> = points.mapNotNull { point ->
    val interval = point.optJSONObject("interval") ?: return@mapNotNull null
    val millilitres = point.optJSONObject("amountConsumed")?.number("milliliters") ?: return@mapNotNull null
    interval.record("gh-water", millilitres / 1_000)
}

/** Sleep sessions with their stages, in Health Connect's stage numbers. */
fun sleepRecords(points: List<JSONObject>): List<SavedRecord> = points.mapNotNull { point ->
    val interval = point.optJSONObject("interval") ?: return@mapNotNull null
    val start = interval.instantOf("startTime") ?: return@mapNotNull null
    val end = interval.instantOf("endTime") ?: return@mapNotNull null
    if (end <= start) return@mapNotNull null
    // Health Connect wants stages inside the session and not overlapping: an overlapping one is left out.
    var reached = start.toEpochMilli()
    val stages = point.optJSONArray("stages")?.objects().orEmpty().mapNotNull { stage ->
        val from = stage.instantOf("startTime") ?: return@mapNotNull null
        val to = stage.instantOf("endTime") ?: return@mapNotNull null
        Triple(from.toEpochMilli(), to.toEpochMilli(), SLEEP_STAGES[stage.optString("type")] ?: SleepSessionRecord.STAGE_TYPE_UNKNOWN)
    }.sortedBy { it.first }.filter { (from, to, _) ->
        (from >= reached && to > from && to <= end.toEpochMilli()).also { kept -> if (kept) reached = to }
    }
    SavedRecord(
        id = "gh-sleep-${start.toEpochMilli()}",
        origin = ORIGIN,
        start = start.toEpochMilli(),
        startOffset = interval.offsetSeconds("startUtcOffset"),
        end = end.toEpochMilli(),
        endOffset = interval.offsetSeconds("endUtcOffset"),
        times = LongArray(stages.size) { stages[it].first },
        ends = LongArray(stages.size) { stages[it].second },
        kinds = IntArray(stages.size) { stages[it].third },
    )
}

/** Workouts, their type in Health Connect's numbers and their name as shown. */
fun exerciseRecords(points: List<JSONObject>): List<SavedRecord> = points.mapNotNull { point ->
    val interval = point.optJSONObject("interval") ?: return@mapNotNull null
    val start = interval.instantOf("startTime") ?: return@mapNotNull null
    val end = interval.instantOf("endTime")?.takeIf { it > start } ?: return@mapNotNull null
    val type = point.optString("exerciseType")
    SavedRecord(
        id = "gh-exercise-${start.toEpochMilli()}",
        origin = ORIGIN,
        start = start.toEpochMilli(),
        startOffset = interval.offsetSeconds("startUtcOffset"),
        end = end.toEpochMilli(),
        endOffset = interval.offsetSeconds("endUtcOffset"),
        kind = exerciseType(type),
        title = point.optString("displayName").ifBlank { null },
    )
}

/** [records] by the local day they start on, as openFit files records. */
fun byDay(records: List<SavedRecord>, zone: ZoneId): Map<LocalDate, List<SavedRecord>> =
    records.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }

private val SLEEP_STAGES = mapOf(
    "AWAKE" to SleepSessionRecord.STAGE_TYPE_AWAKE,
    // Fitbit's classic "restless" counts as awake, as its minutes asleep leave it out.
    "RESTLESS" to SleepSessionRecord.STAGE_TYPE_AWAKE,
    "ASLEEP" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
    "LIGHT" to SleepSessionRecord.STAGE_TYPE_LIGHT,
    "DEEP" to SleepSessionRecord.STAGE_TYPE_DEEP,
    "REM" to SleepSessionRecord.STAGE_TYPE_REM,
)

/** Google Health's exercise type names, as Health Connect's types; the rest are "other" (their name still shows). */
fun exerciseType(name: String): Int = when {
    name == "RUNNING" || name == "RUN" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    "TREADMILL" in name -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
    name == "WALKING" || name == "WALK" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    "HIK" in name -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    "STATIONARY" in name || "SPINNING" in name || "INDOOR_BIK" in name -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
    "BIK" in name || "CYCL" in name -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    "OPEN_WATER" in name -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
    "SWIM" in name -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    "YOGA" in name -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
    "PILATES" in name -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
    "WEIGHT" in name -> ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
    "STRENGTH" in name -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    "ELLIPTICAL" in name -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
    "ROW" in name -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
    "STAIR" in name -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
    "HIIT" in name || "INTERVAL" in name -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
    "CALISTHENICS" in name -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
    "DANC" in name || "ZUMBA" in name -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
    "TENNIS" in name -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
    "BADMINTON" in name -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
    "BASKETBALL" in name -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
    "SOCCER" in name -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
    "CRICKET" in name -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
    "BOXING" in name -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
    "MARTIAL" in name -> ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
    "GOLF" in name -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
    "BOOTCAMP" in name -> ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP
    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}

private fun JSONObject.record(prefix: String, value: Double): SavedRecord? {
    val start = instantOf("startTime") ?: return null
    val end = instantOf("endTime")?.takeIf { it >= start } ?: return null
    if (value < 0) return null
    return SavedRecord(
        id = "$prefix-${start.toEpochMilli()}",
        origin = ORIGIN,
        start = start.toEpochMilli(),
        startOffset = offsetSeconds("startUtcOffset"),
        end = end.toEpochMilli(),
        endOffset = offsetSeconds("endUtcOffset"),
        value = value,
    )
}

private fun JSONObject.instantOf(key: String): Instant? =
    optString(key).takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "21600s" → 21600; null when missing. */
private fun JSONObject.offsetSeconds(key: String): Int? =
    optString(key).removeSuffix("s").takeIf { it.isNotBlank() }?.toDoubleOrNull()?.toInt()

private fun civilDate(date: JSONObject?): LocalDate? {
    if (date == null || !date.has("year")) return null
    return LocalDate.of(date.getInt("year"), date.getInt("month"), date.getInt("day"))
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
