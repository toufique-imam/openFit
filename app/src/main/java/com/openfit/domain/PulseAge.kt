@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln

/**
 * Pulse Age: how old the body behaves, from nine habits and vitals. Each maps to a change in all-cause mortality risk
 * against a reference for the user's age and sex, through dose-response curves from published studies; the summed log
 * hazard ratio becomes years. Modelled on pulse-mobile's and WHOOP's published method, with openFit's own code.
 */
enum class AgeFactor(val group: AgeGroup) {
    SleepHours(AgeGroup.Sleep),
    SleepConsistency(AgeGroup.Sleep),
    ModerateMinutes(AgeGroup.Activity),
    VigorousMinutes(AgeGroup.Activity),
    StrengthMinutes(AgeGroup.Activity),
    Steps(AgeGroup.Activity),
    Vo2Max(AgeGroup.Fitness),
    RestingHeartRate(AgeGroup.Fitness),
    BodyFat(AgeGroup.Fitness),
}

enum class AgeGroup { Sleep, Activity, Fitness }

/** One factor's part: its value (in the factor's units: hours, SRI, minutes a week, steps, mL/kg/min, bpm, %). */
@Serializable
data class AgeEffect(val factor: AgeFactor, val value: Double, val reference: Double, val years: Double, val estimated: Boolean = false)

private const val ESTIMATE_WEIGHT = 0.5

@Serializable
data class PulseAge(val age: Double, val chronologicalAge: Int, val effects: List<AgeEffect>) {
    val difference: Double get() = age - chronologicalAge
    val missing: List<AgeFactor> get() = AgeFactor.entries - effects.map { it.factor }.toSet()
}

object PulseAgeModel {
    /**
     * Years per unit of summed ln HR. Mortality rises about 10% a year of age (WHOOP's white paper), so one unit of ln HR
     * is about 10 years; summing separate studies' effects overstates their joint effect (Li 2018, Circulation: five
     * healthy habits together HR 0.26, about half their single-factor sum), so openFit halves it.
     */
    const val YEARS_PER_LN_HR = 5.0
    const val CLAMP_YEARS = 15.0
    const val MIN_FACTORS = 5
}

/** ln HR as a piecewise-linear curve through (x, HR) knots, flat beyond the ends. */
private class Curve(knots: List<Pair<Double, Double>>) {
    private val points = knots.map { (x, hr) -> x to ln(hr) }

