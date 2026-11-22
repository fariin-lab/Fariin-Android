package com.fariin.app.local

import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.MusicRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOutboxTest {

    private val enc = EncMeta(1, "n", "k", "kn", mapOf("bob" to "w.n"), "alice").asMap()

    private val playlist = FileOutbox.Entry(
        cid = "a_b", clientId = "C1", ts = 1_700_000_000_123.0, kind = "playlist",
        paths = listOf("/o/file-C1-t0", "/o/file-C1-t1"), names = listOf("one.mp3", "two.mp3"),
        preview = "/o/fprev-C1.jpg", title = "Road", caption = "for you\nand them",
        renames = listOf("Song" to null, null to "Singer"),
        replyId = "m0", replyAuthor = "bob", replyText = "earlier",
        messageId = "M1", sealed = listOf("/o/seal-C1-0", "/o/seal-C1-1"),
        songs = listOf(MusicRules.SealedSong(enc, "one.mp3", "sn", 10, "st", null),
            MusicRules.SealedSong(EncMeta.PLAIN.asMap(), "two.mp3", null, 20)),
        listNameSealed = "ln", urls = listOf("https://x/0", null),
    )

    @Test fun roundTripKeepsEveryField() {
        val song = FileOutbox.Entry("a_b", "C2", 5.0, "song", listOf("/o/file-C2"), listOf("s.mp3"))
        assertEquals(listOf(playlist, song), FileOutbox.decode(FileOutbox.encode(listOf(playlist, song))))
    }

    @Test fun damagedStoreReadsEmptyAndDamagedEntryIsSkipped() {
        assertEquals(emptyList<FileOutbox.Entry>(), FileOutbox.decode("not json"))
        assertEquals(emptyList<FileOutbox.Entry>(), FileOutbox.decode(null))
        val raw = FileOutbox.encode(listOf(playlist)).replaceFirst("[", "[{\"cid\":\"x\"},")
        assertEquals(listOf(playlist), FileOutbox.decode(raw))
    }

    @Test fun missingSongsAreTheOnesWithoutUrl() {
        assertEquals(listOf(1), MusicRules.songsToUpload(FileOutbox.decode(FileOutbox.encode(listOf(playlist)))[0].urls))
    }

    @Test fun failedOnlyForSendsThisPhoneStarted() {
        val kept = setOf("C1")
        val idle: (String) -> Boolean = { false }
        assertTrue(FileOutbox.failedHere("file", "C1", kept, idle))
        // Sent from another phone of mine: not in this phone's record, keeps uploading.
        assertFalse(FileOutbox.failedHere("file", "C9", kept, idle))
        assertFalse(FileOutbox.failedHere("file", null, kept, idle))
        // Still going in this process.
        assertFalse(FileOutbox.failedHere("file", "C1", kept) { it == "C1" })
        assertFalse(FileOutbox.failedHere("image", "C1", kept, idle))
    }

    @Test fun readableNeedsEveryCopy() {
        val all: (String) -> Long = { 1L }
        assertTrue(FileOutbox.readable(playlist, all))
        assertFalse(FileOutbox.readable(playlist) { if (it.endsWith("seal-C1-1")) 0L else 1L })
        assertFalse(FileOutbox.readable(playlist) { if (it.endsWith("t0")) 0L else 1L })
        // Not written yet: the sealed copies are not needed.
        assertTrue(FileOutbox.readable(playlist.copy(messageId = null, sealed = emptyList())) { if (it.contains("seal")) 0L else 1L })
        assertFalse(FileOutbox.readable(playlist.copy(paths = listOf("/o/a"), names = listOf("a"), sealed = listOf("/o/s")), all))
        assertTrue(FileOutbox.readable(FileOutbox.Entry("c", "C3", 1.0, "file", listOf("/o/f"), listOf("f.pdf")), all))
    }
}
