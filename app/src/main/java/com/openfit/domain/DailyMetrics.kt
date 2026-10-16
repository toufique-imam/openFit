@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant

@Serializable
data class Reading<T>(val value: T, val time: Instant)

@Serializable
data class SleepSummary(val asleep: Duration, val end: Instant)

@Serializable
data class Exercise(val name: String, val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)
}

@Serializable
data class DailyMetrics(
    val steps: Long? = null,
    val hourlySteps: Map<Int, Long> = emptyMap(),
    val distanceMeters: Double? = null,
    val floors: Double? = null,
    val caloriesOutKcal: Double? = null,
    val caloriesInKcal: Double? = null,
    val waterLiters: Double? = null,
    val zoneMinutes: ZoneMinutes? = null,
    val cardioLoad: Double? = null,
    val sleep: SleepSummary? = null,
    val exercises: List<Exercise> = emptyList(),
    val heartRateRange: IntRange? = null,
    val latestHeartRate: Reading<Int>? = null,
    val restingBpm: Reading<Int>? = null,
    val hrvMs: Reading<Double>? = null,
    val spo2Percent: Reading<Double>? = null,
    val breathsPerMinute: Reading<Double>? = null,
    val skinTempDeltaCelsius: Reading<Double>? = null,
    val vo2Max: Reading<Double>? = null,
    val weightKg: Reading<Double>? = null,
    val bodyFatPercent: Reading<Double>? = null,
)
