package com.openfit.health

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SleepRecordsTest {
    private val start = Instant.parse("2026-10-15T22:00:00Z")
    private fun night(from: Long, until: Long) = SleepSessionRecord(
        start.plusSeconds(from), null, start.plusSeconds(until), null, Metadata.unknownRecordingMethod(),
    )

    @Test fun `mirrored nights count once and a separate nap still counts`() {
        assertEquals(9 * 60L, totalAsleepDuration(listOf(night(0, 8 * 3600), night(0, 8 * 3600), night(12 * 3600, 13 * 3600))).toMinutes())
    }

    @Test fun `partial overlap counts only the union of asleep intervals`() {
        assertEquals(10 * 60L, totalAsleepDuration(listOf(night(0, 8 * 3600), night(6 * 3600, 10 * 3600))).toMinutes())
    }

    @Test fun `awake in bed contributes no sleep`() {
        val end = start.plusSeconds(3600)
        val session = SleepSessionRecord(start, null, end, null, Metadata.unknownRecordingMethod(), stages = listOf(
            SleepSessionRecord.Stage(start, end, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
        ))
        assertEquals(0, totalAsleepDuration(listOf(session)).toMinutes().toInt())
    }
}
