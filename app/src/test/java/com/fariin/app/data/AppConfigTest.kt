package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** getAppConfig values against the registry bounds (iOS ConfigClient.int). */
class AppConfigTest {
    private val k = AppConfig.K.pinnedMessagesPerChat   // 3, 1..10

    @Test fun numbersAreRoundedAndClamped() {
        assertEquals(5, AppConfig.clampInt(5.0, k))
        assertEquals(5, AppConfig.clampInt(4.6, k))
        assertEquals(10, AppConfig.clampInt(99, k))
        assertEquals(1, AppConfig.clampInt(-4L, k))
    }

    @Test fun wrongTypesGiveTheDefault() {
        assertEquals(3, AppConfig.clampInt(null, k))
        assertEquals(3, AppConfig.clampInt(true, k))
        assertEquals(3, AppConfig.clampInt("7", k))
        assertEquals(3, AppConfig.clampInt(Double.NaN, k))
    }

    @Test fun noServerValueKeepsTheShippedNumbers() {
        assertEquals(4096, AppConfig.int(AppConfig.K.messageMaxChars))
        assertEquals(200, AppConfig.int(AppConfig.K.storyCaptionChars))
        assertEquals(100L * 1024 * 1024, AppConfig.bytes(AppConfig.K.videoMessageMB))
    }

    @Test fun cleanKeepsPlainValuesOnly() {
        val out = AppConfig.clean(mapOf("a" to 1, "b" to true, "c" to "x", "d" to listOf(1), 5 to 2))
        assertEquals(mapOf("a" to 1.0, "b" to true, "c" to "x"), out)
    }
}
