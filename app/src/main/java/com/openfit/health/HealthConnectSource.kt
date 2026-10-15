package com.openfit.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openfit.domain.DailyHistory
import com.openfit.domain.cardioLoad
import com.openfit.domain.workoutLoads
import com.openfit.domain.cardioLoadByMinute
import com.openfit.domain.CardioLoadDay
import com.openfit.domain.cardioMaxHeartRate
import com.openfit.domain.DailyMetrics
import com.openfit.domain.Exercise
import com.openfit.domain.HeartRateSample
import com.openfit.domain.Reading
import com.openfit.domain.SleepSummary
import com.openfit.domain.estimateRestingBpm
import com.openfit.domain.heartRateZones
import com.openfit.domain.zoneMinutes
import com.openfit.health.HealthConnectGateway.Companion.read
import com.openfit.profile.Profile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectSource(
    private val gateway: HealthConnectGateway,
    private val dayLoadCache: DayLoadCache,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : DailyMetricsSource {

    private val client: HealthConnectClient get() = gateway.client

    override suspend fun load(date: LocalDate, profile: Profile): DailyMetrics {
        val granted = gateway.grantedPermissions()
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        val window = Window(start, end, granted)

        val totals = aggregate(window)
        val heartRate = window.records(HeartRateRecord::class, start)
            .flatMap { record -> record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) } }
        val restingBpm = window.records(RestingHeartRateRecord::class, start - MEASUREMENT_LOOKBACK)
            .maxByOrNull { it.time }
            ?.let { Reading(it.beatsPerMinute.toInt(), it.time) }
            ?: estimateRestingBpm(heartRate)?.let { Reading(it, end) }
        val age = profile.age

        return DailyMetrics(
            steps = totals?.get(StepsRecord.COUNT_TOTAL),
            hourlySteps = hourlySteps(window),
            distanceMeters = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            floors = totals?.get(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
            caloriesOutKcal = totals?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            caloriesInKcal = totals?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories,
            waterLiters = totals?.get(HydrationRecord.VOLUME_TOTAL)?.inLiters,
            zoneMinutes = if (age != null && restingBpm != null && heartRate.isNotEmpty()) {
                zoneMinutes(heartRate, heartRateZones(age, restingBpm.value))
            } else {
                null
            },
            cardioLoad = if (age != null && restingBpm != null && heartRate.isNotEmpty()) {
                cardioLoad(heartRate, restingBpm.value, cardioMaxHeartRate(age), profile.sex, movingMinutes(start, end, granted))
            } else {
                null
            },
            sleep = window.records(SleepSessionRecord::class, start - SLEEP_LOOKBACK)
                .filter { it.endTime > start && it.endTime <= end }
                .takeIf { it.isNotEmpty() }
                ?.let { sessions -> SleepSummary(sessions.sumDurations(::asleepDuration), sessions.maxOf { it.endTime }) },
            exercises = window.records(ExerciseSessionRecord::class, start).map { it.toExercise() },
            heartRateRange = heartRate.takeIf { it.isNotEmpty() }?.let { samples ->
                samples.minOf { it.bpm }.toInt()..samples.maxOf { it.bpm }.toInt()
            },
            latestHeartRate = heartRate.maxByOrNull { it.time }?.let { Reading(it.bpm.toInt(), it.time) },
            restingBpm = restingBpm,
            hrvMs = window.records(HeartRateVariabilityRmssdRecord::class, start - SLEEP_LOOKBACK)
                .latestAverage({ it.time }) { it.heartRateVariabilityMillis },
            spo2Percent = window.records(OxygenSaturationRecord::class, start - SLEEP_LOOKBACK)
                .latestAverage({ it.time }) { it.percentage.value },
            breathsPerMinute = window.records(RespiratoryRateRecord::class, start - SLEEP_LOOKBACK)
                .latestAverage({ it.time }) { it.rate },
            skinTempDeltaCelsius = window.records(SkinTemperatureRecord::class, start - SLEEP_LOOKBACK)
                .filter { it.deltas.isNotEmpty() }
                .maxByOrNull { it.endTime }
                ?.let { record -> Reading(record.deltas.map { it.delta.inCelsius }.average(), record.endTime) },
            vo2Max = window.records(Vo2MaxRecord::class, start - MEASUREMENT_LOOKBACK)
                .maxByOrNull { it.time }
                ?.let { Reading(it.vo2MillilitersPerMinuteKilogram, it.time) },
            weightKg = window.records(WeightRecord::class, start - MEASUREMENT_LOOKBACK)
                .maxByOrNull { it.time }
                ?.let { Reading(it.weight.inKilograms, it.time) },
            bodyFatPercent = window.records(BodyFatRecord::class, start - MEASUREMENT_LOOKBACK)
                .maxByOrNull { it.time }
                ?.let { Reading(it.percentage.value, it.time) },
        )
    }

    override suspend fun exerciseDays(from: LocalDate, until: LocalDate): Set<LocalDate> {
        if (read(ExerciseSessionRecord::class) !in gateway.grantedPermissions()) return emptySet()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        return readAll(ExerciseSessionRecord::class, range)
            .map { it.startTime.atZone(zone).toLocalDate() }
            .toSet()
    }

    override suspend fun history(from: LocalDate, until: LocalDate, profile: Profile, backfillDays: Int): DailyHistory {
        val granted = gateway.grantedPermissions()
        val start = from.atStartOfDay(zone).toInstant()
        val window = Window(start, until.plusDays(1).atStartOfDay(zone).toInstant(), granted)
        val resting = window.records(RestingHeartRateRecord::class, start - MEASUREMENT_LOOKBACK)
        val (loads, pending) = dayLoads(from, until, profile, window, resting, backfillDays)
        return DailyHistory(
            zoneMinutes = loads.mapValues { it.value.zoneMinutes },
            cardioLoad = loads.mapValues { it.value.cardioLoad },
            restingBpm = resting
                .filter { it.time >= start }
                .groupBy { localDate(it.time) }
                .mapValues { (_, readings) -> readings.maxBy { it.time }.beatsPerMinute.toInt() },
            hrvMs = window.records(HeartRateVariabilityRmssdRecord::class, start)
                .groupBy { localDate(it.time) }
                .mapValues { (_, readings) -> readings.map { it.heartRateVariabilityMillis }.average() },
            sleepMinutes = window.records(SleepSessionRecord::class, start - SLEEP_LOOKBACK)
                .filter { it.endTime > start }
                .groupBy { localDate(it.endTime) }
                .mapValues { (_, sessions) -> sessions.sumDurations(::asleepDuration).toMinutes().toInt() },
            pendingDays = pending,
        )
    }

    override suspend fun loadHistory(from: LocalDate, until: LocalDate, profile: Profile, backfillDays: Int): DailyHistory {
        val granted = gateway.grantedPermissions()
        val start = from.atStartOfDay(zone).toInstant()
        val window = Window(start, until.plusDays(1).atStartOfDay(zone).toInstant(), granted)
        val resting = window.records(RestingHeartRateRecord::class, start - MEASUREMENT_LOOKBACK)
        val (loads, pending) = dayLoads(from, until, profile, window, resting, backfillDays)
        return DailyHistory(
            zoneMinutes = loads.mapValues { it.value.zoneMinutes },
            cardioLoad = loads.mapValues { it.value.cardioLoad },
            pendingDays = pending,
        )
    }

    private suspend fun dayLoads(
        from: LocalDate,
        until: LocalDate,
        profile: Profile,
        window: Window,
        resting: List<RestingHeartRateRecord>,
        backfillDays: Int,
    ): Pair<Map<LocalDate, DayLoad>, Int> {
        val age = profile.age
        if (age == null || read(HeartRateRecord::class) !in window.granted) return emptyMap<LocalDate, DayLoad>() to 0
        val profileKey = "$LOAD_FORMULA:$age:${profile.sex?.name ?: "-"}"
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.toList()
        val cached = days.associateWith { dayLoadCache.get(profileKey, it) }
        val uncached = days.filter { cached[it] == null }
        val computed = uncached
            .sortedDescending()
            .take(backfillDays)
            .associateWith { day -> dayLoad(day, age, profile, resting, window.granted) }
        val settled = LocalDate.now(zone).minusDays(1)
        dayLoadCache.putAll(profileKey, computed.filterKeys { it < settled })
        val loads = (cached + computed)
            .mapNotNull { (day, load) -> load?.takeIf { it != DayLoad.NONE }?.let { day to it } }
            .toMap()
        return loads to (uncached.size - computed.size)
    }

    private suspend fun dayLoad(
        day: LocalDate,
        age: Int,
        profile: Profile,
        resting: List<RestingHeartRateRecord>,
        granted: Set<String>,
    ): DayLoad {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val samples = readAll(HeartRateRecord::class, TimeRangeFilter.between(dayStart, dayEnd))
            .flatMap { record -> record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) } }
        if (samples.isEmpty()) return DayLoad.NONE
        val restingBpm = resting.filter { it.time < dayEnd }.maxByOrNull { it.time }?.beatsPerMinute?.toInt()
            ?: estimateRestingBpm(samples)
            ?: return DayLoad.NONE
        return DayLoad(
            zoneMinutes = zoneMinutes(samples, heartRateZones(age, restingBpm)).total,
            cardioLoad = cardioLoad(samples, restingBpm, cardioMaxHeartRate(age), profile.sex, movingMinutes(dayStart, dayEnd, granted)),
        )
    }

    override suspend fun cardioLoadDay(date: LocalDate, profile: Profile): CardioLoadDay? {
        val age = profile.age ?: return null
        val granted = gateway.grantedPermissions()
        if (read(HeartRateRecord::class) !in granted) return null
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        val window = Window(start, end, granted)
        val samples = window.records(HeartRateRecord::class, start)
            .flatMap { record -> record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) } }
        val restingBpm = window.records(RestingHeartRateRecord::class, start - MEASUREMENT_LOOKBACK).maxByOrNull { it.time }?.beatsPerMinute?.toInt()
            ?: estimateRestingBpm(samples)
            ?: return CardioLoadDay(date, emptyMap(), emptyList())
        val byMinute = cardioLoadByMinute(samples, restingBpm, cardioMaxHeartRate(age), profile.sex, movingMinutes(start, end, granted))
        val exercises = window.records(ExerciseSessionRecord::class, start).map { it.toExercise() }
        return CardioLoadDay(date, byMinute, workoutLoads(byMinute, exercises))
    }

    private suspend fun movingMinutes(start: Instant, end: Instant, granted: Set<String>): Set<Long>? {
        val steps = read(StepsRecord::class) in granted
        val exercise = read(ExerciseSessionRecord::class) in granted
        if (!steps && !exercise) return null
        val range = TimeRangeFilter.between(start, end)
        val moving = mutableSetOf<Long>()
        if (steps) {
            client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range, Duration.ofMinutes(1)))
                .filter { (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) > 0L }
                .forEach { moving += it.startTime.epochSecond / 60 }
        }
        if (exercise) {
            readAll(ExerciseSessionRecord::class, range).forEach { session ->
                moving += session.startTime.epochSecond / 60..session.endTime.epochSecond / 60
            }
        }
        return moving
    }

    private fun localDate(time: Instant): LocalDate = time.atZone(zone).toLocalDate()

    private suspend fun hourlySteps(window: Window): Map<Int, Long> {
        if (read(StepsRecord::class) !in window.granted) return emptyMap()
        val request = AggregateGroupByDurationRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(window.start, window.end),
            timeRangeSlicer = Duration.ofHours(1),
        )
        return client.aggregateGroupByDuration(request).associate { bucket ->
            bucket.startTime.atZone(zone).hour to (bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L)
        }
    }

    private inner class Window(val start: Instant, val end: Instant, val granted: Set<String>) {
        suspend fun <T : Record> records(type: KClass<T>, from: Instant): List<T> =
            if (read(type) in granted) readAll(type, TimeRangeFilter.between(from, end)) else emptyList()
    }

    private suspend fun aggregate(window: Window): AggregationResult? {
        val metrics = AGGREGATES
            .filterKeys { read(it) in window.granted }
            .values
            .toSet()
        if (metrics.isEmpty()) return null
        return client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(window.start, window.end)))
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records
    }

    private fun asleepDuration(session: SleepSessionRecord): Duration {
        val awake = session.stages
            .filter { it.stage in AWAKE_STAGES }
            .sumDurations { Duration.between(it.startTime, it.endTime) }
        return Duration.between(session.startTime, session.endTime) - awake
    }

    private companion object {
        val MEASUREMENT_LOOKBACK: Duration = Duration.ofDays(30)
        val SLEEP_LOOKBACK: Duration = Duration.ofHours(18)
        val NIGHT: Duration = Duration.ofHours(12)
        const val DEFAULT_EXERCISE_NAME = "Workout"

        /** Bump when the day-load formula changes so cached days are recalculated. */
        const val LOAD_FORMULA = "v2"

        val EXERCISE_NAMES: Map<Int, String> = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walk",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Run",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill run",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hike",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Outdoor bike",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Bike",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Swim",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swim",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Weights",
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weights",
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stairclimber",
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dancing",
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Martial arts",
        )

        fun ExerciseSessionRecord.toExercise(): Exercise =
            Exercise(title?.takeIf { it.isNotBlank() } ?: EXERCISE_NAMES[exerciseType] ?: DEFAULT_EXERCISE_NAME, startTime, endTime)

        val AGGREGATES: Map<KClass<out Record>, AggregateMetric<*>> = mapOf(
            StepsRecord::class to StepsRecord.COUNT_TOTAL,
            DistanceRecord::class to DistanceRecord.DISTANCE_TOTAL,
            FloorsClimbedRecord::class to FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL,
            TotalCaloriesBurnedRecord::class to TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            NutritionRecord::class to NutritionRecord.ENERGY_TOTAL,
            HydrationRecord::class to HydrationRecord.VOLUME_TOTAL,
        )

        val AWAKE_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )

        fun <T> List<T>.latestAverage(time: (T) -> Instant, value: (T) -> Double): Reading<Double>? {
            val latest = maxOfOrNull(time) ?: return null
            val night = filter { Duration.between(time(it), latest) <= NIGHT }
            return Reading(night.map(value).average(), latest)
        }

        fun <T> Iterable<T>.sumDurations(duration: (T) -> Duration): Duration =
            fold(Duration.ZERO) { total, item -> total + duration(item) }
    }
}
