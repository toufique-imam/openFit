package com.openfit.health

import android.content.Context
import com.openfit.domain.ZoneMinutes
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** What a past day earned, computed once from raw heart rate: AZM points, cardio load and minutes per zone. */
data class DayLoad(val zoneMinutes: Int, val cardioLoad: Double, val minutes: ZoneMinutes = ZoneMinutes(0, 0, 0)) {
    companion object {
        val NONE = DayLoad(zoneMinutes = -1, cardioLoad = -1.0)
    }
}

class DayLoadCache(context: Context) {

    private val prefs = context.getSharedPreferences("day_load", Context.MODE_PRIVATE)

    fun get(profileKey: String, date: LocalDate): DayLoad? =
        prefs.getString(key(profileKey, date), null)?.split(';')?.let { parts ->
            DayLoad(
                zoneMinutes = parts[0].toInt(),
                cardioLoad = parts[1].toDouble(),
                minutes = if (parts.size >= 5) ZoneMinutes(parts[2].toInt(), parts[3].toInt(), parts[4].toInt()) else ZoneMinutes(0, 0, 0),
            )
        }

    /** Taken before working days out and passed to [putAll]: loads worked out before a [forget] or [clear] are not saved. */
    fun generation(): Int = GENERATION.get()

    fun putAll(profileKey: String, loads: Map<LocalDate, DayLoad>, generation: Int) = synchronized(LOCK) {
        // Worked out from the copy as it was before a sync changed it: saving it would keep the old figures for good.
        if (generation != GENERATION.get()) return
        val editor = prefs.edit()
        loads.forEach { (date, load) ->
            val minutes = load.minutes
            editor.putString(key(profileKey, date), "${load.zoneMinutes};${load.cardioLoad};${minutes.fatBurn};${minutes.cardio};${minutes.peak}")
        }
        editor.apply()
    }

    /** Forgets [dates] for every profile: their heart rate was read again, so their loads are worked out again. */
    fun forget(dates: Collection<LocalDate>) = synchronized(LOCK) {
        if (dates.isEmpty()) return
        GENERATION.incrementAndGet()
        val suffixes = dates.map { ":$it" }
        val editor = prefs.edit()
        prefs.all.keys.filter { key -> suffixes.any(key::endsWith) }.forEach(editor::remove)
        editor.apply()
    }

    /** Forgets every day: the import copied them, maybe after a page had worked them out from a partial copy. */
    fun clear() = synchronized(LOCK) {
        GENERATION.incrementAndGet()
        prefs.edit().clear().apply()
    }

    private fun key(profileKey: String, date: LocalDate) = "$profileKey:$date"

    private companion object {
        /** Shared by every instance: they all write the same preferences. */
        val GENERATION = AtomicInteger()
        val LOCK = Any()
    }
}
