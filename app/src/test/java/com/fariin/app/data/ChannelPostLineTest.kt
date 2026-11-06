package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A follower's chat-list line for a channel comes from the server's postPreview, never a notice. */
class ChannelPostLineTest {
    private fun ch(text: String?, type: String?) =
        Conversation(id = "c1", users = listOf("owner"), isChannel = true, postPreviewText = text, postPreviewType = type)

    @Test fun wordsFirstThenKind() {
        assertEquals("Hello Guys", ChatList.channelPostLine(ch("Hello Guys", "text")))
        assertEquals("Photo", ChatList.channelPostLine(ch("", "image")))
        assertEquals("Voice message", ChatList.channelPostLine(ch("", "audio")))
        assertEquals("Post", ChatList.channelPostLine(ch("", "something-new")))
    }

    @Test fun cardMarkerNeverShown() {
        assertEquals("Location", ChatList.channelPostLine(ch("fariin-location:47.93|-97.02|Near East Grand Forks", "text")))
    }

    @Test fun noPostYet() {
        assertEquals("No posts yet", ChatList.channelPostLine(ch(null, null)))
    }
}
