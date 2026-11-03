package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenCallsTest {
    @Test fun deleteTitleNamesWhatGoes() {
        assertEquals("Delete 1 call?", HiddenCalls.deleteTitle(1, 0))
        assertEquals("Delete 3 calls?", HiddenCalls.deleteTitle(3, 0))
        assertEquals("Delete 1 call link?", HiddenCalls.deleteTitle(0, 1))
        assertEquals("Delete 2 call links?", HiddenCalls.deleteTitle(0, 2))
        assertEquals("Delete 3 items?", HiddenCalls.deleteTitle(2, 1))
    }
}
