package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GoalMetricsTest {

    @Test
    fun weekSummaryMatchesFitbit() {
        // Fitbit, 20–26 Sept: 6,644 steps per day (avg), goal hit on 1 day, 46,509 steps in total.
        val week = LocalDate.of(2026, 9, 20)
        val steps = listOf(5298.0, 9697.0, 10087.0, 5678.0, 8769.0, 3557.0, 3423.0)
            .mapIndexed { index, value -> week.plusDays(index.toLong()) to value }
            .toMap()

        val summary = goalSummary(periodDays(ChartPeriod.Week, week), steps, goal = 10_000.0)

        assertEquals(6644.0, summary.averagePerDay!!, 0.5)
        assertEquals(46509.0, summary.total, 0.0)
        assertEquals(1, summary.goalDays)
    }

    @Test
    fun goalAxisAddsATopOnlyAboveTheGoal() {
        // Fitbit's week chart is labelled 0 / 5k / 10k; its September chart, topped by ~12.3k, adds 13k.
        assertEquals(listOf(0.0, 5000.0, 10000.0), goalAxis(10_000.0, 9_900.0).labels)
        assertEquals(listOf(0.0, 5000.0, 10000.0), goalAxis(10_000.0, 10_087.0).labels)
        assertEquals(listOf(0.0, 5000.0, 10000.0, 13000.0), goalAxis(10_000.0, 12_300.0).labels)
    }

    @Test
    fun hourlyAxisMatchesFitbit() {
        // Fitbit's busiest hour of ~3,050 steps was drawn against 0 / 1.6k / 3.2k.
        assertEquals(ChartAxis(3200.0, 1600), hourlyAxis(3050.0))
        assertEquals(ChartAxis(880.0, 440), hourlyAxis(870.0))
    }
}
