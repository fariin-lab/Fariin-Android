package com.fariin.app.data

import com.fariin.app.crypto.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Crafted message docs must never crash a reader (audit 2026-11-23). */
class CrashDocsTest {
    @Test fun thousandDeepJsonIsRefusedNotCrashed() {
        val deep = "[".repeat(1000) + "]".repeat(1000)
        assertNull(MiniJson.parse(deep))
        val deepObj = "{\"a\":".repeat(1000) + "1" + "}".repeat(1000)
        assertNull(MiniJson.parse(deepObj))
    }

    @Test fun shallowJsonStillReads() {
        val v = MiniJson.parse("{\"n\":\"x\",\"w\":{\"u\":\"a.b\"}}") as Map<*, *>
        assertEquals("x", v["n"])
        assertEquals(mapOf("u" to "a.b"), v["w"])
    }
}
