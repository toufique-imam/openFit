package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyBankTest {

    private val start = LocalDate.of(2026, 9, 1)

    /** A day: asleep until 7:30 (75 slots), then awake at [awakeBpm], with an optional workout hour at [workoutBpm]. */
    private fun day(awakeBpm: Int, workoutBpm: Int? = null): List<Slot> = List(SLOTS_PER_DAY) { i ->
        when {
            i < 75 -> Slot(55, asleep = true)
            workoutBpm != null && i in 170 until 180 -> Slot(workoutBpm, exercise = true)
            i >= 230 -> Slot(60, asleep = true)
            else -> Slot(awakeBpm + i % 10)
        }
    }

    @Test
    fun `stress ranks a slot among the baseline`() {
        val baseline = (60..99).toList().toIntArray()
        assertEquals(0, slotStress(50, baseline))
        assertEquals(51, slotStress(80, baseline))
        assertEquals(100, slotStress(120, baseline))
        assertNull(slotStress(70, IntArray(0)))
    }

    @Test
    fun `a night charges about 40 points`() {
        val days = listOf(start to List(SLOTS_PER_DAY) { i -> if (i < 75) Slot(55, asleep = true) else Slot(null) })
        val result = energyBank(days, showFrom = start)
        // From 50, 75 asleep slots charge about 32: the charge slows over the last 40 points below 100.
        val morning = result.single().levels[74]!!
        assertTrue("morning $morning", morning in 78.0..86.0)
        assertTrue(result.single().charged in 28.0..36.0)
    }

    @Test
    fun `the bank carries across days and warm-up days are not shown`() {
        val days = (0L until 10L).map { start.plusDays(it) to day(70) }
        val result = energyBank(days, showFrom = start.plusDays(7))
        assertEquals(listOf(start.plusDays(7), start.plusDays(8), start.plusDays(9)), result.map { it.date })
        result.forEach { assertTrue(it.levels.filterNotNull().all { level -> level in 1.0..100.0 }) }
    }

    @Test
    fun `a hard workout is the biggest drain`() {
        val days = (0L until 9L).map { start.plusDays(it) to day(70, workoutBpm = if (it == 8L) 160 else null) }
        val last = energyBank(days, showFrom = start.plusDays(8)).single()
        val top = last.drains.first()
        assertTrue("top drain $top", top is EnergyDrain.Workout && top.slot == 170)
        assertTrue(last.drained > 0)
    }

    @Test
    fun `shown stress is three quarters of the rank, as fitted to Pulse`() {
        assertEquals(75, displayedStress(100))
        assertEquals(StressBand.Medium, stressBand(displayedStress(51)))
        val day = stressDays(listOf(start to day(70), start.plusDays(1) to day(70)), showFrom = start.plusDays(1)).single()
        assertTrue(day.slots.filterNotNull().all { it in 0..75 })
    }

    @Test
    fun `bands follow the thirds`() {
        assertEquals(EnergyBand.Plenty, energyBand(67.0))
        assertEquals(EnergyBand.PaceYourself, energyBand(34.0))
        assertEquals(EnergyBand.RunningLow, energyBand(33.9))
    }
}
