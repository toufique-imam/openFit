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
    fun scoresMatchFitbitWithinTwoPoints() {
        // Stage minutes from Health Connect against Fitbit's own scores for the same nights.
        assertEquals(58.0, sleepScore(night(deep = 69, rem = 53, light = 176, awake = 94))!!.toDouble(), 2.5)
        assertEquals(78.0, sleepScore(night(deep = 92, rem = 79, light = 210, awake = 43))!!.toDouble(), 2.5)
        assertEquals(65.0, sleepScore(night(deep = 65, rem = 66, light = 177, awake = 87))!!.toDouble(), 2.5)
        assertEquals(68.0, sleepScore(night(deep = 72, rem = 68, light = 176, awake = 61))!!.toDouble(), 2.5)
        assertEquals(85.0, sleepScore(night(deep = 82, rem = 114, light = 276, awake = 57))!!.toDouble(), 2.5)
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
