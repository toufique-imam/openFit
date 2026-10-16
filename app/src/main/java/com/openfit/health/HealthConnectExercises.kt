@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class)

package com.openfit.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import com.openfit.domain.DurationSerializer
import com.openfit.domain.InstantSerializer
import com.openfit.domain.IntRangeSerializer
import com.openfit.domain.LocalDateSerializer
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A logged or auto-detected workout with what Fitbit shows under its name: distance, or calories when it has none. */
@Serializable
data class ExerciseSession(
    val name: String,
    val type: Int,
    val start: Instant,
    val end: Instant,
    val distanceMeters: Double?,
    val caloriesKcal: Double?,
) {
    val duration: Duration get() = Duration.between(start, end)
}

class HealthConnectExercises(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun sessions(from: LocalDate, until: LocalDate): List<ExerciseSession> {
        val granted = gateway.grantedPermissions()
        if (read(ExerciseSessionRecord::class) !in granted) return emptyList()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        val records = mutableListOf<ExerciseSessionRecord>()
        var pageToken: String? = null
        do {
            val response = gateway.client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        val metrics = buildSet {
            if (read(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (read(TotalCaloriesBurnedRecord::class) in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        return records.sortedBy { it.startTime }.map { record ->
            val totals = if (metrics.isEmpty()) null else gateway.client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(record.startTime, record.endTime)))
            ExerciseSession(
                name = record.toExercise().name,
                type = record.exerciseType,
                start = record.startTime,
                end = record.endTime,
                distanceMeters = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.takeIf { it > 0 },
                caloriesKcal = totals?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories?.takeIf { it > 0 },
            )
        }
    }
}
