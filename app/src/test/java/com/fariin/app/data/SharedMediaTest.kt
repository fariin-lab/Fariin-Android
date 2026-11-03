package com.fariin.app.data

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All Media's tabs, links, order and sections against Kulan-iOS MediaGalleryView.makeDerived. */
class SharedMediaTest {
    private val now = ms(2026, 11, 2, 12)
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 10) =
        LocalDateTime.of(y, mo, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli().toDouble()
    private fun msg(id: String, at: Double, type: String? = "text", text: String = "", f: (Message) -> Message = { it }) =
        f(Message(id = id, authorId = "a", text = text, type = type, createdAtMillis = at))

    @Test fun tabsTakeTheirOwnKinds() {
        val all = listOf(
            msg("p", now - 1, "image") { it.copy(imageUrl = "u/p") },
            msg("v", now - 2, "video") { it.copy(videoUrl = "u/v", thumbUrl = "u/vt", duration = 7.0) },
            msg("f", now - 3, "file") { it.copy(fileUrl = "u/f", fileName = "a.pdf") },
            msg("n", now - 4, "audio") { it.copy(audioUrl = "u/n") },
            msg("l", now - 5, text = "look at https://fariin.com/help ok"),
            msg("t", now - 6, text = "just words"),
            msg("sys", now - 7, "system", text = "see fariin.com"),
        )
        val l = SharedMedia.lists(all, now)
        assertEquals(listOf("p", "v"), l.media.map { it.id })
        assertTrue(l.media[1].isVideo)
        assertEquals("u/vt", l.media[1].pictureUrl)
        assertEquals(listOf("f"), l.files.map { it.id })
        assertEquals(listOf("n"), l.voice.map { it.id })
        assertEquals(listOf("l"), l.links.map { it.first.id })
        assertEquals(1, l.photoCount); assertEquals(1, l.videoCount); assertEquals(5, l.total)
    }

    @Test fun leftOut() {
        val all = listOf(
            msg("once", now - 1, "image") { it.copy(imageUrl = "u", viewOnce = true) },
            msg("onceNote", now - 1, "audio") { it.copy(audioUrl = "u", viewOnce = true) },
            msg("gone", now - 1, "image") { it.copy(imageUrl = "u", deleted = true) },
            msg("hid", now - 1, "image") { it.copy(imageUrl = "u") },
            msg("exp", now - 1, "image") { it.copy(imageUrl = "u", expiresAtMillis = now - 10) },
            msg("up", now - 1, "audio") { it.copy(uploading = true) },   // no URL yet
            msg("vidUp", now - 1, "video") { it.copy(thumbUrl = "t") },
        )
        val l = SharedMedia.lists(all, now) { it == "hid" }
        assertEquals(0, l.total)
    }

    @Test fun newestFirstAndAlbumsInTheirOwnOrder() {
        val album = msg("al", now - 5, "album") {
            it.copy(album = listOf(
                Message.AlbumItem("a0", null, null, null),
                Message.AlbumItem("a1", null, null, null, videoUrl = "a1v"),
            ))
        }
        val all = listOf(msg("old", now - 100, "image") { it.copy(imageUrl = "o") }, album,
            msg("new", now - 1, "image") { it.copy(imageUrl = "n") })
        assertEquals(listOf("new", "al-0", "al-1", "old"), SharedMedia.lists(all, now).media.map { it.id })
        assertEquals("al", SharedMedia.lists(all, now).media[1].messageId)
        val vids = SharedMedia.filtered(SharedMedia.lists(all, now).media, SharedMedia.Filter.VIDEOS)
        assertEquals(listOf("al-1"), vids.map { it.id })
        assertEquals(3, SharedMedia.filtered(SharedMedia.lists(all, now).media, SharedMedia.Filter.PHOTOS).size)
    }

    @Test fun links() {
        assertEquals(SharedMedia.Link("https://fariin.com/help", "fariin.com"), SharedMedia.firstLink("see https://fariin.com/help."))
        assertEquals("https://www.example.org/a?b=1", SharedMedia.firstLink("(www.example.org/a?b=1)")?.url)
        assertEquals("example.org", SharedMedia.firstLink("www.example.org")?.host)
        assertEquals(SharedMedia.Link("https://fariin.com", "fariin.com"), SharedMedia.firstLink("go to fariin.com, now"))
        assertEquals("http://x.io/p", SharedMedia.firstLink("first http://x.io/p then https://y.io")?.url)
        assertNull(SharedMedia.firstLink("mail me at bob@fariin.com"))
        assertNull(SharedMedia.firstLink("version 1.2 costs 3.50"))
        assertNull(SharedMedia.firstLink("no link here."))
        assertNull(SharedMedia.firstLink(""))
        // A sealed text that could not be opened is no link.
        assertEquals(0, SharedMedia.lists(listOf(msg("x", now, text = Message.NOT_ENCRYPTED)), now).links.size)
    }

    @Test fun wordsMatchIos() {
        val l = SharedMedia.lists(listOf(msg("p", now, "image") { it.copy(imageUrl = "u") }), now)
        assertEquals("1 photo, 0 videos", SharedMedia.subtitle(SharedMedia.Tab.MEDIA, l))
        assertEquals("0 files", SharedMedia.subtitle(SharedMedia.Tab.FILES, l))
        assertEquals("0 voice messages", SharedMedia.subtitle(SharedMedia.Tab.VOICE, l))
        assertEquals("0 links", SharedMedia.subtitle(SharedMedia.Tab.LINKS, l))
        assertEquals(listOf("Media", "Files", "Voice", "Links"), SharedMedia.Tab.entries.map { it.label })
        assertEquals(listOf("No media", "No files", "No voice messages", "No links"), SharedMedia.Tab.entries.map(SharedMedia::emptyText))
    }

    @Test fun sections() {
        fun item(id: String, at: Double) = SharedMedia.Item(id, id, at, "u", null)
        val items = listOf(item("a", now - 1000), item("b", ms(2026, 11, 1)), item("c", ms(2026, 11, 1, 1)),
            item("d", ms(2026, 6, 3)), item("e", ms(2024, 6, 3)))
        val s = SharedMedia.sections(items, now, ZoneOffset.UTC, Locale.ENGLISH)
        assertEquals(listOf("Today", "Yesterday", "June", "June 2024"), s.map { it.first })
        assertEquals(listOf("b", "c"), s[1].second.map { it.id })
        val month = SharedMedia.sections(listOf(item("m", ms(2026, 11, 2, 1)), item("n", ms(2026, 10, 30))),
            ms(2026, 11, 20), ZoneOffset.UTC, Locale.ENGLISH)
        assertEquals(listOf("This Month", "October"), month.map { it.first })
    }
}
