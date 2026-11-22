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
        // Two-step is only listed when the account has it (hidden like iOS since 02053fca).
        assertTrue(SettingsIndex.matching("2fa").isEmpty())
        SettingsIndex.hasTwoStep = true
        try {
            assertEquals("Two-step verification", SettingsIndex.matching("2fa").first().title)
        } finally {
            SettingsIndex.hasTwoStep = false
        }
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

    @Test fun nightModeOpensItsOwnPage() {
        // iOS: Night Mode is a page of its own, listed with the pages.
        val e = SettingsIndex.entries.first { it.title == "Night Mode" }
        assertEquals(PAGE_NIGHT, e.page)
        assertTrue(e.isPage)
    }

    @Test fun audiencePagesUseTheIosWords() {
        // iOS audienceKw: "my chats" finds the audience pages, "contacts" does not.
        val chats = SettingsIndex.matching("my chats").map { it.title }
        assertTrue("Last seen & online" in chats)
        assertTrue("Calls" in chats)
        assertTrue(SettingsIndex.matching("contacts").none { it.title == "Bio" })
    }

    @Test fun customStoryListsAreFound() {
        // iOS lists every story audience, my own lists included, each opening its page.
        val before = SettingsIndex.storyLists
        SettingsIndex.storyLists = { listOf(com.fariin.app.data.StoryListDoc("l1", "custom", name = "Family"),
            com.fariin.app.data.StoryListDoc("l2", "custom", name = "Everyone")) }
        try {
            val e = SettingsIndex.matching("family").first()
            assertEquals(listOf("Stories"), e.path)
            assertEquals(PAGE_STORY_CUSTOM + "l1", e.page)
            assertTrue(SettingsIndex.matching("custom list").any { it.title == "Family" })
            // Same name as a built-in: listed once, as iOS.
            assertEquals(1, SettingsIndex.entries.count { it.id == "Stories\u203aEveryone" })
        } finally {
            SettingsIndex.storyLists = before
        }
        assertTrue(SettingsIndex.matching("family").isEmpty())
    }

    @Test fun appLockSwitchIsFound() {
        val e = SettingsIndex.matching("biometrics").first()
        assertEquals("Require Screen Lock", e.title)
        assertEquals(listOf("Privacy & Security", "App Lock"), e.path)
        assertEquals(PAGE_APP_LOCK, e.page)
    }

    @Test fun noRowThePageDoesNotHave() {
        assertTrue(SettingsIndex.entries.none { it.title == "Clear Media" })
    }

    @Test fun idsAreUnique() {
        val ids = SettingsIndex.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
