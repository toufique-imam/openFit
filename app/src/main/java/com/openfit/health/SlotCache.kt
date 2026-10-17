package com.openfit.health

import android.content.Context
import com.openfit.data.DaySlotsEntity
import com.openfit.data.OpenFitDatabase
import com.openfit.domain.SLOTS_PER_DAY
import com.openfit.domain.Slot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Room holds one compact six-minute series per day; preferences no longer hold the entire history. */
class SlotCache(context: Context, database: OpenFitDatabase = OpenFitDatabase.get(context)) {
    private val dao = database.calculations()
    private val legacy = context.getSharedPreferences("day_slots", Context.MODE_PRIVATE)

    suspend fun get(date: LocalDate): List<Slot>? = dao.slots(key(date))?.slots?.split(',')
        ?.takeIf { it.size == SLOTS_PER_DAY }?.map { token ->
            Slot(token.trimEnd('s', 'x').toIntOrNull(), asleep = token.endsWith('s'), exercise = token.endsWith('x'))
        }

    fun generation(): Int = GENERATION.get()

    suspend fun putAll(days: Map<LocalDate, List<Slot>>, generation: Int): Unit = LOCK.withLock {
        if (days.isEmpty() || generation != GENERATION.get()) return@withLock
        dao.putSlots(days.map { (date, slots) ->
            require(slots.size == SLOTS_PER_DAY)
            DaySlotsEntity(key(date), date.toString(), slots.joinToString(",") {
                "${it.bpm ?: ""}${if (it.asleep) "s" else if (it.exercise) "x" else ""}"
            })
        })
        legacy.edit().clear().apply()
    }

    suspend fun forget(dates: Collection<LocalDate>): Unit = LOCK.withLock {
        if (dates.isEmpty()) return@withLock
        GENERATION.incrementAndGet()
        dates.map(LocalDate::toString).chunked(500).forEach { dao.forgetSlots(it) }
    }

    suspend fun clear(): Unit = LOCK.withLock {
        GENERATION.incrementAndGet()
        dao.clearSlots()
        legacy.edit().clear().apply()
    }

    private fun key(date: LocalDate) = "v3:${ZoneId.systemDefault().id}:$date"
    private companion object {
        val GENERATION = AtomicInteger()
        val LOCK = Mutex()
    }
}
