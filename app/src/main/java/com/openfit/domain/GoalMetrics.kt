package com.openfit.domain

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A goal metric (steps, distance, floors, energy, zone minutes) over a period. */
data class GoalSummary(val averagePerDay: Double?, val total: Double, val goalDays: Int)

/** Without a goal (the user has not set one) no day counts as a goal day. */
fun goalSummary(days: List<LocalDate>, values: Map<LocalDate, Double>, goal: Double?): GoalSummary {
    val tracked = days.mapNotNull { values[it] }
    return GoalSummary(
        averagePerDay = tracked.takeIf { it.isNotEmpty() }?.average(),
        total = tracked.sum(),
        goalDays = if (goal == null) 0 else tracked.count { it >= goal },
    )
}

/** Goal charts label 0, half the goal, the goal itself and — only when a bar passes it — a rounded-up top. */
data class GoalAxis(val max: Double, val labels: List<Double>)

fun goalAxis(goal: Double, highest: Double): GoalAxis {
    if (highest <= goal) return GoalAxis(goal * GOAL_HEADROOM, listOf(0.0, goal / 2, goal))
    val top = roundUp(highest, 2)
    // Fitbit drops the top label when it would crowd the goal's (10,087 steps: no "11k" over "10k").
    val labels = if (top - goal < top * LABEL_CLEARANCE) listOf(0.0, goal / 2, goal) else listOf(0.0, goal / 2, goal, top)
    return GoalAxis(top, labels)
}

/** Charts without a goal (hourly steps): the top rounds up to two significant figures with an even last digit. */
fun hourlyAxis(highest: Double): ChartAxis {
    if (highest <= 0) return ChartAxis(MIN_HOURLY_TOP, (MIN_HOURLY_TOP / 2).toInt())
    val step = 2 * 10.0.pow(floor(log10(highest)) - 1)
    val top = ceil(highest / step) * step
    return ChartAxis(top, (top / 2).toInt())
}

private const val GOAL_HEADROOM = 1.1
private const val LABEL_CLEARANCE = 0.15
private const val MIN_HOURLY_TOP = 100.0

private fun roundUp(value: Double, significant: Int): Double {
    val step = 10.0.pow(floor(log10(value)) - (significant - 1))
    return ceil(value / step) * step
}
