package com.openfit.health

import android.content.Context
import com.openfit.data.DayLoadEntity
import com.openfit.data.OpenFitDatabase
import com.openfit.domain.ZoneMinutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Per-day effort minutes, training load and minutes in each heart-rate zone. */
data class DayLoad(val zoneMinutes: Int, val cardioLoad: Double, val minutes: ZoneMinutes = ZoneMinutes(0, 0, 0)) {
    companion object { val NONE = DayLoad(zoneMinutes = -1, cardioLoad = -1.0) }
}

class DayLoadCache(context: Context, database: OpenFitDatabase = OpenFitDatabase.get(context)) {
    private val dao = database.calculations()
    private val legacy = context.getSharedPreferences("day_load", Context.MODE_PRIVATE)

    suspend fun get(profileKey: String, date: LocalDate): DayLoad? = dao.load(key(profileKey, date))?.let {
        DayLoad(it.zoneMinutes, it.cardioLoad, ZoneMinutes(it.moderate, it.vigorous, it.peak))
    }

    fun generation(): Int = GENERATION.get()

    suspend fun putAll(profileKey: String, loads: Map<LocalDate, DayLoad>, generation: Int): Unit = LOCK.withLock {
        if (generation != GENERATION.get() || loads.isEmpty()) return@withLock
        dao.putLoads(loads.map { (date, load) ->
            DayLoadEntity(key(profileKey, date), date.toString(), load.zoneMinutes, load.cardioLoad,
                load.minutes.fatBurn, load.minutes.cardio, load.minutes.peak)
        })
        // These derived values can be rebuilt entirely from the local copy. Release the old preferences file.
        legacy.edit().clear().apply()
    }

    suspend fun forget(dates: Collection<LocalDate>): Unit = LOCK.withLock {
        if (dates.isEmpty()) return@withLock
        GENERATION.incrementAndGet()
        dates.map(LocalDate::toString).chunked(500).forEach { dao.forgetLoads(it) }
    }

    suspend fun forgetFrom(date: LocalDate): Unit = LOCK.withLock {
        GENERATION.incrementAndGet()
        dao.forgetLoadsFrom(date.toString())
    }

    suspend fun clear(): Unit = LOCK.withLock {
        GENERATION.incrementAndGet()
        dao.clearLoads()
        legacy.edit().clear().apply()
    }

    private fun key(profileKey: String, date: LocalDate) = "${ZoneId.systemDefault().id}:$profileKey:$date"

    private companion object {
        val GENERATION = AtomicInteger()
        val LOCK = Mutex()
    }
}
