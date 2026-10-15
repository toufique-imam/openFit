package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TargetsTest {

    private val sunday = LocalDate.of(2026, 10, 11)

    @Test
    fun habitualLoadIsTheHigherOfRollingAndSmoothedAverages() {
        assertNull(habitualLoad(emptyList()))
        assertEquals(200.0, habitualLoad(listOf(180.0, 200.0, 220.0, 200.0))!!, 1.0)
        assertEquals(225.0, habitualLoad(listOf(300.0, 300.0, 300.0, 0.0))!!, 1e-9)
        assertEquals(120.0, habitualLoad(listOf(0.0, 0.0, 0.0, 300.0))!!, 1e-9)
    }

    @Test
    fun zoneTargetRampsTowardsTheGuideline() {
        assertEquals(150.0, weeklyZoneTarget(FitnessGoal.Maintain, emptyList()), 0.0)
        assertEquals(54.0, weeklyZoneTarget(FitnessGoal.Maintain, listOf(40.0)), 1e-9)
        assertEquals(400.0, weeklyZoneTarget(FitnessGoal.Maintain, listOf(400.0)), 1e-9)
        assertEquals(420.0, weeklyZoneTarget(FitnessGoal.Improve, listOf(400.0)), 1e-9)
    }

    @Test
    fun weeksNeedFourTrackedDays() {
        val daily = (0L until 3L).associate { sunday.minusWeeks(1).plusDays(it) to 10.0 } +
            (0L until 7L).associate { sunday.minusWeeks(2).plusDays(it) to 20.0 }

        assertEquals(listOf(null, null, 140.0, null), completedWeeks(daily, sunday))
    }

    @Test
    fun dailyTargetSpreadsWhatIsLeftAndFollowsReadiness() {
        val thursday = 4

        val neutral = dailyTarget(200.0, doneBeforeToday = 90.0, doneToday = 5.0, dayIndex = thursday, readiness = Readiness(50, false))
        val tired = dailyTarget(200.0, doneBeforeToday = 90.0, doneToday = 0.0, dayIndex = thursday, readiness = Readiness(35, false))

        assertEquals(110.0 / 3, neutral.daily, 1e-9)
        assertEquals(95.0, neutral.doneThisWeek, 0.0)
        assertEquals(110.0 / 3 * 0.85, tired.daily, 1e-9)
    }

    @Test
    fun dailyTargetIsCappedAndProtectsRecovery() {
        val saturday = 6

        assertEquals(400.0 / 7, dailyTarget(200.0, 0.0, 0.0, saturday, readiness = null).daily, 1e-9)
        assertEquals(200.0 / 7, dailyTarget(200.0, 0.0, 0.0, saturday, readiness = Readiness(20, false)).daily, 1e-9)
        assertEquals(50.0 / 7, dailyTarget(200.0, 0.0, 0.0, saturday, readiness = Readiness(60, true)).daily, 1e-9)
        assertEquals(10.0, dailyTarget(200.0, 0.0, 0.0, saturday, readiness = null, chronicWeekly = 200.0, previousSixDays = 250.0).daily, 1e-9)
        assertEquals(0.0, dailyTarget(200.0, 250.0, 0.0, saturday, readiness = null).daily, 0.0)
    }
}
