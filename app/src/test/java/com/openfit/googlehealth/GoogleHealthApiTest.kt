package com.openfit.googlehealth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class GoogleHealthApiTest {
    @Test fun `reconciliation sends source family filter and paging without using raw list`() = runBlocking {
        val requested = mutableListOf<URL>()
        var disconnected = false
        val api = GoogleHealthApi("fixture-token") { url ->
            requested += url
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() { disconnected = true }
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = """{"dataPoints":[{"exercise":{"exerciseType":"RUNNING"}}],"nextPageToken":"next"}""".byteInputStream()
            }
        }
        val response = api.reconcile("exercise", "exercise.interval.civil_start_time >= \"2026-10-01T00:00:00\"", "page +/token", GoogleHealthApi.ALL_SOURCES, 25)
        assertEquals("/v4/users/me/dataTypes/exercise/dataPoints:reconcile", requested.single().path)
        val query = URLDecoder.decode(requested.single().query, "UTF-8")
        assertTrue(query.contains("dataSourceFamily=${GoogleHealthApi.ALL_SOURCES}"))
        assertTrue(query.contains("pageToken=page +/token"))
        assertTrue(query.contains("pageSize=25"))
        assertEquals("RUNNING", response.points("exercise").single().getString("exerciseType"))
        assertTrue(disconnected)
    }
}
