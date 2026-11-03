package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerRulesTest {
    private val me = "me"

    private fun photo(id: String, author: String = me) =
        Message(id = id, authorId = author, text = "", type = "image", createdAtMillis = 1000.0, imageUrl = "https://x/$id")

    private fun album(id: String, author: String = me) = Message(
        id = id, authorId = author, text = "", type = "album", createdAtMillis = 2000.0,
        album = listOf(
            Message.AlbumItem("https://x/a0", null, null, null),
            Message.AlbumItem("https://x/p1", null, null, null, videoUrl = "https://x/v1"),
            Message.AlbumItem("https://x/a2", null, null, null),
            Message.AlbumItem("https://x/a3", null, null, null),
        ),
    )

    @Test fun singlePhotoOpensAlone() {
        val (pages, start) = ViewerRules.chatPages(photo("m1"))
        assertEquals(listOf("m1"), pages.map { it.key })
        assertEquals(0, start)
        assertFalse(pages[0].isVideo)
    }

    @Test fun albumPhotoPagesThroughTheAlbumsPhotosInOrderSkippingVideos() {
        val (pages, start) = ViewerRules.chatPages(album("al"), albumIndex = 2)
        assertEquals(listOf("al-0", "al-2", "al-3"), pages.map { it.key })
        assertEquals(1, start)
        assertTrue(pages.all { it.albumParent == "al" })
    }

    @Test fun albumVideoOpensAlone() {
        val (pages, start) = ViewerRules.chatPages(album("al"), albumIndex = 1)
        assertEquals(listOf("al-1"), pages.map { it.key })
        assertTrue(pages[0].isVideo)
        assertEquals("https://x/v1", pages[0].url)
        assertEquals("https://x/p1", pages[0].posterUrl)
        assertEquals(0, start)
    }

    @Test fun allMediaPagesPhotosInGridOrderVideosAlone() {
        val items = listOf(
            SharedMedia.Item("c", "c", 3.0, "https://x/c", null),
            SharedMedia.Item("b", "b", 2.0, "https://x/b", null, videoUrl = "https://x/bv"),
            SharedMedia.Item("a", "a", 1.0, "https://x/a", null),
        )
        val (pages, start) = ViewerRules.sharedPages(items, items[2])
        assertEquals(listOf("c", "a"), pages.map { it.key })
        assertEquals(1, start)
        assertEquals(listOf("b"), ViewerRules.sharedPages(items, items[1]).first.map { it.key })
    }

    @Test fun myPhotoInChatHasShareAndDelete() {
        val p = ViewerRules.chatPages(photo("m1")).first
        val b = ViewerRules.buttons(p[0], p, me, chatCanDelete = true)
        assertTrue(b.share); assertTrue(b.barDelete); assertFalse(b.barSave)
        assertTrue(b.menuSave); assertTrue(b.menuDelete); assertEquals(0, b.saveAll)
    }

    @Test fun receivedPhotoHasShareAndSave() {
        val p = ViewerRules.chatPages(photo("m1", author = "them")).first
        val b = ViewerRules.buttons(p[0], p, me, chatCanDelete = true)
        assertTrue(b.share); assertFalse(b.barDelete); assertTrue(b.barSave); assertTrue(b.menuDelete)
    }

    @Test fun viewOnceHasNoShareAndNoSave() {
        val m = photo("m1", author = "them").copy(type = "image", viewOnce = true)
        val p = ViewerRules.chatPages(m, once = "cid" to "m1").first
        val b = ViewerRules.buttons(p[0], p, me, chatCanDelete = true)
        assertFalse(b.share); assertFalse(b.barSave); assertFalse(b.menuSave); assertEquals(0, b.saveAll)
        assertTrue(b.menuDelete)
    }

    @Test fun albumPageOffersSaveAllAndNoSingleTileDelete() {
        val p = ViewerRules.chatPages(album("al", author = "them"), albumIndex = 0).first
        val b = ViewerRules.buttons(p[0], p, me, chatCanDelete = true)
        assertEquals(3, b.saveAll)
        assertFalse(b.menuDelete); assertFalse(b.barDelete); assertTrue(b.barSave)
    }

    @Test fun mineWithoutChatDeleteKeepsSave() {
        val p = ViewerRules.chatPages(photo("m1")).first
        val b = ViewerRules.buttons(p[0], p, me, chatCanDelete = false)
        assertFalse(b.barDelete); assertTrue(b.barSave); assertFalse(b.menuDelete)
    }

    @Test fun titleIsYouOrTheName() {
        val mine = ViewerRules.chatPages(photo("m1")).first[0]
        val theirs = ViewerRules.chatPages(photo("m2", author = "them")).first[0]
        assertEquals("You", ViewerRules.title(mine, me) { "Bobby" })
        assertEquals("Bobby", ViewerRules.title(theirs, me) { "Bobby" })
    }

    @Test fun clockLabels() {
        assertEquals("00:07", ViewerRules.clock(7.4))
        assertEquals("01:05", ViewerRules.clock(65.0))
        assertEquals("1:00:01", ViewerRules.clock(3601.0))
    }
}
