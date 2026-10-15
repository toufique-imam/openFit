package com.openfit.health

import com.openfit.domain.CardioLoadDay
import com.openfit.domain.DailyHistory
import com.openfit.domain.DailyMetrics
import com.openfit.profile.Profile
import java.time.LocalDate

interface DailyMetricsSource {
    suspend fun load(date: LocalDate, profile: Profile): DailyMetrics

    suspend fun exerciseDays(from: LocalDate, until: LocalDate): Set<LocalDate>

    /** Daily history; sources that compute days from raw data compute at most [backfillDays] uncached days per call. */
    suspend fun history(from: LocalDate, until: LocalDate, profile: Profile, backfillDays: Int = DEFAULT_BACKFILL_DAYS): DailyHistory

    /** Only the per-day loads (zone minutes, cardio load) of [history]; cheaper for repeated backfill rounds. */
    suspend fun loadHistory(from: LocalDate, until: LocalDate, profile: Profile, backfillDays: Int = DEFAULT_BACKFILL_DAYS): DailyHistory =
        history(from, until, profile, backfillDays)

    /** Minute-level Cardio Load and workouts for one day, when the source has raw heart rate. */
    suspend fun cardioLoadDay(date: LocalDate, profile: Profile): CardioLoadDay? = null

    companion object {
        const val DEFAULT_BACKFILL_DAYS = 7
    }
}
