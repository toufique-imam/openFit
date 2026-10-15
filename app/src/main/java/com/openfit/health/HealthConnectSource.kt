package com.openfit.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.DailyMetrics
import com.openfit.domain.Exercise
import com.openfit.domain.HeartRateSample
import com.openfit.domain.Reading
import com.openfit.domain.SleepSummary
import com.openfit.domain.estimateRestingBpm
import com.openfit.domain.heartRateZones
import com.openfit.domain.zoneMinutes
import com.openfit.health.HealthConnectGateway.Companion.read
import com.openfit.profile.Profile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectSource(
    private val gateway: HealthConnectGateway,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : DailyMetricsSource {

    private val client: HealthConnectClient get() = gateway.client

    override suspend fun load(date: LocalDate, profile: Profile): DailyMetrics {
        val granted = gateway.grantedPermissions()
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        val window = Window(start, end, granted)

        val totals = aggregate(window)
        val heartRate = window.records(HeartRateRecord::class, start)
            .flatMap { record -> record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) } }
        val restingBpm = window.records(RestingHeartRateRecord::class, start - MEASUREMENT_LOOKBACK)
            .maxByOrNull { it.time }
            ?.let { Reading(it.beatsPerMinute.toInt(), it.time) }
            ?: estimateRestingBpm(heartRate)?.let { Reading(it, end) }
        val age = profile.age

        return DailyMetrics(
            steps = totals?.get(StepsRecord.COUNT_TOTAL),
            distanceMeters = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            floors = totals?.get(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
            caloriesOutKcal = totals?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            caloriesInKcal = totals?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories,
            waterLiters = totals?.get(HydrationRecord.VOLUME_TOTAL)?.inLiters,
            zoneMinutes = if (age != null && restingBpm != null && heartRate.isNotEmpty()) {
                zoneMinutes(heartRate, heartRateZones(age, restingBpm.value))
            } else {
                null
            },
            sleep = window.records(SleepSessionRecord::class, start - SLEEP_LOOKBACK)
                .filter { it.endTime > start && it.endTime <= end }
                .takeIf { it.isNotEmpty() }
                ?.let { sessions -> SleepSummary(sessions.sumDurations(::asleepDuration), sessions.maxOf { it.endTime }) },
            exercises = window.records(ExerciseSessionRecord::class, start)
                .map { Exercise(it.title ?: DEFAULT_EXERCISE_NAME, it.startTime, it.endTime) },
            restingBpm = restingBpm,
            hrvMs = window.records(HeartRateVariabilityRmssdRecord::class, start - SLEEP_LOOKBACK)
                .latestAverage({ it.time }) { it.heartRateVariabilityMillis },
            spo2Percent = window.records(OxygenSaturationRecord::class, start - SLEEP_LOOKBACK)
                .latestAverage({ it.time }) { it.percentage.value },
            breathsPerMinute = window.records(RespiratoryRateRecord::class, start - SLEEP_LOOKBACK)
                .latestAverage({ it.time }) { it.rate },
            skinTempDeltaCelsius = window.records(SkinTemperatureRecord::class, start - SLEEP_LOOKBACK)
                .filter { it.deltas.isNotEmpty() }
                .maxByOrNull { it.endTime }
                ?.let { record -> Reading(record.deltas.map { it.delta.inCelsius }.average(), record.endTime) },
            vo2Max = window.records(Vo2MaxRecord::class, start - MEASUREMENT_LOOKBACK)
                .maxByOrNull { it.time }
                ?.let { Reading(it.vo2MillilitersPerMinuteKilogram, it.time) },
            weightKg = window.records(WeightRecord::class, start - MEASUREMENT_LOOKBACK)
                .maxByOrNull { it.time }
                ?.let { Reading(it.weight.inKilograms, it.time) },
            bodyFatPercent = window.records(BodyFatRecord::class, start - MEASUREMENT_LOOKBACK)
                .maxByOrNull { it.time }
                ?.let { Reading(it.percentage.value, it.time) },
        )
    }

    private inner class Window(val start: Instant, val end: Instant, val granted: Set<String>) {
        suspend fun <T : Record> records(type: KClass<T>, from: Instant): List<T> =
            if (read(type) in granted) readAll(type, TimeRangeFilter.between(from, end)) else emptyList()
    }

    private suspend fun aggregate(window: Window): AggregationResult? {
        val metrics = AGGREGATES
            .filterKeys { read(it) in window.granted }
            .values
            .toSet()
        if (metrics.isEmpty()) return null
        return client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(window.start, window.end)))
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private fun asleepDuration(session: SleepSessionRecord): Duration {
        val awake = session.stages
            .filter { it.stage in AWAKE_STAGES }
            .sumDurations { Duration.between(it.startTime, it.endTime) }
        return Duration.between(session.startTime, session.endTime) - awake
    }

    private companion object {
        val MEASUREMENT_LOOKBACK: Duration = Duration.ofDays(30)
        val SLEEP_LOOKBACK: Duration = Duration.ofHours(18)
        val NIGHT: Duration = Duration.ofHours(12)
        const val DEFAULT_EXERCISE_NAME = "Exercise"

        val AGGREGATES: Map<KClass<out Record>, AggregateMetric<*>> = mapOf(
            StepsRecord::class to StepsRecord.COUNT_TOTAL,
            DistanceRecord::class to DistanceRecord.DISTANCE_TOTAL,
            FloorsClimbedRecord::class to FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL,
            TotalCaloriesBurnedRecord::class to TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            NutritionRecord::class to NutritionRecord.ENERGY_TOTAL,
            HydrationRecord::class to HydrationRecord.VOLUME_TOTAL,
        )

        val AWAKE_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )

        fun <T> List<T>.latestAverage(time: (T) -> Instant, value: (T) -> Double): Reading<Double>? {
            val latest = maxOfOrNull(time) ?: return null
            val night = filter { Duration.between(time(it), latest) <= NIGHT }
            return Reading(night.map(value).average(), latest)
        }

        fun <T> Iterable<T>.sumDurations(duration: (T) -> Duration): Duration =
            fold(Duration.ZERO) { total, item -> total + duration(item) }
    }
}
