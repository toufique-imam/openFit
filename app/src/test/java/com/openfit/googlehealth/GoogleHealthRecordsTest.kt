package com.openfit.googlehealth

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.openfit.health.HealthStore
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class GoogleHealthRecordsTest {

    private val zone = ZoneOffset.ofHours(6)

    @Test
    fun `steps keep their interval, count and offsets`() {
        val page = JSONObject(
            """{"dataPoints":[{"steps":{"interval":{"startTime":"2025-03-01T04:00:00Z","startUtcOffset":"21600s",
            "endTime":"2025-03-01T04:01:00Z","endUtcOffset":"21600s"},"count":"52"}}]}""",
        )
        val record = intervalRecords(page.points("steps"), "steps", "count").single()
        assertEquals(Instant.parse("2025-03-01T04:00:00Z").toEpochMilli(), record.start)
        assertEquals(52.0, record.value!!, 0.0)
        assertEquals(21_600, record.startOffset)
        assertEquals(HealthStore.CLOUD_ORIGIN, record.origin)
    }

    @Test
    fun `distance in millimetres becomes metres`() {
        val page = JSONObject(
            """{"dataPoints":[{"distance":{"interval":{"startTime":"2025-03-01T04:00:00Z","endTime":"2025-03-01T04:01:00Z"},"millimeters":"61500"}}]}""",
        )
        assertEquals(61.5, intervalRecords(page.points("distance"), "distance", "millimeters", scale = 0.001).single().value!!, 1e-9)
    }

    @Test
    fun `heart rate minutes go into hourly records, out of range ones left out`() {
        val windows = (0 until 90).map { minute ->
            val start = Instant.parse("2025-03-01T00:00:00Z").plusSeconds(minute * 60L)
            Triple(start, start.plusSeconds(60), JSONObject().put("beatsPerMinuteAvg", if (minute == 5) 0 else 60 + minute % 10))
        }
        val records = heartRateRecords(windows, zone)
        assertEquals(2, records.size)
        assertEquals(89, records.sumOf { it.times!!.size })
        records.forEach { record -> assertTrue(record.times!!.all { it >= record.start && it < record.end!! }) }
    }

    @Test
    fun `heart rate falls back to the middle of min and max`() {
        val start = Instant.parse("2025-03-01T00:00:00Z")
        val records = heartRateRecords(listOf(Triple(start, start.plusSeconds(60), JSONObject().put("beatsPerMinuteMin", 60).put("beatsPerMinuteMax", 70))), zone)
        assertEquals(65.0, records.single().values!!.single(), 0.0)
    }

    @Test
    fun `daily values become a reading on their date, out of range ones left out`() {
        val page = JSONObject(
            """{"dataPoints":[
              {"dailyRestingHeartRate":{"date":{"year":2025,"month":3,"day":1},"beatsPerMinute":"58"}},
              {"dailyRestingHeartRate":{"date":{"year":2025,"month":3,"day":2},"beatsPerMinute":"0"}}]}""",
        )
        val record = dailyRecords(page.points("dailyRestingHeartRate"), "rhr", "beatsPerMinute", zone, hour = 7, range = 1.0..300.0).single()
        assertEquals(58.0, record.value!!, 0.0)
        assertEquals(LocalDate.of(2025, 3, 1), byDay(listOf(record), zone).keys.single())
    }

    @Test
    fun `a night without a number is left out`() {
        val page = JSONObject(
            """{"dataPoints":[{"dailySleepTemperatureDerivations":{"date":{"year":2025,"month":3,"day":1},
            "nightlyTemperatureCelsius":"NaN","baselineTemperatureCelsius":33.1}}]}""",
        )
        assertTrue(skinTemperatureRecords(page.points("dailySleepTemperatureDerivations"), zone).isEmpty())
    }

    @Test
    fun `sleep keeps its stages in Health Connect numbers and drops overlapping ones`() {
        val page = JSONObject(
            """{"dataPoints":[{"sleep":{"interval":{"startTime":"2025-03-01T17:00:00Z","endTime":"2025-03-02T01:00:00Z"},"type":"STAGES",
            "stages":[
              {"startTime":"2025-03-01T17:00:00Z","endTime":"2025-03-01T18:00:00Z","type":"LIGHT"},
              {"startTime":"2025-03-01T17:30:00Z","endTime":"2025-03-01T18:30:00Z","type":"AWAKE"},
              {"startTime":"2025-03-01T18:00:00Z","endTime":"2025-03-01T19:00:00Z","type":"DEEP"},
              {"startTime":"2025-03-01T19:00:00Z","endTime":"2025-03-01T20:00:00Z","type":"REM"}]}}]}""",
        )
        val record = sleepRecords(page.points("sleep")).single()
        assertArrayEquals(
            intArrayOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM),
            record.kinds,
        )
        // Filed by start: the night belongs to the evening it began.
        assertEquals(LocalDate.of(2025, 3, 1), byDay(listOf(record), zone).keys.single())
    }

    @Test
    fun `workouts map their type and keep their name`() {
        val page = JSONObject(
            """{"dataPoints":[{"exercise":{"interval":{"startTime":"2025-03-01T12:00:00Z","endTime":"2025-03-01T12:30:00Z"},
            "exerciseType":"RUNNING","displayName":"Morning run"}}]}""",
        )
        val record = exerciseRecords(page.points("exercise")).single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, record.kind)
        assertEquals("Morning run", record.title)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, exerciseType("AEROBIC_WORKOUT"))
    }

    @Test
    fun `total calories windows become quarter-hour records, empty windows skipped`() {
        val start = Instant.parse("2025-03-01T00:00:00Z")
        val windows = listOf(
            Triple(start, start.plusSeconds(900), JSONObject().put("kcalSum", 25.5)),
            Triple(start.plusSeconds(900), start.plusSeconds(1800), JSONObject().put("kcalSum", 0)),
        )
        assertEquals(25.5, windowRecords(windows, "calories", "kcalSum", zone).single().value!!, 0.0)
    }
}
