package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CardioTargetsTest {

    /** Daily cardio load read off Fitbit 4.67's month and week charts (late August estimated from its averages). */
    private val fitbitSeptember: Map<LocalDate, Double> = buildMap {
        (23..29).forEach { put(LocalDate.of(2026, 8, it), 40.0) }
        put(LocalDate.of(2026, 8, 30), 36.0)
        put(LocalDate.of(2026, 8, 31), 36.0)
        listOf(
            63.5, 10.9, 49.6, 12.8, 64.5, 39.7, 72.5, 9.0, 49.6, 53.7, 15.8, 66.5, 99.4, 39.7, 15.8,
            46.8, 72.5, 18.5, 64.3, 18.0, 103.0, 49.0, 14.0, 77.0, 6.0,
        ).forEachIndexed { index, load -> put(LocalDate.of(2026, 9, index + 1), load) }
    }

    private val moderate = Readiness(48, illness = false)

    @Test
    fun reproducesFitbitsDailyTargetRanges() {
        // Fitbit showed "Target 58–91" on Thu 24 Sept and "Target 11–46" on Fri 25 Sept (readiness 48, maintaining).
        // The fitted window leaves about ±0.02 × chronic load (≈ ±7) unexplained, so allow 8.
        val thursday = dailyCardioRange(LocalDate.of(2026, 9, 24), FitnessGoal.Maintain, fitbitSeptember, moderate)!!
        assertEquals(58.0, thursday.low, 8.0)
        assertEquals(91.0, thursday.high, 8.0)

        val friday = dailyCardioRange(LocalDate.of(2026, 9, 25), FitnessGoal.Maintain, fitbitSeptember, moderate)!!
        assertEquals(11.0, friday.low, 8.0)
        assertEquals(46.0, friday.high, 8.0)
    }

    @Test
    fun noTargetWithoutReadinessOrHistory() {
        assertNull(dailyCardioRange(LocalDate.of(2026, 9, 24), FitnessGoal.Maintain, fitbitSeptember, readiness = null))
        assertNull(dailyCardioRange(LocalDate.of(2026, 9, 24), FitnessGoal.Maintain, emptyMap(), moderate))
    }

    @Test
    fun goalAndReadinessMoveTheWindow() {
        val maintain = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = moderate)
        assertEquals(62.0, maintain.low, 1e-9)
        assertEquals(92.0, maintain.high, 1e-9)

        val improve = dailyCardioRange(FitnessGoal.Improve, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = moderate)
        assertEquals(92.0, improve.low, 1e-9)

        val tired = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = Readiness(20, illness = false))
        assertEquals(32.0, tired.low, 1e-9)
        assertEquals(62.0, tired.high, 1e-9)

        val ill = dailyCardioRange(FitnessGoal.Improve, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = Readiness(50, illness = true))
        assertEquals(0.0, ill.low, 0.0)
        assertEquals(15.0, ill.high, 1e-9)
    }

    @Test
    fun anOverreachedWeekStillLeavesALightTarget() {
        val range = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 500.0, readiness = moderate)

        assertEquals(0.0, range.low, 0.0)
        assertEquals(15.0, range.high, 1e-9)
    }

    @Test
    fun trainingStatusComparesTheLastWeekWithTheMonth() {
        val steady = (0L until 28L).associate { LocalDate.of(2026, 9, 28).minusDays(it) to 40.0 }
        val ratio = acuteChronicRatio(LocalDate.of(2026, 9, 28), steady)!!
        assertEquals(1.0, ratio, 1e-9)
        assertEquals(TrainingStatus.Maintaining, trainingStatus(ratio))

        // Fitbit's bands: < 0.4 undertraining, < 0.8 at risk, < 1.1 maintaining, < 1.3 improving, < 1.5 at risk, else over.
        assertEquals(TrainingStatus.Undertraining, trainingStatus(0.39))
        assertEquals(TrainingStatus.RiskOfUndertraining, trainingStatus(0.5))
        assertEquals(TrainingStatus.Improving, trainingStatus(1.2))
        assertEquals(TrainingStatus.Overtraining, trainingStatus(1.6))
        // Six equal segments split at 0.8, 0.945, 1.1, 1.195 and 1.3.
        assertEquals(0.0, trainingBarPosition(0.0), 1e-9)
        assertEquals(2.0 / 6, trainingBarPosition(0.945), 1e-9)
        assertEquals(1.0, trainingBarPosition(2.0), 1e-9)
    }
}
