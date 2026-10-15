package com.openfit.health

import com.openfit.domain.DailyHistory
import com.openfit.domain.DailyMetrics
import com.openfit.profile.Profile
import java.time.LocalDate

interface DailyMetricsSource {
    suspend fun load(date: LocalDate, profile: Profile): DailyMetrics

    suspend fun exerciseDays(from: LocalDate, until: LocalDate): Set<LocalDate>

    suspend fun history(from: LocalDate, until: LocalDate, profile: Profile): DailyHistory
}
