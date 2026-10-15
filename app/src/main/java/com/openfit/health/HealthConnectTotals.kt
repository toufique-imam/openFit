package com.openfit.health

import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** Metrics that add up over a day, as Fitbit's goal screens show them. */
enum class DailyTotal(val record: KClass<out Record>) {
    Steps(StepsRecord::class),
    DistanceMeters(DistanceRecord::class),
    Floors(FloorsClimbedRecord::class),
    CaloriesOutKcal(TotalCaloriesBurnedRecord::class),
}

/** Daily and hourly sums from Health Connect's aggregation API — one request per range, however long. */
class HealthConnectTotals(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun daily(total: DailyTotal, from: LocalDate, until: LocalDate): Map<LocalDate, Double> {
        if (read(total.record) !in gateway.grantedPermissions()) return emptyMap()
        val request = AggregateGroupByPeriodRequest(
            metrics = setOf(metric(total)),
            timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), until.plusDays(1).atStartOfDay()),
            timeRangeSlicer = Period.ofDays(1),
        )
        return gateway.client.aggregateGroupByPeriod(request)
            .mapNotNull { bucket -> value(total, bucket.result)?.let { bucket.startTime.toLocalDate() to it } }
            .toMap()
    }

    suspend fun hourly(total: DailyTotal, date: LocalDate): Map<Int, Double> {
        if (read(total.record) !in gateway.grantedPermissions()) return emptyMap()
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        if (end <= start) return emptyMap()
        val request = AggregateGroupByDurationRequest(
            metrics = setOf(metric(total)),
            timeRangeFilter = TimeRangeFilter.between(start, end),
            timeRangeSlicer = Duration.ofHours(1),
        )
        return gateway.client.aggregateGroupByDuration(request)
            .mapNotNull { bucket -> value(total, bucket.result)?.let { bucket.startTime.atZone(zone).hour to it } }
            .toMap()
    }

    private fun metric(total: DailyTotal) = when (total) {
        DailyTotal.Steps -> StepsRecord.COUNT_TOTAL
        DailyTotal.DistanceMeters -> DistanceRecord.DISTANCE_TOTAL
        DailyTotal.Floors -> FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
        DailyTotal.CaloriesOutKcal -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
    }

    private fun value(total: DailyTotal, result: AggregationResult): Double? = when (total) {
        DailyTotal.Steps -> result[StepsRecord.COUNT_TOTAL]?.toDouble()
        DailyTotal.DistanceMeters -> result[DistanceRecord.DISTANCE_TOTAL]?.inMeters
        DailyTotal.Floors -> result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
        DailyTotal.CaloriesOutKcal -> result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
    }
}
