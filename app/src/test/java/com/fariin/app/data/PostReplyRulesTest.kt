package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostReplyRulesTest {
    @Test fun barWords() {
        assertEquals("Reply", PostReplyRules.barLabel(0, 0, repliesOn = true))
        assertEquals("1 Reply", PostReplyRules.barLabel(1, 0, repliesOn = true))
        assertEquals("3 Replies · 4 Quotes", PostReplyRules.barLabel(3, 4, repliesOn = true))
        assertEquals("Reply · 1 Quote", PostReplyRules.barLabel(0, 1, repliesOn = true))
        assertEquals("1.2K Replies", PostReplyRules.barLabel(1234, 0, repliesOn = true))
    }

    @Test fun repliesOffLeavesQuotesOrNothing() {
        assertEquals("2 Quotes", PostReplyRules.barLabel(5, 2, repliesOn = false))
        assertNull(PostReplyRules.barLabel(5, 0, repliesOn = false))
    }

    @Test fun noBarWhileSendingOrDeleted() {
        assertNull(PostReplyRules.barLabel(0, 3, repliesOn = true, sent = false))
        assertNull(PostReplyRules.barLabel(1, 3, repliesOn = true, deleted = true))
    }

    @Test fun offByChannelOrPost() {
        assertTrue(PostReplyRules.repliesOn(channelOff = false, postOff = false))
        assertFalse(PostReplyRules.repliesOn(channelOff = true, postOff = false))
        assertFalse(PostReplyRules.repliesOn(channelOff = false, postOff = true))
        assertEquals("Replies are turned off for this post.", PostReplyRules.offNote(false))
        assertEquals("Replies are turned off in this channel.", PostReplyRules.offNote(true))
    }

    @Test fun facesOnlyWhenQuoted() {
        assertFalse(PostReplyRules.showFaces(0, listOf("a")))
        assertFalse(PostReplyRules.showFaces(2, emptyList()))
        assertTrue(PostReplyRules.showFaces(2, listOf("a")))
    }

    @Test fun adminMenuWords() {
        assertEquals("Turn Off Replies for This Post", PostReplyRules.postToggleLabel(false))
        assertEquals("Turn On Replies for This Post", PostReplyRules.postToggleLabel(true))
        assertEquals("Turn Off Replies in Channel", PostReplyRules.channelToggleLabel(false))
        assertEquals("Turn On Replies in Channel", PostReplyRules.channelToggleLabel(true))
    }

    @Test fun draftTrimmedAndCapped() {
        assertEquals("hi", PostReplyRules.cleanDraft("  hi \n"))
        assertNull(PostReplyRules.cleanDraft("   "))
        assertNull(PostReplyRules.cleanDraft("a".repeat(1001)))
        assertEquals(1000, PostReplyRules.cleanDraft("a".repeat(1000))?.length)
    }

    @Test fun parseAnswer() {
        val a = PostReplyRules.parse(mapOf(
            "replies" to listOf(
                mapOf("id" to "r1", "uid" to "u1", "name" to "Amina", "text" to "Nice", "at" to 5000L,
                    "verified" to true, "mine" to true, "canDelete" to true),
                mapOf("id" to "r2", "uid" to "u2", "name" to " ", "at" to 6000),
                mapOf("uid" to "u3"),
                "junk",
            ),
            "count" to 7, "off" to false, "channelOff" to false, "postOff" to true, "staff" to true,
        ))
        assertEquals(listOf("r1", "r2"), a.replies.map { it.id })
        assertEquals(PostReply("r1", "u1", "Amina", "", true, "Nice", 5000, true, true), a.replies[0])
        assertEquals("Someone", a.replies[1].name)
        assertEquals(7, a.count)
        assertTrue(a.off)
        assertTrue(a.postOff)
        assertTrue(a.staff)
        val empty = PostReplyRules.parse(null)
        assertEquals(0, empty.count)
        assertFalse(empty.off)
    }

    @Test fun threadsAndAnswers() {
        val a = PostReply("a", "u"); val b = PostReply("b", "u", parentId = "a"); val c = PostReply("c", "u")
        val list = listOf(a, b, c)
        assertEquals(listOf("a", "c"), PostReplyRules.threads(list).map { it.id })
        assertEquals(listOf("b"), PostReplyRules.answers(list, "a").map { it.id })
        assertEquals("a", PostReplyRules.threadOf(b))
        assertEquals("a", PostReplyRules.threadOf(a))
        assertEquals(listOf("c"), PostReplyRules.removed(list, "a").map { it.id })
        assertEquals(listOf("a", "c"), PostReplyRules.removed(list, "b").map { it.id })
        assertEquals("View 1 reply", PostReplyRules.threadToggle(1, open = false))
        assertEquals("View 3 replies", PostReplyRules.threadToggle(3, open = false))
        assertEquals("Hide replies", PostReplyRules.threadToggle(3, open = true))
    }

    @Test fun ages() {
        val now = 10_000_000_000L
        assertEquals("now", PostReplyRules.age(now - 30_000, now))
        assertEquals("now", PostReplyRules.age(now + 5_000, now))
        assertEquals("5m", PostReplyRules.age(now - 5 * 60_000, now))
        assertEquals("3h", PostReplyRules.age(now - 3 * 3_600_000L, now))
        assertEquals("2d", PostReplyRules.age(now - 2 * 86_400_000L, now))
        assertEquals("6w", PostReplyRules.age(now - 42 * 86_400_000L, now))
    }

    @Test fun likeFlipsAndCounts() {
        val list = listOf(PostReply("a", "u", likes = 2), PostReply("b", "u", likes = 1, liked = true))
        val on = PostReplyRules.toggledLike(list, "a")
        assertTrue(on[0].liked); assertEquals(3, on[0].likes); assertEquals(list[1], on[1])
        val off = PostReplyRules.toggledLike(list, "b")
        assertFalse(off[1].liked); assertEquals(0, off[1].likes)
        assertEquals(0, PostReplyRules.toggledLike(listOf(PostReply("z", "u", likes = 0, liked = true)), "z")[0].likes)
    }

    @Test fun parseThreadFields() {
        val r = PostReplyRules.parseReply(mapOf("id" to "r", "uid" to "u", "parentId" to "p", "likes" to 4, "liked" to true))!!
        assertEquals("p", r.parentId); assertEquals(4, r.likes); assertTrue(r.liked)
        val bare = PostReplyRules.parseReply(mapOf("id" to "r", "uid" to "u"))!!
        assertEquals("", bare.parentId); assertEquals(0, bare.likes); assertFalse(bare.liked)
    }

    @Test fun appendOnce() {
        val r = PostReply("r1", "u1")
        assertEquals(1, PostReplyRules.appended(listOf(r), r).size)
        assertEquals(2, PostReplyRules.appended(listOf(r), PostReply("r2", "u1")).size)
    }
}
