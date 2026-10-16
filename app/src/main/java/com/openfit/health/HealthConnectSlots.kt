package com.openfit.health

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.SLOTS_PER_DAY
import com.openfit.domain.SLOT_MINUTES
import com.openfit.domain.Slot
import com.openfit.health.HealthConnectGateway.Companion.read
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Each day as six-minute slots (mean heart rate, asleep, in a workout) for Energy Bank. Days before yesterday are
 * cached, so only the first run reads weeks of heart rate.
 */
class HealthConnectSlots(private val gateway: HealthConnectGateway, private val cache: SlotCache, private val zone: ZoneId = ZoneId.systemDefault()) {

    suspend fun days(from: LocalDate, until: LocalDate): List<Pair<LocalDate, List<Slot>>> {
        val granted = gateway.grantedPermissions()
        if (read(HeartRateRecord::class) !in granted) return emptyList()
        val settled = LocalDate.now().minusDays(1)
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.toList()
        val fresh = mutableMapOf<LocalDate, List<Slot>>()
        val result = dates.map { date ->
            date to (cache.get(date) ?: slots(date, granted).also { if (date < settled) fresh[date] = it })
        }
        cache.putAll(fresh)
        return result
    }

    private suspend fun slots(date: LocalDate, granted: Set<String>): List<Slot> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)
        val sums = DoubleArray(SLOTS_PER_DAY)
        val counts = IntArray(SLOTS_PER_DAY)
        readAll(HeartRateRecord::class, range).forEach { record ->
            record.samples.forEach { sample ->
                slotOf(sample.time, start)?.let { slot ->
                    sums[slot] += sample.beatsPerMinute.toDouble()
                    counts[slot]++
                }
            }
        }
        val asleep = BooleanArray(SLOTS_PER_DAY)
        if (read(SleepSessionRecord::class) in granted) {
            // Sessions that started the evening before still cover this day's early slots.
            readAll(SleepSessionRecord::class, TimeRangeFilter.between(start.minusSeconds(SLEEP_LOOKBACK_SECONDS), end)).forEach { session ->
                val stages = session.stages.filter { it.stage != SleepSessionRecord.STAGE_TYPE_AWAKE && it.stage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED }
                if (session.stages.isEmpty()) mark(asleep, session.startTime, session.endTime, start) else stages.forEach { mark(asleep, it.startTime, it.endTime, start) }
            }
        }
        val exercise = BooleanArray(SLOTS_PER_DAY)
        if (read(ExerciseSessionRecord::class) in granted) {
            readAll(ExerciseSessionRecord::class, range).forEach { mark(exercise, it.startTime, it.endTime, start) }
        }
        return List(SLOTS_PER_DAY) { i ->
            Slot(bpm = if (counts[i] > 0) (sums[i] / counts[i]).roundToInt() else null, asleep = asleep[i], exercise = exercise[i] && !asleep[i])
        }
    }

    /** Marks every slot whose midpoint lies within [from, to). */
    private fun mark(slots: BooleanArray, from: Instant, to: Instant, dayStart: Instant) {
        for (i in 0 until SLOTS_PER_DAY) {
            val middle = dayStart.plusSeconds(i * SLOT_SECONDS + SLOT_SECONDS / 2)
            if (middle >= from && middle < to) slots[i] = true
        }
    }

    private fun slotOf(time: Instant, dayStart: Instant): Int? =
        ((time.epochSecond - dayStart.epochSecond) / SLOT_SECONDS).toInt().takeIf { it in 0 until SLOTS_PER_DAY }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
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
        const val SLOT_SECONDS = SLOT_MINUTES * 60L
        const val SLEEP_LOOKBACK_SECONDS = 18 * 3600L
    }
}

/** Past days' slots, one string per day: `bpm` plus `s` (asleep) or `x` (workout) per slot, empty when not worn. */
class SlotCache(context: Context) {

    private val prefs = context.getSharedPreferences("day_slots", Context.MODE_PRIVATE)

    fun get(date: LocalDate): List<Slot>? = prefs.getString(key(date), null)?.split(',')?.takeIf { it.size == SLOTS_PER_DAY }?.map { token ->
        Slot(
            bpm = token.trimEnd('s', 'x').toIntOrNull(),
            asleep = token.endsWith('s'),
            exercise = token.endsWith('x'),
        )
    }

    fun putAll(days: Map<LocalDate, List<Slot>>) {
        if (days.isEmpty()) return
        val editor = prefs.edit()
        days.forEach { (date, slots) ->
            editor.putString(key(date), slots.joinToString(",") { "${it.bpm ?: ""}${if (it.asleep) "s" else if (it.exercise) "x" else ""}" })
        }
        editor.apply()
    }

    private fun key(date: LocalDate) = "$VERSION:$date"

    private companion object {
        const val VERSION = "v1"
    }
}
