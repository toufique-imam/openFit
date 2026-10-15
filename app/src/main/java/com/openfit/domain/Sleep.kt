package com.openfit.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

enum class SleepStage { Awake, Rem, Light, Deep }

data class StageSegment(val stage: SleepStage, val start: Instant, val end: Instant) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

/** A night of sleep, filed under the day it ended: the main sleep's stages and all the day's sleep combined. */
data class SleepNight(
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val segments: List<StageSegment>,
    /** Minutes asleep across every sleep that ended on [date], naps included (Fitbit's daily total). */
    val totalAsleepMinutes: Int,
) {
    val hasStages: Boolean get() = segments.any { it.stage != SleepStage.Awake && it.stage != SleepStage.Light } || segments.size > 1
    val timeInBedMinutes: Long get() = Duration.between(start, end).toMinutes()

    fun minutes(stage: SleepStage): Long = segments.filter { it.stage == stage }.sumOf { it.minutes }

    /** Share of the main sleep (awake included) spent in [stage], 0–100. */
    fun percent(stage: SleepStage): Double {
        val total = segments.sumOf { it.minutes }.takeIf { it > 0 } ?: return 0.0
        return minutes(stage) * 100.0 / total
    }
}

enum class SleepRating { Excellent, Good, Fair, Poor }

fun sleepRating(score: Int): SleepRating = when {
    score >= 90 -> SleepRating.Excellent
    score >= 80 -> SleepRating.Good
    score >= 60 -> SleepRating.Fair
    else -> SleepRating.Poor
}

/** "Typical range for people like you": Fitbit's benchmark bands per stage, as a share of the night (read off its chart). */
val TYPICAL_STAGE_PERCENT: Map<SleepStage, ClosedFloatingPointRange<Double>> = mapOf(
    SleepStage.Awake to 6.0..18.0,
    SleepStage.Rem to 16.0..24.0,
    SleepStage.Light to 40.0..59.0,
    SleepStage.Deep to 13.0..22.0,
)

/**
 * openFit's Sleep Score in Fitbit's shape (duration up to 50, quality up to 25, restoration up to 25). Fitbit computes
 * its score on its servers; these constants were fitted to Fitbit's scores for five nights (within 2 points; see
 * docs/findings_algorithms.md). Fitbit only scores a main sleep of at least 3 hours.
 */
fun sleepScore(night: SleepNight): Int? {
    if (night.timeInBedMinutes < MIN_SCORED_MINUTES) return null
    val asleep = night.totalAsleepMinutes.toDouble()
    val duration = DURATION_POINTS * min(1.0, asleep / FULL_DURATION_MINUTES)
    val quality = if (night.segments.size > 1) {
        val restorative = (night.minutes(SleepStage.Deep) + night.minutes(SleepStage.Rem)).toDouble()
        QUALITY_POINTS * min(1.0, restorative / FULL_DEEP_REM_MINUTES)
    } else {
        QUALITY_POINTS / 2
    }
    val restoration = (RESTORATION_BASE - AWAKE_PENALTY * night.percent(SleepStage.Awake)).coerceIn(0.0, RESTORATION_POINTS)
    return (duration + quality + restoration).roundToInt().coerceIn(1, 100)
}

private const val MIN_SCORED_MINUTES = 180
private const val DURATION_POINTS = 50.0
private const val QUALITY_POINTS = 25.0
private const val RESTORATION_POINTS = 25.0
private const val FULL_DURATION_MINUTES = 465.0
private const val FULL_DEEP_REM_MINUTES = 130.0
private const val RESTORATION_BASE = 18.0
private const val AWAKE_PENALTY = 0.6
