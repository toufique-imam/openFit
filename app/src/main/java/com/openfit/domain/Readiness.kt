package com.openfit.domain

import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ReadinessLevel { Low, Moderate, High }

data class Readiness(val score: Int, val illness: Boolean) {
    val level: ReadinessLevel
        get() = when {
            score <= LOW_READINESS -> ReadinessLevel.Low
            score >= HIGH_READINESS -> ReadinessLevel.High
            else -> ReadinessLevel.Moderate
        }
}

const val LOW_READINESS = 29
const val HIGH_READINESS = 65
private const val BASELINE_DAYS = 30L
private const val HRV_WEIGHT = 0.4
private const val RESTING_WEIGHT = 0.3
private const val SLEEP_WEIGHT = 0.3
private const val MIN_LN_HRV_SD = 0.05
private const val MIN_RESTING_SD = 2.0
private const val MIN_SLEEP_SD_HOURS = 0.5
private const val SHORT_SLEEP_HOURS = 6.0
private const val MAX_Z = 3.0
private val SLEEP_NIGHT_WEIGHTS = listOf(0.4, 0.3, 0.2, 0.1)
private const val ILLNESS_JUMP_BPM = 7
private const val ILLNESS_STREAK_BPM = 4
private const val MIN_RESTING_BASELINE = 5
private const val MIN_COMPONENTS = 2

fun readiness(date: LocalDate, history: DailyHistory): Readiness? {
    val baselineDays = (1..BASELINE_DAYS).map { date.minusDays(it) }
    val hrv = hrvComponent(date, baselineDays, history.hrvMs)
    val resting = restingComponent(date, baselineDays, history.restingBpm)
    val sleep = sleepComponent(date, baselineDays, history.sleepMinutes)
    val components = listOfNotNull(hrv, resting, sleep)
    if (sleep == null || components.size < MIN_COMPONENTS || components.none { it.confidence > 0 }) return null

    val combined = components.sumOf { it.weight * it.confidence * it.z } / components.sumOf { it.weight }
    val score = (50 + 20 * combined).roundToInt().coerceIn(0, 100)
    return Readiness(score, illness = likelyIll(date, baselineDays, history.restingBpm, resting, hrv))
}

private class Component(val weight: Double, val z: Double, val confidence: Double)

private fun hrvComponent(date: LocalDate, baselineDays: List<LocalDate>, hrvMs: Map<LocalDate, Double>): Component? {
    val today = hrvMs[date]?.takeIf { it > 0 } ?: return null
    val baseline = baselineDays.mapNotNull { hrvMs[it]?.takeIf { value -> value > 0 }?.let(::ln) }
    if (baseline.size < 2) return null
    val mean = baseline.average()
    val sd = standardDeviation(baseline).coerceAtLeast(MIN_LN_HRV_SD)
    val week = (0L until 7L).mapNotNull { hrvMs[date.minusDays(it)]?.takeIf { value -> value > 0 }?.let(::ln) }
    val z = 0.5 * ((ln(today) - mean) / sd) + 0.5 * ((week.average() - mean) / sd)
    return Component(HRV_WEIGHT, z.clamp(), confidence(baseline.size))
}

private fun restingComponent(date: LocalDate, baselineDays: List<LocalDate>, restingBpm: Map<LocalDate, Int>): Component? {
    val today = restingBpm[date] ?: return null
    val baseline = baselineDays.mapNotNull { restingBpm[it]?.toDouble() }
    if (baseline.size < 2) return null
    val sd = standardDeviation(baseline).coerceAtLeast(MIN_RESTING_SD)
    return Component(RESTING_WEIGHT, (-(today - baseline.average()) / sd).clamp(), confidence(baseline.size))
}

private fun sleepComponent(date: LocalDate, baselineDays: List<LocalDate>, sleepMinutes: Map<LocalDate, Int>): Component? {
    if (sleepMinutes[date] == null) return null
    val nights = SLEEP_NIGHT_WEIGHTS.withIndex().mapNotNull { (ago, weight) ->
        sleepMinutes[date.minusDays(ago.toLong())]?.let { weight to it / 60.0 }
    }
    val recent = nights.sumOf { (weight, hours) -> weight * hours } / nights.sumOf { it.first }
    val baseline = baselineDays.mapNotNull { sleepMinutes[it]?.div(60.0) }
    if (baseline.size < 2) return null
    val sd = standardDeviation(baseline).coerceAtLeast(MIN_SLEEP_SD_HOURS)
    val penalty = if (recent < SHORT_SLEEP_HOURS) 1.0 else 0.0
    return Component(SLEEP_WEIGHT, ((recent - baseline.average()) / sd - penalty).clamp(), confidence(baseline.size))
}

private fun likelyIll(
    date: LocalDate,
    baselineDays: List<LocalDate>,
    restingBpm: Map<LocalDate, Int>,
    resting: Component?,
    hrv: Component?,
): Boolean {
    val baseline = baselineDays.mapNotNull { restingBpm[it] }
    if (baseline.size < MIN_RESTING_BASELINE) return false
    val mean = baseline.average()
    val today = restingBpm[date] ?: return false
    val jump = today >= mean + ILLNESS_JUMP_BPM
    val streak = (0L until 3L).all { ago -> restingBpm[date.minusDays(ago)]?.let { it >= mean + ILLNESS_STREAK_BPM } == true }
    val strained = resting != null && hrv != null && resting.z <= -2.5 && hrv.z <= -2.0
    return jump || streak || strained
}

private fun confidence(baselineDays: Int): Double = ((baselineDays - 4) / 10.0).coerceIn(0.0, 1.0)

private fun standardDeviation(values: List<Double>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
}

private fun Double.clamp(): Double = coerceIn(-MAX_Z, MAX_Z)
