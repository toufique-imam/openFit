@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import kotlin.math.roundToInt

/** Fitbit's heart-rate zones: below moderate is "light"; moderate, vigorous and peak follow the AZM thresholds. */
enum class HeartZone { Light, Moderate, Vigorous, Peak }

fun HeartRateZones.zoneOf(bpm: Double): HeartZone = when {
    bpm >= peakFrom -> HeartZone.Peak
    bpm >= cardioFrom -> HeartZone.Vigorous
    bpm >= fatBurnFrom -> HeartZone.Moderate
    else -> HeartZone.Light
}

/** One day of heart rate: the per-minute average, the day's resting rate and time in each zone. */
@Serializable
data class HeartDay(
    val date: LocalDate,
    val byMinute: Map<Long, Double>,
    val restingBpm: Int?,
    val zones: HeartRateZones?,
    val minutes: ZoneMinutes?,
) {
    val range: IntRange? = byMinute.values.takeIf { it.isNotEmpty() }?.let { it.min().roundToInt()..it.max().roundToInt() }
}

/** Minutes at moderate intensity or above (Fitbit's "Heart rate zones" chart). */
val ZoneMinutes.activeMinutes: Int get() = fatBurn + cardio + peak

/** Zones a day reached above light ("3 zones"); Fitbit's counts match moderate, vigorous and peak only. */
fun zonesEntered(minutes: ZoneMinutes): Int = listOf(minutes.fatBurn, minutes.cardio, minutes.peak).count { it > 0 }

/** A heart-rate axis around the day: 5 bpm of room below the low and above the high (Fitbit: 49–188 → 44–193). */
fun heartAxis(range: IntRange): Pair<Double, Double> = (range.first - HEART_MARGIN).toDouble() to (range.last + HEART_MARGIN).toDouble()

private const val HEART_MARGIN = 5
