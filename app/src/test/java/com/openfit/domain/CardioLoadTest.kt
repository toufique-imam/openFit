package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CardioLoadTest {

    @Test
    fun followsBanisterWeighting() {
        assertEquals(0.552, cardioLoadPerMinute(0.4, Sex.Male), 0.001)
        assertEquals(4.36, cardioLoadPerMinute(1.0, Sex.Male), 0.01)
        assertEquals(0.0, cardioLoadPerMinute(0.29, Sex.Male), 0.0)
    }

    @Test
    fun lightMinutesCountHalfAndSexOnlyChangesTheExponent() {
        val full = 0.64 * 0.35 * kotlin.math.exp(1.92 * 0.35)

        assertEquals(full / 2, cardioLoadPerMinute(0.35, Sex.Male), 1e-9)
        assertEquals(0.64 * 0.6 * kotlin.math.exp(1.67 * 0.6), cardioLoadPerMinute(0.6, Sex.Female), 1e-9)
    }

    @Test
    fun onlyMovingMinutesAccrueLoad() {
        val start = Instant.parse("2026-10-15T08:00:00Z")
        val minutes = (0L until 3L).map { HeartRateSample(start.plusSeconds(it * 60), 140) }
        val firstMinute = start.epochSecond / 60

        val all = cardioLoad(minutes, restingBpm = 60, maxBpm = 180, sex = Sex.Male, movingMinutes = null)
        val moving = cardioLoad(minutes, restingBpm = 60, maxBpm = 180, sex = Sex.Male, movingMinutes = setOf(firstMinute))

        assertEquals(3 * cardioLoadPerMinute(80.0 / 120, Sex.Male), all, 1e-9)
        assertEquals(all / 3, moving, 1e-9)
    }
}
