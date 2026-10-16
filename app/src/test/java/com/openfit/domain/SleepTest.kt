package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SleepTest {

    /** A night with the given minutes per stage, laid out back to back from 1am. */
    private fun night(deep: Long, rem: Long, light: Long, awake: Long, total: Int = (deep + rem + light).toInt()): SleepNight {
        var at = Instant.parse("2026-10-14T19:00:00Z")
        val segments = listOf(SleepStage.Deep to deep, SleepStage.Rem to rem, SleepStage.Light to light, SleepStage.Awake to awake)
            .map { (stage, minutes) -> StageSegment(stage, at, at.plusSeconds(minutes * 60)).also { at = it.end } }
        return SleepNight(LocalDate.of(2026, 10, 15), segments.first().start, at, segments, total)
    }

    @Test
    fun scoresMatchFitbitsNights() {
        // Health Connect stage minutes against Fitbit's own scores (24 Sept, 12-15 Oct, 25 Sept, 11 Sept 2026).
        assertEquals(58.0, sleepScore(night(deep = 69, rem = 53, light = 176, awake = 94))!!.toDouble(), 3.0)
        assertEquals(78.0, sleepScore(night(deep = 92, rem = 79, light = 210, awake = 43))!!.toDouble(), 3.0)
        assertEquals(65.0, sleepScore(night(deep = 65, rem = 66, light = 177, awake = 87))!!.toDouble(), 3.0)
        assertEquals(68.0, sleepScore(night(deep = 72, rem = 68, light = 176, awake = 61))!!.toDouble(), 3.0)
        assertEquals(85.0, sleepScore(night(deep = 82, rem = 114, light = 276, awake = 57))!!.toDouble(), 3.0)
        // A short night with plenty of deep sleep and little time awake still scores well.
        assertEquals(79.0, sleepScore(night(deep = 77, rem = 39, light = 75, awake = 10))!!.toDouble(), 3.0)
        assertEquals(76.0, sleepScore(night(deep = 58, rem = 73, light = 158, awake = 19))!!.toDouble(), 3.0)
    }

    @Test
    fun stageMinutesAreSummedInSecondsBeforeRounding() {
        // Sixty-seven segments of 1 m 30 s: truncating each would give 67 minutes instead of 100.5.
        val start = Instant.parse("2026-10-14T18:05:00Z")
        val segments = (0 until 67).map { i -> StageSegment(SleepStage.Light, start.plusSeconds(90L * i), start.plusSeconds(90L * (i + 1))) }
        assertEquals(101L, minutesOf(segments))
    }

    @Test
    fun onlyTheMainSleepIsScored() {
        // 5 Oct 2026: 9 h 05 m asleep that day, but the main sleep was 5 h 39 m with a quarter of it awake. Fitbit: 54.
        val withNap = night(deep = 28, rem = 71, light = 140, awake = 82, total = 539)
        assertEquals(54.0, sleepScore(withNap)!!.toDouble(), 3.0)
    }

    @Test
    fun shortSleepHasNoScoreAndRatingsFollowFitbitsBands() {
        assertNull(sleepScore(night(deep = 30, rem = 20, light = 60, awake = 10)))
        assertEquals(SleepRating.Poor, sleepRating(58))
        assertEquals(SleepRating.Fair, sleepRating(78))
        assertEquals(SleepRating.Good, sleepRating(85))
        assertEquals(SleepRating.Excellent, sleepRating(90))
    }
}
