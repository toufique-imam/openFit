package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class JournalInsightsTest {

    private val start = LocalDate.of(2026, 9, 1)

    @Test
    fun `t quantile matches the table`() {
        // Exact Student t 0.85 quantiles: df 5 → 1.156, 10 → 1.093, 30 → 1.055.
        assertEquals(1.156, studentT85(5.0), 0.01)
        assertEquals(1.093, studentT85(10.0), 0.005)
        assertEquals(1.055, studentT85(30.0), 0.002)
    }

    @Test
    fun `too few days of either answer needs more data`() {
        val answers = (0L until 10L).associate { start.plusDays(it) to (it < 4) }
        val outcome = (0L..10L).associate { start.plusDays(it) to 60.0 }
        val impact = behaviourImpact(answers, outcome)
        assertEquals(4, impact.yesDays)
        assertEquals(6, impact.noDays)
        assertNull(impact.difference)
        assertEquals(ImpactVerdict.NeedsMoreData, impact.verdict)
    }

    @Test
    fun `a clear drop the next morning is negative`() {
        // Even days yes: the next morning averages 49.0; odd days no: 60.9.
        val answers = (0L until 20L).associate { start.plusDays(it) to (it % 2 == 0L) }
        val outcome = (1L..20L).associate { day -> start.plusDays(day) to if ((day - 1) % 2 == 0L) 48.0 + day % 3 else 62.0 - day % 3 }
        val impact = behaviourImpact(answers, outcome)
        assertEquals(10, impact.yesDays)
        assertEquals(10, impact.noDays)
        assertEquals(ImpactVerdict.Negative, impact.verdict)
        assertEquals(-11.9, impact.difference!!, 0.01)
    }

    @Test
    fun `overlapping groups show no clear effect`() {
        val answers = (0L until 20L).associate { start.plusDays(it) to (it % 2 == 0L) }
        val outcome = (1L..20L).associate { day -> start.plusDays(day) to 50.0 + (day * 7 % 11) }
        assertEquals(ImpactVerdict.NoClearEffect, behaviourImpact(answers, outcome).verdict)
    }

    @Test
    fun `days without an outcome are left out`() {
        val answers = (0L until 12L).associate { start.plusDays(it) to (it % 2 == 0L) }
        val outcome = mapOf(start.plusDays(1) to 50.0)
        val impact = behaviourImpact(answers, outcome)
        assertEquals(1, impact.yesDays)
        assertEquals(0, impact.noDays)
    }
}
