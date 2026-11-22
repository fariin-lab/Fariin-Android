package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRulesTest {
    private val enc = mapOf("v" to 1L, "n" to "N", "k" to "K", "kn" to "KN")
    /** "enc1:x" opens to "x"; anything else is the lock sentinel. */
    private val open: (String) -> String = { if (it.startsWith("enc1:")) it.removePrefix("enc1:") else "🔒" }

    private fun song(name: String, extra: Map<String, Any> = emptyMap()) =
        mapOf("kind" to "audio", "fileUrl" to "https://x/$name", "enc" to enc, "fileName" to name, "fileSize" to 100L) + extra

    @Test fun playlistReadsEverySongAndItsName() {
        val p = MusicRules.parse("file", listOf(
            song("a.mp3", mapOf("listName" to "enc1:Road trip", "title" to "enc1:First")),
            song("Document.m4a", mapOf("fileNameEnc" to "enc1:b.m4a", "artist" to "enc1:Hodan")),
        ), open)
        assertEquals(2, p.tracks!!.size)
        assertEquals("Road trip", p.listName)
        assertEquals("First", p.tracks!![0].title)
        assertEquals("b.m4a", p.tracks!![1].fileName)
        assertEquals("Hodan", p.tracks!![1].artist)
        assertEquals("https://x/a.mp3", p.tracks!![0].url)
        assertEquals(100L, p.tracks!![0].fileSize)
        assertEquals("N", p.tracks!![0].enc!!.n)
    }

    @Test fun aSealThatDoesNotOpenReadsAsNotRenamed() {
        val p = MusicRules.parse("file", listOf(song("a.mp3", mapOf("listName" to "bad", "title" to "bad")), song("b.mp3")), open)
        assertEquals("", p.listName)
        assertNull(p.tracks!![0].title)
    }

    @Test fun oldPlaylistWithoutListNameHasNull() {
        val p = MusicRules.parse("file", listOf(song("a.mp3"), song("b.mp3")), open)
        assertNull(p.listName)
        assertEquals("Mix", MusicRules.playlistName(p.listName, " Mix "))
        assertNull(MusicRules.caption(true, p.listName, "Mix"))
        assertEquals("hi", MusicRules.caption(true, "", "hi"))
    }

    @Test fun oneAudioItemIsARenamedSong() {
        val p = MusicRules.parse("file", listOf(song("a.mp3", mapOf("title" to "enc1:T", "artist" to "enc1:A"))), open)
        assertNull(p.tracks)
        assertEquals("T", p.songTitle)
        assertEquals("A", p.songArtist)
    }

    @Test fun photosAndOtherTypesAreLeftAlone() {
        assertNull(MusicRules.parse("album", listOf(song("a.mp3"), song("b.mp3")), open).tracks)
        assertNull(MusicRules.parse("file", listOf(mapOf("imageUrl" to "u", "enc" to enc)), open).tracks)
        assertNull(MusicRules.parse("file", "junk", open).tracks)
    }

    @Test fun trackIdsMatchIos() {
        assertEquals("m1", MusicRules.trackId("m1", 0))
        assertEquals("m1-t2", MusicRules.trackId("m1", 2))
        assertEquals("chat/c/m.file.enc", MusicRules.songPath("c", "m", 0))
        assertEquals("chat/c/m.t3.file.enc", MusicRules.songPath("c", "m", 3))
    }

    @Test fun refsForASongAPlaylistAndADocument() {
        assertNull(MusicRules.refs("m", "file", null, "a.pdf", "u", null, 1, null, null))
        val one = MusicRules.refs("m", "file", null, "a.MP3", "u", null, 1, "T", null)!!
        assertEquals(listOf("m"), one.map { it.id })
        assertEquals("T", one[0].title)
        val tracks = listOf(MusicRules.Track("u0", null, "a.mp3", 1), MusicRules.Track("u1", null, "b.mp3", 1))
        assertEquals(listOf("m", "m-t1"), MusicRules.refs("m", "file", tracks, "a.mp3", "u0", null, 1, null, null)!!.map { it.id })
        assertEquals("a", MusicRules.plainTitle("a.mp3"))
    }

    @Test fun clock() {
        assertEquals("0:00", MusicRules.clock(0))
        assertEquals("1:05", MusicRules.clock(65_400))
        assertEquals("1:02:03", MusicRules.clock(3_723_000))
    }

    @Test fun playlistWriteHasTheIosKeys() {
        val songs = listOf(
            MusicRules.SealedSong(enc, "Document.mp3", "enc1:a.mp3", 10, sealedTitle = "enc1:T"),
            MusicRules.SealedSong(enc, "b.mp3", null, 20),
        )
        val msg = MusicRules.playlistMessage("me", songs, "", "enc1:L", 5.0, "CID", "turl", enc, "TS")
        assertEquals(setOf("type", "fileName", "fileSize", "enc", "text", "authorId", "createdAt", "clientTs", "uploading",
            "album", "fileNameEnc", "thumbUrl", "thumbEnc", "clientId"), msg.keys)
        assertEquals("file", msg["type"])
        assertEquals("Document.mp3", msg["fileName"])
        assertEquals(10, msg["fileSize"])
        @Suppress("UNCHECKED_CAST") val items = msg["album"] as List<Map<String, Any>>
        assertEquals(setOf("kind", "enc", "fileName", "fileSize", "fileNameEnc", "title", "listName"), items[0].keys)
        assertEquals(setOf("kind", "enc", "fileName", "fileSize"), items[1].keys)
        assertEquals("audio", items[1]["kind"])
        val done = MusicRules.playlistAttach(songs, listOf("u0", "u1"), "enc1:L", "DEL")
        assertEquals("u0", done["fileUrl"])
        @Suppress("UNCHECKED_CAST") val up = done["album"] as List<Map<String, Any>>
        assertEquals("u1", up[1]["fileUrl"])
        assertTrue("listName" in up[0])
        assertFalse("listName" in up[1])
        assertEquals("🎵 3 songs", MusicRules.playlistPreview(3))
    }

    @Test fun voiceNoteLineNamesTheSender() {
        assertEquals("Voice message · You", MusicRules.noteSubtitle(MusicRules.noteSender(true, false, false, "Hodan", null, "Hodan"), "Hodan"))
        // One-to-one with the other person: the sender is the title, so the line is plain.
        assertEquals("Voice message", MusicRules.noteSubtitle(MusicRules.noteSender(false, false, false, "Hodan", null, "Hodan"), "Hodan"))
        assertEquals("Voice message · Ali", MusicRules.noteSubtitle(MusicRules.noteSender(false, false, true, "Family", "Ali", ""), "Family"))
        assertEquals("Voice message", MusicRules.noteSubtitle(MusicRules.noteSender(false, false, true, "Family", null, ""), "Family"))
        assertEquals("Voice message", MusicRules.noteSubtitle(MusicRules.noteSender(false, true, true, "News", "Ali", ""), "News"))
        assertEquals("Voice message", MusicRules.noteSubtitle("", "Hodan"))
    }
}
