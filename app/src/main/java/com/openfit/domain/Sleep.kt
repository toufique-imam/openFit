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

    /** Minutes asleep in the main sleep alone (its non-awake stages), which is what Fitbit scores. */
    val mainAsleepMinutes: Long get() = segments.filter { it.stage != SleepStage.Awake }.sumOf { it.minutes }

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
 * openFit's Sleep Score in Fitbit's published shape: duration, quality (deep + REM) and restoration (time awake).
 * Fitbit computes its score on its servers and scores the main sleep only, not the day's naps (a 9 h 05 m day with a
 * 5.5 h main sleep scored 54). The constants were fitted to 31 of Fitbit's nightly scores, 9 Sept - 16 Oct 2026:
 * 3.2 points RMS, 27 nights within 3 (see docs/findings_algorithms.md). Fitbit gives no score below 3 hours asleep.
 */
fun sleepScore(night: SleepNight): Int? {
    val asleep = night.mainAsleepMinutes.toDouble()
    if (asleep < MIN_SCORED_MINUTES) return null
    val duration = DURATION_POINTS_PER_HOUR * min(asleep, FULL_DURATION_MINUTES) / 60
    val restorativePercent = if (night.segments.size > 1) {
        (night.minutes(SleepStage.Deep) + night.minutes(SleepStage.Rem)) * 100.0 / asleep
    } else {
        TYPICAL_DEEP_REM_PERCENT
    }
    val quality = QUALITY_POINTS_PER_PERCENT * restorativePercent
    val restoration = -AWAKE_POINTS_PER_PERCENT * night.percent(SleepStage.Awake)
    return (SCORE_BASE + duration + quality + restoration).roundToInt().coerceIn(1, 100)
}

private const val MIN_SCORED_MINUTES = 180
private const val SCORE_BASE = 41.3
private const val DURATION_POINTS_PER_HOUR = 4.37
private const val FULL_DURATION_MINUTES = 465.0
private const val QUALITY_POINTS_PER_PERCENT = 0.42
private const val AWAKE_POINTS_PER_PERCENT = 0.86

/** Nights recorded without stages are scored as if deep + REM made up a typical share of the sleep. */
private const val TYPICAL_DEEP_REM_PERCENT = 38.0
