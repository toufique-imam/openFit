package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ChartPeriodsTest {

    private val week = LocalDate.of(2026, 9, 20)
    private val loads = listOf(18.0, 103.0, 49.0, 14.0, 77.0, 6.0, 11.0)
        .mapIndexed { index, load -> week.plusDays(index.toLong()) to load }
        .toMap()

    @Test
    fun axisMatchesFitbitsLabels() {
        // Fitbit's week chart topped by 103 is labelled 0 / 65 / 123; the year chart topped by 88 is 0 / 55 / 106.
        assertEquals(ChartAxis(103 * 1.2, 65), chartAxis(103.0))
        assertEquals(ChartAxis(88 * 1.2, 55), chartAxis(88.0))
    }

    @Test
    fun weeksRunSundayToSaturday() {
        val days = periodDays(ChartPeriod.Week, LocalDate.of(2026, 9, 24))

        assertEquals(LocalDate.of(2026, 9, 20), days.first())
        assertEquals(LocalDate.of(2026, 9, 26), days.last())
        assertEquals(30, periodDays(ChartPeriod.Month, LocalDate.of(2026, 9, 24)).size)
    }

    @Test
    fun weekSummaryCountsDaysInTarget() {
        val ranges = mapOf(
            week.plusDays(1) to LoadRange(66.0, 98.0, 140.0),
            week.plusDays(4) to LoadRange(58.0, 91.0, 140.0),
            week.plusDays(5) to LoadRange(11.0, 46.0, 144.0),
        )
        val points = dailyPoints(periodDays(ChartPeriod.Week, week), loads, ranges)

        assertEquals(1, daysInTarget(points))
        assertEquals(39.7, averagePerDay(periodDays(ChartPeriod.Week, week), loads)!!, 0.05)
        assertEquals(LocalDate.of(2026, 9, 26), dayRows(periodDays(ChartPeriod.Week, week), loads).first().start)
    }

    @Test
    fun monthListShowsTheSundayWeeksTouchingTheMonth() {
        val rows = weekRows(YearMonth.of(2026, 9), loads)

        assertEquals(1, rows.size)
        assertEquals(LocalDate.of(2026, 9, 20), rows.single().start)
        assertEquals(39.7, rows.single().load, 0.05)
    }

    @Test
    fun monthStatusComparesWithTheMonthBefore() {
        val august = (1..31).associate { LocalDate.of(2026, 8, it) to 40.0 }
        // Fitbit called September (44 a day after August's 40) "Maintained fitness".
        val september = (1..30).associate { LocalDate.of(2026, 9, it) to 44.0 }

        assertEquals(TrainingStatus.Maintaining, monthStatus(YearMonth.of(2026, 9), august + september, LocalDate.of(2026, 9, 30)))
        val harder = (1..30).associate { LocalDate.of(2026, 9, it) to 60.0 }
        assertEquals(TrainingStatus.Overtraining, monthStatus(YearMonth.of(2026, 9), august + harder, LocalDate.of(2026, 9, 30)))
    }
}
