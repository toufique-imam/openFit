package com.openfit.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.records.*
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class RecordCodecTest {

    private val start = Instant.parse("2026-10-16T18:04:00Z")
    private val end = start.plusSeconds(60)
    private val zone = ZoneOffset.ofHours(6)
    private val metadata = Metadata.unknownRecordingMethod()

    @Test
    fun `heart rate keeps every sample`() {
        val record = HeartRateRecord(
            start, zone, end, zone,
            listOf(HeartRateRecord.Sample(start, 61), HeartRateRecord.Sample(start.plusSeconds(30), 64)),
            metadata,
        )
        val back = record.toSaved().toRecord(HeartRateRecord::class)
        assertEquals(record.samples, back.samples)
        assertEquals(record.startTime, back.startTime)
        assertEquals(record.endZoneOffset, back.endZoneOffset)
    }

    @Test
    fun `sleep keeps its stages`() {
        val stages = listOf(
            SleepSessionRecord.Stage(start, start.plusSeconds(600), SleepSessionRecord.STAGE_TYPE_LIGHT),
            SleepSessionRecord.Stage(start.plusSeconds(600), start.plusSeconds(1200), SleepSessionRecord.STAGE_TYPE_DEEP),
        )
        val record = SleepSessionRecord(start, zone, start.plusSeconds(1200), zone, metadata, stages = stages)
        assertEquals(stages, record.toSaved().toRecord(SleepSessionRecord::class).stages)
    }

    @Test
    fun `weight keeps its value`() {
        val record = WeightRecord(start, zone, Mass.kilograms(78.4), metadata)
        assertEquals(78.4, record.toSaved().toRecord(WeightRecord::class).weight.inKilograms, 1e-9)
    }

    @Test
    fun `amounts keep their interval and value, filed by start`() {
        val saved = listOf(
            StepsRecord(start, zone, end, zone, 8, metadata).toSaved() to 8.0,
            DistanceRecord(start, zone, end, zone, Length.meters(6.1), metadata).toSaved() to 6.1,
            TotalCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(1.5), metadata).toSaved() to 1.5,
            HydrationRecord(start, zone, end, zone, Volume.liters(0.25), metadata).toSaved() to 0.25,
        )
        saved.forEach { (record, value) ->
            assertEquals(start.toEpochMilli(), record.start)
            assertEquals(end.toEpochMilli(), record.end)
            assertEquals(value, record.value!!, 1e-9)
        }
        assertEquals(start, StepsRecord(start, zone, end, zone, 8, metadata).filedAt)
    }

    @Test
    @OptIn(ExperimentalMindfulnessSessionApi::class)
    fun `every supported type survives disk serialization and record reconstruction`() {
        val records: List<Record> = listOf(
            HeartRateRecord(start, zone, end, zone, listOf(HeartRateRecord.Sample(start, 75)), metadata),
            RestingHeartRateRecord(start, zone, 62, metadata),
            HeartRateVariabilityRmssdRecord(start, zone, 52.75, metadata),
            OxygenSaturationRecord(start, zone, Percentage(97.5), metadata),
            RespiratoryRateRecord(start, zone, 16.25, metadata),
            SkinTemperatureRecord(start, zone, end, zone, metadata,
                listOf(SkinTemperatureRecord.Delta(start, TemperatureDelta.celsius(-0.2)), SkinTemperatureRecord.Delta(start.plusSeconds(30), TemperatureDelta.celsius(0.4))),
                Temperature.celsius(33.2), SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST),
            Vo2MaxRecord(start, zone, metadata, 48.2, Vo2MaxRecord.MEASUREMENT_METHOD_OTHER),
            WeightRecord(start, zone, Mass.kilograms(78.4), metadata),
            BodyFatRecord(start, zone, Percentage(19.25), metadata),
            SleepSessionRecord(start, zone, end, zone, metadata, title = "Night", stages = listOf(SleepSessionRecord.Stage(start, end, SleepSessionRecord.STAGE_TYPE_REM))),
            ExerciseSessionRecord(start, zone, end, zone, metadata, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Morning run"),
            MindfulnessSessionRecord(start, zone, end, zone, metadata, MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNKNOWN, "Quiet time"),
            StepsRecord(start, zone, end, zone, 123, metadata),
            DistanceRecord(start, zone, end, zone, Length.meters(126.5), metadata),
            FloorsClimbedRecord(start, zone, end, zone, 2.5, metadata),
            TotalCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(6.2), metadata),
            NutritionRecord(start, zone, end, zone, metadata, energy = Energy.kilocalories(254.5)),
            HydrationRecord(start, zone, end, zone, Volume.liters(0.375), metadata),
        )
        assertEquals(SAVED_TYPES.keys, records.map { it::class }.toSet())
        val serializer = ListSerializer(SavedRecord.serializer())
        val onDisk = Json.encodeToString(serializer, records.map { it.toSaved() })
        val restored = Json.decodeFromString(serializer, onDisk)
        records.zip(restored).forEach { (original, saved) ->
            // Compare serialized fields, since primitive arrays use reference equality in SavedRecord.
            val rebuilt = saved.toRecord(original::class)
            assertEquals(original::class.simpleName, Json.encodeToString(SavedRecord.serializer(), original.toSaved()),
                Json.encodeToString(SavedRecord.serializer(), rebuilt.toSaved()))
            assertEquals(original.filedAt, rebuilt.filedAt)
        }
    }
}
