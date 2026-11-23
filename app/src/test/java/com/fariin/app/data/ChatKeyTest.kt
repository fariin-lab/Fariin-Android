package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatKeyTest {
    @Test fun pasteTurnsEasternArabicDigitsIntoAscii() {
        assertEquals("1234", ChatKey.fromPaste("١٢٣٤"))
        assertEquals("123456", ChatKey.fromPaste("key: 12-34 56 78"))
    }

    @Test fun onlyAsciiDigitsAreValid() {
        assertTrue(ChatKey.isValid("1234"))
        assertFalse(ChatKey.isValid("١٢٣٤"))
        assertFalse(ChatKey.isValid("123"))
    }
}
