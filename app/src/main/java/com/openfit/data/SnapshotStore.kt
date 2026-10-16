package com.openfit.data

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/** A saved screen; [current] is false for a row of an older format that still decodes (shown, but reloaded). */
class Snapshot<T>(val value: T, val savedAt: Instant, val profileKey: String, val current: Boolean = true)

/**
 * Snapshots of computed screens per date: kept in memory for this session and in Room across launches. A row of an older
 * format is still returned while it decodes, so there is something to show before Health Connect answers.
 */
class SnapshotStore(context: Context) {

    private val dao = OpenFitDatabase.get(context).snapshots()
    private val memory = object : LinkedHashMap<String, Snapshot<*>>(MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot<*>>?): Boolean = size > MEMORY_ENTRIES
    }

    suspend fun <T : Any> get(kind: String, date: LocalDate, version: Int, serializer: KSerializer<T>): Snapshot<T>? =
        get(kind, date.toString(), version, serializer)

    /** A snapshot saved under [name] (a date, or a period and its anchor for a chart screen). */
    suspend fun <T : Any> get(kind: String, name: String, version: Int, serializer: KSerializer<T>): Snapshot<T>? {
        val key = "$kind:$name"
        synchronized(memory) {
            @Suppress("UNCHECKED_CAST")
            (memory[key] as Snapshot<T>?)?.let { return it }
        }
        val row = runCatching { dao.get(key) }.getOrNull() ?: return null
        val value = runCatching { JSON.decodeFromString(serializer, row.json) }.getOrNull() ?: return null
        return Snapshot(value, Instant.ofEpochMilli(row.savedAt), row.profileKey, current = row.version == version).also { synchronized(memory) { memory[key] = it } }
    }

    suspend fun <T : Any> put(kind: String, date: LocalDate, version: Int, serializer: KSerializer<T>, value: T, profileKey: String) =
        put(kind, date.toString(), date, version, serializer, value, profileKey)

    /** Saves [value] under [name]; [date] is the day it belongs to, for [prune]. */
    suspend fun <T : Any> put(kind: String, name: String, date: LocalDate, version: Int, serializer: KSerializer<T>, value: T, profileKey: String) {
        val key = "$kind:$name"
        val snapshot = Snapshot(value, Instant.now(), profileKey)
        synchronized(memory) { memory[key] = snapshot }
        runCatching {
            dao.put(SnapshotEntity(key, kind, date.toString(), version, profileKey, snapshot.savedAt.toEpochMilli(), JSON.encodeToString(serializer, value)))
        }
    }

    /** Drops snapshots of dates before [before]. */
    suspend fun prune(before: LocalDate) {
        runCatching { dao.deleteBefore(before.toString()) }
    }

    private companion object {
        const val MEMORY_ENTRIES = 32
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