    fun at(x: Double): Double {
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        val upper = points.indexOfFirst { it.first >= x }
        val (x0, y0) = points[upper - 1]
        val (x1, y1) = points[upper]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}

private val CURVES = mapOf(
    // Kodama 2009, JAMA: RR 0.87 per 1 MET (3.5 mL/kg/min) higher, log-linear.
    AgeFactor.Vo2Max to Curve(listOf(10.0 to 1.0, 80.0 to Math.pow(0.87, 70 / 3.5))),
    // Zhang 2016, CMAJ: RR 1.09 per 10 bpm, log-linear from 45 bpm.
    AgeFactor.RestingHeartRate to Curve(listOf(45.0 to 1.0, 105.0 to Math.pow(1.09, 6.0))),
    // Saint-Maurice 2024: HR 1.29 at 5 h against 7 h; no long-sleep penalty.
    AgeFactor.SleepHours to Curve(listOf(5.0 to 1.29, 7.0 to 1.0)),
    // Windred 2024, Sleep: sleep regularity index quintile medians, HR against the lowest quintile.
    AgeFactor.SleepConsistency to Curve(listOf(65.1 to 1.0, 75.62 to 0.8, 80.99 to 0.75, 85.22 to 0.72, 89.8 to 0.7)),
    // Arem 2015, JAMA Intern Med: minutes a week at moderate intensity, at 0, 1–2×, 3–5× and 8× the guideline.
    AgeFactor.ModerateMinutes to Curve(listOf(0.0 to 1.0, 75.0 to 0.8, 225.0 to 0.69, 375.0 to 0.63, 600.0 to 0.61)),
    // Ahmadi 2022, Eur Heart J: vigorous minutes a week.
    AgeFactor.VigorousMinutes to Curve(listOf(2.2 to 1.0, 15.0 to 0.82, 53.6 to 0.64)),
    // Momma 2022, BJSM: RR 0.83 at 40 min a week of muscle strengthening; no extra benefit beyond.
    AgeFactor.StrengthMinutes to Curve(listOf(0.0 to 1.0, 40.0 to 0.83)),
)

// Paluch 2022, Lancet Public Health: steps a day, quartile medians, separately under 60 and from 60.
private val STEPS_UNDER_60 = Curve(listOf(4849.0 to 1.0, 7245.0 to 0.59, 8911.0 to 0.51))
private val STEPS_FROM_60 = Curve(listOf(2841.0 to 1.0, 5217.0 to 0.62, 7116.0 to 0.52, 10501.0 to 0.43))

/** Jayedi 2022, Int J Obes: HR 1.11 per 10 points of body fat; credit stops 5 points under the reference, none from 60. */
private const val BODY_FAT_HR_PER_10 = 1.11
private const val BODY_FAT_MAX_CREDIT = 5.0
private const val BODY_FAT_NO_EFFECT_FROM = 60

/** WHOOP's recommended VO2 max by age (white paper, 2025), mL/kg/min at 20, 25 … 100. */
private val VO2_REFERENCE = mapOf(
    Sex.Male to listOf(46, 46, 44, 42, 40, 37, 36, 34, 32, 30, 29, 27, 26, 25, 23, 22, 21),
    Sex.Female to listOf(40, 40, 38, 36, 34, 32, 30, 29, 27, 26, 25, 23, 22, 21, 20, 19, 18),
)

/** The reference profile: Pulse Age equals the user's age when every factor sits here. */
fun ageReference(factor: AgeFactor, age: Int, sex: Sex): Double = when (factor) {
    AgeFactor.Vo2Max -> {
        val table = VO2_REFERENCE.getValue(sex)
        val position = ((age - 20) / 5.0).coerceIn(0.0, table.size - 1.0)
        val low = position.toInt().coerceAtMost(table.size - 2)
        table[low] + (table[low + 1] - table[low]) * (position - low)
    }
    AgeFactor.RestingHeartRate -> if (sex == Sex.Male) 60.0 else 64.0
    AgeFactor.SleepHours -> 7.0
    AgeFactor.SleepConsistency -> 75.6
    AgeFactor.Steps -> if (age < 60) 8_000.0 else 5_600.0
    // Weekly minutes, easing from the first value at 30 to the second at 70.
    AgeFactor.ModerateMinutes -> byAge(age, 100.0, 70.0)
    AgeFactor.VigorousMinutes -> byAge(age, 10.0, 7.0)
    AgeFactor.StrengthMinutes -> 40.0
    AgeFactor.BodyFat -> if (sex == Sex.Male) 20.0 else 33.0
}

private fun byAge(age: Int, at30: Double, at70: Double): Double = at30 + (at70 - at30) * ((age - 30) / 40.0).coerceIn(0.0, 1.0)

/** ln HR of [value] against the reference: negative is better than the reference. */
fun ageLnHr(factor: AgeFactor, value: Double, age: Int, sex: Sex): Double {
    val reference = ageReference(factor, age, sex)
    return when (factor) {
        AgeFactor.BodyFat ->
            if (age >= BODY_FAT_NO_EFFECT_FROM) 0.0 else ln(BODY_FAT_HR_PER_10) * maxOf(value - reference, -BODY_FAT_MAX_CREDIT) / 10
        AgeFactor.Steps -> {
            val curve = if (age < 60) STEPS_UNDER_60 else STEPS_FROM_60
            curve.at(value) - curve.at(reference)
        }
        else -> CURVES.getValue(factor).let { it.at(value) - it.at(reference) }
    }
}

/**
 * Pulse Age from the factors there is data for ([inputs] in each factor's units). The factors present are scaled up to
 * stand for all nine, and the result is clamped to ±[PulseAgeModel.CLAMP_YEARS]. Null with fewer than
 * [PulseAgeModel.MIN_FACTORS] factors.
 */
fun pulseAge(age: Int, sex: Sex, inputs: Map<AgeFactor, Double>, estimated: Set<AgeFactor> = emptySet()): PulseAge? {
    if (inputs.size < PulseAgeModel.MIN_FACTORS) return null
    val scale = AgeFactor.entries.size.toDouble() / inputs.size
    val effects = AgeFactor.entries.mapNotNull { factor ->
        inputs[factor]?.let { value ->
            // An estimated input (VO2 max from heart rate) counts at half weight, as in Pulse.
            val weight = if (factor in estimated) ESTIMATE_WEIGHT else 1.0
            AgeEffect(factor, value, ageReference(factor, age, sex), PulseAgeModel.YEARS_PER_LN_HR * ageLnHr(factor, value, age, sex) * scale * weight, factor in estimated)
        }
    }
    val years = effects.sumOf { it.years }.coerceIn(-PulseAgeModel.CLAMP_YEARS, PulseAgeModel.CLAMP_YEARS)
    return PulseAge(age + years, age, effects)
}

/**
 * Sleep regularity index (Phillips 2017): how often the same clock minute has the same state (asleep or awake) on
 * consecutive days, from −100 to 100. [asleep] holds each day's 1,440 minutes; pairs need both days present. Null
 * without a pair.
 */
fun sleepRegularity(asleep: Map<LocalDate, BooleanArray>): Double? {
    var same = 0L
    var compared = 0L
    asleep.forEach { (day, minutes) ->
        val previous = asleep[day.minusDays(1)] ?: return@forEach
        for (minute in 0 until MINUTES_PER_DAY) {
            if (minutes[minute] == previous[minute]) same++
            compared++
        }
    }
    if (compared == 0L) return null
    return -100 + 200.0 * same / compared
}

const val MINUTES_PER_DAY = 1_440

/** Whether a difference reads as younger, older or about the same (within half a year). */
fun ageDirection(years: Double): Int = if (abs(years) < 0.05) 0 else if (years < 0) -1 else 1
