package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HourlyActivityTest {

    @Test
    fun countsHoursWithEnoughSteps() {
        val steps = mapOf(9 to 300L, 10 to 249L, 12 to 1_000L, 20 to 5_000L)

        val result = hourlyActivity(steps, currentHour = null, window = 9..17)

        assertEquals(2, result.met)
        assertEquals(HourStatus.Missed, result.hours[1])
        assertEquals(9, result.hours.size)
    }

    @Test
    fun hoursNotYetFinishedAreUpcomingUnlessAlreadyMet() {
        val result = hourlyActivity(mapOf(13 to 400L), currentHour = 13, window = 9..17)

        assertEquals(HourStatus.Missed, result.hours[3])
        assertEquals(HourStatus.Met, result.hours[4])
        assertEquals(HourStatus.Upcoming, result.hours[5])
    }

    @Test
    fun glanceCountsActiveMinutesAndLongInactiveStretches() {
        // 0–4 active, 5–19 still (15 min), 20 active, 21–25 still (5 min, too short to count), 26–29 active.
        val steps = ((0L..4L) + 20L + (26L..29L)).associateWith { 30.0 }

        val glance = activityGlance(steps, 0, 30)

        assertEquals(10, glance.activeMinutes)
        assertEquals(15, glance.inactiveMinutes)
        assertEquals(15, glance.longestInactiveMinutes)
    }
}

