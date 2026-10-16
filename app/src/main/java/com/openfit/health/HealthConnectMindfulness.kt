package com.openfit.health

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.LocalDate
import java.time.ZoneId

/** Fitbit's "Mindful days": days with at least one mindfulness session in Health Connect. */
@OptIn(ExperimentalMindfulnessSessionApi::class)
class HealthConnectMindfulness(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun mindfulDays(from: LocalDate, until: LocalDate): Set<LocalDate> {
        if (read(MindfulnessSessionRecord::class) !in gateway.grantedPermissions()) return emptySet()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        val days = mutableSetOf<LocalDate>()
        var pageToken: String? = null
        do {
            val response = gateway.client.readRecords(ReadRecordsRequest(MindfulnessSessionRecord::class, range, pageToken = pageToken))
            response.records.forEach { days += it.startTime.atZone(zone).toLocalDate() }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return days
    }
}
