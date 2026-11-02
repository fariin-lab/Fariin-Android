package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRulesTest {
    private val me = "me"

    private fun story(id: String, created: Long, oneTime: Boolean = false, left: Int = -1) =
        Story(id, "bob", created, created + 86_400_000, "u", oneTime = oneTime, recipientsLeft = left)

    private fun dm(other: String, blockedByMe: Boolean = false, blockedByThem: Boolean = false, request: Boolean = false) =
        Conversation(
            id = listOf(me, other).sorted().joinToString("_"), users = listOf(me, other),
            blockedBy = buildMap { if (blockedByMe) put(me, true); if (blockedByThem) put(other, true) },
            startedBy = if (request) other else "", accepted = !request, lastSender = if (request) other else "",
        )

    @Test fun storyIdIsTenAlphanumerics() {
        val id = StoryRules.newStoryId()
        assertEquals(10, id.length)
        assertTrue(id.all { it.isLetterOrDigit() && it.code < 128 })
    }

    @Test fun pathsMatchTheStorageAndFirestoreRules() {
        assertEquals("stories/abc/photo.jpg", StoryRules.photoPath("abc"))
        assertEquals("stories/abc/video.mp4", StoryRules.videoPath("abc"))
        assertEquals("stories/abc/thumb.jpg", StoryRules.thumbPath("abc"))
    }

    @Test fun headerTimeIsNumberAndLetter() {
        val now = 10_000_000_000L
        assertEquals("12s", StoryRules.timeAgo(now - 12_000, now))
        assertEquals("5m", StoryRules.timeAgo(now - 300_000, now))
        assertEquals("3h", StoryRules.timeAgo(now - 3 * 3_600_000, now))
        assertEquals("2d", StoryRules.timeAgo(now - 2 * 86_400_000L, now))
        assertEquals("0s", StoryRules.timeAgo(now + 5000, now))
    }

    @Test fun viewsLabelAndOneTimeFull() {
        assertEquals("1 View", StoryRules.viewsLabel(1, false))
        assertEquals("0 Views", StoryRules.viewsLabel(0, false))
        assertEquals("7 Views", StoryRules.viewsLabel(7, false))
        assertEquals("Once viewer is full", StoryRules.viewsLabel(3, true))
        assertTrue(StoryRules.oneTimeFull(story("a", 1, oneTime = true, left = 0)))
        assertFalse(StoryRules.oneTimeFull(story("a", 1, oneTime = true, left = -1)))
        assertFalse(StoryRules.oneTimeFull(story("a", 1, left = 0)))
    }

    @Test fun audienceTitles() {
        assertEquals("View once", StoryRules.audienceTitle(story("a", 1, oneTime = true).copy(audienceLabel = "oneTime")))
        assertEquals("Everyone", StoryRules.audienceTitle(story("a", 1).copy(audienceLabel = "everyone")))
        assertEquals("My Chats", StoryRules.audienceTitle(story("a", 1).copy(audienceLabel = "friends")))
        assertEquals("Custom", StoryRules.audienceTitle(story("a", 1).copy(audienceLabel = "custom")))
    }

    @Test fun audienceLeavesOutBlocksAndRequests() {
        val convs = listOf(dm("a"), dm("b", blockedByMe = true), dm("c", blockedByThem = true), dm("d", request = true),
            Conversation(id = "g1", users = listOf(me, "e"), convType = "group"))
        assertEquals(setOf("a"), StoryRules.contacts(me, convs))
        assertEquals(setOf("a") to "all", StoryRules.resolveAudience(me, convs, StoryAudienceChoice.MY_CHATS, emptySet()))
        assertEquals(setOf("a") to "everyone", StoryRules.resolveAudience(me, convs, StoryAudienceChoice.EVERYONE, emptySet()))
        // Picked people outside my chats are dropped.
        assertEquals(setOf("a") to "only", StoryRules.resolveAudience(me, convs, StoryAudienceChoice.CUSTOM, setOf("a", "b", "zz")))
    }

    @Test fun payloadHasTheIosFields() {
        val p = StoryRules.payload(me, false, "S1", "hi", StoryAudienceChoice.MY_CHATS, "all", setOf("a"), "EXP", "NOW", "blur")
        assertEquals("image", p["type"])
        assertEquals("stories/S1/photo.jpg", p["mediaPath"])
        assertEquals("", p["mediaUrl"])
        assertEquals("friends", p["audienceLabel"])
        assertEquals(mapOf("mode" to "all", "listId" to "my-story"), p["audience"])
        assertEquals(false, p["public"]); assertEquals(false, p["oneTime"]); assertEquals(true, p["allowsReplies"])
        assertEquals(listOf(StoryAudienceToken.token("a")), p["recipientUids"])
        assertEquals("NOW", p["createdAt"]); assertEquals("EXP", p["expiresAt"])
        assertNull(p["thumbUrl"])
        val v = StoryRules.payload(me, true, "S2", "", StoryAudienceChoice.ONE_TIME, "only", setOf("a"), "E", "N", "",
            StoryRules.VideoInfo(7.5, 720, 1280, 3_000_000))
        assertEquals("video", v["type"]); assertEquals("stories/S2/video.mp4", v["mediaPath"])
        assertEquals("", v["thumbUrl"]); assertEquals(7.5, v["duration"]); assertEquals(1536L * 1024, v["preloadPrefix"])
        assertEquals(true, v["oneTime"]); assertEquals("oneTime", v["audienceLabel"])
        val e = StoryRules.payload(me, false, "S3", "", StoryAudienceChoice.EVERYONE, "everyone", emptySet(), "E", "N", "")
        assertEquals(true, e["public"])
    }

    @Test fun audienceTokenIsSha256OfThePrefixedUid() {
        // sha256("fariin-audience-v1:a")
        val t = StoryAudienceToken.token("a")
        assertEquals(64, t.length)
        assertEquals(t, StoryAudienceToken.token("a"))
        assertFalse(t == StoryAudienceToken.token("b"))
    }

    @Test fun viewerStartsAtFirstUnseen() {
        val g = StoryGroup("bob", "Bob", null, listOf(story("1", 100), story("2", 200), story("3", 300)), lastViewedAtMs = 201)
        assertEquals(2, StoryRules.startIndex(g))
        assertEquals(0, StoryRules.startIndex(g.copy(lastViewedAtMs = null)))
        assertEquals(0, StoryRules.startIndex(g.copy(lastViewedAtMs = 999)))
    }

    @Test fun tapZonesAndMoves() {
        assertTrue(StoryRules.tapGoesBack(100f, 1080f))
        assertFalse(StoryRules.tapGoesBack(400f, 1080f))
        val sizes = listOf(2, 0, 1)
        assertEquals(0 to 1, StoryRules.next(0 to 0, sizes))
        assertEquals(2 to 0, StoryRules.next(0 to 1, sizes))   // the empty ring is skipped
        assertNull(StoryRules.next(2 to 0, sizes))
        assertEquals(0 to 1, StoryRules.previous(2 to 0, sizes))
        assertEquals(0 to 0, StoryRules.previous(0 to 0, sizes))
    }

    @Test fun watermarkOnlyMovesForward() {
        assertTrue(StoryRules.advancesWatermark(null, 5))
        assertTrue(StoryRules.advancesWatermark(4, 5))
        assertFalse(StoryRules.advancesWatermark(6, 5))
    }

    @Test fun summaryCountedFromReceipts() {
        val v = listOf(StoryViewer("a", "A", null, 3, "❤️"), StoryViewer("b", "B", null, 2), StoryViewer("c", "C", null, 1, ""),
            StoryViewer("d", "D", null, 0))
        val s = StoryViewSummary.counted(v)
        assertEquals(4, s.count); assertEquals(1, s.reactionCount); assertEquals(listOf("a", "b", "c"), s.recent.map { it.uid })
    }

    @Test fun whoPrefersMyChatThenGroupThenSomeone() {
        val convs = listOf(
            Conversation(id = "a_me", users = listOf("a", me), names = mapOf("a" to "Amina")),
            Conversation(id = "g", users = listOf(me, "z"), convType = "group", names = mapOf("z" to "Zed")),
        )
        assertEquals("Amina", StoryService.who("a", convs, me).first)
        assertEquals("Zed", StoryService.who("z", convs, me).first)
        assertEquals("Someone", StoryService.who("q", convs, me).first)
    }

    @Test fun parseReadsTheNewFields() {
        val ts = com.google.firebase.Timestamp(java.util.Date(2_000_000))
        val s = StoryList.parse("x", mapOf(
            "authorUid" to "bob", "mediaUrl" to "m", "expiresAt" to ts, "createdAt" to ts, "type" to "video",
            "caption" to "hey", "audienceLabel" to "custom", "oneTime" to true, "allowsReplies" to false,
            "duration" to 9.0, "recipientUids" to listOf("t1", "t2"), "public" to true,
        ))!!
        assertTrue(s.isVideo); assertEquals("hey", s.caption); assertEquals("custom", s.audienceLabel)
        assertTrue(s.oneTime); assertFalse(s.allowsReplies); assertEquals(2, s.recipientsLeft); assertTrue(s.isPublic)
        assertEquals(9000L, s.showMs)
        assertEquals(Story.PHOTO_MS, s.copy(isVideo = false).showMs)
    }
}
