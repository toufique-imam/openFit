package com.openfit.health

import org.junit.Assert.*
import org.junit.Test

class PreferredRecordsTest {
    private fun sample(id: String, vararg minutes: Long) = SavedRecord(
        id, id, start = minutes.first() * 60_000, end = minutes.last() * 60_000 + 60_000,
        times = minutes.map { it * 60_000 + 30_000 }.toLongArray(), values = DoubleArray(minutes.size) { 80.0 },
    )

    @Test fun `partial watch day preserves separate cloud observations`() {
        val merged = preferredRecords("heart_rate", listOf(sample("watch", 540)), listOf(sample("cloud", 1260)))
        assertEquals(listOf(540L, 1260L), merged.flatMap { it.times!!.map { time -> time / 60_000 } })
    }

    @Test fun `raw samples replace matching cloud minutes while gaps survive`() {
        val merged = preferredRecords("heart_rate", listOf(sample("watch", 60, 62)), listOf(sample("cloud", 60, 61, 62)))
        assertEquals(listOf(60L, 62L, 61L), merged.flatMap { it.times!!.map { time -> time / 60_000 } })
        merged.forEach { record -> assertTrue(record.times!!.all { it >= record.start && it < record.end!! }) }
    }

    @Test fun `distinct workouts and naps survive but mirrored sessions do not`() {
        val own = SavedRecord("watch", "watch", start = 100, end = 200)
        val mirror = SavedRecord("mirror", "cloud", start = 100, end = 200)
        val separate = SavedRecord("later", "cloud", start = 200, end = 300)
        assertEquals(listOf(own, separate), preferredRecords("sleep", listOf(own), listOf(mirror, separate)))
        assertEquals(listOf(own, separate), preferredRecords("exercise", listOf(own), listOf(mirror, separate)))
    }

    @Test fun `distinct weight readings survive and matching timestamps prefer watch`() {
        val own = SavedRecord("watch", "watch", start = 100, value = 70.0)
        val duplicate = own.copy(id = "cloud", origin = "cloud")
        val later = duplicate.copy(start = 200, value = 71.0)
        assertEquals(listOf(own, later), preferredRecords("weight", listOf(own), listOf(duplicate, later)))
    }
}
