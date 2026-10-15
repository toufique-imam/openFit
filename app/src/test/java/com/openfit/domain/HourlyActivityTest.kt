package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HourlyActivityTest {

    @Test
    fun countsHoursWithEnoughSteps() {
        val steps = mapOf(9 to 300L, 10 to 249L, 12 to 1_000L, 20 to 5_000L)

        val result = hourlyActivity(steps, currentHour = null)

        assertEquals(2, result.met)
        assertEquals(HourStatus.Missed, result.hours[1])
        assertEquals(9, result.hours.size)
    }

    @Test
    fun hoursNotYetFinishedAreUpcomingUnlessAlreadyMet() {
        val result = hourlyActivity(mapOf(13 to 400L), currentHour = 13)

        assertEquals(HourStatus.Missed, result.hours[3])
        assertEquals(HourStatus.Met, result.hours[4])
        assertEquals(HourStatus.Upcoming, result.hours[5])
    }
}
