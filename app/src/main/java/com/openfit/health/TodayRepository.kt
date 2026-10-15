package com.openfit.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.HeartRateSample
import com.openfit.domain.ZoneMinutes
import com.openfit.domain.estimateRestingBpm
import com.openfit.domain.heartRateZones
import com.openfit.domain.zoneMinutes
import com.openfit.health.HealthConnectGateway.Companion.CALORIES
import com.openfit.health.HealthConnectGateway.Companion.DISTANCE
import com.openfit.health.HealthConnectGateway.Companion.FLOORS
import com.openfit.health.HealthConnectGateway.Companion.HEART_RATE
import com.openfit.health.HealthConnectGateway.Companion.RESTING_HEART_RATE
import com.openfit.health.HealthConnectGateway.Companion.SLEEP
import com.openfit.health.HealthConnectGateway.Companion.STEPS
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

data class TodaySummary(
    val steps: Long?,
    val distanceMeters: Double?,
    val floors: Double?,
    val caloriesKcal: Double?,
    val restingBpm: Int?,
    val zoneMinutes: ZoneMinutes?,
    val asleep: Duration?,
)

class TodayRepository(
    private val client: HealthConnectClient,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun load(date: LocalDate, granted: Set<String>, age: Int?): TodaySummary {
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        val day = TimeRangeFilter.between(start, end)

        val totals = aggregate(day, granted)
        val heartRate = if (HEART_RATE in granted) heartRateSamples(day) else emptyList()
        val restingBpm = latestRestingBpm(start, end, granted) ?: estimateRestingBpm(heartRate)
        val zoneMinutes = if (age != null && restingBpm != null && heartRate.isNotEmpty()) {
            zoneMinutes(heartRate, heartRateZones(age, restingBpm))
        } else {
            null
        }

        return TodaySummary(
            steps = totals?.get(StepsRecord.COUNT_TOTAL),
            distanceMeters = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            floors = totals?.get(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
            caloriesKcal = totals?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            restingBpm = restingBpm,
            zoneMinutes = zoneMinutes,
            asleep = if (SLEEP in granted) asleepEndingWithin(start, end) else null,
        )
    }

    private suspend fun aggregate(range: TimeRangeFilter, granted: Set<String>): AggregationResult? {
        val metrics = buildSet<AggregateMetric<*>> {
            if (STEPS in granted) add(StepsRecord.COUNT_TOTAL)
            if (DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (FLOORS in granted) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            if (CALORIES in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (metrics.isEmpty()) return null
        return client.aggregate(AggregateRequest(metrics, range))
    }

    private suspend fun heartRateSamples(range: TimeRangeFilter): List<HeartRateSample> =
        readAll(HeartRateRecord::class, range).flatMap { record ->
            record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) }
        }

    private suspend fun latestRestingBpm(start: Instant, end: Instant, granted: Set<String>): Int? {
        if (RESTING_HEART_RATE !in granted) return null
        val lookback = TimeRangeFilter.between(start.minus(Duration.ofDays(30)), end)
        return readAll(RestingHeartRateRecord::class, lookback)
            .maxByOrNull { it.time }
            ?.beatsPerMinute
            ?.toInt()
    }

    private suspend fun asleepEndingWithin(start: Instant, end: Instant): Duration? {
        val sessions = readAll(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofHours(18)), end))
            .filter { it.endTime > start && it.endTime <= end }
        if (sessions.isEmpty()) return null
        return sessions.fold(Duration.ZERO) { total, session -> total + asleepDuration(session) }
    }

    private fun asleepDuration(session: SleepSessionRecord): Duration {
        val awake = session.stages
            .filter { it.stage in AWAKE_STAGES }
            .fold(Duration.ZERO) { total, stage -> total + Duration.between(stage.startTime, stage.endTime) }
        return Duration.between(session.startTime, session.endTime) - awake
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

    private companion object {
        val AWAKE_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )
    }
}
