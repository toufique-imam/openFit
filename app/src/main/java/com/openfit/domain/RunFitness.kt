package com.openfit.domain

import kotlin.math.max
import kotlin.math.sqrt

/** One minute from the wearable: metres covered and mean heart rate (null without a reading). */
data class MovingMinute(val meters: Double, val bpm: Double?)

/** A run of [RUN_SPEED] or faster held until heart rate neared its maximum: the pace, and VO2 max from it. */
data class RunBout(val startMinute: Int, val minutes: Int, val metersPerMinute: Double, val vo2Max: Double)

/** 8 km/h: slower minutes are walking, whose heart rate says too little about fitness (heat, terrain, lag). */
const val RUN_SPEED = 134.0

/** Days of runs the estimate looks back over. */
const val RUN_WINDOW_DAYS = 42

/** Heart rate counts as near maximum from this share of it. */
private const val NEAR_MAX_SHARE = 0.95

/**
 * The pace has to be held at least this long before heart rate nears its maximum: a faster rise follows an earlier
 * interval, not the pace. And at most this long: a slower one is heart rate drifting at an easier pace.
 */
const val MIN_MINUTES_TO_MAX = 6
private const val MAX_MINUTES_TO_MAX = 20

/** Minutes of a steady pace vary by at most this share of their mean. */
private const val MAX_PACE_VARIATION = 0.12

/**
 * A pace that takes heart rate to its maximum within 6-20 minutes lies above critical speed, close to the speed at VO2
 * max (vVO2max): about 92% of it on average, the middle of the 88-95% that critical speed usually sits at. This is
 * openFit's assumption, not a measurement, and is what makes the estimate good to about ±10%.
 */
private const val PACE_SHARE_OF_VO2_MAX = 0.92

/** Oxygen cost of running on the flat, in mL/kg/min, from speed in metres per minute (ACSM's metabolic equation). */
fun runningVo2(metersPerMinute: Double): Double = 0.2 * metersPerMinute + 3.5

/** The higher of [cardioMaxHeartRate] and the highest two-minute heart rate the watch has seen. */
fun personalMaxBpm(age: Int, minutes: Collection<List<MovingMinute?>>): Double {
    val peak = minutes.maxOfOrNull { day ->
        day.zipWithNext().maxOfOrNull { (a, b) -> if (a?.bpm != null && b?.bpm != null) minOf(a.bpm, b.bpm) else 0.0 } ?: 0.0
    } ?: 0.0
    return max(cardioMaxHeartRate(age), peak)
}

/**
 * The day's qualifying runs ([day] holds one entry per minute of the day, null when the watch recorded nothing): each
 * stretch of [RUN_SPEED] or faster in which heart rate reached 95% of [maxBpm] after 6-20 minutes of a steady pace.
 */
fun runBouts(day: List<MovingMinute?>, maxBpm: Double): List<RunBout> {
    val bouts = mutableListOf<RunBout>()
    var start = 0
    while (start < day.size) {
        if ((day[start]?.meters ?: 0.0) < RUN_SPEED) {
            start++
            continue
        }
        var end = start
        while (end < day.size && (day[end]?.meters ?: 0.0) >= RUN_SPEED) end++
        boutOf(day.subList(start, end), start, maxBpm)?.let(bouts::add)
        start = end
    }
    return bouts
}

private fun boutOf(run: List<MovingMinute?>, startMinute: Int, maxBpm: Double): RunBout? {
    val reached = run.indexOfFirst { (it?.bpm ?: 0.0) >= NEAR_MAX_SHARE * maxBpm }
    val minutes = reached + 1
    if (reached < 0 || minutes < MIN_MINUTES_TO_MAX || minutes > MAX_MINUTES_TO_MAX) return null
    val paces = run.take(minutes).map { it!!.meters }
    val mean = paces.average()
    val spread = sqrt(paces.sumOf { (it - mean) * (it - mean) } / paces.size)
    if (spread > MAX_PACE_VARIATION * mean) return null
    return RunBout(startMinute, minutes, mean, runningVo2(mean) / PACE_SHARE_OF_VO2_MAX)
}

/** The median VO2 max of the runs, or null without any. */
fun runVo2Max(bouts: Collection<RunBout>): Double? {
    if (bouts.isEmpty()) return null
    val sorted = bouts.map { it.vo2Max }.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
