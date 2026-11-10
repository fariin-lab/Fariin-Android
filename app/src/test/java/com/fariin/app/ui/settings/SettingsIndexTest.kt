package com.fariin.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {
    @Test fun emptyQueryListsThePagesOneStepDeep() {
        val shown = SettingsIndex.matching("   ")
        assertTrue(shown.all { it.isPage && it.path.size <= 1 })
        assertTrue(shown.any { it.title == "Account" })
        assertTrue(shown.any { it.title == "Blocked Users" })
        assertTrue(shown.none { it.title == "Read Receipts" })   // a row, not a page
    }

    @Test fun findsRowsInsidePages() {
        // Owner 2026-11-10: rows inside the pages were not found at all.
        val receipts = SettingsIndex.matching("read receipts").first()
        assertEquals("Read Receipts", receipts.title)
        assertEquals(listOf("Privacy & Security", "Messages"), receipts.path)
        assertEquals(PAGE_MESSAGES, receipts.page)
        assertEquals("Wallpaper Color", SettingsIndex.matching("wallpaper colour").first().title)
        assertTrue(SettingsIndex.matching("in-app preview").any { it.title == "In-App Preview" })
    }

    @Test fun matchesTitleOrKeywords() {
        assertEquals("Two-step verification", SettingsIndex.matching("2fa").first().title)
        assertTrue(SettingsIndex.matching(" DARK ").any { it.title == "Appearance" })
        assertEquals("App Lock", SettingsIndex.matching("passcode").first().title)
        assertTrue(SettingsIndex.matching("zzz").isEmpty())
    }

    @Test fun titleBeatsKeywords() {
        // "Bio" is a title; other rows only mention it in their words.
        assertEquals("Bio", SettingsIndex.matching("bio").first().title)
    }

    @Test fun rowsCarryTheirPath() {
        val e = SettingsIndex.entries.first { it.title == "Blocked Users" }
        assertEquals("Privacy & Security", e.crumb)
        assertEquals(null, e.icon)
        assertEquals(null, SettingsIndex.entries.first { it.title == "Account" }.crumb)
    }

    @Test fun idsAreUnique() {
        val ids = SettingsIndex.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
