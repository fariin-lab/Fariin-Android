package com.fariin.app.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The official chat's day pill words (iOS OfficialChatView.dayLabel). */
class OfficialDayLabelTest {
    private val zone = ZoneId.of("Africa/Nairobi")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Double =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli().toDouble()
    private val now = at(2026, 11, 3, 15)
    private fun label(t: Double) = ThreadLogic.officialDayLabel(t, now, zone, Locale.US)

    @Test fun todayAndYesterday() {
        assertEquals("Today", label(at(2026, 11, 3, 0, 1)))
        assertEquals("Yesterday", label(at(2026, 11, 2, 23, 59)))
    }

    @Test fun olderIsWeekdayMonthDay() {
        assertEquals("Sun, Nov 1", label(at(2026, 11, 1)))
        assertEquals("Tue, Oct 20", label(at(2026, 10, 20)))
    }

    @Test fun neverAYear() {
        assertEquals("Sun, Mar 3", label(at(2019, 3, 3)))
    }

    @Test fun usesTheGivenZone() {
        // 22:30 UTC on Nov 2 is already Nov 3 in Nairobi (UTC+3).
        val t = LocalDateTime.of(2026, 11, 2, 22, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli().toDouble()
        assertEquals("Today", label(t))
    }
}
