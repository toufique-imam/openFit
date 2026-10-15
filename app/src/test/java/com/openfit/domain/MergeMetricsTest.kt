package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MergeMetricsTest {

    private val morning = Instant.parse("2026-10-15T07:00:00Z")
    private val evening = Instant.parse("2026-10-15T19:00:00Z")

    @Test
    fun cumulativeTotalsKeepTheMostUpToDateSource() {
        val merged = mergeMetrics(DailyMetrics(steps = 4_000, floors = 3.0), DailyMetrics(steps = 6_500, distanceMeters = 4_800.0))

        assertEquals(6_500L, merged.steps)
        assertEquals(3.0, merged.floors)
        assertEquals(4_800.0, merged.distanceMeters)
    }

    @Test
    fun readingsKeepTheLatestMeasurement() {
        val merged = mergeMetrics(
            DailyMetrics(weightKg = Reading(80.0, morning), restingBpm = Reading(58, evening)),
            DailyMetrics(weightKg = Reading(79.4, evening), restingBpm = Reading(61, morning)),
        )

        assertEquals(79.4, merged.weightKg?.value)
        assertEquals(58, merged.restingBpm?.value)
    }

    @Test
    fun scoredMetricsPreferThePrimarySource() {
        val reported = ZoneMinutes(fatBurn = 10, cardio = 5, peak = 0)
        val computed = ZoneMinutes(fatBurn = 14, cardio = 6, peak = 1)

        val merged = mergeMetrics(DailyMetrics(zoneMinutes = reported), DailyMetrics(zoneMinutes = computed))

        assertEquals(reported, merged.zoneMinutes)
        assertEquals(computed, mergeMetrics(DailyMetrics(), DailyMetrics(zoneMinutes = computed)).zoneMinutes)
    }

    @Test
    fun overlappingExercisesAreCountedOnce() {
        val run = Exercise("Run", morning, morning.plus(Duration.ofMinutes(30)))
        val sameRunElsewhere = Exercise("Running", morning.plusSeconds(90), morning.plus(Duration.ofMinutes(31)))
        val walk = Exercise("Walk", evening, evening.plus(Duration.ofMinutes(20)))

        val merged = mergeMetrics(DailyMetrics(exercises = listOf(run)), DailyMetrics(exercises = listOf(walk, sameRunElsewhere)))

        assertEquals(listOf(run, walk), merged.exercises)
    }
}
