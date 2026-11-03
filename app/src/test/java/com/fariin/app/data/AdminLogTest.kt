package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class AdminLogTest {
    private fun ev(type: String, detail: Map<String, Any?> = emptyMap(), id: String = "e1", at: Double = 1_700_000_000_000.0,
                   by: String = "a", target: String = "t") = AdminLog.Event(id, at, type, by, target, detail)

    private fun line(parts: List<AdminLog.Part>) = parts.joinToString("") { if (it.bold) "[${it.text}]" else it.text }

    @Test fun parsesAPageAndSkipsEventsWithoutId() {
        val p = AdminLog.Page.from(mapOf(
            "events" to listOf(mapOf("id" to "x", "at" to 5L, "type" to "member_added", "by" to "a", "target" to "b"), mapOf("type" to "info")),
            "next" to mapOf("at" to 5L, "id" to "x"),
            "names" to mapOf("b" to "Bob", "c" to 3)))
        assertEquals(1, p.events.size)
        assertEquals(5.0, p.events[0].atMs, 0.0)
        assertEquals(mapOf("b" to "Bob"), p.names)
        assertEquals("x", p.next?.get("id"))
        assertNull(AdminLog.Page.from(mapOf("events" to emptyList<Any>())).next)
    }

    @Test fun kindsGroupTheTypes() {
        assertEquals(AdminLog.Kind.JOIN_REQUESTS, AdminLog.Kind.of("join_denied"))
        assertEquals(AdminLog.Kind.RESTRICTIONS, AdminLog.Kind.of("unrestricted"))
        assertEquals(AdminLog.Kind.ADMINS, AdminLog.Kind.of("roles"))
        assertEquals(AdminLog.Kind.LINKS, AdminLog.Kind.of("links_reset"))
        assertEquals(AdminLog.Kind.INFO, AdminLog.Kind.of("username_set"))
        assertEquals(AdminLog.Kind.FARIIN, AdminLog.Kind.of("fariin_action"))
    }

    @Test fun actionWordsMatchIos() {
        assertEquals("removed and banned [Bob]", line(AdminLog.action(ev("member_removed", mapOf("banned" to true)), "Bob")))
        assertEquals("made [Bob] an admin", line(AdminLog.action(ev("admin_added"), "Bob")))
        assertEquals("changed the group [name, description and photo]",
            line(AdminLog.action(ev("info", mapOf("fields" to listOf("name", "x", "description", "photo"))), "")))
        assertEquals("changed [Slow Mode]", line(AdminLog.action(ev("settings", mapOf("keys" to listOf("slowMode"))), "")))
        assertEquals("changed the group settings", line(AdminLog.action(ev("settings", mapOf("keys" to listOf("a", "b"))), "")))
        assertEquals("pinned [3 messages]", line(AdminLog.action(ev("pinned", mapOf("messages" to listOf("1", "2", "3"))), "")))
        assertEquals("unpinned a message", line(AdminLog.action(ev("unpinned"), "")))
        assertEquals("turned off [the invite link “Friends”]", line(AdminLog.action(ev("link_revoked", mapOf("name" to "Friends")), "")))
        assertEquals("created [the main invite link]", line(AdminLog.action(ev("link_created", mapOf("primary" to true)), "")))
        assertEquals("deleted [2 invite links]", line(AdminLog.action(ev("link_deleted", mapOf("count" to 2L)), "")))
        assertEquals("set the public link to [@fam]", line(AdminLog.action(ev("username_set", mapOf("username" to "fam")), "")))
        assertEquals("verified this group", line(AdminLog.action(ev("fariin_action", mapOf("op" to "groupVerify")), "")))
        assertEquals("changed member roles", line(AdminLog.action(ev("roles", target = ""), "")))
        assertEquals("changed the group", line(AdminLog.action(ev("whatever"), "")))
    }

    @Test fun namesFallBack() {
        assertEquals("You", AdminLog.name("me", "me", emptyMap(), emptyMap()))
        assertEquals("Fariin", AdminLog.name("fariin", "me", emptyMap(), emptyMap()))
        assertEquals("Someone", AdminLog.name("", "me", emptyMap(), emptyMap()))
        assertEquals("Log", AdminLog.name("u", "me", mapOf("u" to "Log"), mapOf("u" to "Group")))
        assertEquals("Group", AdminLog.name("u", "me", emptyMap(), mapOf("u" to "Group")))
        assertEquals("Someone", AdminLog.name("u", "me", emptyMap(), mapOf("u" to "")))
    }

    @Test fun detailRightsRestrictionsAndInfo() {
        val none: (Double) -> String = { "LATER" }
        val full = AdminLog.detail(ev("admin_added"), emptyList(), null, null, { it }, none)
        assertEquals("All admin rights", full.note)
        val some = AdminLog.detail(ev("admin_rights", mapOf("rights" to listOf("banUsers", "odd"))), emptyList(), null, null, { it }, none)
        assertEquals(listOf("Remove members", "odd"), some.bullets)
        val forever = AdminLog.detail(ev("restricted", mapOf("flags" to listOf("sendMedia"), "until" to 1_700_000_000_000.0 + 50.0 * 365 * 86_400_000)),
            emptyList(), null, null, { it }, none)
        assertEquals("No end date. Held back from:", forever.note)
        assertEquals(listOf("Photos, Videos, Files and Links"), forever.bullets)
        val day = AdminLog.detail(ev("restricted", mapOf("until" to 1_700_086_400_000.0)), emptyList(), null, null, { it }, none)
        assertEquals("Until LATER. Held back from:", day.note)
        // The photo and description go on the newest change only.
        val newer = ev("info", mapOf("fields" to listOf("photo", "description")), id = "n")
        val older = ev("info", mapOf("fields" to listOf("photo")), id = "o")
        val list = listOf(newer, older)
        val d = AdminLog.detail(newer, list, "url", "About", { it }, none)
        assertEquals("url", d.photoUrl); assertEquals("Description now", d.quoteLabel); assertEquals("About", d.quote)
        assertTrue(AdminLog.detail(older, list, "url", "About", { it }, none).isEmpty)
        val renamed = AdminLog.detail(ev("info", mapOf("fields" to listOf("name"), "name" to "New")), emptyList(), null, null, { it }, none)
        assertEquals("New name", renamed.quoteLabel)
        assertEquals("Before: Ann", AdminLog.detail(ev("owner_changed", mapOf("from" to "ann")), emptyList(), null, null, { "Ann" }, none).note)
        assertTrue(AdminLog.detail(ev("owner_changed", mapOf("from" to "a")), emptyList(), null, null, { "Ann" }, none).isEmpty)
    }

    @Test fun filterAndDays() {
        val a = ev("member_added", id = "1", by = "x", at = 0.0)
        val b = ev("pinned", id = "2", by = "y", at = 3_600_000.0)
        val c = ev("pinned", id = "3", by = "x", at = 86_400_000.0 * 2)
        assertEquals(listOf(a, c), AdminLog.shown(listOf(a, b, c), null, setOf("x")))
        assertEquals(listOf(b, c), AdminLog.shown(listOf(a, b, c), setOf(AdminLog.Kind.PINS), null))
        val days = AdminLog.days(listOf(c, b, a), ZoneOffset.UTC)
        assertEquals(2, days.size); assertEquals(listOf(b, a), days[1].second)
        assertEquals(listOf("o", "a1", "x"), AdminLog.filterPeople("o", listOf("o", "a1"), listOf(a, ev("x", by = "o"))))
        assertEquals(listOf("1", "2"), AdminLog.merge(listOf(a), listOf(a, b)).map { it.id })
    }

    @Test fun dayTitlesAndTimes() {
        val today = LocalDate.of(2026, 11, 3)
        assertEquals("Today", AdminLog.dayTitle(today, today, Locale.US, "MMMM d", "MMMM d, yyyy"))
        assertEquals("Yesterday", AdminLog.dayTitle(today.minusDays(1), today, Locale.US, "MMMM d", "MMMM d, yyyy"))
        assertEquals("October 27", AdminLog.dayTitle(LocalDate.of(2026, 10, 27), today, Locale.US, "MMMM d", "MMMM d, yyyy"))
        assertEquals("October 27, 2025", AdminLog.dayTitle(LocalDate.of(2025, 10, 27), today, Locale.US, "MMMM d", "MMMM d, yyyy"))
        assertEquals("15:05", AdminLog.timeText(15 * 3_600_000.0 + 5 * 60_000.0, true, Locale.US, ZoneOffset.UTC))
        assertEquals("3:05 PM", AdminLog.timeText(15 * 3_600_000.0 + 5 * 60_000.0, false, Locale.US, ZoneOffset.UTC).replace(' ', ' '))
        assertEquals(mapOf("cid" to "g", "limit" to 50), AdminLog.request("g", null))
    }
}
