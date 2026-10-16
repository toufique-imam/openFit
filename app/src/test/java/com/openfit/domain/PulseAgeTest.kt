package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PulseAgeTest {

    private fun reference(age: Int, sex: Sex) = AgeFactor.entries.associateWith { ageReference(it, age, sex) }

    @Test
    fun `everything at the reference is the user's age`() {
        val result = pulseAge(30, Sex.Male, reference(30, Sex.Male))!!
        assertEquals(30.0, result.age, 1e-9)
        assertTrue(result.effects.all { kotlin.math.abs(it.years) < 1e-9 })
    }

    @Test
    fun `fewer than five factors gives no age`() {
        assertNull(pulseAge(30, Sex.Female, reference(30, Sex.Female).entries.take(4).associate { it.key to it.value }))
    }

    @Test
    fun `short sleep adds years and the present factors stand for all nine`() {
        val inputs = reference(30, Sex.Male).filterKeys { it != AgeFactor.Vo2Max && it != AgeFactor.BodyFat } + (AgeFactor.SleepHours to 5.0)
        val result = pulseAge(30, Sex.Male, inputs)!!
        // ln(1.29) × 5 years × 9/7 factors.
        assertEquals(kotlin.math.ln(1.29) * 5 * 9 / 7, result.difference, 1e-6)
        assertEquals(listOf(AgeFactor.Vo2Max, AgeFactor.BodyFat), result.missing)
    }

    @Test
    fun `fitter than the reference takes years off`() {
        val inputs = reference(40, Sex.Female) + (AgeFactor.Vo2Max to 45.0) + (AgeFactor.Steps to 11_000.0) + (AgeFactor.RestingHeartRate to 52.0)
        assertTrue(pulseAge(40, Sex.Female, inputs)!!.difference < -2)
    }

    @Test
    fun `body fat has no effect from 60 and limited credit below the reference`() {
        assertEquals(0.0, ageLnHr(AgeFactor.BodyFat, 40.0, 65, Sex.Male), 1e-9)
        assertEquals(kotlin.math.ln(1.11) * -0.5, ageLnHr(AgeFactor.BodyFat, 5.0, 30, Sex.Male), 1e-9)
    }

    @Test
    fun `the result is clamped`() {
        val awful = mapOf(
            AgeFactor.SleepHours to 3.0, AgeFactor.SleepConsistency to 20.0, AgeFactor.ModerateMinutes to 0.0,
            AgeFactor.VigorousMinutes to 0.0, AgeFactor.StrengthMinutes to 0.0, AgeFactor.Steps to 500.0,
            AgeFactor.Vo2Max to 12.0, AgeFactor.RestingHeartRate to 110.0, AgeFactor.BodyFat to 60.0,
        )
        assertEquals(45.0, pulseAge(30, Sex.Male, awful)!!.age, 1e-9)
    }

    @Test
    fun `regularity is 100 for identical days and lower for shifted ones`() {
        val start = LocalDate.of(2026, 9, 1)
        fun night(from: Int, to: Int) = BooleanArray(MINUTES_PER_DAY) { it in from until to }
        assertEquals(100.0, sleepRegularity((0L until 7L).associate { start.plusDays(it) to night(0, 450) })!!, 1e-9)
        val shifted = (0L until 7L).associate { start.plusDays(it) to if (it % 2 == 0L) night(0, 450) else night(120, 570) }
        // 240 of 1,440 minutes differ each pair: −100 + 200 × 1200/1440.
        assertEquals(-100 + 200.0 * 1200 / 1440, sleepRegularity(shifted)!!, 1e-9)
        assertNull(sleepRegularity(mapOf(start to night(0, 450))))
    }
}
