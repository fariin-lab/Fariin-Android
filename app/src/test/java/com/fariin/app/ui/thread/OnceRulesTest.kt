package com.fariin.app.ui.thread

import com.fariin.app.ui.thread.OnceRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnceRulesTest {
    @Test fun receivedUnopenedShowsKindAndCanOpen() {
        val opened = OnceRules.opened(isMe = false, consumed = false, seenHere = false)
        assertFalse(opened)
        assertEquals("Photo", OnceRules.label(Kind.PHOTO, opened))
        assertEquals("Voice", OnceRules.label(Kind.VOICE, opened))
        assertTrue(OnceRules.canOpen(isMe = false, opened = opened, hasMedia = true, sending = false))
        assertTrue(OnceRules.ringShowsOne(opened))
        assertTrue(OnceRules.ringIsAccent(isMe = false, opened = opened))
    }

    @Test fun receivedOpenedHereOrBurnedIsOpened() {
        assertTrue(OnceRules.opened(isMe = false, consumed = false, seenHere = true))
        assertTrue(OnceRules.opened(isMe = false, consumed = true, seenHere = false))
        assertEquals("Opened", OnceRules.label(Kind.PHOTO, true))
        assertEquals("Opened", OnceRules.label(Kind.VOICE, true))
        assertFalse(OnceRules.canOpen(isMe = false, opened = true, hasMedia = true, sending = false))
        assertFalse(OnceRules.ringShowsOne(true))
        assertFalse(OnceRules.ringIsAccent(isMe = false, opened = true))
    }

    @Test fun mineFollowsTheServerOnly() {
        // A mark this phone left under the old "open my own once" rule is not the other side opening it.
        assertFalse(OnceRules.opened(isMe = true, consumed = false, seenHere = true))
        assertTrue(OnceRules.opened(isMe = true, consumed = true, seenHere = false))
        assertFalse(OnceRules.ringIsAccent(isMe = true, opened = false))
    }

    @Test fun hintLine() {
        assertEquals("Tap to view", OnceRules.hint(Kind.PHOTO, isMe = false))
        assertEquals("Tap to play", OnceRules.hint(Kind.VOICE, isMe = false))
        assertEquals("Not opened yet", OnceRules.hint(Kind.PHOTO, isMe = true))
        assertEquals("Not opened yet", OnceRules.hint(Kind.VOICE, isMe = true))
    }

    @Test fun iCanNeverOpenMine() {
        assertFalse(OnceRules.canOpen(isMe = true, opened = false, hasMedia = true, sending = false))
    }

    @Test fun notOpenableWithoutMediaOrWhileSending() {
        assertFalse(OnceRules.canOpen(isMe = false, opened = false, hasMedia = false, sending = false))
        assertFalse(OnceRules.canOpen(isMe = false, opened = false, hasMedia = true, sending = true))
    }
}
