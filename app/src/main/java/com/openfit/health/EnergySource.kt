package com.openfit.health

import com.openfit.domain.EnergyDay
import com.openfit.domain.EnergyRates
import com.openfit.domain.energyBank
import com.openfit.domain.StressDay
import com.openfit.domain.stressDays
import java.time.LocalDate

/** Energy Bank days from Health Connect: reads the 30-day stress baseline and a week of warm-up before [from]. */
class EnergySource(gateway: HealthConnectGateway, cache: SlotCache) {

    private val slots = HealthConnectSlots(gateway, cache)

    /** Days in [from]..[until] with a settled bank; days still calibrating (the first week of data) are left out. */
    suspend fun days(from: LocalDate, until: LocalDate): List<EnergyDay> {
        val all = slots.days(from.minusDays(LEAD_DAYS), until)
        val firstWorn = all.firstOrNull { (_, day) -> day.any { it.bpm != null } }?.first ?: return emptyList()
        val showFrom = maxOf(from, firstWorn.plusDays(EnergyRates.WARM_UP_DAYS.toLong()))
        return energyBank(all.filter { it.first >= firstWorn }, showFrom)
    }

    /** Stress for [from]..[until], with a 30-day baseline before each day; days without a baseline are left out. */
    suspend fun stress(from: LocalDate, until: LocalDate): List<StressDay> {
        val all = slots.days(from.minusDays(30), until)
        val firstWorn = all.firstOrNull { (_, day) -> day.any { it.bpm != null } }?.first ?: return emptyList()
        return stressDays(all, maxOf(from, firstWorn.plusDays(MIN_STRESS_BASELINE_DAYS)))
    }

    private companion object {
        /** Pulse: stress needs 5 days of data. */
        const val MIN_STRESS_BASELINE_DAYS = 5L

        /** A 30-day stress baseline before the warm-up week. */
        const val LEAD_DAYS = 30L + EnergyRates.WARM_UP_DAYS
    }
}
