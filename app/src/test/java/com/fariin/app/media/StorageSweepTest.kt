package com.fariin.app.media

import com.fariin.app.ui.settings.SettingsRules
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSweepTest {
    @Test fun choicesMatchIos() {
        assertEquals(listOf(0, 30, 90), SettingsRules.keepDays.map { it.first })
        assertEquals(listOf("Forever", "30 days", "90 days"), SettingsRules.keepDays.map { it.second })
        assertEquals(listOf("standard" to "Standard", "high" to "High"), SettingsRules.sentQuality)
        assertEquals("Standard", SettingsRules.label(SettingsRules.sentQuality, "anything else"))
    }

    @Test fun photoSizesPerQuality() {
        assertEquals(1280, MediaRules.PhotoQuality.STANDARD.maxSide)
        assertEquals(2560, MediaRules.PhotoQuality.HIGH.maxSide)
    }
}
