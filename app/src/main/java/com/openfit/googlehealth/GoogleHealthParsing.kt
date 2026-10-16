package com.openfit.googlehealth

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The data points of a list or reconcile answer, as the object under [field] ("steps", "sleep", ...). */
fun JSONObject.points(field: String): List<JSONObject> =
    (optJSONArray("dataPoints") ?: optJSONArray("reconciledDataPoints"))
        ?.objects()
        ?.mapNotNull { it.optJSONObject(field) }
        .orEmpty()

/** A dailyRollUp answer: each day's totals under [field]. */
fun parseDailyRollups(response: JSONObject, field: String): Map<LocalDate, JSONObject> =
    response.optJSONArray("rollupDataPoints")
        ?.objects()
        ?.mapNotNull { point ->
            val date = civilDate(point.optJSONObject("civilStartTime")?.optJSONObject("date")) ?: return@mapNotNull null
            point.optJSONObject(field)?.let { date to it }
        }
        ?.toMap()
        .orEmpty()

/** A number, from a JSON number or a string ("52"); null when missing or not finite (the API can send "NaN"). */
fun JSONObject.number(key: String): Double? = when (val value = opt(key)) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}?.takeIf { it.isFinite() }

private fun civilDate(date: JSONObject?): LocalDate? {
    if (date == null || !date.has("year")) return null
    return LocalDate.of(date.getInt("year"), date.getInt("month"), date.getInt("day"))
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
