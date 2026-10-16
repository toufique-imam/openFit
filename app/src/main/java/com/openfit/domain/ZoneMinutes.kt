@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

data class HeartRateSample(val time: Instant, val bpm: Long)

@Serializable
data class HeartRateZones(val fatBurnFrom: Int, val cardioFrom: Int, val peakFrom: Int)

@Serializable
data class ZoneMinutes(val fatBurn: Int, val cardio: Int, val peak: Int) {
    val total: Int get() = fatBurn + 2 * (cardio + peak)
}

fun heartRateZones(age: Int, restingBpm: Int): HeartRateZones {
    val maxBpm = maxHeartRate(age)
    val reserve = maxBpm - restingBpm
    // Fitbit rounds zone thresholds up: rest 61, max 190 gives 113 / 139 / 171 (138.4 → 139).
    fun atReserve(fraction: Double) = ceil(restingBpm + reserve * fraction).toInt()
    return HeartRateZones(
        fatBurnFrom = atReserve(0.40),
        cardioFrom = atReserve(0.60),
        peakFrom = atReserve(0.85),
    )
}

fun zoneMinutes(samples: List<HeartRateSample>, zones: HeartRateZones): ZoneMinutes {
    val minuteAverages = averageBpmByMinute(samples).values
    return ZoneMinutes(
        fatBurn = minuteAverages.count { it >= zones.fatBurnFrom && it < zones.cardioFrom },
        cardio = minuteAverages.count { it >= zones.cardioFrom && it < zones.peakFrom },
        peak = minuteAverages.count { it >= zones.peakFrom },
    )
}

fun estimateRestingBpm(samples: List<HeartRateSample>): Int? {
    val sorted = averageBpmByMinute(samples).values.sorted()
    if (sorted.isEmpty()) return null
    return sorted[(sorted.size * 0.05).toInt()].roundToInt()
}

fun averageBpmByMinute(samples: List<HeartRateSample>): Map<Long, Double> =
    samples
        .groupBy { it.time.epochSecond / 60 }
        .mapValues { (_, minute) -> minute.map { it.bpm }.average() }
