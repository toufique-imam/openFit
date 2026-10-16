package com.openfit.domain

import java.time.LocalDate
import kotlin.math.min

/** Six-minute slots on the local-midnight grid: 240 a day. */
const val SLOT_MINUTES = 6
const val SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES

/** One six-minute slot: its mean heart rate (null when not worn), and whether it was asleep or in a workout. */
data class Slot(val bpm: Int?, val asleep: Boolean = false, val exercise: Boolean = false)

/**
 * Stress from 0 to 100 for an awake slot: where its heart rate ranks among the user's awake, non-workout slots of the
 * last 30 days ([baseline], sorted). A rate higher than usual reads as more stress. Null without a baseline.
 */
fun slotStress(bpm: Int, baseline: IntArray): Int? {
    if (baseline.isEmpty()) return null
    var below = 0
    var equal = 0
    for (value in baseline) {
        if (value < bpm) below++ else if (value == bpm) equal++
    }
    return ((below + equal / 2.0) / baseline.size * 100).toInt().coerceIn(0, 100)
}

/** The awake, non-workout heart rates of [days] as a sorted baseline for [slotStress]. */
fun stressBaseline(days: Collection<List<Slot>>): IntArray =
    days.flatMap { slots -> slots.mapNotNull { slot -> slot.bpm.takeIf { !slot.asleep && !slot.exercise } } }.sorted().toIntArray()

/** A stretch that drained the bank most: a workout by name, or an hour of stress. */
sealed interface EnergyDrain {
    val points: Double

    data class Workout(val slot: Int, override val points: Double) : EnergyDrain

    /** Stress in the hour starting at [hour]. */
    data class Stress(val hour: Int, override val points: Double) : EnergyDrain
}

/** A day of Energy Bank: the level after each slot (null before the bank has settled), and what charged and drained it. */
data class EnergyDay(
    val date: LocalDate,
    val levels: List<Double?>,
    val charged: Double,
    val drained: Double,
    /** The three biggest drains, largest first. */
    val drains: List<EnergyDrain>,
) {
    val latest: Double? get() = levels.lastOrNull { it != null }

    /** The slot of [latest]: the bank only knows heart rate up to the last sync. */
    val latestSlot: Int? get() = levels.indexOfLast { it != null }.takeIf { it >= 0 }
    val peak: Double? get() = levels.filterNotNull().maxOrNull()
}

enum class EnergyBand { Plenty, PaceYourself, RunningLow }

fun energyBand(level: Double): EnergyBand = when {
    level >= 67 -> EnergyBand.Plenty
    level >= 34 -> EnergyBand.PaceYourself
    else -> EnergyBand.RunningLow
}

/** The model's rates, per six-minute slot. */
object EnergyRates {
    /** A 7.5-hour night (75 slots) charges about 80 points. */
    const val ASLEEP = 80.0 / 75

    /** Calm waking time charges about 15 points over 16.5 hours (165 slots). */
    const val CALM = 15.0 / 165

    /** Stress at [BASE_STRESS] drains about 60 points over 16.5 hours; higher stress drains proportionally faster. */
    const val DRAIN = 60.0 / 165
    const val BASE_STRESS = 50.0

    /** Below this stress, waking time charges instead of draining. */
    const val CALM_BELOW = 20

    /** Charging slows over the last [EDGE] points below 100, and draining over the last [EDGE] above 1. */
    const val EDGE = 20.0

    const val START = 50.0
    const val MIN = 1.0
    const val MAX = 100.0

    /** Days the bank runs before it is shown, so the starting level no longer matters. */
    const val WARM_UP_DAYS = 7
}

/**
 * Runs the bank through [days] in order (each [SLOTS_PER_DAY] slots), carrying the level across midnight. Each day's
 * stress baseline is the 30 days before it in [days]. Days before [showFrom] only warm the bank up and are not returned.
 */
fun energyBank(days: List<Pair<LocalDate, List<Slot>>>, showFrom: LocalDate): List<EnergyDay> {
    var level = EnergyRates.START
    val result = mutableListOf<EnergyDay>()
    days.forEachIndexed { index, (date, slots) ->
        val baseline = stressBaseline(days.subList((index - BASELINE_DAYS).coerceAtLeast(0), index).map { it.second })
        val levels = ArrayList<Double?>(slots.size)
        var charged = 0.0
        var drained = 0.0
        val workoutDrain = mutableMapOf<Int, Double>()
        val stressDrain = DoubleArray(24)
        var workoutStart = -1
        slots.forEachIndexed { i, slot ->
            val change = slotChange(slot, level, baseline)
            if (slot.exercise) {
                if (workoutStart < 0 || !slots[i - 1].exercise) workoutStart = i
            } else {
                workoutStart = -1
            }
            if (change != null) {
                level = (level + change).coerceIn(EnergyRates.MIN, EnergyRates.MAX)
                if (change > 0) charged += change else drained -= change
                if (change < 0) {
                    if (slot.exercise) workoutDrain[workoutStart] = (workoutDrain[workoutStart] ?: 0.0) - change
                    else stressDrain[i * SLOT_MINUTES / 60] -= change
                }
            }
            levels += if (change == null && slot.bpm == null) null else level
        }
        if (date >= showFrom) {
            val drains = workoutDrain.map { EnergyDrain.Workout(it.key, it.value) } +
                stressDrain.withIndex().filter { it.value > 0 }.map { EnergyDrain.Stress(it.index, it.value) }
            result += EnergyDay(date, levels, charged, drained, drains.sortedByDescending { it.points }.take(TOP_DRAINS))
        }
    }
    return result
}

/** How one slot moves the bank, or null when it cannot (not worn, and not asleep). */
private fun slotChange(slot: Slot, level: Double, baseline: IntArray): Double? {
    val toTop = min(1.0, (EnergyRates.MAX - level) / EnergyRates.EDGE)
    val toBottom = min(1.0, (level - EnergyRates.MIN) / EnergyRates.EDGE)
    if (slot.asleep) return EnergyRates.ASLEEP * toTop
    val bpm = slot.bpm ?: return null
    val stress = slotStress(bpm, baseline) ?: return null
    return if (stress < EnergyRates.CALM_BELOW) {
        EnergyRates.CALM * toTop
    } else {
        -EnergyRates.DRAIN * stress / EnergyRates.BASE_STRESS * toBottom
    }
}

private const val BASELINE_DAYS = 30
private const val TOP_DRAINS = 3
