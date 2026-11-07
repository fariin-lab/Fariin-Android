package com.fariin.app.data

import com.fariin.app.security.AppLock
import com.fariin.app.ui.settings.AccountReport
import com.fariin.app.ui.settings.SettingsRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagesTest {
    @Test fun audienceLabelsAndDefaultsAsIos() {
        assertEquals("My Chats", Audience.CONTACTS.label("lastSeen"))
        assertEquals("No One", Audience.NOBODY.label("photo"))
        assertEquals("My Chats", Audience.NOBODY.label("calls"))
        assertEquals("People who know my key", Audience.CONTACTS.label("messages"))
        assertEquals(Audience.CONTACTS, PrivacyRules.audience(emptyMap(), "calls"))
        assertEquals(Audience.EVERYONE, PrivacyRules.audience(emptyMap(), "lastSeen"))
        // An old "nobody" for calls reads as contacts; junk reads as the default.
        assertEquals(Audience.CONTACTS, PrivacyRules.audience(mapOf("calls" to "nobody"), "calls"))
        assertEquals(Audience.EVERYONE, PrivacyRules.audience(mapOf("photo" to "??"), "photo"))
        assertEquals(Audience.NOBODY, PrivacyRules.audience(mapOf("lastSeen" to "nobody"), "lastSeen"))
        assertEquals(2, PrivacyRules.options("calls").size)
        assertEquals(2, PrivacyRules.options("messages").size)
        assertEquals(3, PrivacyRules.options("bio").size)
        // The stored values are the raw strings every other client reads.
        assertEquals(listOf("everyone", "contacts", "nobody"), Audience.entries.map { it.raw })
    }

    @Test fun flagsAreStringsAbsentMeansOn() {
        assertTrue(PrivacyRules.flag(emptyMap(), "readReceipts"))
        assertFalse(PrivacyRules.flag(mapOf("readReceipts" to "false"), "readReceipts"))
        assertTrue(PrivacyRules.flag(mapOf("readReceipts" to "true"), "readReceipts"))
    }

    @Test fun presenceFollowsLastSeenNobody() {
        assertFalse(Presence.shares(mapOf("lastSeen" to "nobody")))
        assertTrue(Presence.shares(mapOf("lastSeen" to "contacts")))
        assertEquals(null, Presence.line(mapOf(), mapOf("lastSeen" to "nobody"), true, true, null, 0.0))
        assertEquals("online", Presence.line(mapOf(), mapOf("lastSeen" to "contacts"), true, true, null, 0.0))
        assertEquals(null, Presence.line(mapOf(), mapOf("lastSeen" to "contacts"), false, true, null, 0.0))
    }

    @Test fun privacyMirrorRoundTrip() {
        val m = mapOf("lastSeen" to "nobody", "readReceipts" to "false", "calls" to "contacts")
        assertEquals(m, PrivacySettings.decode(PrivacySettings.encode(m)))
        assertEquals(emptyMap<String, String>(), PrivacySettings.decode(null))
    }

    @Test fun blockListHidesNowAndPastSpans() {
        val s = BlockList.Snapshot("me", entries = mapOf("bob" to 1000.0), spans = mapOf("amy" to listOf(100.0..200.0)))
        assertTrue(s.hides("bob", 1000.0))
        assertTrue(s.hides("bob", 5000.0))
        assertFalse(s.hides("bob", 999.0))
        assertTrue(s.hides("amy", 150.0))
        assertFalse(s.hides("amy", 250.0))
        assertFalse(s.hides("", 150.0))
        assertTrue(s.contains("bob")); assertFalse(s.contains("amy"))
    }

    @Test fun appLockTiming() {
        assertFalse(AppLock.shouldLock(false, false, -1, 0, 0))
        assertTrue(AppLock.shouldLock(true, false, -1, 0, 0))          // cold start
        assertFalse(AppLock.shouldLock(true, true, -1, 0, 0))          // just turned on here
        assertTrue(AppLock.shouldLock(true, true, 1000, 1000, 0))      // Immediately
        assertFalse(AppLock.shouldLock(true, true, 1000, 30_000, 60))  // inside 1 minute
        assertTrue(AppLock.shouldLock(true, true, 1000, 61_000, 60))
        assertEquals("After 5 minutes", AppLock.delayLabel(300))
    }

    @Test fun storageAndCallsChoices() {
        assertTrue(SettingsRules.lessDataNow("always", true))
        assertTrue(SettingsRules.lessDataNow("cellular", false))
        assertFalse(SettingsRules.lessDataNow("cellular", true))
        assertFalse(SettingsRules.lessDataNow(null, false))
        assertEquals("Cellular Only", SettingsRules.label(SettingsRules.lessData, "cellular"))
        assertEquals("always", SettingsRules.autoDefault("photos"))
        assertEquals("wifi", SettingsRules.autoDefault("videos"))
        assertFalse(SettingsRules.autoDownload("wifi", false))
        assertEquals("1.5 MB", SettingsRules.bytes(1_572_864))
        assertEquals("12 KB", SettingsRules.bytes(12_288))
    }

    @Test fun devicesWords() {
        assertEquals("Online", SettingsRules.deviceLine(true, 0, 10_000_000, ""))
        assertEquals("Online · Mogadishu", SettingsRules.deviceLine(false, 9_900_000, 10_000_000, "Mogadishu"))
        assertEquals("Last active 2 hr. ago", SettingsRules.deviceLine(false, 0, 7_200_000, ""))
        assertEquals(24, SettingsRules.freshHoursLeft(0, 0))
        assertEquals(1, SettingsRules.freshHoursLeft(0, 23 * 3600_000L + 1))
        assertEquals(0, SettingsRules.freshHoursLeft(0, 24 * 3600_000L))
        assertEquals("6 months", SettingsRules.autoSignOutLabel(180))
    }

    @Test fun accountReportHasNoMessagesAndIosSections() {
        val t = AccountReport.build(
            0, "u1", "Alice", "alice", "", "a@x.test", 0, true, listOf("password"), mapOf("lastSeen" to "nobody"),
            listOf(AccountReport.Device("Pixel 8", "Android 16", "1.0 (1)", 5, true)),
            listOf(AccountReport.Blocked("Bobby", "bobby"), AccountReport.Blocked("", "")), 3, fmt = { "T$it" },
        )
        for (h in listOf("ACCOUNT", "SIGN-IN METHODS", "PRIVACY", "DEVICES", "BLOCKED ACCOUNTS", "ACTIVITY")) assertTrue(h, t.contains("\n$h\n"))
        assertTrue(t.contains("Email and password"))
        assertTrue(t.contains("  lastSeen: nobody"))
        assertTrue(t.contains("Pixel 8, Android 16, app 1.0 (1), last active T5 (this phone)"))
        assertTrue(t.contains("Bobby (@bobby)")); assertTrue(t.contains("An account you blocked"))
        assertTrue(t.contains("Chats: 3"))
        assertTrue(t.contains("Messages are not in this file"))
    }

    @Test fun noEmDashesInPageWords() {
        val words = listOf(PrivacyRules.footer("lastSeen"), PrivacyRules.footer("groups"), PrivacyRules.footer("calls")) +
            AccountDeletion.reasons.map { it.second } + SettingsRules.lessData.map { it.second } + AppLock.delays.map { it.second }
        for (w in words) assertFalse(w, w.contains('—'))
        assertEquals(listOf("other_account", "abusive_users", "app_problem", "not_using", "other"), AccountDeletion.reasons.map { it.first })
    }
}
