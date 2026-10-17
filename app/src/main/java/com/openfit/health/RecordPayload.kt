package com.openfit.health

import com.openfit.data.RecordDayEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Lossless per-day compression removes repeated field names/origins without losing record metadata. */
internal object RecordPayload {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedRecord.serializer())

    fun row(type: String, day: String, fetchedAt: Long, records: List<SavedRecord>): RecordDayEntity =
        compact(RecordDayEntity(type, day, fetchedAt, json.encodeToString(serializer, records)))

    fun compact(row: RecordDayEntity): RecordDayEntity {
        if (row.payload != null || row.json.length < 1024) return row
        val bytes = row.json.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        val payload = output.toByteArray()
        return if (payload.size < bytes.size) row.copy(json = "", payload = payload) else row
    }

    fun records(row: RecordDayEntity): List<SavedRecord> {
        val text = row.payload?.let { payload ->
            GZIPInputStream(payload.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: row.json
        return json.decodeFromString(serializer, text)
    }
}
