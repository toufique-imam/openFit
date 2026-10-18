package com.openfit.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.MIN_MINUTES_TO_MAX
import com.openfit.domain.MINUTES_PER_DAY
import com.openfit.domain.MovingMinute
import com.openfit.domain.RUN_SPEED
import com.openfit.domain.RUN_WINDOW_DAYS
import com.openfit.domain.RunBout
import com.openfit.domain.personalMaxBpm
import com.openfit.domain.runBouts
import com.openfit.domain.runVo2Max
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * VO2 max from runs, read from the local copy: the wearable's distance and heart rate minute by minute. Distance from
 * other apps (a phone's step counter) is left out, as it doubles the pace where both record the same walk.
 */
class RunFitnessSource(private val gateway: HealthConnectGateway, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** VO2 max from the runs in the [RUN_WINDOW_DAYS] days to [until], or null without a qualifying run. */
    suspend fun vo2Max(until: LocalDate, age: Int): Double? =
        runVo2Max(bouts(until.minusDays(RUN_WINDOW_DAYS - 1L), until, age).values.flatten())

    /** The qualifying runs of each day in [from]..[until] that has any. */
    suspend fun bouts(from: LocalDate, until: LocalDate, age: Int): Map<LocalDate, List<RunBout>> {
        val granted = gateway.grantedPermissions()
        if (read(DistanceRecord::class) !in granted || read(HeartRateRecord::class) !in granted) return emptyMap()
        val start = from.atStartOfDay(zone).toInstant()
        val end = until.plusDays(1).atStartOfDay(zone).toInstant()
        val wearables = gateway.store.wearables
        val meters = HashMap<LocalDate, DoubleArray>()
        gateway.store.read(DistanceRecord::class, TimeRangeFilter.between(start, end), origins = wearables)
            .forEach { record ->
                val minutes = generateSequence(record.startTime) { it.plusSeconds(60) }.takeWhile { it < record.endTime }.toList().ifEmpty { listOf(record.startTime) }
                val share = record.distance.inMeters / minutes.size
                minutes.forEach { minute -> slot(minute)?.let { (day, index) -> meters.getOrPut(day) { DoubleArray(MINUTES_PER_DAY) }[index] += share } }
            }
        // Heart rate only for days with enough running to qualify.
        val days = meters.filterValues { day -> day.count { it >= RUN_SPEED } >= MIN_MINUTES_TO_MAX }.mapValues { (day, distance) ->
            val dayStart = day.atStartOfDay(zone).toInstant()
            val beats = Array(MINUTES_PER_DAY) { mutableListOf<Long>() }
            gateway.store.readOverlapping(HeartRateRecord::class, TimeRangeFilter.between(dayStart, day.plusDays(1).atStartOfDay(zone).toInstant()))
                .flatMap { it.samples }
                .forEach { sample -> slot(sample.time)?.takeIf { it.first == day }?.let { (_, index) -> beats[index] += sample.beatsPerMinute } }
            List(MINUTES_PER_DAY) { index ->
                val bpm = beats[index].takeIf { it.isNotEmpty() }?.average()
                if (distance[index] == 0.0 && bpm == null) null else MovingMinute(distance[index], bpm)
            }
        }
        val maxBpm = personalMaxBpm(age, days.values)
        return days.mapValues { (_, minutes) -> runBouts(minutes, maxBpm) }.filterValues { it.isNotEmpty() }
    }

    private fun slot(time: Instant): Pair<LocalDate, Int>? {
        val local = time.atZone(zone)
        return local.toLocalDate() to local.hour * 60 + local.minute
    }
}
