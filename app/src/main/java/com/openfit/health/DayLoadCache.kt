package com.openfit.health

import android.content.Context
import java.time.LocalDate

data class DayLoad(val zoneMinutes: Int, val cardioLoad: Double) {
    companion object {
        val NONE = DayLoad(zoneMinutes = -1, cardioLoad = -1.0)
    }
}

class DayLoadCache(context: Context) {

    private val prefs = context.getSharedPreferences("day_load", Context.MODE_PRIVATE)

    fun get(profileKey: String, date: LocalDate): DayLoad? =
        prefs.getString(key(profileKey, date), null)?.split(';')?.let { (zone, load) -> DayLoad(zone.toInt(), load.toDouble()) }

    fun putAll(profileKey: String, loads: Map<LocalDate, DayLoad>) {
        val editor = prefs.edit()
        loads.forEach { (date, load) -> editor.putString(key(profileKey, date), "${load.zoneMinutes};${load.cardioLoad}") }
        editor.apply()
    }

    private fun key(profileKey: String, date: LocalDate) = "$profileKey:$date"
}
