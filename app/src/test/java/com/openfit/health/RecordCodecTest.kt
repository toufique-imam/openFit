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
}
