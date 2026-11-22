package com.fariin.app.push

import com.fariin.app.data.OpenGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PushTapRouteTest {
    @Test fun officialChatOpensItsOwnPage() {
        assertEquals("official", PushTapRoute.route("official-fariin"))
    }

    @Test fun otherChatsOpenTheThread() {
        assertEquals("thread/a_b", PushTapRoute.route("a_b"))
        assertEquals("thread/grp123", PushTapRoute.route("grp123"))
    }

    @Test fun gateOpensOnlyForOpenOrPostGone() {
        assertTrue(PushTapRoute.opens(OpenGate.Result.OPEN))
        assertTrue(PushTapRoute.opens(OpenGate.Result.POST_GONE))
        assertFalse(PushTapRoute.opens(OpenGate.Result.REFUSED))
        assertFalse(PushTapRoute.opens(OpenGate.Result.CHAT_GONE))
    }

    @Test fun visitorOpensOnlyAQuoteWithItsChannel() {
        assertTrue(PushTapRoute.quoteOpens(OpenGate.Result.VISITOR))
        assertTrue(PushTapRoute.quoteOpens(OpenGate.Result.OPEN))
        assertFalse(PushTapRoute.quoteOpens(OpenGate.Result.REFUSED))
        assertFalse(PushTapRoute.quoteOpens(OpenGate.Result.CHAT_GONE))
        // Any other kind: a channel I do not follow does not open.
        assertFalse(PushTapRoute.opens(OpenGate.Result.VISITOR))
    }

    @Test fun noUserYetThenUserArrivesOpens() = runBlocking {
        val uids = MutableStateFlow<String?>(null)
        var asked = false
        launch { delay(50); uids.value = "u1" }
        assertTrue(PushTapRoute.allowed(uids, 2_000L) { asked = true; OpenGate.Result.OPEN })
        assertTrue(asked)
    }

    @Test fun signedInButRefusedDoesNotOpen() = runBlocking {
        assertFalse(PushTapRoute.allowed(MutableStateFlow<String?>("u2"), 2_000L) { OpenGate.Result.REFUSED })
        assertFalse(PushTapRoute.allowed(MutableStateFlow<String?>("u2"), 2_000L) { OpenGate.Result.CHAT_GONE })
    }

    @Test fun authNeverArrivesOpensAsBefore() = runBlocking {
        var asked = false
        assertTrue(PushTapRoute.allowed(MutableStateFlow<String?>(null), 50L) { asked = true; OpenGate.Result.REFUSED })
        assertFalse(asked)
    }
}
