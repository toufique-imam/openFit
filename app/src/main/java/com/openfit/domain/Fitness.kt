@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

/**
 * VO2 max from heart rate alone (Uth et al. 2004, Eur J Appl Physiol): 15.3 x max heart rate / resting heart rate,
 * with max heart rate 220 - age. This is a population-derived estimate rather than a direct measurement.
 */
fun estimateVo2Max(age: Int, restingBpm: Double): Double = UTH_FACTOR * (220 - age) / restingBpm

private const val UTH_FACTOR = 15.3

enum class FitnessBand { Poor, Fair, Good, Excellent, Superior }

@Serializable
data class FitnessLevel(val vo2Max: Double, val estimated: Boolean, val percentile: Double) {
    val band: FitnessBand
        get() = when {
            percentile >= 80 -> FitnessBand.Superior
            percentile >= 60 -> FitnessBand.Excellent
            percentile >= 40 -> FitnessBand.Good
            percentile >= 20 -> FitnessBand.Fair
            else -> FitnessBand.Poor
        }
}

private val PERCENTILES = listOf(5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0)

/**
 * FRIEND treadmill reference values (Kaminsky et al. 2015, Prog Cardiovasc Dis), VO2 max in mL/kg/min at the 5th, 10th,
 * 25th, 50th, 75th, 90th and 95th percentiles for each decade from 20-29 to 70-79.
 */
private val FRIEND = mapOf(
    Sex.Male to listOf(
        listOf(29.0, 32.1, 40.1, 48.0, 55.2, 61.8, 66.3),
        listOf(27.2, 30.2, 35.9, 42.4, 49.2, 56.5, 59.8),
        listOf(24.2, 26.8, 31.9, 37.8, 45.0, 52.1, 55.6),
        listOf(20.9, 22.8, 27.1, 32.6, 39.7, 45.6, 50.7),
        listOf(17.4, 19.8, 23.7, 28.2, 34.5, 40.3, 43.0),
        listOf(16.3, 17.1, 20.4, 24.4, 30.4, 36.6, 39.7),
    ),
    Sex.Female to listOf(
        listOf(21.7, 23.9, 30.5, 37.6, 44.7, 51.3, 56.0),
        listOf(19.0, 20.9, 25.3, 30.2, 36.1, 41.4, 45.8),
        listOf(17.0, 18.8, 22.1, 26.7, 32.4, 38.4, 41.7),
        listOf(16.0, 17.3, 19.9, 23.4, 27.6, 32.0, 35.9),
        listOf(13.4, 14.6, 17.2, 20.0, 23.8, 27.0, 29.4),
        listOf(13.1, 13.6, 15.6, 18.3, 20.8, 23.1, 24.1),
    ),
)

/** Where [vo2Max] sits in the user's FRIEND row, read linearly between columns and kept within the 5th-95th. */
fun fitnessPercentile(vo2Max: Double, age: Int, sex: Sex): Double {
    val row = FRIEND.getValue(sex)[((age - 20) / 10).coerceIn(0, 5)]
    if (vo2Max <= row.first()) return PERCENTILES.first()
    if (vo2Max >= row.last()) return PERCENTILES.last()
    val upper = row.indexOfFirst { it >= vo2Max }
    val (v0, v1) = row[upper - 1] to row[upper]
    val (p0, p1) = PERCENTILES[upper - 1] to PERCENTILES[upper]
    return p0 + (p1 - p0) * (vo2Max - v0) / (v1 - v0)
}

/** A measured VO2 max when there is one, else the Uth estimate from the average resting heart rate. */
fun fitnessLevel(age: Int, sex: Sex, measured: Double?, averageRestingBpm: Double?): FitnessLevel? {
    val vo2 = measured ?: averageRestingBpm?.let { estimateVo2Max(age, it) } ?: return null
    return FitnessLevel(vo2, estimated = measured == null, percentile = fitnessPercentile(vo2, age, sex))
}

/** The VO2 max at [percentile] in the user's FRIEND row (the inverse of [fitnessPercentile]), clamped to the 5th-95th. */
fun vo2AtPercentile(percentile: Double, age: Int, sex: Sex): Double {
    val row = FRIEND.getValue(sex)[((age - 20) / 10).coerceIn(0, 5)]
    val p = percentile.coerceIn(PERCENTILES.first(), PERCENTILES.last())
    val upper = PERCENTILES.indexOfFirst { it >= p }.coerceAtLeast(1)
    val (p0, p1) = PERCENTILES[upper - 1] to PERCENTILES[upper]
    val (v0, v1) = row[upper - 1] to row[upper]
    return v0 + (v1 - v0) * (p - p0) / (p1 - p0)
}

/** Where each band above Poor starts, in mL/kg/min: the 20th, 40th, 60th and 80th percentiles. */
fun fitnessBandEdges(age: Int, sex: Sex): List<Double> = BAND_EDGES.map { vo2AtPercentile(it, age, sex) }

private val BAND_EDGES = listOf(20.0, 40.0, 60.0, 80.0)

/**
 * VO2 max per day in [days]: the day's measurement if there is one, else the estimate from the average resting heart
 * rate over the [ESTIMATE_DAYS] days to that day (as on Today). Days with neither are left out.
 */
fun dailyVo2Max(age: Int, days: List<LocalDate>, measured: Map<LocalDate, Double>, resting: Map<LocalDate, Double>): Map<LocalDate, Pair<Double, Boolean>> =
    days.mapNotNull { day ->
        measured[day]?.let { return@mapNotNull day to (it to false) }
        val window = (0 until ESTIMATE_DAYS).mapNotNull { resting[day.minusDays(it.toLong())] }
        window.takeIf { it.isNotEmpty() }?.let { day to (estimateVo2Max(age, it.average()) to true) }
    }.toMap()

const val ESTIMATE_DAYS = 30
