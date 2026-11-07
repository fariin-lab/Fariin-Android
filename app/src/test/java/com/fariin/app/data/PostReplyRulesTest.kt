package com.fariin.app.data

import com.fariin.app.data.PostReplyRules.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostReplyRulesTest {
    private fun r(id: String, at: Long, mine: Boolean = false, send: PostReply.Send = PostReply.Send.SENT) =
        PostReply(id, at = at, mine = mine, send = send)

    @Test fun barWords() {
        assertEquals("Reply", PostReplyRules.barLabel(0, 0, repliesOn = true))
        assertEquals("1 Reply", PostReplyRules.barLabel(1, 0, repliesOn = true))
        assertEquals("3 Replies · 4 Quotes", PostReplyRules.barLabel(3, 4, repliesOn = true))
        assertEquals("Reply · 1 Quote", PostReplyRules.barLabel(0, 1, repliesOn = true))
        assertEquals("1.2K Replies", PostReplyRules.barLabel(1234, 0, repliesOn = true))
    }

    @Test fun offHidesTheReplyPart() {
        // Off: no Reply part at all (iOS 2026-11-07); only the quotes, or no bar.
        assertEquals("2 Quotes", PostReplyRules.barLabel(5, 2, repliesOn = false))
        assertNull(PostReplyRules.barLabel(5, 0, repliesOn = false))
        assertEquals("2 Quotes", PostReplyRules.barLabel(0, 2, repliesOn = false))
        assertNull(PostReplyRules.barLabel(0, 0, repliesOn = false))
    }

    @Test fun offOpensQuotesOnly() {
        assertEquals(1, PostReplyRules.startTab(repliesOn = false))
        assertEquals(0, PostReplyRules.startTab(repliesOn = true))
    }

    @Test fun postSwitchWinsOverChannel() {
        // Missing on the post = the channel's; an explicit false turns a post on in an off channel.
        assertTrue(PostReplyRules.postOff(null, channelOff = true))
        assertFalse(PostReplyRules.postOff(null, channelOff = false))
        assertFalse(PostReplyRules.postOff(false, channelOff = true))
        assertTrue(PostReplyRules.postOff(true, channelOff = false))
        // A switch changed on this phone wins over the post's doc.
        assertFalse(PostReplyRules.postOff(true, channelOff = true, local = false))
        assertTrue(PostReplyRules.postOff(false, channelOff = false, local = true))
    }

    @Test fun barStartIconFacesOrChannels() {
        assertEquals(PostReplyRules.BarStart.Icon, PostReplyRules.barStart(true, 0, listOf("a"), 3, listOf("q")))
        assertEquals(PostReplyRules.BarStart.Icon, PostReplyRules.barStart(true, 2, emptyList(), 0, emptyList()))
        assertEquals(PostReplyRules.BarStart.Faces(listOf("a", "", "c"), people = true),
            PostReplyRules.barStart(true, 9, listOf("a", "", "c", "d"), 0, emptyList()))
        assertEquals(PostReplyRules.BarStart.Faces(listOf("q"), people = false), PostReplyRules.barStart(false, 5, listOf("a"), 1, listOf("q")))
        assertEquals(PostReplyRules.BarStart.Faces(emptyList(), people = false), PostReplyRules.barStart(false, 5, listOf("a"), 0, listOf("q")))
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

    @Test fun clientIdFitsTheServer() {
        val id = PostReplyRules.clientId("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(Regex("^[A-Za-z0-9_-]{6,64}$").matches(id))
        assertEquals(64, PostReplyRules.clientId("a".repeat(80)).length)
        assertEquals("abc_def", PostReplyRules.clientId("abc def/_def".replace(" def/", "")))
    }

    @Test fun parseShortKeys() {
        val x = PostReplyRules.parseReply(mapOf("id" to "r1", "t" to "Nice", "at" to 5000L, "n" to "Amina", "ph" to "https://p",
            "v" to true, "lc" to 4, "ac" to 2, "mine" to true, "del" to true, "liked" to true, "held" to true, "u" to "u1"))!!
        assertEquals(PostReply("r1", "u1", "Amina", "https://p", true, "Nice", 5000, true, true, "", 4, true, 2, true,
            my = PostReplyRules.HEART, rx = listOf(PostReply.Rx(PostReplyRules.HEART, 4))), x)
        val bare = PostReplyRules.parseReply(mapOf("id" to "r", "n" to " ", "lc" to -3), parent = "t")!!
        assertEquals("Someone", bare.name); assertEquals(0, bare.likes); assertEquals("t", bare.parentId); assertEquals("", bare.uid)
        assertNull(PostReplyRules.parseReply(mapOf("t" to "no id")))
    }

    @Test fun parsePage() {
        val p = PostReplyRules.parsePage(mapOf(
            "items" to listOf(mapOf("id" to "new", "at" to 9), mapOf("id" to "old", "at" to 3), "junk"),
            "next" to "3.old", "count" to 7, "off" to false, "channelOff" to false, "postOff" to true,
            "may" to mapOf("del" to true, "ban" to false, "settings" to true), "staff" to true, "rev" to 12,
        ))
        assertEquals(listOf("old", "new"), p.items.map { it.id })
        assertEquals("3.old", p.next); assertEquals(7, p.count); assertTrue(p.off); assertTrue(p.postOff)
        assertEquals(ReplyRights(del = true, ban = false, settings = true), p.may); assertEquals(12, p.rev)
        val empty = PostReplyRules.parsePage(null)
        assertEquals(0, empty.count); assertFalse(empty.off); assertEquals(ReplyRights(), empty.may); assertEquals("", empty.next)
        assertNull(p.pinned)
        // The first page's pinned thread, and a Creator reply (the owner writing as the channel).
        val pp = PostReplyRules.parsePage(mapOf("items" to emptyList<Any>(),
            "pinned" to mapOf("id" to "p1", "n" to "News", "cr" to true, "ac" to 2)))
        assertEquals("p1", pp.pinned?.id); assertTrue(pp.pinned!!.creator); assertEquals(2, pp.pinned!!.answers)
        assertFalse(PostReplyRules.parseReply(mapOf("id" to "x"))!!.creator)
        // The server's postOff is the effective switch: a post turned on in an off channel takes replies.
        assertFalse(PostReplyRules.parsePage(mapOf("channelOff" to true, "postOff" to false, "off" to true)).off)
        val a = PostReplyRules.parseAnswers(mapOf("items" to listOf(mapOf("id" to "a1")), "next" to ""), "t1")
        assertEquals("t1", a.items[0].parentId)
    }

    @Test fun headMarker() {
        assertFalse(PostReplyRules.headMoved(-1, 4, loaded = true))   // the first value only sets the start
        assertFalse(PostReplyRules.headMoved(4, 4, loaded = true))
        assertTrue(PostReplyRules.headMoved(4, 5, loaded = true))
        assertFalse(PostReplyRules.headMoved(4, 5, loaded = false))
    }

    @Test fun firstPageKeepsOlderAndUnsent() {
        val shown = listOf(r("older", 1), r("a", 5), r("mine", 2, mine = true, send = PostReply.Send.SENDING))
        val page = listOf(r("a", 5).copy(likes = 3), r("b", 6))
        val out = PostReplyRules.mergeFirst(shown, page)
        assertEquals(listOf("older", "mine", "a", "b"), out.map { it.id })
        assertEquals(3, out.first { it.id == "a" }.likes)
    }

    @Test fun newestPageMerges() {
        val shown = listOf(r("a", 1), r("b", 5), r("gone", 6), r("c", 7))
        val m = PostReplyRules.mergeNewest(shown, listOf(r("b", 5), r("c", 7), r("d", 8)), next = "5.b", recentMine = emptySet())
        // "a" is older than the page: kept; "gone" was inside its range and missing: removed; "d" is new.
        assertEquals(listOf("a", "b", "c", "d"), m.list.map { it.id })
        assertNull(m.resetCursor)
        assertEquals(1, m.added)
    }

    @Test fun newestPageKeepsMyRecentAndUnsent() {
        val shown = listOf(r("b", 5), r("saved", 6, mine = true), r("sending", 9, mine = true, send = PostReply.Send.SENDING))
        val m = PostReplyRules.mergeNewest(shown, listOf(r("b", 5), r("c", 7)), next = "", recentMine = setOf("saved"))
        assertEquals(listOf("b", "saved", "c", "sending"), m.list.map { it.id })
    }

    @Test fun newestPageWithAGapStartsAgain() {
        val shown = listOf(r("a", 1), r("b", 2))
        val m = PostReplyRules.mergeNewest(shown, listOf(r("x", 10), r("y", 11)), next = "10.x", recentMine = emptySet())
        assertEquals(listOf("x", "y"), m.list.map { it.id })
        assertEquals("10.x", m.resetCursor)
        assertEquals(2, m.added)
    }

    @Test fun emptyNewestPage() {
        val shown = listOf(r("a", 1), r("m", 2, mine = true, send = PostReply.Send.FAILED))
        assertEquals(listOf("m"), PostReplyRules.mergeNewest(shown, emptyList(), "", emptySet()).list.map { it.id })
        assertEquals(shown, PostReplyRules.mergeNewest(shown, emptyList(), "1.a", emptySet()).list)
    }

    @Test fun olderPageAbove() {
        assertEquals(listOf("x", "a", "b"), PostReplyRules.mergeOlder(listOf(r("a", 5), r("b", 6)), listOf(r("x", 1), r("a", 5))).map { it.id })
    }

    @Test fun answersPages() {
        val shown = listOf(r("a1", 1), r("mine", 9, mine = true, send = PostReply.Send.FAILED))
        assertEquals(listOf("a2", "mine"), PostReplyRules.mergeAnswers(shown, listOf(r("a2", 2)), more = false).map { it.id })
        assertEquals(listOf("a1", "a2", "mine"), PostReplyRules.mergeAnswers(shown, listOf(r("a1", 1), r("a2", 2)), more = true).map { it.id })
    }

    @Test fun emojiReactionsParse() {
        val x = PostReplyRules.parseReply(mapOf("id" to "r", "lc" to 3, "my" to "😂",
            "rx" to mapOf("❤️" to 1, "😂" to 2, "👍" to 0, "" to 4)))!!
        assertEquals(listOf(PostReply.Rx("😂", 2), PostReply.Rx("❤️", 1)), x.rx)
        assertEquals("😂", x.my); assertTrue(x.liked); assertEquals(3, x.likes)
        val none = PostReplyRules.parseReply(mapOf("id" to "n", "lc" to 0, "my" to ""))!!
        assertEquals("", none.my); assertFalse(none.liked); assertTrue(none.rx.isEmpty())
    }

    @Test fun fourPillsMineAlwaysShown() {
        fun rx(vararg p: Pair<String, Int>) = p.map { PostReply.Rx(it.first, it.second) }
        val six = rx("👍" to 1, "❤️" to 9, "😂" to 5, "😮" to 4, "😢" to 3, "🙏" to 2)
        // The 4 most used, biggest first.
        assertEquals(listOf("❤️", "😂", "😮", "😢"), PostReplyRules.shownPills(six, "").map { it.emoji })
        // Mine already in the top 4: unchanged.
        assertEquals(listOf("❤️", "😂", "😮", "😢"), PostReplyRules.shownPills(six, "😂").map { it.emoji })
        // Mine outside it: takes the 4th place.
        assertEquals(listOf("❤️", "😂", "😮", "👍"), PostReplyRules.shownPills(six, "👍").map { it.emoji })
        // Fewer than 4: all of them; mine not on the reply at all: just the top.
        assertEquals(listOf("❤️", "😂"), PostReplyRules.shownPills(rx("😂" to 1, "❤️" to 2), "🙏").map { it.emoji })
    }

    @Test fun oneCleanEmoji() {
        assertEquals("❤️", PostReplyRules.cleanEmoji("❤️"))
        assertEquals("👍", PostReplyRules.cleanEmoji(" ​👍​ "))
        assertEquals("👍🏽", PostReplyRules.cleanEmoji("👍🏽"))
        assertEquals("👨‍👩‍👧", PostReplyRules.cleanEmoji("⁠👨‍👩‍👧﻿"))
        assertEquals("🇸🇴", PostReplyRules.cleanEmoji("🇸🇴"))
        assertEquals("😂", PostReplyRules.cleanEmoji("‍😂"))
        assertNull(PostReplyRules.cleanEmoji("👍👍"))
        assertNull(PostReplyRules.cleanEmoji("ok👍"))
        assertNull(PostReplyRules.cleanEmoji("ce_12"))
        assertNull(PostReplyRules.cleanEmoji(""))
        assertNull(PostReplyRules.cleanEmoji("​"))
        for (e in PostReplyRules.REACT_BAR) assertEquals(e, PostReplyRules.cleanEmoji(e))
    }

    @Test fun mineMovesBetweenPills() {
        val x = PostReply("a", likes = 2, rx = listOf(PostReply.Rx("❤️", 2)))
        assertEquals(x, PostReplyRules.overlay(x, null))
        val heart = PostReplyRules.withMine(x, "❤️")
        assertEquals(listOf(PostReply.Rx("❤️", 3)), heart.rx); assertEquals(3, heart.likes); assertTrue(heart.liked)
        // Another emoji: the heart goes down, the new pill comes, still one person more.
        val laugh = PostReplyRules.withMine(heart, "😂")
        assertEquals(listOf(PostReply.Rx("❤️", 2), PostReply.Rx("😂", 1)), laugh.rx); assertEquals(3, laugh.likes)
        // Taken back: the pill at 0 goes.
        val off = PostReplyRules.withMine(laugh, "")
        assertEquals(listOf(PostReply.Rx("❤️", 2)), off.rx); assertEquals(2, off.likes); assertFalse(off.liked)
        assertEquals("", PostReplyRules.nextPick(laugh, "😂"))
        assertEquals("👍", PostReplyRules.nextPick(laugh, "👍"))
    }

    @Test fun pendingRow() {
        val p = PostReplyRules.pending("id1", "hi", "", "", "t", 50)
        assertEquals(PostReply.Send.SENDING, p.send); assertEquals("You", p.name); assertTrue(p.mine); assertEquals("t", p.parentId)
    }

    @Test fun menuByRights() {
        val theirs = PostReply("a", canDelete = false)
        assertEquals(listOf(Action.REPLY, Action.COPY, Action.REPORT), PostReplyRules.actions(theirs, off = false, may = ReplyRights()))
        val mod = ReplyRights(del = true, ban = true)
        assertEquals(listOf(Action.REPLY, Action.COPY, Action.REPORT, Action.DELETE, Action.BAN),
            PostReplyRules.actions(theirs.copy(canDelete = true), off = false, may = mod))
        val mine = PostReply("m", mine = true, canDelete = true)
        assertEquals(listOf(Action.COPY, Action.DELETE), PostReplyRules.actions(mine, off = true, may = mod))
        assertEquals(listOf(Action.RETRY, Action.COPY, Action.DELETE), PostReplyRules.actions(mine.copy(send = PostReply.Send.FAILED), false, mod))
        assertEquals(listOf(Action.COPY), PostReplyRules.actions(mine.copy(send = PostReply.Send.SENDING), false, mod))
        // Pin / Unpin with the switches' right, top-level replies only, never held ones.
        val admin = ReplyRights(settings = true)
        assertEquals(listOf(Action.REPLY, Action.PIN, Action.COPY, Action.REPORT), PostReplyRules.actions(theirs, false, admin))
        assertEquals(listOf(Action.REPLY, Action.UNPIN, Action.COPY, Action.REPORT), PostReplyRules.actions(theirs, false, admin, pinnedId = "a"))
        assertEquals(listOf(Action.REPLY, Action.COPY, Action.REPORT), PostReplyRules.actions(theirs.copy(parentId = "t"), false, admin))
        assertEquals(listOf(Action.REPLY, Action.COPY, Action.REPORT), PostReplyRules.actions(theirs.copy(held = true), false, admin))
        assertFalse(PostReplyRules.showsAdminMenu(ReplyRights(ban = true)))
        assertTrue(PostReplyRules.showsAdminMenu(ReplyRights(settings = true)))
        assertTrue(PostReplyRules.showsAdminMenu(ReplyRights(del = true)))
    }

    @Test fun reasonsMatchTheServer() {
        assertEquals(listOf("spam", "abuse", "hate", "sexual", "violence", "other"), PostReplyRules.REASONS.map { it.first })
    }

    @Test fun alertPushes() {
        assertEquals("c1" to "m1", PostReplyRules.alertTarget("postReplies", "c1", "m1"))
        assertEquals("c1" to "m1", PostReplyRules.alertTarget("replyAnswer", "c1", "m1"))
        assertNull(PostReplyRules.alertTarget("text", "c1", "m1"))
        assertNull(PostReplyRules.alertTarget("postReplies", "c1", ""))
        assertNull(PostReplyRules.alertTarget("postReplies", "c/1", "m1"))
        assertNull(PostReplyRules.alertTarget(null, "c1", "m1"))
    }

    @Test fun wordsAndAges() {
        assertEquals("View 1 reply", PostReplyRules.threadToggle(1, open = false))
        assertEquals("View 3 replies", PostReplyRules.threadToggle(3, open = false))
        assertEquals("Hide replies", PostReplyRules.threadToggle(3, open = true))
        assertEquals("1 new reply", PostReplyRules.newPill(1))
        assertEquals("4 new replies", PostReplyRules.newPill(4))
        val now = 10_000_000_000L
        assertEquals("now", PostReplyRules.age(now - 30_000, now))
        assertEquals("now", PostReplyRules.age(now + 5_000, now))
        assertEquals("5m", PostReplyRules.age(now - 5 * 60_000, now))
        assertEquals("3h", PostReplyRules.age(now - 3 * 3_600_000L, now))
        assertEquals("2d", PostReplyRules.age(now - 2 * 86_400_000L, now))
        assertEquals("6w", PostReplyRules.age(now - 42 * 86_400_000L, now))
    }
}
