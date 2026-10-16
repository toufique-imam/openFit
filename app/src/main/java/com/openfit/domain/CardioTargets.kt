@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A day's Cardio Load target range, and the full-scale value of the arc that shows it. */
@Serializable
data class LoadRange(val low: Double, val high: Double, val scale: Double) {
    operator fun contains(load: Double): Boolean = load >= low && load <= high
}

enum class TargetStatus { Below, InTarget, Above }

enum class TrainingStatus {
    Undertraining,
    RiskOfUndertraining,
    Maintaining,
    Improving,
    RiskOfOvertraining,
    Overtraining,
}

private const val ROLLING_WEEKS = 4
private const val MIN_WEEKS_FOR_STATUS = 2
private const val MIN_TRACKED_DAYS_PER_WEEK = 4
private const val LOW_READINESS_SHIFT = -0.10
private const val ILLNESS_HIGH_SHARE = 0.05

/**
 * Fitbit's minimum daily target: one value for all users (white paper, section 3.3). On 15, 18 and 23 Sept 2026 its
 * range bottomed out at 4 and never went under 24.
 */
private const val MIN_TARGET_LOW = 4.0
private const val MIN_TARGET_HIGH = 24.0
private const val RING_SCALE_SHARE = 0.45
private const val RING_HEADROOM = 1.25
// Fitbit 4.67's acute:chronic bands and bar segments (decompiled; the ratio itself is computed on Fitbit's server).
private val TRAINING_THRESHOLDS = listOf(
    0.4 to TrainingStatus.Undertraining,
    0.8 to TrainingStatus.RiskOfUndertraining,
    1.1 to TrainingStatus.Maintaining,
    1.3 to TrainingStatus.Improving,
    1.5 to TrainingStatus.RiskOfOvertraining,
)
private const val MAX_RATIO = 1.89
private val BAR_SEGMENTS = listOf(0.0, 0.8, 0.945, 1.1, 1.195, 1.3, MAX_RATIO)

/**
 * The four rolling 7-day blocks before [date] (oldest first), each scaled to a full week. Blocks with fewer than
 * four tracked days are left out.
 */
fun rollingWeeks(date: LocalDate, daily: Map<LocalDate, Double>): List<Double> =
    (ROLLING_WEEKS downTo 1).mapNotNull { weeksAgo ->
        val start = date.minusDays(7L * weeksAgo)
        val tracked = (0L until 7L).mapNotNull { daily[start.plusDays(it)] }
        if (tracked.size < MIN_TRACKED_DAYS_PER_WEEK) null else tracked.sum() * 7 / tracked.size
    }

/**
 * Chronic weekly load before [date]: the 28-day rolling mean. It reproduces Fitbit's daily ranges more closely than
 * the max(rolling mean, EWMA) the 2025 white paper uses for its weekly targets (see docs/findings_algorithms.md).
 */
fun chronicWeeklyLoad(date: LocalDate, daily: Map<LocalDate, Double>): Double? =
    rollingWeeks(date, daily).takeIf { it.isNotEmpty() }?.average()?.takeIf { it > 0 }

fun previousSixDays(date: LocalDate, daily: Map<LocalDate, Double>): Double = (1L..6L).sumOf { daily[date.minusDays(it)] ?: 0.0 }

/**
 * Fitbit 4.67's daily target load, reverse-engineered from 19 of its daily ranges (7-25 Sept 2026): today's load that
 * brings the rolling 7-day load to a window around the chronic weekly load, never below Fitbit's minimum target. The
 * window is set by the fitness goal. Readiness from 43 to 88 left it unchanged, so only low readiness moves it
 * (assumed; untested), and a day without a readiness score still gets a target, as on Fitbit.
 */
fun dailyCardioRange(goal: FitnessGoal, chronicWeekly: Double, previousSixDays: Double, readiness: Readiness?): LoadRange {
    val shift = if (readiness?.level == ReadinessLevel.Low) LOW_READINESS_SHIFT else 0.0
    var high = max((goal.highLoadRatio + shift) * chronicWeekly - previousSixDays, MIN_TARGET_HIGH)
    var low = ((goal.lowLoadRatio + shift) * chronicWeekly - previousSixDays).coerceIn(MIN_TARGET_LOW, high)
    if (readiness?.illness == true) {
        low = 0.0
        high = ILLNESS_HIGH_SHARE * chronicWeekly
    }
    return LoadRange(low = low, high = high, scale = max(RING_SCALE_SHARE * chronicWeekly, RING_HEADROOM * high))
}

/** The target for [date], or null without a week of history; Fitbit then shows "No target". */
fun dailyCardioRange(date: LocalDate, goal: FitnessGoal, daily: Map<LocalDate, Double>, readiness: Readiness?): LoadRange? {
    val chronic = chronicWeeklyLoad(date, daily) ?: return null
    return dailyCardioRange(goal, chronic, previousSixDays(date, daily), readiness)
}

fun targetStatus(load: Double, range: LoadRange): TargetStatus = when {
    load < range.low -> TargetStatus.Below
    load > range.high -> TargetStatus.Above
    else -> TargetStatus.InTarget
}

/** The past seven days (ending on [date]) against the 28-day average week, as on Fitbit's "past seven days of training". */
fun acuteChronicRatio(date: LocalDate, daily: Map<LocalDate, Double>): Double? {
    val lastWeek = (0L until 7L).mapNotNull { daily[date.minusDays(it)] }
    if (lastWeek.size < MIN_TRACKED_DAYS_PER_WEEK) return null
    val acute = lastWeek.sum() * 7 / lastWeek.size
    val weeks = rollingWeeks(date.plusDays(1), daily)
    if (weeks.size < MIN_WEEKS_FOR_STATUS) return null
    // Fitbit clamps the ratio at 1.89 and truncates it to two decimals.
    return weeks.average().takeIf { it > 0 }?.let { floor(min(acute / it, MAX_RATIO) * 100) / 100 }
}

fun trainingStatus(ratio: Double): TrainingStatus =
    TRAINING_THRESHOLDS.firstOrNull { (limit, _) -> ratio < limit }?.second ?: TrainingStatus.Overtraining

/** Position of [ratio] on Fitbit's six equal bar segments (0–0.8, 0.8–0.945, …, 1.3–1.89), 0..1. */
fun trainingBarPosition(ratio: Double): Double {
    val clamped = ratio.coerceIn(0.0, MAX_RATIO)
    val segments = BAR_SEGMENTS.size - 1
    val segment = BAR_SEGMENTS.zipWithNext().indexOfFirst { (_, to) -> clamped < to }.let { if (it < 0) segments - 1 else it }
    val (from, to) = BAR_SEGMENTS[segment] to BAR_SEGMENTS[segment + 1]
    return (segment + ((clamped - from) / (to - from)).coerceIn(0.0, 1.0)) / segments
}
