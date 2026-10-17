package com.openfit.health

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalSlotsTest {
    private val zone = ZoneId.of("America/New_York")

    @Test fun `fall back keeps the last hour and combines repeated clock times`() {
        val date = LocalDate.of(2025, 11, 2)
        assertEquals(235, localSlot(date.atTime(23, 30).atZone(zone).toInstant(), date, zone))
        assertEquals(15, localSlot(Instant.parse("2025-11-02T05:30:00Z"), date, zone))
        assertEquals(15, localSlot(Instant.parse("2025-11-02T06:30:00Z"), date, zone))
        assertNull(localSlot(date.plusDays(1).atStartOfDay(zone).toInstant(), date, zone))
    }

    @Test fun `spring forward does not shift the afternoon into an earlier chart slot`() {
        val date = LocalDate.of(2026, 3, 8)
        assertEquals(155, localSlot(date.atTime(15, 30).atZone(zone).toInstant(), date, zone))
    }
}
