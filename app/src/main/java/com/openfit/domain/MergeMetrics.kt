package com.openfit.domain

import java.time.Duration

private val SAME_EXERCISE_TOLERANCE: Duration = Duration.ofMinutes(5)

fun mergeMetrics(byPriority: List<DailyMetrics>): DailyMetrics =
    byPriority.reduceOrNull(::mergeMetrics) ?: DailyMetrics()

fun mergeMetrics(primary: DailyMetrics, secondary: DailyMetrics): DailyMetrics = DailyMetrics(
    steps = largest(primary.steps, secondary.steps),
    hourlySteps = (primary.hourlySteps.keys + secondary.hourlySteps.keys).associateWith { hour ->
        maxOf(primary.hourlySteps[hour] ?: 0, secondary.hourlySteps[hour] ?: 0)
    },
    distanceMeters = largest(primary.distanceMeters, secondary.distanceMeters),
    floors = largest(primary.floors, secondary.floors),
    caloriesOutKcal = largest(primary.caloriesOutKcal, secondary.caloriesOutKcal),
    caloriesInKcal = largest(primary.caloriesInKcal, secondary.caloriesInKcal),
    waterLiters = largest(primary.waterLiters, secondary.waterLiters),
    zoneMinutes = primary.zoneMinutes ?: secondary.zoneMinutes,
    cardioLoad = primary.cardioLoad ?: secondary.cardioLoad,
    sleep = primary.sleep ?: secondary.sleep,
    exercises = mergeExercises(primary.exercises, secondary.exercises),
    heartRateRange = widest(primary.heartRateRange, secondary.heartRateRange),
    latestHeartRate = latest(primary.latestHeartRate, secondary.latestHeartRate),
    restingBpm = latest(primary.restingBpm, secondary.restingBpm),
    hrvMs = latest(primary.hrvMs, secondary.hrvMs),
    spo2Percent = latest(primary.spo2Percent, secondary.spo2Percent),
    breathsPerMinute = latest(primary.breathsPerMinute, secondary.breathsPerMinute),
    skinTempDeltaCelsius = latest(primary.skinTempDeltaCelsius, secondary.skinTempDeltaCelsius),
    vo2Max = latest(primary.vo2Max, secondary.vo2Max),
    weightKg = latest(primary.weightKg, secondary.weightKg),
    bodyFatPercent = latest(primary.bodyFatPercent, secondary.bodyFatPercent),
)

private fun <T : Comparable<T>> largest(primary: T?, secondary: T?): T? =
    if (primary == null || secondary == null) primary ?: secondary else maxOf(primary, secondary)

private fun widest(primary: IntRange?, secondary: IntRange?): IntRange? =
    if (primary == null || secondary == null) primary ?: secondary
    else minOf(primary.first, secondary.first)..maxOf(primary.last, secondary.last)

private fun <T> latest(primary: Reading<T>?, secondary: Reading<T>?): Reading<T>? =
    if (primary == null || secondary == null) primary ?: secondary
    else if (secondary.time > primary.time) secondary else primary

private fun mergeExercises(primary: List<Exercise>, secondary: List<Exercise>): List<Exercise> {
    val unmatched = secondary.filter { candidate -> primary.none { it.isSameSessionAs(candidate) } }
    return (primary + unmatched).sortedBy { it.start }
}

private fun Exercise.isSameSessionAs(other: Exercise): Boolean =
    Duration.between(start, other.start).abs() <= SAME_EXERCISE_TOLERANCE &&
        Duration.between(end, other.end).abs() <= SAME_EXERCISE_TOLERANCE
