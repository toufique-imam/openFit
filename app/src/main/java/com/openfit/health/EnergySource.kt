package com.openfit.health

import com.openfit.domain.EnergyDay
import com.openfit.domain.EnergyRates
import com.openfit.domain.energyBank
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

    private companion object {
        /** A 30-day stress baseline before the warm-up week. */
        const val LEAD_DAYS = 30L + EnergyRates.WARM_UP_DAYS
    }
}
