package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RunFitnessTest {

    /** A day with [minutes] (metres, bpm) from 19:54, nothing recorded otherwise. */
    private fun day(vararg minutes: Pair<Int, Int>): List<MovingMinute?> =
        List(MINUTES_PER_DAY) { i -> minutes.getOrNull(i - START)?.let { (m, bpm) -> MovingMinute(m.toDouble(), bpm.toDouble()) } }

    /** 17 Sept 2026's treadmill run (watch distance / heart rate per minute): 14 minutes at about 10 km/h to 190 bpm. */
    private val september17 = day(
        41 to 76, 166 to 108, 168 to 129, 173 to 144, 170 to 157, 172 to 166, 171 to 173, 170 to 176, 168 to 176,
        168 to 183, 172 to 185, 166 to 185, 171 to 187, 167 to 188, 167 to 190,
    )

    @Test
    fun `a steady run to near maximum heart rate rates fitness`() {
        val bouts = runBouts(september17, maxBpm = 188.65)
        val bout = bouts.single()
        // Heart rate passes 95% of 188.65 (179.2) on the run's ninth minute, at a mean of 169.6 m/min.
        assertEquals(START + 1, bout.startMinute)
        assertEquals(9, bout.minutes)
        assertEquals((0.2 * bout.metersPerMinute + 3.5) / 0.92, bout.vo2Max, 1e-9)
        assertEquals(40.7, bout.vo2Max, 0.1)
    }

    @Test
    fun `a quick rise after an earlier interval, a walk or an easy run does not count`() {
        // 13 Sept's second interval: heart rate is already near maximum in its second minute.
        val secondInterval = day(129 to 143, 169 to 168, 175 to 179, 173 to 183, 174 to 185, 176 to 187, 174 to 187)
        assertTrue(runBouts(secondInterval, maxBpm = 188.65).isEmpty())
        // A brisk walk that pushes heart rate up is too slow to count.
        val walk = day(*Array(12) { 90 to 182 })
        assertTrue(runBouts(walk, maxBpm = 188.65).isEmpty())
        // An easy run that never nears maximum leaves no estimate.
        val easy = day(*Array(25) { 150 to 150 })
        assertTrue(runBouts(easy, maxBpm = 188.65).isEmpty())
    }

    @Test
    fun `maximum heart rate is the age formula or the highest two minutes seen`() {
        assertEquals(cardioMaxHeartRate(29), personalMaxBpm(29, listOf(day(150 to 170, 150 to 175))), 1e-9)
        assertEquals(188.0, personalMaxBpm(29, listOf(september17)), 1e-9)
    }

    @Test
    fun `runs win over resting heart rate, measurements over both`() {
        val date = LocalDate.of(2026, 10, 18)
        val bout = runBouts(september17, maxBpm = 188.65).single()
        val runs = mapOf(LocalDate.of(2026, 9, 17) to listOf(bout))
        val resting = mapOf(date to 60.0)
        val estimated = dailyVo2Max(29, listOf(date), emptyMap(), resting, runs).getValue(date)
        assertEquals(bout.vo2Max to Vo2Source.Runs, estimated)
        // Older than six weeks: back to resting heart rate.
        val later = date.plusDays(30)
        assertEquals(Vo2Source.RestingHeartRate, dailyVo2Max(29, listOf(later), emptyMap(), mapOf(later to 60.0), runs).getValue(later).second)
        assertEquals(45.0 to Vo2Source.Measured, dailyVo2Max(29, listOf(date), mapOf(date to 45.0), resting, runs).getValue(date))

        val level = fitnessLevel(29, Sex.Male, measured = null, averageRestingBpm = 60.0, fromRuns = bout.vo2Max)!!
        assertEquals(Vo2Source.Runs, level.source)
        assertTrue(level.estimated)
        assertNull(runVo2Max(emptyList()))
        assertEquals(41.0, runVo2Max(listOf(bout.copy(vo2Max = 40.0), bout.copy(vo2Max = 42.0))))
    }

    private companion object {
        const val START = 19 * 60 + 54
    }
}
