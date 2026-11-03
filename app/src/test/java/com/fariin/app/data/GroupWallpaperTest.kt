package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared group wallpaper rules (iOS WallpaperStore.groupResolved and wallpaper(for:)). */
class GroupWallpaperTest {
    private fun reader(vararg kv: Pair<String, String>): (String) -> String? { val m = mapOf(*kv); return { m[it] } }
    private val none: (String) -> Boolean = { false }

    @Test fun groupWallpaperBeatsMyOwnPickAndTheSettingsOne() {
        val r = reader("wallpaper.__default__" to "g:ocean", "wallpaper.grp" to "g:rose")
        val shared = ChatLookRules.groupWallpaper("g:forest", none)
        assertEquals(ChatWallpaper.Gradient("forest"), ChatLookRules.wallpaper("grp", r, shared))
        // A group whose wallpaper is "none" is plain for everyone, over my own pick.
        assertEquals(ChatWallpaper.None, ChatLookRules.wallpaper("grp", r, ChatLookRules.groupWallpaper("none", none)))
    }

    @Test fun noGroupWallpaperFallsBackToMine() {
        val r = reader("wallpaper.__default__" to "g:ocean", "wallpaper.grp" to "g:rose")
        assertNull(ChatLookRules.groupWallpaper(null, none))
        assertEquals(ChatWallpaper.Gradient("rose"), ChatLookRules.wallpaper("grp", r, null))
        assertEquals(ChatWallpaper.Gradient("ocean"), ChatLookRules.wallpaper("other", r, null))
    }

    @Test fun officialChatNeverHasOne() {
        assertEquals(ChatWallpaper.None, ChatLookRules.wallpaper(OfficialChannel.CID, reader(), ChatWallpaper.Gradient("forest")))
    }

    @Test fun photoWaitsForItsFileThenDraws() {
        val link = "https://firebasestorage.googleapis.com/v0/b/x/o/profiles%2Fgroup_a-wp1.jpg?alt=media"
        val id = ChatLookRules.photoId(link)
        assertTrue(id.startsWith("grp-") && id.length == 36)
        assertEquals(id, ChatLookRules.photoId(link))                     // same on every launch
        // Not on the phone yet: what the chat showed before, or (nothing shown) my own pick.
        assertNull(ChatLookRules.groupWallpaper("u:$link", none))
        assertEquals(ChatWallpaper.Gradient("dusk"), ChatLookRules.groupWallpaper("u:$link", none, ChatWallpaper.Gradient("dusk")))
        assertEquals(ChatWallpaper.Photo(id), ChatLookRules.groupWallpaper("u:$link", { it == id }))
    }

    @Test fun someoneElsesLocalPhotoReadsAsPlain() {
        assertEquals(ChatWallpaper.None, ChatLookRules.groupWallpaper("p:abc", none))
        assertEquals(ChatWallpaper.None, ChatLookRules.groupWallpaper("photo", none))
        assertEquals(ChatWallpaper.Solid(0x123B24), ChatLookRules.groupWallpaper("c:123B24", none))
    }

    @Test fun autoColourFollowsTheGroupWallpaper() {
        val shared = ChatWallpaper.Gradient("g-blaze")
        val r = reader("wallpaper.grp" to "g:ocean")
        assertEquals(shared.pairedColor, ChatLookRules.color("grp", r, shared))
        // My own colour pick still wins: the chat colour stays each phone's own.
        val mine = reader("chatColor.grp" to "s:3B7845")
        assertEquals(ChatColorSpec.parse("s:3B7845"), ChatLookRules.color("grp", mine, shared))
    }

    @Test fun noticeLines() {
        assertEquals("Ana changed the group wallpaper", GroupWallpaper.noticeText("Ana", "g:ocean"))
        assertEquals("Ana removed the group wallpaper", GroupWallpaper.noticeText("Ana", "none"))
    }
}
