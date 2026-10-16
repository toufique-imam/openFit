package com.openfit.health

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
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
                // Fitbit's resting heart rate is one value a day (61 on 16 Oct 2026); other apps' readings would pull an average away from it.
                HealthMetric.RestingHeartRate -> byDay(RestingHeartRateRecord::class, granted, range, { it.time }, latest = true) { it.beatsPerMinute.toDouble() }
                HealthMetric.HeartRateVariability -> byDay(HeartRateVariabilityRmssdRecord::class, granted, range, { it.time }) { it.heartRateVariabilityMillis }
                HealthMetric.BreathingRate -> byDay(RespiratoryRateRecord::class, granted, range, { it.time }) { it.rate }
                HealthMetric.BloodOxygen -> byDay(OxygenSaturationRecord::class, granted, range, { it.time }) { it.percentage.value }
                HealthMetric.SkinTemperature -> byDay(SkinTemperatureRecord::class, granted, range, { it.endTime }) { record ->
                    record.deltas.takeIf { it.isNotEmpty() }?.map { it.delta.inCelsius }?.average() ?: Double.NaN
                }.filterValues { !it.isNaN() }
            }
        }
    }

    /** Measured VO2 max per day (the day's last reading), from a watch or app that writes it to Health Connect. */
    suspend fun vo2Max(from: LocalDate, until: LocalDate): Map<LocalDate, Double> {
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        return byDay(Vo2MaxRecord::class, gateway.grantedPermissions(), range, { it.time }, latest = true) { it.vo2MillilitersPerMinuteKilogram }
    }

    /** Weight in kilograms per day (the day's last reading). */
    suspend fun weights(from: LocalDate, until: LocalDate): Map<LocalDate, Double> {
        val granted = gateway.grantedPermissions()
        if (read(WeightRecord::class) !in granted) return emptyMap()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        val records = mutableListOf<WeightRecord>()
        var pageToken: String? = null
        do {
            val response = gateway.client.readRecords(ReadRecordsRequest(WeightRecord::class, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records.groupBy { it.time.atZone(zone).toLocalDate() }.mapValues { (_, day) -> day.maxBy { it.time }.weight.inKilograms }
    }

    private suspend fun <T : Record> byDay(
        type: KClass<T>,
        granted: Set<String>,
        range: TimeRangeFilter,
        time: (T) -> Instant,
        latest: Boolean = false,
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
        return records.groupBy { time(it).atZone(zone).toLocalDate() }.mapValues { (_, day) -> if (latest) value(day.maxBy(time)) else day.map(value).average() }
    }
}
