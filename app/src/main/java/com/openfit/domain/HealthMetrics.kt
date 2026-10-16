package com.openfit.domain

import java.time.LocalDate
import kotlin.math.sqrt

/** Fitbit's health metrics: the overnight vitals it compares with a personal range. */
enum class HealthMetric { BreathingRate, RestingHeartRate, HeartRateVariability, BloodOxygen, SkinTemperature }

data class PersonalRange(val low: Double, val high: Double) {
    operator fun contains(value: Double): Boolean = value in low..high
}

private const val MIN_BASELINE_DAYS = 7
private const val BASELINE_DAYS = 30L
private const val RANGE_DEVIATIONS = 2.0

/**
 * Fitbit: "calculates your baseline from the most recent 7 to 30 days of data. Your personal range is plus or minus
 * 2 standard deviations" from it. Null until there are 7 days.
 */
fun personalRange(date: LocalDate, values: Map<LocalDate, Double>): PersonalRange? {
    val baseline = (1..BASELINE_DAYS).mapNotNull { values[date.minusDays(it)] }
    if (baseline.size < MIN_BASELINE_DAYS) return null
    val mean = baseline.average()
    val sd = sqrt(baseline.sumOf { (it - mean) * (it - mean) } / (baseline.size - 1))
    return PersonalRange(mean - RANGE_DEVIATIONS * sd, mean + RANGE_DEVIATIONS * sd)
}

/** One metric's value on a day against its personal range (null range while there is too little history). */
data class MetricReading(val metric: HealthMetric, val value: Double, val range: PersonalRange?) {
    val inRange: Boolean? get() = range?.let { value in it }
}

fun metricReadings(date: LocalDate, values: Map<HealthMetric, Map<LocalDate, Double>>): List<MetricReading> =
    HealthMetric.entries.mapNotNull { metric ->
        val daily = values[metric].orEmpty()
        daily[date]?.let { MetricReading(metric, it, personalRange(date, daily)) }
    }
