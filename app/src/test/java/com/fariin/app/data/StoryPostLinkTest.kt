package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryPostLinkTest {
    @Test fun idFormIsAPost() {
        assertEquals("ch_1" to "m-2", StoryPostLink.ref("https://fariin.com/c/ch_1/m-2"))
        assertEquals("a" to "b", StoryPostLink.ref("https://www.FARIIN.com/c/a/b"))
    }

    @Test fun otherLinksAreNot() {
        assertNull(StoryPostLink.ref("http://fariin.com/c/a/b"))
        assertNull(StoryPostLink.ref("https://fariin.com/c/a/123"))
        assertNull(StoryPostLink.ref("https://fariin.com/c/a"))
        assertNull(StoryPostLink.ref("https://fariin.com/c/a/b/c"))
        assertNull(StoryPostLink.ref("https://evil.com/c/a/b"))
        assertNull(StoryPostLink.ref("https://fariin.com/x/a/b"))
        assertNull(StoryPostLink.ref("https://fariin.com/c/a.b/c"))
        assertNull(StoryPostLink.ref("not a url"))
        assertNull(StoryPostLink.ref(null))
    }
}
