package com.openfit.googlehealth

import android.util.Log
import com.openfit.domain.DailyMetrics
import com.openfit.domain.Reading
import com.openfit.health.DailyMetricsSource
import com.openfit.profile.Profile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class GoogleHealthSource(
    private val api: GoogleHealthApi,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : DailyMetricsSource {

    override suspend fun load(date: LocalDate, profile: Profile): DailyMetrics = coroutineScope {
        val lookback = date.minusDays(LOOKBACK_DAYS)
        val nextDay = date.plusDays(1)
        val lookbackStart = lookback.atStartOfDay(zone).toInstant()

        fun dailyFilter(field: String) = "$field.date >= \"$lookback\" AND $field.date < \"$nextDay\""
        fun sampleFilter(field: String) = "$field.sample_time.physical_time >= \"$lookbackStart\""

        val steps = async { rollUp("steps", date, "steps")?.number("countSum")?.toLong() }
        val distance = async { rollUp("distance", date, "distance")?.number("millimetersSum")?.div(1000) }
        val floors = async { rollUp("floors", date, "floors")?.number("countSum") }
        val calories = async { rollUp("total-calories", date, "totalCalories")?.number("kcalSum") }
        val zoneMinutes = async { rollUp("active-zone-minutes", date, "activeZoneMinutes")?.let(::parseZoneMinutes) }
        val restingBpm = async {
            latestDaily(list("daily-resting-heart-rate", dailyFilter("daily_resting_heart_rate"), "dailyRestingHeartRate"), zone) {
                it.number("beatsPerMinute")
            }
        }
        val hrv = async {
            latestDaily(list("daily-heart-rate-variability", dailyFilter("daily_heart_rate_variability"), "dailyHeartRateVariability"), zone) {
                it.number("averageHeartRateVariabilityMilliseconds")
            }
        }
        val spo2 = async {
            latestDaily(list("daily-oxygen-saturation", dailyFilter("daily_oxygen_saturation"), "dailyOxygenSaturation"), zone) {
                it.number("averagePercentage")
            }
        }
        val breathing = async {
            latestDaily(list("daily-respiratory-rate", dailyFilter("daily_respiratory_rate"), "dailyRespiratoryRate"), zone) {
                it.number("breathsPerMinute")
            }
        }
        val skinTemp = async {
            latestDaily(
                list("daily-sleep-temperature-derivations", dailyFilter("daily_sleep_temperature_derivations"), "dailySleepTemperatureDerivations"),
                zone,
            ) { point ->
                val nightly = point.number("nightlyTemperatureCelsius")
                val baseline = point.number("baselineTemperatureCelsius")
                if (nightly != null && baseline != null) nightly - baseline else null
            }
        }
        val vo2Max = async {
            latestDaily(list("daily-vo2-max", dailyFilter("daily_vo2_max"), "dailyVo2Max"), zone) { it.number("vo2Max") }
        }
        val weight = async {
            latestSample(list("weight", sampleFilter("weight"), "weight")) { point -> point.number("weightGrams")?.div(1000) }
        }
        val bodyFat = async {
            latestSample(list("body-fat", sampleFilter("body_fat"), "bodyFat")) { it.number("percentage") }
        }
        val sleep = async {
            parseSleep(
                reconcile("sleep", "sleep.interval.civil_end_time >= \"${date}T00:00:00\" AND sleep.interval.civil_end_time < \"${nextDay}T00:00:00\"", "sleep"),
            )
        }
        val exercises = async {
            parseExercises(
                reconcile("exercise", "exercise.interval.civil_start_time >= \"$date\" AND exercise.interval.civil_start_time < \"$nextDay\"", "exercise"),
            )
        }

        DailyMetrics(
            steps = steps.await(),
            distanceMeters = distance.await(),
            floors = floors.await(),
            caloriesOutKcal = calories.await(),
            zoneMinutes = zoneMinutes.await(),
            sleep = sleep.await(),
            exercises = exercises.await(),
            restingBpm = restingBpm.await()?.let { Reading(it.value.toInt(), it.time) },
            hrvMs = hrv.await(),
            spo2Percent = spo2.await(),
            breathsPerMinute = breathing.await(),
            skinTempDeltaCelsius = skinTemp.await(),
            vo2Max = vo2Max.await(),
            weightKg = weight.await(),
            bodyFatPercent = bodyFat.await(),
        )
    }

    private suspend fun rollUp(dataType: String, date: LocalDate, field: String): JSONObject? =
        tolerant(dataType) { api.dailyRollUp(dataType, date).firstRollup(field) }

    private suspend fun list(dataType: String, filter: String, field: String): List<JSONObject> =
        tolerant(dataType) { api.list(dataType, filter).points(field) }.orEmpty()

    private suspend fun reconcile(dataType: String, filter: String, field: String): List<JSONObject> =
        tolerant(dataType) { api.reconcile(dataType, filter).points(field) }.orEmpty()

    private suspend fun <T> tolerant(dataType: String, block: suspend () -> T): T? = try {
        block()
    } catch (error: GoogleHealthException) {
        if (error.isAuthProblem) throw error
        Log.w(TAG, "Skipping $dataType", error)
        null
    }

    private companion object {
        const val TAG = "GoogleHealthSource"
        const val LOOKBACK_DAYS = 30L
    }
}
