package com.vigil.android.presentation.traffic

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class TrafficDutyContractTest {
    private val gson = Gson()
    private fun duty(mode: String, windowEnd: String?): TrafficDuty {
        val summary = mapOf(
            "id" to 1, "label" to "Clock Tower", "latitude" to 23.1, "longitude" to 75.1,
            "duty_date" to "2026-11-19", "mode" to mode, "window_end" to windowEnd,
            "eligible" to true, "active" to true, "presence" to "awaiting",
            "session_started_at" to "2026-11-19T12:30:00Z"
        )
        return gson.fromJson(gson.toJson(summary), TrafficDuty::class.java)
    }

    @Test fun manualDutyCanContinueAfterMidnight() {
        assertEquals(Long.MAX_VALUE, duty("manual", null).deadline())
    }

    @Test fun scheduledDutiesStopAtServerDeadlineInAnyOffset() {
        val end = Instant.parse("2026-11-19T18:30:00Z").toEpochMilli()
        for (mode in listOf("timed", "full_day"))
            assertEquals(end, duty(mode, "2026-11-20T00:00:00+05:30").deadline())
    }

    @Test fun missingScheduledEndIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { duty("timed", null).deadline() }
    }
    @Test fun congestionLinksOnlyOpenGoogleMaps() {
        val url = "https://www.google.com/maps/search/?api=1&query=23.1,75.1"
        val alert = TrafficAlert(1, "congestion", "Post A", "2026-11-19T12:30:00Z", false, "A", "North", null, url)
        assertEquals(url, alert.congestionMap)
        for (invalid in listOf("http://www.google.com/maps/", "https://www.google.com.evil.test/maps/",
            "https://www.google.com:8888/maps/", "https://www.google.com@other.test/maps/",
            "https://other.test@www.google.com/maps/", "https://www.google.com/maps/../url",
            "https://www.google.com/maps-lookalike/"))
            assertNull(alert.copy(google_maps_url = invalid).congestionMap)
        assertNull(alert.copy(kind = "assignment").congestionMap)
    }

}
