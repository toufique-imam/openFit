package com.openfit.health

import com.openfit.data.RecordDayEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecordPayloadTest {
    @Test fun `minute records compress without losing IDs offsets values or order`() {
        val records = List(1440) { i -> SavedRecord("watch-step-$i", "example.watch", start = 1_790_000_000_000L + i * 60_000, startOffset = 21600, end = 1_790_000_060_000L + i * 60_000, value = (i % 90).toDouble()) }
        val serializer = ListSerializer(SavedRecord.serializer())
        val original = Json.encodeToString(serializer, records)
        val row = RecordPayload.row("steps", "2026-10-16", 123, records)
        assertEquals("", row.json)
        assertTrue(row.payload!!.size < original.toByteArray().size / 4)
        assertEquals(original, Json.encodeToString(serializer, RecordPayload.records(row)))
        assertEquals(123L, row.fetchedAt)
        assertSame(row, RecordPayload.compact(row))
    }

    @Test fun `legacy JSON and empty days remain readable`() {
        val row = RecordDayEntity("steps", "2026-10-16", 123, "[]")
        assertTrue(RecordPayload.records(row).isEmpty())
        assertSame(row, RecordPayload.compact(row))
        val legacy = row.copy(json = """[{"id":"old","origin":"watch","start":123,"value":42.0}]""")
        assertEquals(42.0, RecordPayload.records(legacy).single().value!!, 0.001)
    }
}
