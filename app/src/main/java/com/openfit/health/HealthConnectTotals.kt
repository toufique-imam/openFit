package com.openfit.health

import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
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
enum class DailyTotal(val metric: TotalMetric) {
    Steps(TotalMetric.Steps),
    DistanceMeters(TotalMetric.DistanceMeters),
    Floors(TotalMetric.Floors),
    CaloriesOutKcal(TotalMetric.CaloriesOutKcal),
    CaloriesInKcal(TotalMetric.CaloriesInKcal),
    WaterLiters(TotalMetric.WaterLiters),
    ;

    val record: KClass<out Record> get() = metric.record
}

/** Daily, hourly and per-minute sums, from openFit's saved copy ([HealthStore]). */
class HealthConnectTotals(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun daily(total: DailyTotal, from: LocalDate, until: LocalDate): Map<LocalDate, Double> {
        if (read(total.record) !in gateway.grantedPermissions()) return emptyMap()
        return gateway.store.dailyTotals(total.metric, from, until)
    }

    suspend fun hourly(total: DailyTotal, date: LocalDate): Map<Int, Double> {
        if (read(total.record) !in gateway.grantedPermissions()) return emptyMap()
        return gateway.store.hourly(total.metric, date)
    }

    /** Steps per minute (epoch minute → steps) for one day between [fromHour] and [untilHour]; only steps are kept per minute. */
    suspend fun minutely(total: DailyTotal, date: LocalDate, fromHour: Int, untilHour: Int): Map<Long, Double> {
        require(total == DailyTotal.Steps) { "Only steps are saved per minute" }
        if (read(total.record) !in gateway.grantedPermissions()) return emptyMap()
        val from = date.atTime(fromHour, 0).atZone(zone).toEpochSecond() / 60
        val until = date.atTime(untilHour.coerceAtMost(23), 59).atZone(zone).toEpochSecond() / 60
        return gateway.store.minuteSteps(date).filterKeys { it in from..until }
    }

    suspend fun hourlyByDay(total: DailyTotal, from: LocalDate, until: LocalDate): Map<LocalDate, Map<Int, Double>> {
        if (read(total.record) !in gateway.grantedPermissions()) return emptyMap()
        return gateway.store.hourlyByDay(total.metric, from, until)
    }
}
