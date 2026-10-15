package com.openfit.googlehealth

import com.openfit.domain.ZoneMinutes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class GoogleHealthParsingTest {

    @Test
    fun readsDailyStepsRollup() {
        val response = JSONObject(
            """{"rollupDataPoints":[{"civilStartTime":{"date":{"year":2026,"month":2,"day":26},"time":{}},
               "civilEndTime":{"date":{"year":2026,"month":2,"day":26},"time":{"hours":23,"minutes":59,"seconds":59}},
               "steps":{"countSum":"3822"}}]}""",
        )

        assertEquals(3822.0, response.firstRollup("steps")?.number("countSum"))
        assertNull(response.firstRollup("distance"))
    }

    @Test
    fun readsZoneMinutesBreakdown() {
        val rollup = JSONObject("""{"sumInFatBurnHeartZone":"12","sumInCardioHeartZone":"4","sumInPeakHeartZone":"1"}""")

        assertEquals(ZoneMinutes(fatBurn = 12, cardio = 4, peak = 1), parseZoneMinutes(rollup))
    }

    @Test
    fun picksLatestDailyAndSampleValues() {
        val daily = JSONObject(
            """{"dataPoints":[
               {"dailyRestingHeartRate":{"date":{"year":2026,"month":10,"day":14},"beatsPerMinute":"61"}},
               {"dailyRestingHeartRate":{"date":{"year":2026,"month":10,"day":12},"beatsPerMinute":"58"}}]}""",
        ).points("dailyRestingHeartRate")
        val weights = JSONObject(
            """{"dataPoints":[
               {"weight":{"sampleTime":{"physicalTime":"2026-10-01T07:00:00Z"},"weightGrams":80500}},
               {"weight":{"sampleTime":{"physicalTime":"2026-10-10T07:00:00Z"},"weightGrams":79800}}]}""",
        ).points("weight")

        assertEquals(61.0, latestDaily(daily, ZoneOffset.UTC) { it.number("beatsPerMinute") }?.value)
        assertEquals(79.8, latestSample(weights) { it.number("weightGrams")?.div(1000) }?.value)
    }

    @Test
    fun sumsSleepAndNamesExercisesFromReconciledPoints() {
        val sleep = JSONObject(
            """{"reconciledDataPoints":[{"dataPointName":"a","sleep":{
               "interval":{"startTime":"2026-10-14T22:30:00Z","endTime":"2026-10-15T06:30:00Z"},
               "summary":{"minutesAsleep":"431","minutesAwake":"49"}}}]}""",
        ).points("sleep")
        val exercise = JSONObject(
            """{"dataPoints":[{"exercise":{"exerciseType":"OUTDOOR_RUN",
               "interval":{"startTime":"2026-10-15T07:00:00Z","endTime":"2026-10-15T07:30:00Z"}}}]}""",
        ).points("exercise")

        val summary = parseSleep(sleep)

        assertEquals(Duration.ofMinutes(431), summary?.asleep)
        assertEquals(Instant.parse("2026-10-15T06:30:00Z"), summary?.end)
        assertEquals("Outdoor Run", parseExercises(exercise).single().name)
    }
}
