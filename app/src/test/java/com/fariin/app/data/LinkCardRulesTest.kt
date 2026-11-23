package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkCardRulesTest {
    @Test fun keepsCardForLinkInText() {
        assertEquals("https://example.com/a", LinkCardRules.safeUrl("https://example.com/a", "look at example.com/a please"))
        assertEquals("https://www.example.com/x", LinkCardRules.safeUrl("https://www.example.com/x", "see https://example.com"))
    }

    @Test fun dropsOtherSite() {
        assertNull(LinkCardRules.safeUrl("https://evil.com/login", "my bank: https://bank.com"))
    }

    @Test fun dropsFileAndOtherSchemes() {
        assertNull(LinkCardRules.safeUrl("file:///sdcard/x", "file:///sdcard/x"))
        assertNull(LinkCardRules.safeUrl("javascript:alert(1)", "hi example.com"))
        assertNull(LinkCardRules.safeUrl("mailto:a@b.com", "a@b.com"))
    }

    @Test fun dropsWhenTextHasNoLink() {
        assertNull(LinkCardRules.safeUrl("https://example.com", "hello there\nfriend"))
    }
}
