package com.fariin.app.ui.call

import com.fariin.app.data.CallEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallInfoTextTest {
    private val now = 10_000_000L
    private fun e(caller: String, outcome: String, video: Boolean = false, dur: Int = 0) =
        CallEntry("id", "cid", "Amina", null, "them", caller, outcome, video, dur, now - 60_000)

    @Test fun kindWords() {
        assertEquals("Outgoing voice call", CallInfoText.kind(e("me", "answered"), "me", now))
        assertEquals("Incoming video call", CallInfoText.kind(e("them", "answered", video = true), "me", now))
        assertEquals("Missed voice call", CallInfoText.kind(e("them", "missed"), "me", now))
        // My own unanswered call is not red "Missed": it is still Outgoing.
        assertEquals("Outgoing voice call", CallInfoText.kind(e("me", "missed"), "me", now))
    }

    @Test fun lengthAndNote() {
        assertEquals("45 sec", CallInfoText.length(e("me", "answered", dur = 45), now))
        assertEquals("3 min", CallInfoText.length(e("me", "answered", dur = 200), now))
        assertEquals("1 hr 5 min", CallInfoText.length(e("me", "answered", dur = 3900), now))
        assertEquals("2 hr", CallInfoText.length(e("me", "answered", dur = 7200), now))
        assertNull(CallInfoText.length(e("them", "missed", dur = 30), now))
        assertEquals("No answer", CallInfoText.note(e("me", "missed"), "me", now))
        assertEquals("Declined", CallInfoText.note(e("me", "declined"), "me", now))
        assertNull(CallInfoText.note(e("them", "missed"), "me", now))
    }
}
