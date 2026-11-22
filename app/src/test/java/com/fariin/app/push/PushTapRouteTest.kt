package com.fariin.app.push

import com.fariin.app.data.OpenGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
