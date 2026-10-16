@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class)

package com.openfit.ui.today

import com.openfit.domain.DailyMetrics
import com.openfit.domain.DurationSerializer
import com.openfit.domain.EnergyDay
import com.openfit.domain.FitnessLevel
import com.openfit.domain.InstantSerializer
import com.openfit.domain.IntRangeSerializer
import com.openfit.domain.LoadRange
import com.openfit.domain.LoadTarget
import com.openfit.domain.LocalDateSerializer
import com.openfit.domain.MetricReading
import com.openfit.domain.PulseAge
import com.openfit.domain.Readiness
import com.openfit.domain.StressDay
import com.openfit.health.ExerciseSession
import com.openfit.profile.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

/** What Today computed for a date, without the live parts (profile, permissions, Google Health), for [com.openfit.data.SnapshotStore]. */
@Serializable
data class TodaySnapshot(
    val date: LocalDate,
    val metrics: DailyMetrics,
    val week: List<LocalDate>,
    val exerciseDays: Set<LocalDate>,
    val latestExercise: ExerciseSession? = null,
    val mindfulDays: Set<LocalDate> = emptySet(),
    val readiness: Readiness? = null,
    val cardioRange: LoadRange? = null,
    val zoneTarget: LoadTarget,
    val healthMetrics: List<MetricReading> = emptyList(),
    val sleepScore: Int? = null,
    val energy: EnergyDay? = null,
    val energyLoaded: Boolean = false,
    val stress: StressDay? = null,
    val fitness: FitnessLevel? = null,
    val pulseAge: PulseAge? = null,
    val pulseAgeLoaded: Boolean = false,
) {
    companion object {
        const val KIND = "today"
        /** Bump when this class or a class in it changes shape, or a value in it is computed differently. */
        const val VERSION = 2
    }
}

fun TodayUiState.Ready.toSnapshot() = TodaySnapshot(
    date = date,
    metrics = metrics,
    week = week,
    exerciseDays = exerciseDays,
    latestExercise = latestExercise,
    mindfulDays = mindfulDays,
    readiness = readiness,
    cardioRange = cardioRange,
    zoneTarget = zoneTarget,
    healthMetrics = healthMetrics,
    sleepScore = sleepScore,
    energy = energy,
    energyLoaded = !energyLoading,
    stress = stress,
    fitness = fitness,
    pulseAge = pulseAge,
    pulseAgeLoaded = !pulseAgeLoading,
)

fun TodaySnapshot.toReady(profile: Profile, missingPermissions: Set<String>, googleHealth: GoogleHealthStatus) = TodayUiState.Ready(
    date = date,
    isToday = date == LocalDate.now(),
    metrics = metrics,
    week = week,
    exerciseDays = exerciseDays,
    latestExercise = latestExercise,
    mindfulDays = mindfulDays,
    readiness = readiness,
    cardioRange = cardioRange,
    zoneTarget = zoneTarget,
    profile = profile,
    missingPermissions = missingPermissions,
    googleHealth = googleHealth,
    healthMetrics = healthMetrics,
    sleepScore = sleepScore,
    energy = energy,
    energyLoading = !energyLoaded,
    stress = stress,
    fitness = fitness,
    pulseAge = pulseAge,
    pulseAgeLoading = !pulseAgeLoaded,
)

/** What a snapshot's values depend on in the profile: everything but the name. */
fun Profile.snapshotKey(): String = copy(name = "").toString()
