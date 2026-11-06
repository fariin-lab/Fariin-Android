package com.fariin.app.channel

import com.fariin.app.channel.ChannelRules.Action
import com.fariin.app.channel.ChannelRules.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRulesTest {
    @Test fun followerCountShortening() {
        // GroupDirectory.shortCount: whole up to 9,999, then K and M with one decimal under 10.
        assertEquals("0", ChannelRules.shortCount(0))
        assertEquals("9999", ChannelRules.shortCount(9_999))
        assertEquals("10K", ChannelRules.shortCount(10_000))
        assertEquals("12K", ChannelRules.shortCount(12_499))
        assertEquals("128K", ChannelRules.shortCount(128_400))
        assertEquals("999K", ChannelRules.shortCount(999_999))
        assertEquals("1M", ChannelRules.shortCount(1_000_000))
        assertEquals("2.4M", ChannelRules.shortCount(2_400_000))
        assertEquals("2.4M", ChannelRules.shortCount(2_499_999))   // rounded down, never up
        assertEquals("12M", ChannelRules.shortCount(12_000_000))
        assertEquals("1 follower", ChannelRules.followersLine(1))
        assertEquals("128K followers", ChannelRules.followersLine(128_400))
        assertEquals("2.4M followers", ChannelRules.followersLine(2_400_000))
    }

    @Test fun pageAndPostCounts() {
        assertEquals("999", ChannelRules.compactCount(999))
        assertEquals("1.2K", ChannelRules.compactCount(1_250))
        assertEquals("1K", ChannelRules.compactCount(1_000))
        assertEquals("12K", ChannelRules.compactCount(12_900))
        assertEquals("2.4M", ChannelRules.compactCount(2_400_000))
        assertEquals("1B", ChannelRules.compactCount(1_000_000_000))
        assertEquals("1.2k Followers", ChannelRules.pageCountLine(1_250))
        assertEquals("1 Follower", ChannelRules.pageCountLine(1))
        assertEquals(1, ChannelRules.shownViews(0, "me", "me"))
        assertEquals(0, ChannelRules.shownViews(0, "them", "me"))
        assertEquals(7, ChannelRules.shownViews(7, "me", "me"))
    }

    @Test fun emojiFoldAndMerge() {
        assertTrue(ChannelRules.sameEmoji("❤", "❤️"))
        assertFalse(ChannelRules.sameEmoji(null, "❤️"))
        // Two spellings of one heart: one chip, drawn with the selector.
        val m = ChannelRules.mergedCounts(mapOf("❤" to 2, "❤️" to 3, "🔥" to 1), mine = null, held = null)
        assertEquals(mapOf("❤️" to 5, "🔥" to 1), m)
        // My new pick moves in at once; my old one moves out.
        val moved = ChannelRules.mergedCounts(mapOf("👍" to 1, "🔥" to 4), mine = "🔥", held = "👍")
        assertEquals(mapOf("🔥" to 5), moved)
        // Taken back.
        assertEquals(mapOf("🔥" to 3), ChannelRules.mergedCounts(mapOf("🔥" to 4), mine = null, held = "🔥"))
        // Already held: nothing changes.
        assertEquals(mapOf("🔥" to 4), ChannelRules.mergedCounts(mapOf("🔥" to 4), mine = "🔥", held = "🔥"))
        val chips = ChannelRules.chips(mapOf("👍" to 2, "🔥" to 5), mine = "👍", held = "👍")
        assertEquals(listOf("🔥", "👍"), chips.map { it.emoji })
        assertTrue(chips[1].mine)
        assertNull(ChannelRules.nextPick("❤️", "❤"))
        assertEquals("🔥", ChannelRules.nextPick("❤️", "🔥"))
    }

    @Test fun reactionLists() {
        assertEquals(73, ChannelRules.channelReactions.size)
        assertEquals("❤️", ChannelRules.channelReactions.first())
        assertEquals("😡", ChannelRules.channelReactions.last())
        assertEquals(listOf("❤️", "👍", "👎", "🔥", "🥰", "👏"), ChannelRules.offered("all", emptyList()))
        assertEquals(listOf("🔥", "🙏"), ChannelRules.offered("some", listOf("🔥", "🙏")))
        assertTrue(ChannelRules.offered("off", listOf("🔥")).isEmpty())
        assertEquals(73, ChannelRules.pickable("all", emptyList()).size)
    }

    @Test fun rolesAndReactRight() {
        val pub = ChannelInfo(cid = "c", isChannel = true, users = listOf("owner"), isPublic = true, username = "news")
        assertEquals(Role.ADMIN, ChannelRules.role(pub, "owner", follows = false))
        assertEquals(Role.FOLLOWER, ChannelRules.role(pub, "bob", follows = true))
        assertEquals(Role.VISITOR, ChannelRules.role(pub, "bob", follows = false))
        assertTrue(ChannelRules.canReact(pub, Role.VISITOR, guest = false))      // public: visitors react too
        assertFalse(ChannelRules.canReact(pub.copy(isPublic = false), Role.VISITOR, guest = false))
        assertFalse(ChannelRules.canReact(pub.copy(reactMode = "off"), Role.ADMIN, guest = false))
    }

    @Test fun buttonsNeverReshuffle() {
        val before = ChannelRules.circles(Role.VISITOR, hasLink = true, muted = false)
        val after = ChannelRules.circles(Role.FOLLOWER, hasLink = true, muted = false)
        assertEquals(listOf("Follow", "Share", "Search", "Mute"), before.map { it.label })
        assertEquals(listOf("Leave", "Share", "Search", "Mute"), after.map { it.label })
        // Only the first circle changes; positions 1 to 3 keep their action.
        assertEquals(before.drop(1).map { it.action }, after.drop(1).map { it.action })
        assertTrue(before[2].dimmed && before[3].dimmed && !before[1].dimmed)
        assertFalse(after.any { it.dimmed })
        assertEquals(Action.UNMUTE, ChannelRules.circles(Role.FOLLOWER, true, muted = true)[3].action)
        assertTrue(ChannelRules.circles(Role.VISITOR, hasLink = false, muted = false)[1].dimmed)
        assertEquals(listOf("Share", "Search", "Mute"), ChannelRules.circles(Role.ADMIN, true, false).map { it.label })
        assertEquals("Follow the channel to search its posts.", ChannelRules.visitorNotice(Action.SEARCH))
    }

    @Test fun unreadAndCounts() {
        assertEquals(3, ChannelRules.unread(postCount = 10, read = 7, deletedSeqs = emptyList()))
        assertEquals(2, ChannelRules.unread(10, 7, listOf(9, 3)))   // a deleted post after my mark is not waiting
        assertEquals(0, ChannelRules.unread(5, 9, emptyList()))
        assertEquals(1, ChannelRules.subscriberCount(followerCount = 0, admins = 1))   // a new channel reads 1
    }

    @Test fun parsesDocAndAnswers() {
        val doc = mapOf<String, Any?>("type" to "group", "channel" to true, "title" to "Fariin News", "users" to listOf("o"),
            "followerCount" to 128_399L, "postCount" to 12L, "isPublic" to true, "username" to "fariin", "verified" to true,
            "reactSet" to mapOf("mode" to "some", "list" to listOf("🔥")), "restrictedBy" to mapOf("o" to true), "desc" to " Hi ")
        val i = ChannelInfo.from("c1", doc)
        assertTrue(i.isChannel && i.isPublic && i.verified && i.restricted)
        assertEquals("fariin", i.username)
        assertEquals("Hi", i.description)
        assertEquals(128_400, i.shownCount)
        assertEquals("some", i.reactMode)
        // A name without isPublic is not a public link.
        assertEquals("", ChannelInfo.from("c1", doc + ("isPublic" to false)).username)
        val rows = DiscoveredChannel.list(mapOf("country" to "SO", "channels" to listOf(
            mapOf("cid" to "a", "title" to "Fariin News", "followerCount" to 128_400, "admins" to 1, "official" to true, "verified" to true),
            mapOf("cid" to "bad/id", "title" to "x"),
            mapOf("cid" to "b", "title" to "", "photoUrl" to ""))))
        assertEquals(listOf("a", "b"), rows.map { it.cid })
        assertTrue(rows[0].official)
        assertEquals("Channel", rows[1].title)
        assertNull(rows[1].photoUrl)
        val p = ChannelPreview.from(mapOf("cid" to "a", "channel" to true, "memberCount" to 5, "followerCount" to 4,
            "label" to "scam", "verified" to true, "createdAt" to 1.7e12))!!
        assertEquals(5, p.shownCount)
        assertFalse(p.verified)   // a labelled channel never shows the mark
        assertEquals(5, ChannelInfo.fromPreview(p).shownCount)   // a visitor's header counts the admins too
    }

    @Test fun wordsAndLinks() {
        assertEquals("You changed the channel photo", ChannelRules.words("You changed the group photo"))
        assertEquals("remember the followers", ChannelRules.words("remember the members"))
        assertNull(ChannelRules.linkProblem(""))
        assertNull(ChannelRules.linkProblem("fariin_news"))
        assertEquals("Use only letters, numbers and _.", ChannelRules.linkProblem("bad-name"))
        assertEquals("A link must start with a letter.", ChannelRules.linkProblem("1news"))
        assertEquals("A link can't start or end with _.", ChannelRules.linkProblem("news_"))
        assertTrue(ChannelRules.linkProblem("abc") != null)
        assertEquals("Follow Fariin News on Fariin: https://fariin.com/fariin", ChannelRules.shareText("Fariin News", "fariin"))
        assertNull(ChannelRules.shareText("Private", ""))
    }

    @Test fun channelNoticesKeepOnlyTheChannelsOwnNews() {
        assertEquals("Channel created", ChannelRules.channelNotice("Adnan created the group"))
        assertEquals("Channel created", ChannelRules.channelNotice("Channel created"))
        assertEquals("Channel name changed to VE3", ChannelRules.channelNotice("Adnan renamed the group to VE3"))
        assertEquals("Channel photo removed", ChannelRules.channelNotice("Adnan removed the group photo"))
        assertNull(ChannelRules.channelNotice("This group is now verified by Fariin"))
        assertNull(ChannelRules.channelNotice("Adnan changed the group wallpaper"))
        assertNull(ChannelRules.channelNotice("Adnan added Sam"))
    }

    @Test fun adminCardLine() {
        assertEquals("You created this channel · 9 followers", ChannelRules.adminCardLine(true, 9))
        assertEquals("You're an admin of this channel · 1 follower", ChannelRules.adminCardLine(false, 1))
    }

    @Test fun noEmDashesInWords() {
        val all = listOf(ChannelRules.REQUEST_SENT, ChannelRules.FOLLOWING_NOTICE, ChannelRules.typeFooter(true),
            ChannelRules.typeFooter(false), ChannelRules.protectFooter(true), ChannelRules.protectFooter(false))
        assertTrue(all.none { it.contains('—') })
    }
}
