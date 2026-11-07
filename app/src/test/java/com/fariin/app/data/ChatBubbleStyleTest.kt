package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One chat's own bubble style over the Settings one: chat override, then global, then none. */
class ChatBubbleStyleTest {
    @Test fun noOverrideFollowsGlobal() {
        assertEquals("sakura", BubbleStyles.resolve(null, "sakura"))
        assertNull(BubbleStyles.resolve(null, null))
        assertNull(BubbleStyles.resolve(null, ""))
    }

    @Test fun overrideBeatsGlobal() {
        assertEquals("pink", BubbleStyles.resolve("pink", "sakura"))
        assertEquals("pink", BubbleStyles.resolve("pink", null))
    }

    @Test fun explicitNoneOptsOutOfGlobal() {
        assertNull(BubbleStyles.resolve(BubbleStyles.NONE, "sakura"))
        assertNull(BubbleStyles.resolve("", "sakura"))
    }

    @Test fun pickStoresStyleOrExplicitNone() {
        assertEquals("sakura", BubbleStyles.chatValue("sakura"))
        assertEquals(BubbleStyles.NONE, BubbleStyles.chatValue(null))
        assertEquals(BubbleStyles.NONE, BubbleStyles.chatValue(""))
        // A stored pick reads back as itself; a stored none reads back as no style.
        assertEquals("sakura", BubbleStyles.resolve(BubbleStyles.chatValue("sakura"), "pink"))
        assertNull(BubbleStyles.resolve(BubbleStyles.chatValue(null), "pink"))
    }

    @Test fun keysAreChatScoped() {
        assertEquals("bubbleStyle.chat.abc", BubbleStyles.chatKey("abc"))
    }
}
