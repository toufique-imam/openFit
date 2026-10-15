package com.openfit.domain

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

fun maxHeartRate(age: Int): Int = 220 - age

fun cardioLoadPerMinute(reserveFraction: Double, sex: Sex?): Double {
    if (reserveFraction < MIN_RESERVE) return 0.0
    val x = reserveFraction.coerceAtMost(1.0)
    val load = LOAD_FACTOR * x * exp((sex?.loadExponent ?: NEUTRAL_LOAD_EXPONENT) * x)
    return if (x < FULL_WEIGHT_RESERVE) load * LOW_INTENSITY_WEIGHT else load
}

fun cardioLoad(
    samples: List<HeartRateSample>,
    restingBpm: Int,
    maxBpm: Int,
    sex: Sex?,
    movingMinutes: Set<Long>?,
): Double {
    val reserve = (maxBpm - restingBpm).coerceAtLeast(1)
    return averageBpmByMinute(samples)
        .filterKeys { minute -> movingMinutes == null || minute in movingMinutes }
        .values
        .sumOf { bpm -> cardioLoadPerMinute((bpm - restingBpm) / reserve, sex) }
}
