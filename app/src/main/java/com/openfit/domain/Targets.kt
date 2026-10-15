package com.openfit.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

enum class FitnessGoal(val weeklyZoneMinutes: Int) {
    Maintain(150),
    Improve(300),
}

data class LoadTarget(val weekly: Double, val daily: Double, val doneThisWeek: Double) {
    val weeklyMet: Boolean get() = doneThisWeek >= weekly
}

private const val WEEKS_OF_HISTORY = 4
private const val MIN_TRACKED_DAYS_PER_WEEK = 4
private const val EWMA_ALPHA = 0.4
private const val PROGRESSION = 1.10
private const val ZONE_RAMP_STEP = 10.0
private const val MAX_ACUTE_TO_CHRONIC = 1.3
private const val MAX_ZONE_MINUTES = 600.0
private const val IMPROVE_ABOVE_GOAL = 1.05
private const val GUIDELINE_ZONE_MINUTES = 150.0
private const val DEFAULT_CARDIO_FLOOR = 130.0
private const val MIN_ZONE_WEEK_FOR_RATIO = 30.0
private const val CATCH_UP_LIMIT = 2.0
private const val MIN_CHRONIC_FOR_CAP = 70.0
private const val ILLNESS_SHARE = 0.25
private const val MAX_READINESS_BOOST = 1.15

fun completedWeeks(daily: Map<LocalDate, Double>, weekStart: LocalDate, count: Int = WEEKS_OF_HISTORY): List<Double?> =
    (count downTo 1).map { weeksAgo ->
        val start = weekStart.minusWeeks(weeksAgo.toLong())
        val tracked = (0L until 7L).mapNotNull { daily[start.plusDays(it)] }
        if (tracked.size < MIN_TRACKED_DAYS_PER_WEEK) null else tracked.sum() * 7 / tracked.size
    }

fun habitualLoad(weeks: List<Double>): Double? {
    if (weeks.isEmpty()) return null
    val rolling = weeks.takeLast(WEEKS_OF_HISTORY).average()
    val smoothed = weeks.drop(1).fold(weeks.first()) { average, week -> EWMA_ALPHA * week + (1 - EWMA_ALPHA) * average }
    return max(rolling, smoothed)
}

fun weeklyCardioTarget(goal: FitnessGoal, weeks: List<Double>, floor: Double): Double {
    val habitual = habitualLoad(weeks) ?: return floor
    return when (goal) {
        FitnessGoal.Maintain -> max(floor, habitual)
        FitnessGoal.Improve -> max(floor, min(PROGRESSION * habitual, MAX_ACUTE_TO_CHRONIC * weeks.takeLast(WEEKS_OF_HISTORY).average()))
    }
}

fun cardioLoadFloor(cardioWeeks: List<Double?>, zoneWeeks: List<Double?>): Double {
    val ratios = cardioWeeks.zip(zoneWeeks).mapNotNull { (cardio, zone) ->
        if (cardio != null && zone != null && zone >= MIN_ZONE_WEEK_FOR_RATIO) cardio / zone else null
    }
    if (ratios.isEmpty()) return DEFAULT_CARDIO_FLOOR
    val sorted = ratios.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    return median * GUIDELINE_ZONE_MINUTES
}

fun weeklyZoneTarget(goal: FitnessGoal, weeks: List<Double>): Double {
    val guideline = goal.weeklyZoneMinutes.toDouble()
    val habitual = habitualLoad(weeks) ?: return guideline
    val ramp = PROGRESSION * habitual + ZONE_RAMP_STEP
    return when {
        habitual < guideline -> min(ramp, guideline)
        goal == FitnessGoal.Maintain -> habitual
        else -> min(IMPROVE_ABOVE_GOAL * habitual, MAX_ZONE_MINUTES)
    }
}

fun dailyTarget(
    weekly: Double,
    doneBeforeToday: Double,
    doneToday: Double,
    dayIndex: Int,
    readiness: Readiness?,
    chronicWeekly: Double? = null,
    previousSixDays: Double? = null,
): LoadTarget {
    val evenShare = weekly / 7
    val remaining = max(0.0, weekly - doneBeforeToday)
    val multiplier = readiness?.let { min(MAX_READINESS_BOOST, 0.5 + it.score / 100.0) } ?: 1.0
    var daily = remaining / (7 - dayIndex.coerceIn(0, 6)) * multiplier
    daily = min(daily, CATCH_UP_LIMIT * evenShare)
    if (chronicWeekly != null && previousSixDays != null && chronicWeekly >= MIN_CHRONIC_FOR_CAP) {
        daily = min(daily, MAX_ACUTE_TO_CHRONIC * chronicWeekly - previousSixDays)
    }
    if (readiness?.illness == true) {
        daily = min(daily, ILLNESS_SHARE * evenShare)
    } else if (readiness != null && readiness.score <= LOW_READINESS) {
        daily = min(daily, evenShare)
    }
    return LoadTarget(weekly = weekly, daily = max(0.0, daily), doneThisWeek = doneBeforeToday + doneToday)
}
