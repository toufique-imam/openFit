package com.openfit.googlehealth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class GoogleHealthException(val code: Int, val body: String) : Exception("Google Health API $code: $body") {
    val isAuthProblem: Boolean get() = code == 401 || "ACCOUNT_NOT_LINKED" in body
}

class GoogleHealthApi(private val accessToken: String) {

    suspend fun dailyRollUp(dataType: String, date: LocalDate): JSONObject = dailyRollUp(dataType, date, date.plusDays(1))

    suspend fun dailyRollUp(dataType: String, from: LocalDate, untilExclusive: LocalDate): JSONObject =
        request(
            "POST",
            "$BASE_URL/users/me/dataTypes/$dataType/dataPoints:dailyRollUp",
            JSONObject()
                .put("range", JSONObject().put("start", civil(from)).put("end", civil(untilExclusive)))
                .put("windowSizeDays", 1)
                .put("dataSourceFamily", WEARABLES),
        )

    suspend fun rollUp(dataType: String, start: Instant, end: Instant, window: Duration): JSONObject =
        request(
            "POST",
            "$BASE_URL/users/me/dataTypes/$dataType/dataPoints:rollUp",
            JSONObject()
                .put("range", JSONObject().put("startTime", start.toString()).put("endTime", end.toString()))
                .put("windowSize", "${window.seconds}s")
                .put("dataSourceFamily", WEARABLES),
        )

    suspend fun list(dataType: String, filter: String, pageToken: String? = null): JSONObject =
        request("GET", "$BASE_URL/users/me/dataTypes/$dataType/dataPoints?${query(filter, pageToken)}")

    suspend fun reconcile(dataType: String, filter: String, pageToken: String? = null): JSONObject =
        request("GET", "$BASE_URL/users/me/dataTypes/$dataType/dataPoints:reconcile?${query(filter, pageToken)}")

    private fun query(filter: String, pageToken: String?) = buildString {
        append("filter=${URLEncoder.encode(filter, "UTF-8")}&dataSourceFamily=${URLEncoder.encode(WEARABLES, "UTF-8")}")
        if (!pageToken.isNullOrEmpty()) append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
    }

    private fun civil(date: LocalDate) = JSONObject()
        .put("date", JSONObject().put("year", date.year).put("month", date.monthValue).put("day", date.dayOfMonth))
        .put("time", JSONObject())

    private suspend fun request(method: String, url: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw GoogleHealthException(code, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://health.googleapis.com/v4"
        const val WEARABLES = "users/me/dataSourceFamilies/google-wearables"
    }
}
