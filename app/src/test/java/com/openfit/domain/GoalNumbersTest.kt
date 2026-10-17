package com.openfit.domain

import com.openfit.ui.goals.parseGoalNumber
import com.openfit.ui.goals.goalNumberText
import org.junit.Assert.*
import org.junit.Test

class GoalNumbersTest {
    @Test fun `decimal input retains magnitude across separators`() {
        listOf("2.5", "2,5", "٢٫٥").forEach { assertEquals(2.5, parseGoalNumber(it, true)!!, 0.0) }
        listOf("-2.5", "2x5", "2,5.0", "NaN", "").forEach { assertNull(parseGoalNumber(it, true)) }
        assertNull(parseGoalNumber("2,5", false))
    }
    @Test fun `initial values have no grouping separator or float noise`() {
        assertEquals("2.05", goalNumberText(2050f.toDouble() / 1000))
        assertEquals("1200", goalNumberText(1200.0))
    }
}
