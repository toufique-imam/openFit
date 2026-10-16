package com.openfit.health

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.TemperatureDelta
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * A Health Connect record as openFit saves it: the fields openFit reads, in one flat shape for every type. Series (heart
 * rate samples, sleep stages, skin temperature deltas) are parallel arrays to keep a day of heart rate small.
 */
@Serializable
data class SavedRecord(
    val id: String,
    val origin: String,
    val method: Int = Metadata.RECORDING_METHOD_UNKNOWN,
    val modified: Long = 0,
    val start: Long,
    val startOffset: Int? = null,
    val end: Long? = null,
    val endOffset: Int? = null,
    val value: Double? = null,
    val kind: Int? = null,
    val title: String? = null,
    val times: LongArray? = null,
    val ends: LongArray? = null,
    val values: DoubleArray? = null,
    val kinds: IntArray? = null,
)

/** When the record starts: its time, or the start of its interval. Health Connect files a record by this. */
val Record.filedAt: Instant
    get() = Instant.ofEpochMilli(toSaved().start)

/** The record types openFit reads one by one (the rest come from aggregates), by a stable name for the cache. */
val SAVED_TYPES: Map<KClass<out Record>, String> = @OptIn(ExperimentalMindfulnessSessionApi::class) mapOf(
    HeartRateRecord::class to "heart_rate",
    RestingHeartRateRecord::class to "resting_heart_rate",
    HeartRateVariabilityRmssdRecord::class to "hrv",
    OxygenSaturationRecord::class to "spo2",
    RespiratoryRateRecord::class to "breathing_rate",
    SkinTemperatureRecord::class to "skin_temperature",
    Vo2MaxRecord::class to "vo2_max",
    WeightRecord::class to "weight",
    BodyFatRecord::class to "body_fat",
    SleepSessionRecord::class to "sleep",
    ExerciseSessionRecord::class to "exercise",
    MindfulnessSessionRecord::class to "mindfulness",
)

@OptIn(ExperimentalMindfulnessSessionApi::class)
fun Record.toSaved(): SavedRecord {
    val meta = metadata
    fun base(start: Instant, startOffset: ZoneOffset?, end: Instant? = null, endOffset: ZoneOffset? = null) = SavedRecord(
        id = meta.id,
        origin = meta.dataOrigin.packageName,
        method = meta.recordingMethod,
        modified = meta.lastModifiedTime.toEpochMilli(),
        start = start.toEpochMilli(),
        startOffset = startOffset?.totalSeconds,
        end = end?.toEpochMilli(),
        endOffset = endOffset?.totalSeconds,
    )
    return when (this) {
        is HeartRateRecord -> base(startTime, startZoneOffset, endTime, endZoneOffset).copy(
            times = LongArray(samples.size) { samples[it].time.toEpochMilli() },
            values = DoubleArray(samples.size) { samples[it].beatsPerMinute.toDouble() },
        )
        is RestingHeartRateRecord -> base(time, zoneOffset).copy(value = beatsPerMinute.toDouble())
        is HeartRateVariabilityRmssdRecord -> base(time, zoneOffset).copy(value = heartRateVariabilityMillis)
        is OxygenSaturationRecord -> base(time, zoneOffset).copy(value = percentage.value)
        is RespiratoryRateRecord -> base(time, zoneOffset).copy(value = rate)
        is SkinTemperatureRecord -> base(startTime, startZoneOffset, endTime, endZoneOffset).copy(
            value = baseline?.inCelsius,
            kind = measurementLocation,
            times = LongArray(deltas.size) { deltas[it].time.toEpochMilli() },
            values = DoubleArray(deltas.size) { deltas[it].delta.inCelsius },
        )
        is Vo2MaxRecord -> base(time, zoneOffset).copy(value = vo2MillilitersPerMinuteKilogram, kind = measurementMethod)
        is WeightRecord -> base(time, zoneOffset).copy(value = weight.inKilograms)
        is BodyFatRecord -> base(time, zoneOffset).copy(value = percentage.value)
        is SleepSessionRecord -> base(startTime, startZoneOffset, endTime, endZoneOffset).copy(
            title = title,
            times = LongArray(stages.size) { stages[it].startTime.toEpochMilli() },
            ends = LongArray(stages.size) { stages[it].endTime.toEpochMilli() },
            kinds = IntArray(stages.size) { stages[it].stage },
        )
        is ExerciseSessionRecord -> base(startTime, startZoneOffset, endTime, endZoneOffset).copy(kind = exerciseType, title = title)
        is MindfulnessSessionRecord -> base(startTime, startZoneOffset, endTime, endZoneOffset).copy(kind = mindfulnessSessionType, title = title)
        else -> error("Unsupported record ${this::class.simpleName}")
    }
}

