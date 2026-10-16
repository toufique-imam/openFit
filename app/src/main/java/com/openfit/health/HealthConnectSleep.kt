package com.openfit.health

import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.SleepNight
import com.openfit.domain.SleepStage
import com.openfit.domain.minutesOf
import com.openfit.domain.StageSegment
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Nights of sleep from Health Connect, filed under the day they ended. */
class HealthConnectSleep(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun nights(from: LocalDate, until: LocalDate): Map<LocalDate, SleepNight> {
        if (read(SleepSessionRecord::class) !in gateway.grantedPermissions()) return emptyMap()
        val range = TimeRangeFilter.between(from.minusDays(1).atTime(12, 0).atZone(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        return readAll(SleepSessionRecord::class, range)
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .filterKeys { it in from..until }
            .mapValues { (date, sessions) ->
                val main = sessions.maxBy { Duration.between(it.startTime, it.endTime) }
                SleepNight(
                    date = date,
                    start = main.startTime,
                    end = main.endTime,
                    segments = segments(main),
                    totalAsleepMinutes = minutesOf(sessions.flatMap { session -> segments(session).filter { it.stage != SleepStage.Awake } }).toInt(),
                )
            }
    }

    /** Blood-oxygen readings during a night, for Fitbit's "estimated oxygen variation". */
    suspend fun oxygen(start: Instant, end: Instant): List<Double> {
        if (read(OxygenSaturationRecord::class) !in gateway.grantedPermissions()) return emptyList()
        return readAll(OxygenSaturationRecord::class, TimeRangeFilter.between(start, end)).map { it.percentage.value }
    }

    /** Stage segments, or a single "light" block for sessions recorded without stages. */
    private fun segments(session: SleepSessionRecord): List<StageSegment> {
        val staged = session.stages.mapNotNull { stage ->
            STAGES[stage.stage]?.let { StageSegment(it, stage.startTime, stage.endTime) }
        }.sortedBy { it.start }
        return staged.ifEmpty { listOf(StageSegment(SleepStage.Light, session.startTime, session.endTime)) }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = gateway.client.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records
    }

    private companion object {
        val STAGES = mapOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE to SleepStage.Awake,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to SleepStage.Awake,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to SleepStage.Awake,
            SleepSessionRecord.STAGE_TYPE_LIGHT to SleepStage.Light,
            SleepSessionRecord.STAGE_TYPE_SLEEPING to SleepStage.Light,
            SleepSessionRecord.STAGE_TYPE_DEEP to SleepStage.Deep,
            SleepSessionRecord.STAGE_TYPE_REM to SleepStage.Rem,
        )
    }
}
