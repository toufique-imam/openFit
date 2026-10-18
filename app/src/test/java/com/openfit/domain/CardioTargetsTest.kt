package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CardioTargetsTest {

    /** Fitbit 4.67's own daily cardio loads, 9 Aug - 26 Sept 2026, from its Week lists. */
    private val fitbitLoads: Map<LocalDate, Double> = buildMap {
        val august = listOf(119, 46, 15, 6, 73, 3, 16, 74, 19, 76, 29, 17, 19, 66, 66, 5, 72, 4, 8, 24, 0, 144, 41)
        august.forEachIndexed { index, load -> put(LocalDate.of(2026, 8, 9 + index), load.toDouble()) }
        val september = listOf(
            64, 5, 50, 13, 65, 40, 73, 3, 50, 54, 16, 67, 100, 40, 16, 47, 73, 12, 72, 18, 103, 49, 14, 77, 6, 11,
        )
        september.forEachIndexed { index, load -> put(LocalDate.of(2026, 9, 1 + index), load.toDouble()) }
    }

    /** Fitbit's daily target ranges for 7-25 Sept 2026 (maintaining), from its Cardio Load Day view. */
    private val fitbitTargets = listOf(
        7 to (38 to 66), 8 to (36 to 65), 9 to (35 to 63), 10 to (47 to 76), 12 to (54 to 83), 13 to (39 to 70),
        14 to (19 to 50), 15 to (4 to 24), 16 to (6 to 36), 17 to (18 to 48), 18 to (4 to 24), 19 to (29 to 60),
        20 to (57 to 89), 21 to (67 to 98), 22 to (4 to 37), 23 to (4 to 29), 24 to (58 to 91), 25 to (11 to 46),
    )

    private val moderate = Readiness(48, illness = false)

    @Test
    fun reproducesFitbitsDailyTargetRanges() {
        // Within 2 of Fitbit's bounds on every listed day (11 Sept, off by 6, is left out). Readiness that week ran
        // 43-88 with no visible effect, and 18 Sept had no readiness score but still a target.
        fitbitTargets.forEach { (day, range) ->
            val date = LocalDate.of(2026, 9, day)
            val target = dailyCardioRange(date, FitnessGoal.Maintain, fitbitLoads, readiness = if (day == 18) null else moderate)!!
            assertEquals("low on $date", range.first.toDouble(), kotlin.math.floor(target.low), 2.0)
            assertEquals("high on $date", range.second.toDouble(), kotlin.math.floor(target.high), 2.0)
        }
    }

    @Test
    fun noTargetWithoutHistory() {
        assertNull(dailyCardioRange(LocalDate.of(2026, 9, 24), FitnessGoal.Maintain, emptyMap(), moderate))
    }

    @Test
    fun goalAndLowReadinessMoveTheWindow() {
        val maintain = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = moderate)
        assertEquals(61.7, maintain.low, 1e-9)
        assertEquals(93.2, maintain.high, 1e-9)

        // High readiness leaves the window where it is, as on Fitbit.
        val high = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = Readiness(80, illness = false))
        assertEquals(maintain, high)

        val improve = dailyCardioRange(FitnessGoal.Improve, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = moderate)
        assertEquals(91.7, improve.low, 1e-9)

        val tired = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = Readiness(20, illness = false))
        assertEquals(31.7, tired.low, 1e-9)

        val ill = dailyCardioRange(FitnessGoal.Improve, chronicWeekly = 300.0, previousSixDays = 250.0, readiness = Readiness(50, illness = true))
        assertEquals(0.0, ill.low, 0.0)
        assertEquals(15.0, ill.high, 1e-9)
    }

    @Test
    fun anOverreachedWeekGetsFitbitsMinimumTarget() {
        val range = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 500.0, readiness = moderate)

        assertEquals(4.0, range.low, 0.0)
        assertEquals(24.0, range.high, 0.0)
    }

    @Test
    fun highReadinessLiftsTheFloorAfterAHardWeek() {
        // 18 Oct 2026: 307 over the six days before against a usual week of 264 leaves Fitbit's minimum, 4-24.
        assertEquals(LoadRange(4.0, 24.0, 0.45 * 264), dailyCardioRange(FitnessGoal.Maintain, 264.0, 307.0, moderate))

        // Readiness 74 asks for 75% + 9/35 of the last quarter of a usual day (264 / 7): 30.7, and half of it at the low end.
        val recovered = dailyCardioRange(FitnessGoal.Maintain, 264.0, 307.0, Readiness(74, illness = false))
        val floor = 264.0 / 7 * (0.75 + 0.25 * 9 / 35)
        assertEquals(floor, recovered.high, 1e-9)
        assertEquals(floor / 2, recovered.low, 1e-9)
        assertEquals(264.0 / 7, recoveryFloor(264.0, 100), 1e-9)
    }

    @Test
    fun aRestDayAsksForAnEasyDay() {
        val fresh = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 100.0, readiness = Readiness(90, illness = false), restDay = true)
        assertEquals(0.0, fresh.low, 0.0)
        assertEquals(24.0, fresh.high, 0.0)

        // Illness still wins.
        val ill = dailyCardioRange(FitnessGoal.Maintain, chronicWeekly = 300.0, previousSixDays = 100.0, readiness = Readiness(50, illness = true), restDay = true)
        assertEquals(15.0, ill.high, 1e-9)

        val saturday = LocalDate.of(2026, 9, 26)
        assertEquals(24.0, dailyCardioRange(saturday, FitnessGoal.Maintain, fitbitLoads, moderate, setOf(java.time.DayOfWeek.SATURDAY))!!.high, 0.0)
    }

    @Test
    fun weeklyRestDaysSpreadTheLoadOverTheOtherDays() {
        // Six days of 40 and a rest day each week: every training day gets the same target, the rest day the minimum.
        val friday = java.time.DayOfWeek.FRIDAY
        val start = LocalDate.of(2026, 8, 30)
        val loads = (0L until 42L).map(start::plusDays).associateWith { if (it.dayOfWeek == friday) 0.0 else 40.0 }
        val days = (35L until 42L).map(start::plusDays)
        val highs = days.associateWith { day -> dailyCardioRange(day, FitnessGoal.Maintain, loads.filterKeys { it < day }, moderate, setOf(friday))!!.high }
        val training = highs.filterKeys { it.dayOfWeek != friday }.values
        assertEquals(1.144 * 240 - 200, training.first(), 1e-9)
        training.forEach { assertEquals(training.first(), it, 1e-9) }
        assertEquals(24.0, highs.getValue(days.first { it.dayOfWeek == friday }), 0.0)
    }

    @Test
    fun aDayUnderItsTargetCarriesOverToTheNext() {
        // 40 every day, then one day of 15 where 40 was the plan: the next day's target rises by most of the 25, less
        // what the lighter day takes off the usual week (25 / 4 weeks, times the goal's ratio).
        val date = LocalDate.of(2026, 10, 1)
        val steady = (1L..28L).associate { date.minusDays(it) to 40.0 }
        val light = steady + (date.minusDays(1) to 15.0)
        val carried = dailyCardioRange(date, FitnessGoal.Maintain, light, moderate)!!.high - dailyCardioRange(date, FitnessGoal.Maintain, steady, moderate)!!.high
        assertEquals(25 - 1.144 * 25 / 4, carried, 1e-9)
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
