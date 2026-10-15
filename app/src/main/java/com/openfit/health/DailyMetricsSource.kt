package com.openfit.health

import com.openfit.domain.DailyMetrics
import com.openfit.profile.Profile
import java.time.LocalDate

interface DailyMetricsSource {
    suspend fun load(date: LocalDate, profile: Profile): DailyMetrics
}
