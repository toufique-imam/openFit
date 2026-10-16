package com.openfit.domain

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

/** What a logged behaviour seems to do to the next day's score. */
enum class ImpactVerdict { Positive, Negative, NoClearEffect, NeedsMoreData }

/**
 * Next-day scores after "yes" days against after "no" days. [difference] is yes minus no; [low]..[high] is a 70%
 * interval around it from Welch's t-test. Without enough days of either answer only the counts are set.
 */
data class BehaviourImpact(
    val yesDays: Int,
    val noDays: Int,
    val difference: Double? = null,
    val low: Double? = null,
    val high: Double? = null,
) {
    val verdict: ImpactVerdict
        get() = when {
            difference == null || low == null || high == null -> ImpactVerdict.NeedsMoreData
            low > 0 -> ImpactVerdict.Positive
            high < 0 -> ImpactVerdict.Negative
            else -> ImpactVerdict.NoClearEffect
        }
}

const val MIN_DAYS_PER_ANSWER = 5

/**
 * Pairs each answer with the outcome [lagDays] later (a behaviour on day D with readiness, HRV and the night's sleep
 * filed under D + 1) and compares the two groups. An association, not a cause: it does not adjust for anything else.
 */
fun behaviourImpact(answers: Map<LocalDate, Boolean>, outcome: Map<LocalDate, Double>, lagDays: Long = 1): BehaviourImpact {
    val yes = answers.filterValues { it }.keys.mapNotNull { outcome[it.plusDays(lagDays)] }
    val no = answers.filterValues { !it }.keys.mapNotNull { outcome[it.plusDays(lagDays)] }
    if (yes.size < MIN_DAYS_PER_ANSWER || no.size < MIN_DAYS_PER_ANSWER) return BehaviourImpact(yes.size, no.size)
    val difference = yes.average() - no.average()
    val varianceYes = sampleVariance(yes) / yes.size
    val varianceNo = sampleVariance(no) / no.size
    val standardError = sqrt(varianceYes + varianceNo)
    if (standardError == 0.0) return BehaviourImpact(yes.size, no.size, difference, difference, difference)
    // Welch–Satterthwaite degrees of freedom.
    val df = (varianceYes + varianceNo).pow(2) /
        (varianceYes.pow(2) / (yes.size - 1) + varianceNo.pow(2) / (no.size - 1))
    val margin = studentT85(df) * standardError
    return BehaviourImpact(yes.size, no.size, difference, difference - margin, difference + margin)
}

private fun sampleVariance(values: List<Double>): Double {
    val mean = values.average()
    return values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
}

/** The normal 85th percentile: the edge of a two-sided 70% interval. */
private const val Z_85 = 1.0364333894937898

/**
 * Student's t 85th percentile for [df] degrees of freedom, by the Cornish–Fisher expansion around the normal quantile
 * (Abramowitz & Stegun 26.7.5); within 0.01 of the exact value from 3 degrees of freedom up.
 */
fun studentT85(df: Double): Double {
    val z = Z_85
    val z3 = z.pow(3)
    val z5 = z.pow(5)
    val z7 = z.pow(7)
    return z +
        (z3 + z) / (4 * df) +
        (5 * z5 + 16 * z3 + 3 * z) / (96 * df.pow(2)) +
        (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * df.pow(3))
}
