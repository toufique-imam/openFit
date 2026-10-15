package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ZoneMinutesTest {

    private val start = Instant.parse("2026-10-15T08:00:00Z")

    private fun minuteOf(index: Long, vararg bpm: Long) =
        bpm.mapIndexed { i, value -> HeartRateSample(start.plusSeconds(index * 60 + i * 10L), value) }

    @Test
    fun zonesFollowHeartRateReserve() {
        assertEquals(HeartRateZones(fatBurnFrom = 108, cardioFrom = 132, peakFrom = 162), heartRateZones(age = 40, restingBpm = 60))
    }

    @Test
    fun cardioAndPeakMinutesCountDouble() {
        val zones = HeartRateZones(fatBurnFrom = 116, cardioFrom = 136, peakFrom = 161)
        val samples = minuteOf(0, 90) + minuteOf(1, 120, 124) + minuteOf(2, 140) + minuteOf(3, 170) + minuteOf(4, 165)

        val result = zoneMinutes(samples, zones)

        assertEquals(ZoneMinutes(fatBurn = 1, cardio = 1, peak = 2), result)
        assertEquals(7, result.total)
    }

    @Test
    fun minuteIsClassifiedByItsAverage() {
        val zones = HeartRateZones(fatBurnFrom = 116, cardioFrom = 136, peakFrom = 161)

        assertEquals(ZoneMinutes(0, 0, 0), zoneMinutes(minuteOf(0, 100, 130), zones))
    }

    @Test
    fun restingEstimateUsesLowMinutes() {
        val samples = (0L until 100L).flatMap { minuteOf(it, 50 + it) }

        assertEquals(55, estimateRestingBpm(samples))
        assertNull(estimateRestingBpm(emptyList()))
    }
}
