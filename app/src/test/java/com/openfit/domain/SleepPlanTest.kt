package com.openfit.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

class SleepPlanTest {
    @Test fun `bedtime and wind down cross midnight correctly`() {
        val plan = sleepPlan(ZonedDateTime.parse("2026-10-18T07:00:00+06:00[Asia/Dhaka]"), 480, 30)
        assertEquals(LocalTime.of(23, 0), plan.bedtime.toLocalTime())
        assertEquals(17, plan.bedtime.dayOfMonth)
        assertEquals(LocalTime.of(22, 30), plan.windDown.toLocalTime())
    }
    @Test fun `clock changes preserve elapsed time in bed`() {
        listOf("2026-03-08T08:00:00-04:00[America/New_York]", "2026-11-01T08:00:00-05:00[America/New_York]").forEach { wake ->
            val plan = sleepPlan(ZonedDateTime.parse(wake), 480, 30)
            assertEquals(Duration.ofHours(8), Duration.between(plan.bedtime, plan.wake))
            assertEquals(Duration.ofMinutes(30), Duration.between(plan.windDown, plan.bedtime))
        }
    }
}
