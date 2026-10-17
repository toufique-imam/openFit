package com.openfit.health

import com.openfit.domain.SLOT_MINUTES
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Repeated local times share a chart slot; the chart always labels local clock time. */
internal fun localSlot(time: Instant, date: LocalDate, zone: ZoneId): Int? =
    time.atZone(zone).takeIf { it.toLocalDate() == date }?.let { (it.hour * 60 + it.minute) / SLOT_MINUTES }
