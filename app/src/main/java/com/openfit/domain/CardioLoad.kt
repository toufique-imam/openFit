package com.openfit.domain

import java.time.Instant
import java.time.LocalDate
import kotlin.math.exp

enum class Sex(val loadExponent: Double) {
    Male(1.92),
    Female(1.67),
}

private const val NEUTRAL_LOAD_EXPONENT = (1.92 + 1.67) / 2
private const val LOAD_FACTOR = 0.64
private const val MIN_RESERVE = 0.30
private const val FULL_WEIGHT_RESERVE = 0.40
private const val LOW_INTENSITY_WEIGHT = 0.5

/** Max heart rate for heart-rate zones (Fitbit's documented 220 − age). */
fun maxHeartRate(age: Int): Int = 220 - age

/**
 * Max heart rate for Cardio Load. Tanaka's 208 − 0.7 × age matched Fitbit's daily cardio load over 17 days far better
 * than 220 − age (see docs/findings_algorithms.md, "Validation").
 */
fun cardioMaxHeartRate(age: Int): Double = 208 - 0.7 * age

fun cardioLoadPerMinute(reserveFraction: Double, sex: Sex?): Double {
    if (reserveFraction < MIN_RESERVE) return 0.0
    val x = reserveFraction.coerceAtMost(1.0)
    val load = LOAD_FACTOR * x * exp((sex?.loadExponent ?: NEUTRAL_LOAD_EXPONENT) * x)
    return if (x < FULL_WEIGHT_RESERVE) load * LOW_INTENSITY_WEIGHT else load
}

/** Load earned in each epoch minute that has movement (or every minute when movement is unknown). */
fun cardioLoadByMinute(
    samples: List<HeartRateSample>,
    restingBpm: Int,
    maxBpm: Double,
    sex: Sex?,
    movingMinutes: Set<Long>?,
): Map<Long, Double> {
    val reserve = (maxBpm - restingBpm).coerceAtLeast(1.0)
    return averageBpmByMinute(samples)
        .filterKeys { minute -> movingMinutes == null || minute in movingMinutes }
        .mapValues { (_, bpm) -> cardioLoadPerMinute((bpm - restingBpm) / reserve, sex) }
        .filterValues { it > 0.0 }
}

fun cardioLoad(
    samples: List<HeartRateSample>,
    restingBpm: Int,
    maxBpm: Double,
    sex: Sex?,
    movingMinutes: Set<Long>?,
): Double = cardioLoadByMinute(samples, restingBpm, maxBpm, sex, movingMinutes).values.sum()

data class WorkoutLoad(val name: String, val start: Instant, val end: Instant, val load: Double)

/** One day of Cardio Load: what each minute earned, and which part came from logged workouts. */
data class CardioLoadDay(val date: LocalDate, val byMinute: Map<Long, Double>, val workouts: List<WorkoutLoad>) {
    val total: Double get() = byMinute.values.sum()
    val workoutShare: Double get() = if (total > 0) workouts.sumOf { it.load }.coerceAtMost(total) / total else 0.0

    /** Running total at the end of each minute that earned load, in time order. */
    fun cumulative(): List<Pair<Long, Double>> {
        var sum = 0.0
        return byMinute.toSortedMap().map { (minute, load) ->
            sum += load
            minute to sum
        }
    }
}

fun workoutLoads(byMinute: Map<Long, Double>, exercises: List<Exercise>): List<WorkoutLoad> =
    exercises.map { exercise ->
        val minutes = exercise.start.epochSecond / 60 until (exercise.end.epochSecond + 59) / 60
        WorkoutLoad(exercise.name, exercise.start, exercise.end, minutes.sumOf { byMinute[it] ?: 0.0 })
    }
