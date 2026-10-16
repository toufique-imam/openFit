package com.openfit.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FitnessTest {

    @Test
    fun `heart rate estimate matches Pulse's figure`() {
        // Pulse: 47.9 mL/kg/min for this user (age 30 there, resting about 60.7).
        assertEquals(47.9, estimateVo2Max(30, 60.7), 0.05)
    }

    @Test
    fun `percentiles read between FRIEND columns`() {
        // Pulse's worked examples: a man of 35 at 45.0 sits at 59.6; a woman of 52 at 30 at 83.2.
        assertEquals(59.6, fitnessPercentile(45.0, 35, Sex.Male), 0.1)
        assertEquals(83.2, fitnessPercentile(30.0, 52, Sex.Female), 0.1)
        assertEquals(FitnessBand.Superior, FitnessLevel(30.0, false, 83.2).band)
        assertEquals(5.0, fitnessPercentile(10.0, 40, Sex.Female), 0.0)
        assertEquals(95.0, fitnessPercentile(90.0, 40, Sex.Male), 0.0)
    }

    @Test
    fun `a measured value wins over the estimate`() {
        assertEquals(false, fitnessLevel(30, Sex.Male, measured = 50.0, averageRestingBpm = 60.0)!!.estimated)
        assertEquals(true, fitnessLevel(30, Sex.Male, measured = null, averageRestingBpm = 60.0)!!.estimated)
    }
}
