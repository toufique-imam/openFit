package com.openfit.ui.vitals

import com.openfit.R
import com.openfit.domain.HealthMetric
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.ui.MetricPage
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.whole
import kotlin.math.roundToInt

/** Fitbit's order on the Health metrics page. */
val FITBIT_ORDER = listOf(
    HealthMetric.BreathingRate,
    HealthMetric.BloodOxygen,
    HealthMetric.RestingHeartRate,
    HealthMetric.HeartRateVariability,
    HealthMetric.SkinTemperature,
)

fun title(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_breathing_rate
    HealthMetric.RestingHeartRate -> R.string.hm_resting_heart_rate
    HealthMetric.HeartRateVariability -> R.string.hm_hrv
    HealthMetric.BloodOxygen -> R.string.hm_blood_oxygen
    HealthMetric.SkinTemperature -> R.string.hm_skin_temperature
}

fun abbreviation(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_breathing_rate_abbr
    HealthMetric.RestingHeartRate -> R.string.hm_resting_heart_rate_abbr
    HealthMetric.HeartRateVariability -> R.string.hm_hrv_abbr
    HealthMetric.BloodOxygen -> R.string.hm_blood_oxygen_abbr
    HealthMetric.SkinTemperature -> R.string.hm_skin_temperature_abbr
}

/** "Breathing rate (BR)"; skin temperature has no abbreviation in Fitbit's list. */
fun listTitle(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_row_breathing_rate
    HealthMetric.RestingHeartRate -> R.string.hm_row_resting_heart_rate
    HealthMetric.HeartRateVariability -> R.string.hm_row_hrv
    HealthMetric.BloodOxygen -> R.string.hm_row_blood_oxygen
    HealthMetric.SkinTemperature -> R.string.hm_skin_temperature
}

fun aboutTitle(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_about_breathing_rate
    HealthMetric.RestingHeartRate -> R.string.hm_about_resting_heart_rate
    HealthMetric.HeartRateVariability -> R.string.hm_about_hrv
    HealthMetric.BloodOxygen -> R.string.hm_about_blood_oxygen
    HealthMetric.SkinTemperature -> R.string.hm_about_skin_temperature
}

fun aboutBody(metric: HealthMetric): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_about_breathing_rate_body
    HealthMetric.RestingHeartRate -> R.string.hm_about_resting_heart_rate_body
    HealthMetric.HeartRateVariability -> R.string.hm_about_hrv_body
    HealthMetric.BloodOxygen -> R.string.hm_about_blood_oxygen_body
    HealthMetric.SkinTemperature -> R.string.hm_about_skin_temperature_body
}

fun unit(metric: HealthMetric, profile: Profile): Int = when (metric) {
    HealthMetric.BreathingRate -> R.string.hm_unit_brpm
    HealthMetric.RestingHeartRate -> R.string.unit_bpm
    HealthMetric.HeartRateVariability -> R.string.unit_ms
    HealthMetric.BloodOxygen -> R.string.unit_percent
    HealthMetric.SkinTemperature -> if (profile.units == UnitSystem.Imperial) R.string.hm_unit_fahrenheit else R.string.hm_unit_celsius
}

/** Skin temperature is a variation, so Fahrenheit only scales it (no offset). */
fun display(metric: HealthMetric, value: Double, profile: Profile): Double =
    if (metric == HealthMetric.SkinTemperature && profile.units == UnitSystem.Imperial) value * 9 / 5 else value

/** Fitbit shows whole numbers ("17 brpm", "54 ms"); skin temperature keeps a signed decimal. */
fun format(metric: HealthMetric, value: Double): String = when (metric) {
    HealthMetric.SkinTemperature -> (if (value > 0) "+" else "") + oneDecimal(value)
    else -> whole(value.roundToInt())
}

fun page(metric: HealthMetric): MetricPage = when (metric) {
    HealthMetric.BreathingRate -> MetricPage.MetricBreathingRate
    HealthMetric.RestingHeartRate -> MetricPage.MetricRestingHeartRate
    HealthMetric.HeartRateVariability -> MetricPage.MetricHrv
    HealthMetric.BloodOxygen -> MetricPage.MetricBloodOxygen
    HealthMetric.SkinTemperature -> MetricPage.MetricSkinTemperature
}

fun metricOf(page: MetricPage): HealthMetric? = HealthMetric.entries.firstOrNull { page(it) == page }
