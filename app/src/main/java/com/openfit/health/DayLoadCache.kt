package com.openfit.health

import android.content.Context
import com.openfit.domain.ZoneMinutes
import java.time.LocalDate

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

    fun putAll(profileKey: String, loads: Map<LocalDate, DayLoad>) {
        val editor = prefs.edit()
        loads.forEach { (date, load) ->
            val minutes = load.minutes
            editor.putString(key(profileKey, date), "${load.zoneMinutes};${load.cardioLoad};${minutes.fatBurn};${minutes.cardio};${minutes.peak}")
        }
        editor.apply()
    }

    /** Forgets [dates] for every profile: their heart rate was read again, so their loads are worked out again. */
    fun forget(dates: Collection<LocalDate>) {
        if (dates.isEmpty()) return
        val suffixes = dates.map { ":$it" }
        val editor = prefs.edit()
        prefs.all.keys.filter { key -> suffixes.any(key::endsWith) }.forEach(editor::remove)
        editor.apply()
    }

    private fun key(profileKey: String, date: LocalDate) = "$profileKey:$date"
}
