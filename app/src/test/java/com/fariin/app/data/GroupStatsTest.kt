package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class GroupStatsTest {
    private val today = LocalDate.of(2026, 11, 3)

    @Test fun missingKeysAreZeros() {
        val s = GroupStats.parse(emptyMap<String, Any>(), ZoneOffset.UTC, Locale.US, today)
        assertEquals(30, s.days.size)
        assertEquals(today, s.days.last())
        assertEquals(List(30) { 0 }, s.growth)
        assertEquals(0, s.members.value); assertNull(s.members.prev)
        assertEquals(24, s.hours.size); assertEquals(7, s.weekdays.size)
        assertTrue(s.topPosters.isEmpty())
        assertNull(s.sinceMs)
    }

    @Test fun parsesTheReply() {
        val hours = MutableList(168) { 0L }
        hours[1 * 24 + 9] = 5   // Monday 09 UTC
        hours[0 * 24 + 23] = 2  // Sunday 23 UTC
        val s = GroupStats.parse(mapOf(
            "now" to 1_762_128_000_000L, "since" to 1_760_000_000_000L,
            "days" to listOf("2026-11-01", "2026-11-02", "2026-11-03"),
            "overview" to mapOf("members" to mapOf("value" to 250, "prev" to 240)),
            "growth" to listOf(1, 2, 3, 4), "joined" to listOf(7),
            "sources" to mapOf("link" to listOf(1, 1, 1)),
            "hourOfWeekUTC" to hours,
            "languages" to listOf(mapOf("code" to "EN", "count" to 3), mapOf("code" to "so", "count" to 0), mapOf("code" to "so", "count" to 1)),
            "topPosters" to listOf(mapOf("uid" to "u", "name" to "Ann", "messages" to 1, "avgChars" to 1234, "photoUrl" to "")),
            "topAdmins" to listOf(mapOf("deleted" to 2, "banned" to 1)),
            "topInviters" to listOf(mapOf("uid" to "v", "invited" to 4)),
        ), ZoneOffset.UTC, Locale.US, today)
        assertEquals(3, s.days.size)
        assertEquals(listOf(2, 3, 4), s.growth)
        assertEquals(listOf(0, 0, 7), s.joined)
        assertEquals(GroupStats.Metric(250, 240), s.members)
        assertEquals(5, s.hours[9].value); assertEquals(2, s.hours[23].value)
        assertEquals("Mon", s.weekdays[0].label); assertEquals(5, s.weekdays[0].value)
        assertEquals("Sun", s.weekdays[6].label); assertEquals(2, s.weekdays[6].value)
        assertEquals(listOf("en", "so"), s.languages.map { it.code }); assertEquals(1, s.languages[1].index)
        assertEquals("1 message · avg 1,234 chars", s.topPosters[0].detail); assertNull(s.topPosters[0].photoUrl)
        assertEquals("2 deleted · 1 removed", s.topAdmins[0].detail); assertEquals("row0", s.topAdmins[0].id)
        assertEquals("4 invited", s.topInviters[0].detail)
    }

    @Test fun hoursMoveIntoTheLocalZone() {
        val utc = MutableList(168) { 0 }; utc[0] = 1; utc[167] = 2
        val plus3 = GroupStats.localHourOfWeek(utc, ZoneId.of("Africa/Mogadishu"))
        assertEquals(1, plus3[3]); assertEquals(2, plus3[2])
        // A half-hour zone rounds down.
        val india = GroupStats.localHourOfWeek(utc, ZoneId.of("Asia/Kolkata"))
        assertEquals(1, india[5])
        val minus5 = GroupStats.localHourOfWeek(utc, ZoneOffset.ofHours(-5))
        assertEquals(1, minus5[163])
        assertEquals(List(168) { 0 }, GroupStats.localHourOfWeek(listOf(1, 2), ZoneOffset.UTC))
    }

    @Test fun changeLinesAndBusiest() {
        assertEquals("+12 (8%)" to 1, GroupStats.change(162.0, 150.0))
        assertEquals("−3 (2%)" to -1, GroupStats.change(147.0, 150.0))
        assertEquals("+0 (0%)" to 0, GroupStats.change(5.0, 5.0))
        assertNull(GroupStats.change(5.0, 0.0)); assertNull(GroupStats.change(5.0, null))
        assertEquals("Your local time", GroupStats.busiestHour(GroupStats.hourBins(List(168) { 0 })))
        val week = MutableList(168) { 0 }; week[24 * 3 + 7] = 4
        assertEquals("Busiest at 07:00", GroupStats.busiestHour(GroupStats.hourBins(week)))
        assertEquals("Busiest on Wed", GroupStats.busiestDay(GroupStats.weekdayBins(week, Locale.US)))
    }

    @Test fun captions() {
        val days = listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2))
        val one = listOf(GroupStats.Series("Members", listOf(10, 12)))
        val two = listOf(GroupStats.Series("Joined", listOf(1, 2)), GroupStats.Series("Left", listOf(0, 1000)))
        val d: (LocalDate) -> String = { "Nov ${it.dayOfMonth}" }
        assertEquals("Now 12", GroupStats.caption(one, days, null, true, d, Locale.US))
        assertEquals("Last 30 days: 22", GroupStats.caption(one, days, null, false, d, Locale.US))
        assertEquals("Last 30 days: Joined 3 · Left 1,000", GroupStats.caption(two, days, null, false, d, Locale.US))
        assertEquals("Nov 1: Joined 1 · Left 0", GroupStats.caption(two, days, 0, false, d, Locale.US))
        assertEquals("Nov 2: 12", GroupStats.caption(one, days, 1, true, d, Locale.US))
    }

    @Test fun rowGateAndWords() {
        val big = mapOf("createdBy" to "o", "admins" to listOf("a"), "users" to List(200) { "u$it" })
        assertTrue(GroupStats.showRow(big, "o", false))
        assertTrue(GroupStats.showRow(big, "a", false))
        assertFalse(GroupStats.showRow(big, "m", true))
        val small = big + ("users" to listOf("o", "a"))
        assertFalse(GroupStats.showRow(small, "o", false))
        assertTrue(GroupStats.showRow(small, "o", true))
        assertFalse(GroupStats.showRow(big + ("mod" to mapOf("state" to "disabled")), "o", true))
        assertFalse(GroupStats.showRow(big, "o", true, featureOn = false))
        assertEquals("Statistics appear once the group is bigger.", GroupStats.failure("FAILED_PRECONDITION"))
        assertEquals("Only the group's owner and admins can see statistics.", GroupStats.failure("PERMISSION_DENIED"))
        assertEquals("Couldn't load statistics. Check your connection and try again.", GroupStats.failure(null))
        assertEquals("Statistics appear once the channel is bigger.", GroupStats.failure("FAILED_PRECONDITION", channel = true))
        assertEquals(false, GroupStats.canRetry("FAILED_PRECONDITION"))
        assertEquals(true, GroupStats.canRetry("UNAVAILABLE"))
        assertEquals("Other", GroupStats.languageName("other")); assertEquals("Unknown", GroupStats.languageName("und"))
        assertEquals("Somali", GroupStats.languageName("so", Locale.US))
        assertEquals("Oct 27 – Nov 3", GroupStats.period(1_762_128_000_000.0, ZoneOffset.UTC, "MMM d", Locale.US))
        assertEquals(75, GroupStats.percent(3, listOf(GroupStats.Language(0, "en", 3), GroupStats.Language(1, "so", 1))))
    }
}