/** Rebuilds the Health Connect record, so code that reads records cannot tell a saved one from a fresh one (bar its metadata). */
@OptIn(ExperimentalMindfulnessSessionApi::class)
@Suppress("UNCHECKED_CAST")
fun <T : Record> SavedRecord.toRecord(type: KClass<T>): T {
    // openFit reads no metadata; Health Connect's id and origin stay in the saved record.
    val metadata = Metadata.unknownRecordingMethod()
    val start = Instant.ofEpochMilli(start)
    val startZone = startOffset?.let(ZoneOffset::ofTotalSeconds)
    val end = end?.let(Instant::ofEpochMilli) ?: start
    val endZone = endOffset?.let(ZoneOffset::ofTotalSeconds)
    val times = times ?: LongArray(0)
    val values = values ?: DoubleArray(0)
    val record: Record = when (type) {
        HeartRateRecord::class -> HeartRateRecord(
            start, startZone, end, endZone,
            List(times.size) { HeartRateRecord.Sample(Instant.ofEpochMilli(times[it]), values[it].toLong()) },
            metadata,
        )
        RestingHeartRateRecord::class -> RestingHeartRateRecord(start, startZone, value!!.toLong(), metadata)
        HeartRateVariabilityRmssdRecord::class -> HeartRateVariabilityRmssdRecord(start, startZone, value!!, metadata)
        OxygenSaturationRecord::class -> OxygenSaturationRecord(start, startZone, Percentage(value!!), metadata)
        RespiratoryRateRecord::class -> RespiratoryRateRecord(start, startZone, value!!, metadata)
        SkinTemperatureRecord::class -> SkinTemperatureRecord(
            start, startZone, end, endZone, metadata,
            List(times.size) { SkinTemperatureRecord.Delta(Instant.ofEpochMilli(times[it]), TemperatureDelta.celsius(values[it])) },
            value?.let { androidx.health.connect.client.units.Temperature.celsius(it) },
            kind ?: SkinTemperatureRecord.MEASUREMENT_LOCATION_UNKNOWN,
        )
        Vo2MaxRecord::class -> Vo2MaxRecord(start, startZone, metadata, value!!, kind ?: Vo2MaxRecord.MEASUREMENT_METHOD_OTHER)
        WeightRecord::class -> WeightRecord(start, startZone, Mass.kilograms(value!!), metadata)
        BodyFatRecord::class -> BodyFatRecord(start, startZone, Percentage(value!!), metadata)
        SleepSessionRecord::class -> SleepSessionRecord(
            start, startZone, end, endZone, metadata, title, null,
            List(times.size) { SleepSessionRecord.Stage(Instant.ofEpochMilli(times[it]), Instant.ofEpochMilli(ends!![it]), kinds!![it]) },
        )
        ExerciseSessionRecord::class -> ExerciseSessionRecord(start, startZone, end, endZone, metadata, kind ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, title)
        MindfulnessSessionRecord::class -> MindfulnessSessionRecord(
            start, startZone, end, endZone, metadata, kind ?: MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNKNOWN, title, null,
        )
        else -> error("Unsupported record ${type.simpleName}")
    }
    return record as T
}
