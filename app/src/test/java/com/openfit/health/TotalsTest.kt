package com.openfit.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TotalsTest {

    private val zone: ZoneId = ZoneOffset.ofHours(6)
    private val day: LocalDate = LocalDate.of(2026, 10, 16)
    private val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private var ids = 0
    private fun record(origin: String, startMinute: Long, minutes: Long, value: Double) = SavedRecord(
        id = "${ids++}",
        origin = origin,
        start = midnight + startMinute * minute,
        end = midnight + (startMinute + minutes) * minute,
        value = value,
    )

    @Test
    fun `a phone copying part of the watch counts once`() {
        val watch = (0L until 10).map { record(WATCH, 600 + it, 1, 10.0) }
        val copy = (0L until 4).map { record(PHONE, 600 + it, 1, 10.0) }
        assertEquals(100.0, dayTotals(watch + copy, day, zone, withMinutes = false).total!!, 1e-9)
    }

    @Test
    fun `the wearable's app comes first even when a copy adds up to more`() {
        // Google Fit's copy of Fitbit's calories is a little higher; Health Connect counts Fitbit's.
        val watch = (0L until 4).map { record(WATCH, it * 15, 15, 25.0) }
        val copy = (0L until 4).map { record(PHONE, it * 15, 15, 26.0) }
        assertEquals(104.0, dayTotals(watch + copy, day, zone, withMinutes = false).total!!, 1e-9)
        assertEquals(100.0, dayTotals(watch + copy, day, zone, withMinutes = false, wearables = setOf(WATCH)).total!!, 1e-9)
    }

    @Test
    fun `the phone fills the time the watch did not record`() {
        val watch = (0L until 10).map { record(WATCH, 600 + it, 1, 10.0) }
        val phone = listOf(record(PHONE, 900, 5, 20.0))
        assertEquals(120.0, dayTotals(watch + phone, day, zone, withMinutes = false).total!!, 1e-9)
    }

    @Test
    fun `only the uncovered part of a longer record counts`() {
        val watch = listOf(record(WATCH, 0, 15, 30.0))
        // Half of the phone's 30-minute record overlaps the watch's: its other half counts.
        val phone = listOf(record(PHONE, 0, 30, 20.0))
        assertEquals(40.0, dayTotals(watch + phone, day, zone, withMinutes = false).total!!, 1e-9)
    }

    @Test
    fun `an interval running past midnight is shared between the days`() {
        val late = record(WATCH, -10, 20, 40.0)
        assertEquals(20.0, dayTotals(listOf(late), day, zone, withMinutes = false).total!!, 1e-9)
        assertEquals(20.0, dayTotals(listOf(late), day.minusDays(1), zone, withMinutes = false).total!!, 1e-9)
    }

    @Test
    fun `one app writing the same record twice counts once`() {
        val twice = listOf(record(WATCH, 60, 1, 8.0), record(WATCH, 60, 1, 8.0))
        assertEquals(8.0, dayTotals(twice, day, zone, withMinutes = false).total!!, 1e-9)
    }

    @Test
    fun `hours and minutes share each record by its time`() {
        val record = record(WATCH, 59, 2, 10.0)
        val totals = dayTotals(listOf(record), day, zone, withMinutes = true)
        assertEquals(mapOf(0 to 5.0, 1 to 5.0), totals.hours)
        assertEquals(2, totals.minutes.size)
        assertEquals(5.0, totals.minutes.getValue((midnight + 59 * minute) / minute), 1e-9)
    }

    @Test
    fun `a day without records has no total, one adding up to nothing has zero`() {
        assertNull(dayTotals(emptyList(), day, zone, withMinutes = false).total)
        assertEquals(0.0, dayTotals(listOf(record(WATCH, 0, 1, 0.0)), day, zone, withMinutes = false).total!!, 1e-9)
    }

    @Test
    fun `moments count once across apps`() {
        val meal = SavedRecord(id = "a", origin = WATCH, start = midnight + 3_600_000, value = 500.0)
        val same = meal.copy(id = "b", origin = PHONE)
        val later = meal.copy(id = "c", start = midnight + 7_200_000, value = 300.0)
        assertEquals(800.0, dayTotals(listOf(meal, same, later), day, zone, withMinutes = false).total!!, 1e-9)
    }

    @Test fun `overlapping independent food and drink entries add together`() {
        val a = record(WATCH, 60, 10, 500.0)
        val b = a.copy(id = "second-item", value = 300.0)
        assertEquals(800.0, dayTotals(listOf(a, b), day, zone, false, additive = true).total!!, 0.0)
        val identical = a.copy(id = "another-serving")
        val cloud = listOf(a, identical).map { it.copy(origin = HealthStore.CLOUD_ORIGIN) }
        assertEquals(1000.0, dayTotals(listOf(a, identical) + cloud, day, zone, false, additive = true).total!!, 0.0)
    }

    private companion object {
        const val WATCH = "com.fitbit.FitbitMobile"
        const val PHONE = "com.google.android.apps.fitness"
    }
}
