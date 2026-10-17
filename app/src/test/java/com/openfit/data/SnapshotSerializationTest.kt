package com.openfit.data

import com.openfit.domain.*
import com.openfit.profile.Profile
import com.openfit.ui.age.PulseAgeUiState
import com.openfit.ui.cardio.*
import com.openfit.ui.energy.EnergyUiState
import com.openfit.ui.exercise.*
import com.openfit.ui.goal.*
import com.openfit.ui.heart.*
import com.openfit.ui.hourly.*
import com.openfit.ui.readiness.ReadinessUiState
import com.openfit.ui.sleep.*
import com.openfit.ui.stress.StressUiState
import com.openfit.ui.today.TodaySnapshot
import com.openfit.ui.vitals.*
import com.openfit.ui.vo2.Vo2MaxUiState
import com.openfit.ui.weight.WeightUiState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class SnapshotSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val day = LocalDate.of(2026, 10, 16)
    private val at = Instant.parse("2026-10-16T06:00:00Z")
    private val period = ChartPeriod.Week
    private val profile = Profile(age = 35, sex = Sex.Female, birthDate = LocalDate.of(1991, 1, 1), activeHours = 9..17, bedtime = LocalTime.of(23, 30))

    private fun <T> roundTrip(value: T, serializer: KSerializer<T>) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    @Test fun `today serializes nested measurements ranges intervals and models`() {
        val metrics = DailyMetrics(steps = 5_678, hourlySteps = mapOf(9 to 456), zoneMinutes = ZoneMinutes(12, 6, 1),
            sleep = SleepSummary(Duration.ofMinutes(465), at), heartRateRange = 55..163,
            latestHeartRate = Reading(72, at), hrvMs = Reading(55.4, at),
            exercises = listOf(Exercise("Walk", at, at.plusSeconds(900))))
        val energy = EnergyDay(day, listOf(null, 61.25, 60.5), 30.5, 21.2,
            listOf(EnergyDrain.Workout(12, 5.5), EnergyDrain.Stress(16, 3.25)), 60, 75.5)
        val snapshot = TodaySnapshot(day, metrics, listOf(day), setOf(day), zoneTarget = LoadTarget(150.0, 23.0, 70.0),
            cardioRange = LoadRange(5.0, 24.0, 30.0), energy = energy, energyLoaded = true,
            stress = StressDay(day, listOf(null, 25, 60), listOf(true, false, false)),
            pulseAge = PulseAge(34.5, 35, listOf(AgeEffect(AgeFactor.Vo2Max, 41.0, 40.0, -0.5, true))), pulseAgeLoaded = true)
        roundTrip(snapshot, TodaySnapshot.serializer())
    }

    @Test fun `legacy today row decodes with defaults and ignores extra fields`() {
        val legacy = """{"date":"2026-10-16","metrics":{"steps":3456},"week":[],"exerciseDays":[],"zoneTarget":{"weekly":150.0,"daily":20.0,"doneThisWeek":40.0},"retiredField":true}"""
        val snapshot = json.decodeFromString(TodaySnapshot.serializer(), legacy)
        assertEquals(3456L, snapshot.metrics.steps)
        assertFalse(snapshot.energyLoaded)
        assertFalse(snapshot.pulseAgeLoaded)
        assertNull(snapshot.pulseAge)
    }

    @Test fun `cardio and recovery detail states round trip`() {
        roundTrip(CardioLoadUiState.Ready(period, day, day, profile, CardioDayView(12.5, LoadRange(4.0, 24.0, 30.0), TargetStatus.InTarget, null), null, TrainingView(1.12), false), CardioLoadUiState.Ready.serializer())
        roundTrip(ReadinessUiState.Ready(period, day, day, null, null, emptyList(), emptyList(), null), ReadinessUiState.Ready.serializer())
        roundTrip(EnergyUiState.Ready(period, day, day, mapOf(day to EnergyDay(day, listOf(null, 50.0), 10.0, 20.0, listOf(EnergyDrain.Stress(12, 5.0)))), emptyList()), EnergyUiState.Ready.serializer())
        roundTrip(StressUiState.Ready(period, day, day, mapOf(day to StressDay(day, listOf(null, 45), listOf(false, false))), hrv = mapOf(day to 54.5)), StressUiState.Ready.serializer())
        roundTrip(PulseAgeUiState.Ready(profile, PulseAge(34.5, 35, emptyList()), mapOf(AgeFactor.Steps to 8000.0)), PulseAgeUiState.Ready.serializer())
    }

    @Test fun `activity detail states round trip`() {
        roundTrip(GoalMetricUiState.Ready(GoalMetric.Steps, profile, period, day, day, 10000.0, 5678.0, listOf(null, 25.0), listOf(5678.0), GoalSummary(5678.0, 5678.0, 0), emptyList()), GoalMetricUiState.Ready.serializer())
        roundTrip(ExerciseUiState.Ready(period, day, day, profile, ExerciseMode.entries.first(), listOf(day), emptyMap(), emptyList()), ExerciseUiState.Ready.serializer())
        roundTrip(HourlyUiState.Ready(period, day, day, HourlyMode.entries.first(), profile, 9..17, mapOf(9 to 270.0), 10, null, mapOf(day to 1.0), emptyMap(), emptyList()), HourlyUiState.Ready.serializer())
        roundTrip(HeartUiState.Ready(period, day, day, HeartMode.entries.first(), null, 135.5, listOf(null, 62.0), listOf(12.0), 62.0, 63.0, 12.0, 12, emptyList()), HeartUiState.Ready.serializer())
    }

    @Test fun `sleep and health detail states round trip`() {
        roundTrip(SleepUiState.Ready(period, day, day, profile, emptyMap(), mapOf(day to 80), null, mapOf(SleepStage.Deep to 20.5), 1.5, StageMode.entries.first(), SleepChart.entries.first(), SleepStage.Deep), SleepUiState.Ready.serializer())
        roundTrip(HealthMetricsUiState.Ready(day, day, profile, emptyList()), HealthMetricsUiState.Ready.serializer())
        roundTrip(MetricDetailUiState.Ready(HealthMetric.entries.first(), period, day, day, profile, mapOf(day to 62.5), emptyMap(), emptyList()), MetricDetailUiState.Ready.serializer())
        roundTrip(WeightUiState.Ready(period, day, day, profile, mapOf(day to 67.25), day to 67.25), WeightUiState.Ready.serializer())
        roundTrip(Vo2MaxUiState.Ready(period, day, day, 35, Sex.Female, mapOf(day to (41.5 to true))), Vo2MaxUiState.Ready.serializer())
    }
}
