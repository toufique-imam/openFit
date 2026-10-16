package com.openfit.health

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.AgeFactor
import com.openfit.domain.MINUTES_PER_DAY
import com.openfit.domain.PulseAge
import com.openfit.domain.pulseAge
import com.openfit.domain.sleepRegularity
import com.openfit.health.HealthConnectGateway.Companion.read
import com.openfit.profile.Profile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** The nine Pulse Age inputs over the [WINDOW_DAYS] days before a date, from Health Connect. */
class PulseAgeSource(private val gateway: HealthConnectGateway, dayLoadCache: DayLoadCache, private val zone: ZoneId = ZoneId.systemDefault()) {

    private val source = HealthConnectSource(gateway, dayLoadCache)
    private val totals = HealthConnectTotals(gateway)
    private val sleep = HealthConnectSleep(gateway)

    /** Null without the user's age and sex, or with fewer than five inputs. */
    suspend fun pulseAge(until: LocalDate, profile: Profile): PulseAge? {
        val age = profile.age ?: return null
        val sex = profile.sex ?: return null
        return pulseAge(age, sex, inputs(until, profile))
    }

    suspend fun inputs(until: LocalDate, profile: Profile): Map<AgeFactor, Double> {
        val from = until.minusDays(WINDOW_DAYS - 1)
        val granted = gateway.grantedPermissions()
        val inputs = mutableMapOf<AgeFactor, Double>()

        val nights = sleep.nights(from, until)
        nights.values.map { it.totalAsleepMinutes / 60.0 }.takeIf { it.isNotEmpty() }?.let { inputs[AgeFactor.SleepHours] = it.average() }
        sleepRegularity(asleepMinutes(from, until, granted))?.let { inputs[AgeFactor.SleepConsistency] = it }

        val steps = totals.daily(DailyTotal.Steps, from, until).values.filter { it > 0 }
        steps.takeIf { it.isNotEmpty() }?.let { inputs[AgeFactor.Steps] = it.average() }

        val history = source.history(from, until, profile, backfillDays = WINDOW_DAYS.toInt())
        history.restingBpm.values.takeIf { it.isNotEmpty() }?.let { inputs[AgeFactor.RestingHeartRate] = it.average() }
        // Days with heart rate stand for the window: weekly minutes are the daily mean × 7.
        val zoneDays = history.heartZoneMinutes.values
        if (zoneDays.isNotEmpty()) {
            inputs[AgeFactor.ModerateMinutes] = zoneDays.map { it.fatBurn + it.cardio }.average() * 7
            inputs[AgeFactor.VigorousMinutes] = zoneDays.map { it.peak }.average() * 7
            // Strength is measured as zero on worn days without a strength workout.
            inputs[AgeFactor.StrengthMinutes] = strengthMinutes(from, until, granted) * 7.0 / WINDOW_DAYS
        }

        latestAverage(Vo2MaxRecord::class, until, granted) { it.time to it.vo2MillilitersPerMinuteKilogram }?.let { inputs[AgeFactor.Vo2Max] = it }
        latestAverage(BodyFatRecord::class, until, granted) { it.time to it.percentage.value }?.let { inputs[AgeFactor.BodyFat] = it }
        return inputs
    }

    /** Each day's minutes asleep (any session's sleep stages) on the local clock. */
    private suspend fun asleepMinutes(from: LocalDate, until: LocalDate, granted: Set<String>): Map<LocalDate, BooleanArray> {
        if (read(SleepSessionRecord::class) !in granted) return emptyMap()
        val start = from.atStartOfDay(zone).toInstant()
        val end = until.plusDays(1).atStartOfDay(zone).toInstant()
        val days = mutableMapOf<LocalDate, BooleanArray>()
        readAll(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofHours(18)), end)).forEach { session ->
            val asleep = session.stages
                .filter { it.stage != SleepSessionRecord.STAGE_TYPE_AWAKE && it.stage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED }
                .map { it.startTime to it.endTime }
                .ifEmpty { listOf(session.startTime to session.endTime) }
            asleep.forEach { (stageStart, stageEnd) ->
                var minute = stageStart.coerceAtLeast(start)
                while (minute < stageEnd && minute < end) {
                    val local = minute.atZone(zone)
                    days.getOrPut(local.toLocalDate()) { BooleanArray(MINUTES_PER_DAY) }[local.hour * 60 + local.minute] = true
                    minute = minute.plusSeconds(60)
                }
            }
        }
        return days
    }

    private suspend fun strengthMinutes(from: LocalDate, until: LocalDate, granted: Set<String>): Double {
        if (read(ExerciseSessionRecord::class) !in granted) return 0.0
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        return readAll(ExerciseSessionRecord::class, range)
            .filter { it.exerciseType in STRENGTH_TYPES }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().toDouble() }
    }

    /** The mean of readings over the window, or the latest within [LOOKBACK_DAYS] when the window has none. */
    private suspend fun <T : Record> latestAverage(type: KClass<T>, until: LocalDate, granted: Set<String>, reading: (T) -> Pair<Instant, Double>): Double? {
        if (read(type) !in granted) return null
        val end = until.plusDays(1).atStartOfDay(zone).toInstant()
        val readings = readAll(type, TimeRangeFilter.between(until.minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant(), end)).map(reading)
        val windowStart = until.minusDays(WINDOW_DAYS - 1).atStartOfDay(zone).toInstant()
        return readings.filter { it.first >= windowStart }.map { it.second }.takeIf { it.isNotEmpty() }?.average()
            ?: readings.maxByOrNull { it.first }?.second
    }

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

    companion object {
        const val WINDOW_DAYS = 28L
        private const val LOOKBACK_DAYS = 90L

        /** Workouts that count as strength: strength training, weightlifting, calisthenics, HIIT, Pilates and yoga. */
        private val STRENGTH_TYPES = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        )
    }
}
