package com.openfit.health

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.HealthMetric
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Daily values of Fitbit's health metrics, filed under the local date of the reading (overnight readings land on the wake day). */
class HealthConnectVitals(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun daily(metrics: Set<HealthMetric>, from: LocalDate, until: LocalDate): Map<HealthMetric, Map<LocalDate, Double>> {
        val granted = gateway.grantedPermissions()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        return metrics.associateWith { metric ->
            when (metric) {
                HealthMetric.RestingHeartRate -> byDay(RestingHeartRateRecord::class, granted, range, { it.time }) { it.beatsPerMinute.toDouble() }
                HealthMetric.HeartRateVariability -> byDay(HeartRateVariabilityRmssdRecord::class, granted, range, { it.time }) { it.heartRateVariabilityMillis }
                HealthMetric.BreathingRate -> byDay(RespiratoryRateRecord::class, granted, range, { it.time }) { it.rate }
                HealthMetric.BloodOxygen -> byDay(OxygenSaturationRecord::class, granted, range, { it.time }) { it.percentage.value }
                HealthMetric.SkinTemperature -> byDay(SkinTemperatureRecord::class, granted, range, { it.endTime }) { record ->
                    record.deltas.takeIf { it.isNotEmpty() }?.map { it.delta.inCelsius }?.average() ?: Double.NaN
                }.filterValues { !it.isNaN() }
            }
        }
    }

    private suspend fun <T : Record> byDay(
        type: KClass<T>,
        granted: Set<String>,
        range: TimeRangeFilter,
        time: (T) -> Instant,
        value: (T) -> Double,
    ): Map<LocalDate, Double> {
        if (read(type) !in granted) return emptyMap()
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = gateway.client.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records.groupBy { time(it).atZone(zone).toLocalDate() }.mapValues { (_, day) -> day.map(value).average() }
    }
}
