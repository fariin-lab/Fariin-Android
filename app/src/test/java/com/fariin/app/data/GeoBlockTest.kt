package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Country restrictions: the getAppConfig answer turns the screen on and off, per account. */
class GeoBlockTest {
    @Test fun answerShowsAndClears() {
        GeoBlock.clear()
        GeoBlock.answer("u1", mapOf("geoBlocked" to mapOf("message" to "Not here")))
        assertEquals("u1" to "Not here", GeoBlock.blocked)
        GeoBlock.answer("u1", mapOf("geoBlocked" to emptyMap<String, Any>()))
        assertEquals("u1" to "Fariin isn't available in your region.", GeoBlock.blocked)
        // Another account's allowed answer leaves this screen alone; this account's clears it.
        GeoBlock.answer("u2", mapOf("values" to emptyMap<String, Any>()))
        assertEquals("u1", GeoBlock.blocked?.first)
        GeoBlock.answer("u1", mapOf("geoRefresh" to true))
        assertNull(GeoBlock.blocked)
    }
}
