package com.fariin.app.data

import com.fariin.app.push.NotifExpiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Story reactions in the chat, the pins sheet order, the menu countdown, notification expiry. */
class ChatLeftoversTest {
    private fun reaction(text: String = "😍", isReaction: Boolean = true, thumb: String? = "https://x/t.jpg",
                         forwarded: Boolean = false, deleted: Boolean = false) =
        Message(id = "m", authorId = "carol", text = text, createdAtMillis = 1.0, forwarded = forwarded, deleted = deleted,
            replyTo = Message.ReplyRef("s1", "alice", "", isStatus = true, storyThumb = thumb, isReaction = isReaction))

    @Test fun countdownMatchesIos() {
        assertEquals("Disappears in 0:00", Disappearing.countdown(-5_000.0))
        assertEquals("Disappears in 0:59", Disappearing.countdown(59_900.0))
        assertEquals("Disappears in 5:00", Disappearing.countdown(300_000.0))
        assertEquals("Disappears in 1:00:00", Disappearing.countdown(3_600_000.0))
        assertEquals("Disappears in 23:59:59", Disappearing.countdown(86_399_000.0))
        assertEquals("Disappears in 1 day", Disappearing.countdown(86_400_000.0))
        // Whole days, rounded: 1.6 days reads 2 days.
        assertEquals("Disappears in 2 days", Disappearing.countdown(1.6 * 86_400_000))
        assertEquals("Disappears in 7 days", Disappearing.countdown(7 * 86_400_000.0))
    }

    @Test fun onlyTheReactBarEmojiSitsOnTheCard() {
        assertEquals("😍", StoryReplyRules.reactionGlyph(reaction(), showsSenderName = false))
        assertEquals("❤️‍🔥", StoryReplyRules.reactionGlyph(reaction("❤️‍🔥"), showsSenderName = false))
        // A typed reply, even one emoji, keeps the ordinary layout.
        assertNull(StoryReplyRules.reactionGlyph(reaction(isReaction = false), false))
        assertNull(StoryReplyRules.reactionGlyph(reaction("Love this walk"), false))
        assertNull(StoryReplyRules.reactionGlyph(reaction(thumb = null), false))
        assertNull(StoryReplyRules.reactionGlyph(reaction(forwarded = true), false))
        assertNull(StoryReplyRules.reactionGlyph(reaction(deleted = true), false))
        assertNull(StoryReplyRules.reactionGlyph(reaction(), showsSenderName = true))
        assertNull(StoryReplyRules.reactionGlyph(Message(id = "x", authorId = "a", text = "🔥", createdAtMillis = 1.0), false))
    }

    @Test fun emojiOnly() {
        assertTrue(StoryReplyRules.isEmojiOnly("🔥"))
        assertTrue(StoryReplyRules.isEmojiOnly("👍🏽"))
        assertTrue(StoryReplyRules.isEmojiOnly("😭😍"))
        assertFalse(StoryReplyRules.isEmojiOnly(""))
        assertFalse(StoryReplyRules.isEmojiOnly("ok 👍"))
        assertFalse(StoryReplyRules.isEmojiOnly("a"))
    }

    @Test fun captionLikeIos() {
        assertEquals("You replied to their story", StoryReplyRules.caption(isMe = true, storyIsMine = false, senderName = "Carol"))
        assertEquals("You replied to your story", StoryReplyRules.caption(true, true, "Carol"))
        assertEquals("Alice replied to your story", StoryReplyRules.caption(false, true, "Alice"))
        assertEquals("Alice replied to their story", StoryReplyRules.caption(false, false, "Alice"))
    }

    @Test fun pinsSheetIsOldestFirstWithoutTombstones() {
        val a = Message(id = "a", authorId = "x", text = "first", createdAtMillis = 10.0)
        val b = Message(id = "b", authorId = "x", text = "second", createdAtMillis = 20.0)
        val gone = Message(id = "c", authorId = "x", text = "", createdAtMillis = 5.0, deleted = true)
        val found = mapOf("a" to a, "b" to b, "c" to gone)
        assertEquals(listOf("a", "b"), PinRules.sheetOrder(listOf("b", "c", "missing", "a")) { found[it] }.map { it.id })
        assertTrue(PinRules.sheetOrder(emptyList()) { found[it] }.isEmpty())
    }

    @Test fun notificationGoesWhenItsNewestLineRunsOut() {
        assertFalse(NotifExpiry.lineGone(0, 1_000))          // no timer
        assertFalse(NotifExpiry.lineGone(2_000, 1_000))      // still ahead
        assertTrue(NotifExpiry.lineGone(1_000, 1_000))
        // The chat list says the newest message (written at 100 s, stamped 160 s) is due:
        // a notification posted before the stamp or just after the message goes.
        assertTrue(NotifExpiry.chatGone(postTimeMs = 105_000, chatUpdatedAtMs = 100_000.0, dueAtMs = 160_000.0))
        assertTrue(NotifExpiry.chatGone(postTimeMs = 150_000, chatUpdatedAtMs = 100_000.0, dueAtMs = 160_000.0))
        // A short timer: posted 8 s after a 5 s message, still the same message.
        assertTrue(NotifExpiry.chatGone(postTimeMs = 108_000, chatUpdatedAtMs = 100_000.0, dueAtMs = 105_000.0))
        // Posted after both: something newer the list has not caught up with stays.
        assertFalse(NotifExpiry.chatGone(postTimeMs = 200_000, chatUpdatedAtMs = 100_000.0, dueAtMs = 160_000.0))
    }
}
