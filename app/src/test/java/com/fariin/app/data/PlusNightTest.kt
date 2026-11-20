package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Plus features built 2026-11-20: chat folders, shared wallpaper, avatar video. */
class PlusNightTest {
    private val me = "me"

    private fun direct(id: String, unread: Int = 0, mutedUntil: Double = 0.0) =
        Conversation(id = id, users = listOf(me, "o$id"), unreadCount = mapOf(me to unread), mutedBy = if (mutedUntil > 0) mapOf(me to mutedUntil) else emptyMap())
    private fun group(id: String) = Conversation(id = id, users = listOf(me, "a", "b"), convType = "group")
    private fun channel(id: String) = Conversation(id = id, users = listOf(me), convType = "group", isChannel = true)

    @Test fun cleanNameCollapsesAndCuts() {
        assertEquals("Work Friends", ChatFolder.cleanName("  Work\n\tFriends  "))
        assertEquals("", ChatFolder.cleanName("   \n "))
        assertEquals(ChatFolder.NAME_MAX, ChatFolder.cleanName("x".repeat(80)).length)
        // Never more code points than the server takes, however few characters that is.
        val long = ChatFolder.cleanName("👨‍👩‍👧".repeat(40))
        assertTrue(long.codePointCount(0, long.length) <= ChatFolder.NAME_RAW_MAX)
    }

    @Test fun oldKindsReadAsWhatTheyMeant() {
        val unread = ChatFolder("u", "Unread", "unread").normalized()
        assertEquals("custom", unread.kind)
        assertEquals(listOf("direct", "groups", "channels"), unread.types)
        assertTrue(unread.unreadOnly)
        val mixed = ChatFolder("m", "Mixed", "custom", types = listOf("groups", "unread")).normalized()
        assertEquals(listOf("groups"), mixed.types)
        assertFalse(mixed.unreadOnly)
        assertEquals(listOf("direct"), ChatFolder("d", "1:1", "direct").normalized().types)
    }

    @Test fun membershipRule() {
        val f = ChatFolder("f", "F", "custom", types = listOf("direct"), include = listOf("g1"), exclude = listOf("d2"))
        val now = 1_000.0
        assertTrue(f.contains(direct("d1"), me, now))       // by type
        assertFalse(f.contains(direct("d2"), me, now))      // left out beats the type
        assertTrue(f.contains(group("g1"), me, now))        // picked by hand
        assertFalse(f.contains(group("g2"), me, now))
        assertFalse(f.contains(channel("c1"), me, now))
        // The switches apply to picked chats too.
        val unreadOnly = f.copy(unreadOnly = true)
        assertFalse(unreadOnly.contains(group("g1"), me, now))
        assertTrue(unreadOnly.contains(direct("d1", unread = 2), me, now))
        val noMuted = f.copy(includeMuted = false)
        assertFalse(noMuted.contains(direct("d1", mutedUntil = 5_000.0), me, now))
        assertTrue(noMuted.contains(direct("d1", mutedUntil = 500.0), me, now))   // the mute is over
        assertTrue(ChatFolder.ALL.contains(channel("c1"), me, now))
    }

    @Test fun suggestionsAreTemplatesMatchedByRule() {
        val oneToOne = ChatFolder.SUGGESTIONS.first { it.id == "direct" }
        val made = oneToOne.make()
        assertEquals("custom", made.kind)
        assertTrue(oneToOne.matches(made))
        assertTrue(oneToOne.matches(made.copy(name = "Renamed")))          // renaming keeps it hidden
        assertFalse(oneToOne.matches(made.copy(include = listOf("x"))))   // a picked chat brings it back
        assertEquals("Only chats with one person", made.summary)
        assertEquals("1 Chat Type, 2 Chats", ChatFolder("x", "X", "custom", types = listOf("groups"), include = listOf("a", "b")).summary)
    }

    @Test fun folderServerShapeRoundTrips() {
        val f = ChatFolder("f1", "Work", "custom", types = listOf("groups"), include = listOf("a"), exclude = listOf("b"), unreadOnly = true, includeMuted = false)
        assertEquals(f, ChatFolder.fromMap(f.toMap()))
        assertEquals(listOf(f), ChatFolder.list(listOf(f.toMap(), mapOf("id" to "", "name" to "bad", "kind" to "custom"))))
        assertNull(ChatFolder.fromMap(mapOf("id" to "z", "name" to "Z", "kind" to "weird")))
    }

    @Test fun sharedWallpaperValueParses() {
        val v = SharedWallpapers.Value.parse(mapOf("w" to "e:wp1795162222118", "by" to "u1", "at" to 1795162222118L, "n" to "N", "k" to "K", "kn" to "KN"))!!
        assertTrue(v.isPhoto)
        assertEquals(v, SharedWallpapers.Value.decode(v.encode()))
        assertNull(SharedWallpapers.Value.parse(mapOf("w" to "", "at" to 1L)))
        assertNull(SharedWallpapers.Value.parse(mapOf("w" to "g:ocean")))      // no at
        assertFalse(SharedWallpapers.Value.parse(mapOf("w" to "a:aurora-green", "by" to "u1", "at" to 5))!!.isPhoto)
    }

    @Test fun avatarVideoParses() {
        assertEquals(AvatarVideo(12, true), AvatarVideo.parse(mapOf("v" to 12L, "on" to true)))
        assertEquals(AvatarVideo(12, false), AvatarVideo.parse(mapOf("v" to 12)))
        assertNull(AvatarVideo.parse(mapOf("v" to 0)))
        assertNull(AvatarVideo.parse("x"))
        assertEquals("profiles/u1-video.mp4", AvatarVideo.storagePath("u1"))
    }
}
