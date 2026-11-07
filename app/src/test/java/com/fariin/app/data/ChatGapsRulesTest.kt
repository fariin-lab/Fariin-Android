package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGapsRulesTest {
    private val reactions = mapOf("b" to "👍", "a" to "❤️", "c" to "👍", "me" to "❤️", "d" to "😂")
    private val names = mapOf("a" to "zara", "b" to "Ali", "c" to "Bilan", "me" to "Mo", "d" to "ali")

    @Test fun reactorTabsMostPickedFirstTiesByEmojiDescending() {
        val tabs = MessageRules.reactorTabs(reactions)
        assertEquals(listOf("👍" to 2, "❤️" to 2, "😂" to 1).sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { it.first }), tabs)
        assertEquals(3, tabs.size)
        assertEquals(1, tabs.last().second)
    }

    @Test fun reactorRowsSortByNameCaseBlindThenUid() {
        val all = MessageRules.reactorRows(reactions, null) { names[it]!! }
        assertEquals(listOf("b", "d", "c", "me", "a"), all.map { it.uid })
        val hearts = MessageRules.reactorRows(reactions, "❤️") { names[it]!! }
        assertEquals(listOf("me", "a"), hearts.map { it.uid })
    }

    @Test fun aTabWithNobodyLeftFallsBackToAll() {
        assertEquals("😂", MessageRules.activeTab(reactions, "😂"))
        assertNull(MessageRules.activeTab(reactions, "🙏"))
        assertNull(MessageRules.activeTab(reactions, null))
    }

    @Test fun jumbomojiCountsOneToFiveEmojiOnly() {
        assertEquals(1, MessageRules.jumbomojiCount("😂"))
        assertEquals(2, MessageRules.jumbomojiCount("🙂 🙂"))
        assertEquals(1, MessageRules.jumbomojiCount("👨‍👩‍👧"))
        assertEquals(1, MessageRules.jumbomojiCount("🇸🇴"))
        assertEquals(2, MessageRules.jumbomojiCount("👍🏽❤️"))
        assertEquals(1, MessageRules.jumbomojiCount("1️⃣"))
        assertEquals(5, MessageRules.jumbomojiCount("😀😀😀😀😀"))
        assertEquals(0, MessageRules.jumbomojiCount("😀😀😀😀😀😀"))
        assertEquals(0, MessageRules.jumbomojiCount("hi 😀"))
        assertEquals(0, MessageRules.jumbomojiCount("1"))
        assertEquals(0, MessageRules.jumbomojiCount("→"))
        assertEquals(1, MessageRules.jumbomojiCount("⭐"))
        assertEquals(0, MessageRules.jumbomojiCount(""))
        assertEquals(0, MessageRules.jumbomojiCount("   "))
        assertEquals(3.5f, MessageRules.jumbomojiScale(1))
        assertEquals(2.25f, MessageRules.jumbomojiScale(5))
    }

    @Test fun noReplyOnAStoryReactionOrWhereIMayNotSend() {
        val plain = Message(id = "m", authorId = "a", text = "hi", createdAtMillis = 0.0)
        val storyReact = plain.copy(replyTo = Message.ReplyRef(id = "s", authorId = "me", text = "", isStatus = true, isReaction = true))
        val storyTyped = plain.copy(replyTo = Message.ReplyRef(id = "s", authorId = "me", text = "", isStatus = true))
        assertEquals(true, MessageRules.canReplyHere(plain, true))
        assertEquals(false, MessageRules.canReplyHere(plain, false))
        assertEquals(false, MessageRules.canReplyHere(storyReact, true))
        assertEquals(true, MessageRules.canReplyHere(storyTyped, true))
    }

    @Test fun liveLineTypingThenRecordingAndMyOwnSettingHidesIt() {
        val n: (String) -> String? = { mapOf("a" to "Ali", "b" to "Bilan")[it] }
        val typing = mapOf("a" to "text-1", "me" to "text-1")
        val rec = mapOf("a" to "audio-5", "b" to "audio-5")
        assertEquals("typing…", MessageRules.liveLine(typing, "me", false, true, true, n))
        assertEquals("recording…", MessageRules.liveLine(mapOf("a" to "audio-1"), "me", false, true, true, n))
        assertEquals("Ali is typing…", MessageRules.liveLine(typing, "me", true, true, true, n))
        assertEquals("2 people recording…", MessageRules.liveLine(rec, "me", true, true, true, n))
        assertEquals("2 people typing…", MessageRules.liveLine(mapOf("a" to true, "b" to "text-2"), "me", true, true, true, n))
        assertEquals("Ali is typing…", MessageRules.liveLine(mapOf("a" to "text-1", "b" to "audio-1"), "me", true, true, true, n))
        assertNull(MessageRules.liveLine(typing, "me", false, false, true, n))
        assertNull(MessageRules.liveLine(typing, "me", false, true, false, n))
        assertNull(MessageRules.liveLine(mapOf("me" to "audio-1", "a" to false), "me", false, true, true, n))
        assertEquals("audio-12", MessageRules.recordingValue(true, 12_345L))
        assertEquals(false, MessageRules.recordingValue(false, 1L))
    }
}
