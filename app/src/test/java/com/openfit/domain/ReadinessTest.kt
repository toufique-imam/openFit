package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReadinessTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun baseline(days: Int, hrv: (Int) -> Double, resting: (Int) -> Int, sleep: (Int) -> Int) = DailyHistory(
        hrvMs = (1..days).associate { today.minusDays(it.toLong()) to hrv(it) },
        restingBpm = (1..days).associate { today.minusDays(it.toLong()) to resting(it) },
        sleepMinutes = (1..days).associate { today.minusDays(it.toLong()) to sleep(it) },
    )

    private val typical = baseline(30, hrv = { if (it % 2 == 0) 50.0 else 60.0 }, resting = { if (it % 2 == 0) 54 else 58 }, sleep = { if (it % 2 == 0) 420 else 480 })

    private fun DailyHistory.withToday(hrv: Double, resting: Int, sleep: Int) = copy(
        hrvMs = hrvMs + (today to hrv),
        restingBpm = restingBpm + (today to resting),
        sleepMinutes = sleepMinutes + (today to sleep),
    )

    @Test
    fun noDataMeansNoScore() {
        assertNull(readiness(today, DailyHistory()))
        assertNull(readiness(today, DailyHistory(restingBpm = mapOf(today to 55))))
    }

    @Test
    fun waitsForLastNightsSleep() {
        val awake = typical.withToday(hrv = 55.0, resting = 50, sleep = 450).let { it.copy(sleepMinutes = it.sleepMinutes - today) }

        assertNull(readiness(today, awake))
    }

    @Test
    fun anOrdinaryDayIsModerate() {
        val result = readiness(today, typical.withToday(hrv = 55.0, resting = 56, sleep = 450))!!

        assertEquals(ReadinessLevel.Moderate, result.level)
        assertFalse(result.illness)
    }

    @Test
    fun highHrvLowRestingAndGoodSleepIsHigh() {
        val result = readiness(today, typical.withToday(hrv = 75.0, resting = 50, sleep = 540))!!

        assertEquals(ReadinessLevel.High, result.level)
    }

    @Test
    fun shortSleepAndPoorRecoveryIsLow() {
        val rough = typical.copy(sleepMinutes = typical.sleepMinutes + (today.minusDays(1) to 300) + (today.minusDays(2) to 300))

        val result = readiness(today, rough.withToday(hrv = 38.0, resting = 61, sleep = 300))!!

        assertEquals(ReadinessLevel.Low, result.level)
    }

    @Test
    fun raisedRestingHeartRateFlagsIllness() {
        assertTrue(readiness(today, typical.withToday(hrv = 55.0, resting = 64, sleep = 450))!!.illness)
    }

    @Test
    fun aShortBaselineIsDampedTowardsNeutral() {
        val week = baseline(7, hrv = { if (it % 2 == 0) 50.0 else 60.0 }, resting = { if (it % 2 == 0) 54 else 58 }, sleep = { 450 })

        val damped = readiness(today, week.withToday(hrv = 75.0, resting = 50, sleep = 540))!!
        val full = readiness(today, typical.withToday(hrv = 75.0, resting = 50, sleep = 540))!!

        assertTrue(damped.score in 51 until full.score)
    }
}
