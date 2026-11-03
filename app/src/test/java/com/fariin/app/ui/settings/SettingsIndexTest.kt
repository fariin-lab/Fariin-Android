package com.fariin.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {
    @Test fun emptyQueryListsEveryPage() {
        assertEquals(SettingsIndex.entries, SettingsIndex.matching("   "))
        assertEquals(17, SettingsIndex.entries.size)
    }

    @Test fun matchesTitleOrKeywords() {
        assertEquals(listOf("Two-step verification"), SettingsIndex.matching("2fa").map { it.title })
        assertTrue(SettingsIndex.matching(" DARK ").any { it.title == "Appearance" })
        assertEquals(listOf("App Lock"), SettingsIndex.matching("passcode").map { it.title })
        assertTrue(SettingsIndex.matching("zzz").isEmpty())
    }

    @Test fun subPagesCarryTheirParent() {
        val e = SettingsIndex.entries.first { it.title == "Blocked Users" }
        assertEquals("Privacy & Security", e.parent)
        assertEquals(null, e.icon)
    }
}
