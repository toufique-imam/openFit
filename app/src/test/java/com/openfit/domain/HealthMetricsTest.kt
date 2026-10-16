package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthMetricsTest {

    private val today = LocalDate.of(2026, 10, 16)

    @Test
    fun personalRangeIsTwoDeviationsAroundTheLastMonth() {
        val values = (1L..10L).associate { today.minusDays(it) to if (it % 2 == 0L) 58.0 else 62.0 }

        val range = personalRange(today, values)!!

        // Mean 60, sample SD ≈ 2.108.
        assertEquals(60 - 2 * 2.108, range.low, 0.01)
        assertEquals(60 + 2 * 2.108, range.high, 0.01)
    }

    @Test
    fun needsAWeekOfHistory() {
        val values = (1L..6L).associate { today.minusDays(it) to 60.0 }

        assertNull(personalRange(today, values))
    }

    @Test
    fun readingsReportWhetherTodayIsInRange() {
        val history = (1L..10L).associate { today.minusDays(it) to if (it % 2 == 0L) 58.0 else 62.0 }
        val readings = metricReadings(
            today,
            mapOf(
                HealthMetric.RestingHeartRate to history + (today to 61.0),
                HealthMetric.HeartRateVariability to history + (today to 80.0),
            ),
        )

        assertTrue(readings.first { it.metric == HealthMetric.RestingHeartRate }.inRange!!)
        assertFalse(readings.first { it.metric == HealthMetric.HeartRateVariability }.inRange!!)
    }
}
