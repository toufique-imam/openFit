package com.openfit.googlehealth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GoogleHealthParsingTest {

    @Test
    fun readsDailyStepsRollup() {
        val response = JSONObject(
            """{"rollupDataPoints":[{"civilStartTime":{"date":{"year":2026,"month":2,"day":26},"time":{}},
               "civilEndTime":{"date":{"year":2026,"month":2,"day":26},"time":{"hours":23,"minutes":59,"seconds":59}},
               "steps":{"countSum":"3822"}}]}""",
        )

        val days = parseDailyRollups(response, "steps")
        assertEquals(3822.0, days[LocalDate.of(2026, 2, 26)]?.number("countSum"))
        assertNull(parseDailyRollups(response, "distance")[LocalDate.of(2026, 2, 26)])
    }

    @Test
    fun readsPointsFromListAndReconcileAnswers() {
        val listed = JSONObject("""{"dataPoints":[{"weight":{"weightGrams":80500}}]}""")
        val reconciled = JSONObject("""{"reconciledDataPoints":[{"dataPointName":"a","sleep":{"type":"STAGES"}}]}""")

        assertEquals(80500.0, listed.points("weight").single().number("weightGrams"))
        assertEquals("STAGES", reconciled.points("sleep").single().optString("type"))
    }

    @Test
    fun numbersComeFromStringsAndSkipNaN() {
        val values = JSONObject("""{"a":"52","b":61.5,"c":"NaN","d":"x"}""")

        assertEquals(52.0, values.number("a"))
        assertEquals(61.5, values.number("b"))
        assertNull(values.number("c"))
        assertNull(values.number("d"))
        assertNull(values.number("missing"))
    }
}
