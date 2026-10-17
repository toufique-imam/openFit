@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
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

enum class StressBand { Low, Medium, High }

/** Body stress bands: 0-29 low, 30-59 medium, 60-100 high. */
fun stressBand(stress: Int): StressBand = when {
    stress < 30 -> StressBand.Low
    stress < 60 -> StressBand.Medium
    else -> StressBand.High
}

/**
 * The stress openFit shows: 0.75 x the heart-rate rank, calibrated against observed daily averages and time in each band for
 * 30 Sep - 14 Oct 2026 (3.7 points from the reference average; low-stress time within 5%, high within 7%). Vitality reserve keeps
 * the raw rank, which its own rates were fitted on.
 */
fun displayedStress(rank: Int): Int = (rank * DISPLAY_SCALE).toInt()

private const val DISPLAY_SCALE = 0.75

/** A day of stress: each slot's 0-100 (null when not worn or in a workout), asleep or not. */
@Serializable
data class StressDay(val date: LocalDate, val slots: List<Int?>, val asleep: List<Boolean>) {
    val latest: Int? get() = slots.lastOrNull { it != null }
    val latestSlot: Int? get() = slots.indexOfLast { it != null }.takeIf { it >= 0 }
    val average: Int? get() = slots.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()

    /** Minutes in each band. */
    fun minutes(band: StressBand): Int = slots.count { it != null && stressBand(it) == band } * SLOT_MINUTES
}

/**
 * Stress for each day in [days] from [showFrom], ranking every worn slot (asleep too) among the awake, non-workout slots
 * of the 30 days before. Workouts show no stress.
 */
fun stressDays(days: List<Pair<LocalDate, List<Slot>>>, showFrom: LocalDate): List<StressDay> =
    days.withIndex().filter { it.value.first >= showFrom }.map { (index, day) ->
        val baseline = stressBaseline(days.subList((index - 30).coerceAtLeast(0), index).map { it.second })
        val (date, slots) = day
        StressDay(date, slots.map { slot -> slot.bpm?.takeIf { !slot.exercise }?.let { bpm -> slotStress(bpm, baseline)?.let(::displayedStress) } }, slots.map { it.asleep })
    }

/** A stretch that drained the bank most: a workout by name, or an hour of stress. */
@Serializable
sealed interface EnergyDrain {
    val points: Double

    @Serializable
    data class Workout(val slot: Int, override val points: Double) : EnergyDrain

    /** Stress in the hour starting at [hour]. */
    @Serializable
    data class Stress(val hour: Int, override val points: Double) : EnergyDrain
}

/** A day of Vitality reserve: the level after each slot (null before the bank has settled), and what charged and drained it. */
@Serializable
data class EnergyDay(
    val date: LocalDate,
    val levels: List<Double?>,
    val charged: Double,
    val drained: Double,
    /** The three biggest drains, largest first. */
    val drains: List<EnergyDrain>,
    /** The last asleep slot of the morning (before 2 pm) and the level then: the estimated level at waking. */
    val wakeSlot: Int? = null,
    val wakeLevel: Double? = null,
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

/**
 * The model's rates, per six-minute slot, calibrated against observed reserve levels for 1-14 Oct 2026 (its wake level, end
 * level, and daily charged and drained totals): about 7.6 points RMS, in the existing calibration sample; this is not physiological validation.
 */
object EnergyRates {
    /** A 7.5-hour night (75 slots) charges about 41 points. */
    const val ASLEEP = 0.55

    /** Calm waking time charges about 8 points over 16.5 hours (165 slots). */
    const val CALM = 0.05

    /** Stress at [BASE_STRESS] drains about 54 points over 16.5 hours; higher stress drains proportionally faster. */
    const val DRAIN = 0.33
    const val BASE_STRESS = 40.0

    /** Below this stress, waking time charges instead of draining. */
    const val CALM_BELOW = 20

    /** Charging slows over the last [EDGE] points below 100, and draining over the last [EDGE] above 1. */
    const val EDGE = 40.0

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
        var wakeSlot: Int? = null
        var wakeLevel: Double? = null
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
            if (slot.asleep && i < MORNING_END_SLOT) {
                wakeSlot = i
                wakeLevel = level
            }
        }
        if (date >= showFrom) {
            val drains = workoutDrain.map { EnergyDrain.Workout(it.key, it.value) } +
                stressDrain.withIndex().filter { it.value > 0 }.map { EnergyDrain.Stress(it.index, it.value) }
            result += EnergyDay(date, levels, charged, drained, drains.sortedByDescending { it.points }.take(TOP_DRAINS), wakeSlot, wakeLevel)
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
private const val MORNING_END_SLOT = 14 * 60 / SLOT_MINUTES
