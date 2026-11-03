package com.fariin.app.local

import com.fariin.app.CrashReports
import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.Conversation
import com.fariin.app.data.Message
import com.fariin.app.data.Presence
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoresTest {

    @Test fun messageRoundTripKeepsEveryField() {
        val enc = EncMeta(1, "n", "k", "kn", mapOf("a" to "w.n"), "a")
        val m = Message(
            id = "m1", authorId = "alice", text = "hello", type = "image", createdAtMillis = 1_700_000_000_123.0,
            hasServerTime = false, clientTs = 1_700_000_000_000.0, clientId = "C1",
            replyTo = Message.ReplyRef("m0", "bob", "earlier"), deleted = false, deletedBy = "bob", edited = true,
            forwarded = true, held = true, imageUrl = "https://x/y", videoUrl = "v", audioUrl = "a", fileUrl = "f",
            fileName = "doc.pdf", enc = enc, width = 640.0, height = 480.0, blurhash = "LKO2", thumb = "b64",
            thumbUrl = "t", thumbEnc = enc.copy(w = null, a = null), duration = 3.5, waveform = listOf(1, 50, 100),
            uploading = false, viewOnce = true,
            album = listOf(Message.AlbumItem("u1", enc, 10.0, 20.0), Message.AlbumItem("u2", null, null, null)),
            reactions = mapOf("bob" to "❤️"),
        )
        val back = LocalJson.decodeMessages(LocalJson.encodeMessages(listOf(m)))
        assertEquals(listOf(m), back)
    }

    @Test fun drawnLiveFieldsRoundTrip() {
        val enc = EncMeta(1, "n", "k", "kn", mapOf("a" to "w.n"), "a")
        val call = Message(id = "c", authorId = "bob", text = "", type = "call", createdAtMillis = 1.0,
            callerUid = "bob", callOutcome = "missed", callVideo = true, callDuration = 62,
            callJoinedUids = listOf("bob"), callRungUids = listOf("alice", "carol"))
        val rest = Message(id = "s", authorId = "bob", text = "hi @alice", createdAtMillis = 2.0,
            mentions = listOf("alice"), views = 7, reactionCounts = mapOf("👍" to 3),
            pollClosed = true, pollCounts = mapOf(0 to 2, 1 to 5), pollVoters = 7, pollTallyAt = 3.5,
            stickerPack = "p", stickerId = "i", stickerEmoji = "😀", stickerAnimated = true,
            linkPreview = Message.LinkPreview("https://x", "T", "D", "https://x/i", enc))
        assertEquals(listOf(call, rest), LocalJson.decodeMessages(LocalJson.encodeMessages(listOf(call, rest))))
    }

    @Test fun minimalMessageRoundTrip() {
        val m = Message(id = "x", authorId = "a", text = "", createdAtMillis = 5.0)
        assertEquals(m, LocalJson.decodeMessage(LocalJson.encodeMessage(m)))
    }

    @Test fun windowKeepsOnlyServerRowsNewestCap() {
        val rows = (1..80).map { Message(id = "m$it", authorId = "a", text = "t$it", createdAtMillis = it.toDouble()) } +
            Message(id = "p", authorId = "me", text = "unsent", createdAtMillis = 99.0, sendState = Message.SendState.SENDING) +
            Message(id = "f", authorId = "me", text = "", createdAtMillis = 100.0, localPath = "/data/x.jpg")
        val kept = LocalJson.windowToKeep(rows, 60)
        assertEquals(60, kept.size)
        assertEquals("m21", kept.first().id)
        assertEquals("m80", kept.last().id)
    }

    @Test fun chatDocsRoundTripKeepTimestampsAndTypes() {
        val ts = Timestamp(1_700_000_123L, 456_000_000)
        val doc = mapOf<String, Any?>(
            "users" to listOf("alice", "bobby"), "updatedAt" to ts, "lastMessage" to "enc1:abc",
            "lastSender" to "alice", "unreadCount" to mapOf("bobby" to 3L), "accepted" to true,
            "pinOrder" to mapOf("alice" to 2.5), "names" to mapOf("alice" to "Alice"), "nothing" to null,
        )
        val back = LocalJson.decodeChats(LocalJson.encodeChats(listOf("alice_bobby" to doc)))
        assertEquals(1, back.size)
        val (id, d) = back[0]
        assertEquals("alice_bobby", id)
        assertEquals(ts, d["updatedAt"])
        assertEquals(3L, (d["unreadCount"] as Map<*, *>)["bobby"])
        val a = Conversation.fromFirestore(id, doc)
        val b = Conversation.fromFirestore(id, d)
        assertEquals(a, b)
    }

    @Test fun sendQueueCodecAndRefusals() {
        val e = SendQueue.Entry("C1", "alice_bobby", "hi there", 12.0, "m0", "bobby", "quoted")
        val f = SendQueue.Entry("C2", "g1", "plain", 13.0)
        assertEquals(listOf(e, f), SendQueue.decode(SendQueue.encode(listOf(e, f))))
        assertTrue(SendQueue.isPermanent(IllegalArgumentException("too long")))
        assertFalse(SendQueue.isPermanent(IllegalStateException("offline")))
    }

    @Test fun draftsCodecDropsEmpty() {
        val m = mapOf("a_b" to "half a thought", "c" to "line one\nline two")
        assertEquals(m, Drafts.decode(Drafts.encode(m)))
        assertEquals(emptyMap<String, String>(), Drafts.decode("{\"x\":\"\"}"))
    }

    @Test fun presenceFollowsBothAudiences() {
        val now = 10_000_000.0
        assertEquals("online", Presence.line(emptyMap(), emptyMap(), true, true, null, now))
        assertEquals("last seen 5 min. ago", Presence.line(emptyMap(), emptyMap(), true, false, now - 5 * 60_000, now))
        // Mine hidden: I see nobody's (reciprocal), as iOS.
        assertNull(Presence.line(mapOf("lastSeen" to "nobody"), emptyMap(), true, true, null, now))
        assertNull(Presence.line(emptyMap(), emptyMap(), true, true, null, now, legacySwitch = false))
        // Theirs: contacts only lets a contact see it.
        assertNull(Presence.line(emptyMap(), mapOf("lastSeen" to "contacts"), false, true, null, now))
        assertEquals("online", Presence.line(emptyMap(), mapOf("lastSeen" to "contacts"), true, true, null, now))
        assertNull(Presence.line(emptyMap(), mapOf("lastSeen" to "nobody"), true, true, null, now))
        assertNull(Presence.line(emptyMap(), emptyMap(), true, false, null, now))
    }

    @Test fun presenceWritesNoTimeWhenHidden() {
        assertEquals(mapOf("online" to false), Presence.fields(online = true, share = false))
        assertEquals(setOf("online", "lastActive"), Presence.fields(online = true, share = true).keys)
        assertEquals(true, Presence.fields(online = true, share = true)["online"])
        assertFalse(Presence.shares(mapOf("lastSeen" to "nobody")))
        assertTrue(Presence.shares(mapOf("lastSeen" to "contacts")))
    }

    @Test fun relativeTimeWords() {
        val now = 1e12
        assertEquals("30 sec. ago", Presence.relative(now - 30_000, now))
        assertEquals("2 hr. ago", Presence.relative(now - 2 * 3_600_000.0, now))
        assertEquals("1 day ago", Presence.relative(now - 26 * 3_600_000.0, now))
        assertEquals("3 days ago", Presence.relative(now - 3 * 86_400_000.0, now))
        assertEquals("2 wk. ago", Presence.relative(now - 15 * 86_400_000.0, now))
        assertEquals("0 sec. ago", Presence.relative(now + 5_000, now))
    }

    @Test fun crashUserIdIsTheIosHash() {
        assertEquals("02770a3b1b884f58fe4d", CrashReports.hashUid("alice123"))
        assertEquals("", CrashReports.hashUid(""))
    }
}
