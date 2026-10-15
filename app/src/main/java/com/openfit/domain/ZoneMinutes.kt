package com.openfit.domain

import java.time.Instant
import kotlin.math.roundToInt

data class HeartRateSample(val time: Instant, val bpm: Long)

data class HeartRateZones(val fatBurnFrom: Int, val cardioFrom: Int, val peakFrom: Int)

data class ZoneMinutes(val fatBurn: Int, val cardio: Int, val peak: Int) {
    val total: Int get() = fatBurn + 2 * (cardio + peak)
}

fun heartRateZones(age: Int, restingBpm: Int): HeartRateZones {
    val maxBpm = 220 - age
    val reserve = maxBpm - restingBpm
    fun atReserve(fraction: Double) = (restingBpm + reserve * fraction).roundToInt()
    return HeartRateZones(
        fatBurnFrom = atReserve(0.40),
        cardioFrom = atReserve(0.60),
        peakFrom = atReserve(0.85),
    )
}

fun zoneMinutes(samples: List<HeartRateSample>, zones: HeartRateZones): ZoneMinutes {
    val minuteAverages = averageBpmPerMinute(samples)
    return ZoneMinutes(
        fatBurn = minuteAverages.count { it >= zones.fatBurnFrom && it < zones.cardioFrom },
        cardio = minuteAverages.count { it >= zones.cardioFrom && it < zones.peakFrom },
        peak = minuteAverages.count { it >= zones.peakFrom },
    )
}

fun estimateRestingBpm(samples: List<HeartRateSample>): Int? {
    val sorted = averageBpmPerMinute(samples).sorted()
    if (sorted.isEmpty()) return null
    return sorted[(sorted.size * 0.05).toInt()].roundToInt()
}

private fun averageBpmPerMinute(samples: List<HeartRateSample>): List<Double> =
    samples
        .groupBy { it.time.epochSecond / 60 }
        .values
        .map { minute -> minute.map { it.bpm }.average() }
