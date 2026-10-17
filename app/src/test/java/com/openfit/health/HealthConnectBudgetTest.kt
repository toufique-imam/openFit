package com.openfit.health

import com.openfit.test.InMemoryPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HealthConnectBudgetTest {
    private val prefs = InMemoryPreferences()
    private var now = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
    private val budget = HealthConnectBudget(prefs, clock = { now })

    @Test fun `foreground refuses before either bucket is exhausted and refills continuously`() = runBlocking {
        repeat(1_000) { budget.call("steps") {} }
        var called = false
        try { budget.call("steps") { called = true }; fail("Expected guard") } catch (_: QuotaGuardException) { }
        assertFalse(called)
        now += 899
        assertFalse(budget.hasRoom(background = false))
        now += 1
        assertTrue(budget.hasRoom(background = false))
        budget.call("steps") {}
        assertFalse(budget.hasRoom(background = false))
        now += 86_400_000
        assertEquals(1_000, budget.usage().available15Min)
        assertEquals(8_000, budget.usage().available24H)
    }

    @Test fun `background leaves a quarter for foreground`() = runBlocking {
        repeat(750) { budget.call("steps") {} }
        assertFalse(budget.hasRoom(background = true))
        assertTrue(budget.hasRoom(background = false))
        try {
            kotlinx.coroutines.withContext(HcPriority.Background) { budget.call("steps") { fail("Called through reserve") } }
            fail("Expected background guard")
        } catch (_: QuotaGuardException) { }
        budget.call("steps") {}
        assertEquals(249, budget.usage().available15Min)
    }

    @Test fun `provider quota error empties both buckets and waitFor includes slower refill`() = runBlocking {
        try { budget.call("steps") { throw IllegalStateException("Read quota exceeded") } } catch (_: IllegalStateException) { }
        assertEquals(0, budget.usage().available15Min)
        assertEquals(0, budget.usage().available24H)
        assertEquals(10_800, budget.waitFor(1, background = false).toMillis())
        now += 10_799
        assertFalse(budget.hasRoom(background = false))
        now += 1
        assertTrue(budget.hasRoom(background = false))
        assertEquals(0, budget.waitFor(1, background = false).toMillis())
        assertTrue(budget.waitFor(1, background = true).toMillis() > 0)
    }

    @Test fun `unrelated failure keeps budget and restart preserves levels`() = runBlocking {
        try { budget.call("steps") { error("provider unavailable") } } catch (_: IllegalStateException) { }
        val restarted = HealthConnectBudget(prefs, clock = { now })
        assertEquals(999, restarted.usage().available15Min)
        now -= 60_000
        assertEquals(999, restarted.usage().available15Min)
    }

    @Test fun `old counters are pruned without resetting tokens`() = runBlocking {
        repeat(30) {
            budget.savedRead()
            budget.call("steps") {}
            now += 86_400_000
        }
        val usage = budget.usage()
        assertEquals(0, usage.savedReadsToday)
        assertTrue(prefs.all.keys.count { it.startsWith("calls:") || it.startsWith("saved:") } <= 14)
        assertTrue(prefs.contains("level:24 h"))
        assertTrue(prefs.contains("last_call"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `waitFor rejects a batch larger than background capacity`() { budget.waitFor(751, background = true) }
}
