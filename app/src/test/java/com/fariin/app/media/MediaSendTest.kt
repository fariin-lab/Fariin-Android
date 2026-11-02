package com.fariin.app.media

import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.Message
import com.fariin.app.local.LocalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video, file, album, view-once, forward and auto-download rules, against the iOS writes. */
class MediaSendTest {
    private val meta = EncMeta(1, "n", "k", "kn")
    private val meta2 = EncMeta(1, "n2", "k2", "kn2")
    private val ts = "SERVER_TS"
    private val del = "DELETE"

    @Test fun viewOncePhotoCarriesNoPreviews() {
        val m = MediaMessages.image("me", "u", meta, "", 1.0, "C", 10, 20, "blur", "thumb", ts, viewOnce = true)
        assertEquals(true, m["viewOnce"])
        assertFalse(m.containsKey("blurhash"))
        assertFalse(m.containsKey("thumb"))
        assertFalse(m.containsKey("forwarded"))
        val normal = MediaMessages.image("me", "u", meta, "cap", 1.0, "C", 10, 20, "blur", "thumb", ts, forwarded = true)
        assertEquals("blur", normal["blurhash"]); assertEquals("thumb", normal["thumb"])
        assertEquals(true, normal["forwarded"]); assertFalse(normal.containsKey("viewOnce"))
        assertEquals("cap", normal["text"])
    }

    @Test fun videoAnnounceThenAttachMatchIos() {
        val a = MediaMessages.videoAnnounce("me", "tu", meta, 12.5, 540.0, 960.0, "", 5.0, "C", "b", null, false, ts)
        assertEquals("video", a["type"]); assertEquals("tu", a["thumbUrl"]); assertEquals(meta.asMap(), a["thumbEnc"])
        assertEquals(true, a["uploading"]); assertEquals(12.5, a["duration"]); assertEquals(540.0, a["width"])
        assertFalse(a.containsKey("videoUrl")); assertFalse(a.containsKey("enc")); assertFalse(a.containsKey("thumb"))
        val at = MediaMessages.attachVideo("vu", meta2, del)
        assertEquals(mapOf("videoUrl" to "vu", "enc" to meta2.asMap(), "uploading" to del), at)
    }

    @Test fun fileMessageNameAndSizeInClear() {
        val f = MediaMessages.file("me", "report.pdf", 2048, meta, 1.0, "C", "th", meta2, true, ts)
        assertEquals("file", f["type"]); assertEquals("report.pdf", f["fileName"]); assertEquals(2048, f["fileSize"])
        assertEquals(true, f["uploading"]); assertEquals("th", f["thumbUrl"]); assertEquals(true, f["forwarded"])
        assertEquals("", f["text"])
        assertEquals(mapOf("fileUrl" to "x", "uploading" to del), MediaMessages.attachFile("x", del))
    }

    @Test fun albumPhotosOnlyHasNoKindMixedHasKind() {
        val photos = (0 until 6).map { MediaMessages.Tile("u$it", meta, 100.0, 200.0) }
        val a = MediaMessages.album("me", photos, "", 1.0, "C", null, null, false, ts)
        val items = a["album"] as List<*>
        assertEquals(6, items.size)
        assertFalse((items[0] as Map<*, *>).containsKey("kind"))
        assertEquals("📷 6 Photos", MediaMessages.albumPreview(photos))
        val mixed = photos.take(2) + MediaMessages.Tile("p", meta, 1.0, 1.0, "v", meta2, 3.0)
        val b = MediaMessages.album("me", mixed, "", 1.0, "C", null, null, false, ts)
        val last = (b["album"] as List<*>).last() as Map<*, *>
        assertEquals("video", last["kind"]); assertEquals("v", last["videoUrl"]); assertEquals(meta2.asMap(), last["videoEnc"])
        assertEquals("🎬 3 Media", MediaMessages.albumPreview(mixed))
        assertEquals("🎥 2 Videos", MediaMessages.albumPreview(List(2) { MediaMessages.Tile("p", meta, 1.0, 1.0, "v", meta2, 1.0) }))
        assertEquals(3, MediaMessages.lastImages(photos).size)
    }

    @Test fun convUpdateLastImagesOnlyForMoreThanOne() {
        val one = MediaMessages.convUpdate("me", "x", listOf("b"), ts, 1, "u", meta, listOf(mapOf("imageUrl" to "u")))
        assertFalse(one.containsKey("lastImages"))
        val two = MediaMessages.convUpdate("me", "x", listOf("me", "b", "c"), ts, 1, "u", meta,
            listOf(mapOf("imageUrl" to "u"), mapOf("imageUrl" to "v")))
        assertTrue(two.containsKey("lastImages"))
        assertFalse(two.containsKey("unreadCount.me")); assertTrue(two.containsKey("unreadCount.c"))
    }

    @Test fun forwardedRuleIsIos() {
        assertTrue(MediaMessages.isForward(false, "a_b", "g1"))
        assertFalse(MediaMessages.isForward(false, "a_b", "a_b"))
        assertTrue(MediaMessages.isForward(true, "a_b", "a_b"))
    }

    @Test fun albumLayoutAndChunks() {
        assertEquals(listOf(1, 2), MediaRules.albumRows(3))
        assertEquals(listOf(3, 3), MediaRules.albumRows(6))
        assertEquals(listOf(2, 2, 3, 3), MediaRules.albumRows(10))
        for (n in 1..10) assertEquals(n, MediaRules.albumRows(n).sum())
        val chunks = MediaRules.albumChunks((1..23).toList())
        assertEquals(listOf(10, 10, 3), chunks.map { it.size })
    }

    @Test fun fileLabels() {
        assertEquals("980 bytes", MediaRules.fileSizeLabel(980))
        assertEquals("245 KB", MediaRules.fileSizeLabel(245_000))
        assertEquals("3.4 MB", MediaRules.fileSizeLabel(3_400_000))
        assertEquals("PDF", MediaRules.fileBadge("a.pdf"))
        assertEquals("FILE", MediaRules.fileBadge("README"))
        assertEquals("evil.txt", MediaRules.safeFileName("../../evil.txt"))
        assertEquals("x.pdf", MediaRules.safeFileName("C:\\dir\\x.pdf"))
        assertEquals("File", MediaRules.safeFileName("..."))
        assertEquals("chat/c/m.mp4.enc", MediaRules.videoPath("c", "m"))
        assertEquals("chat/c/m-3-video.enc", MediaRules.albumTilePath("c", "m", "3-video"))
    }

    @Test fun autoDownloadPolicies() {
        assertTrue(AutoDownload.allowed("always", onWifi = false))
        assertFalse(AutoDownload.allowed("wifi", onWifi = false))
        assertTrue(AutoDownload.allowed("wifi", onWifi = true))
        assertFalse(AutoDownload.allowed("never", onWifi = true))
        assertEquals("wifi", AutoDownload.Kind.DOCUMENTS.default)
        assertEquals("always", AutoDownload.Kind.PHOTOS.default)
    }

    @Test fun messageReadsIosAlbumVideoFileAndViewOnce() {
        val data = mapOf<String, Any?>(
            "type" to "album", "authorId" to "b", "text" to "",
            "album" to listOf(
                mapOf("kind" to "image", "imageUrl" to "p", "enc" to meta.asMap(), "width" to 1.0, "height" to 2.0),
                mapOf("kind" to "video", "imageUrl" to "q", "enc" to meta.asMap(), "videoUrl" to "v", "videoEnc" to meta2.asMap(),
                    "duration" to 4.0, "width" to 1.0, "height" to 1.0),
            ),
        )
        val m = Message.fromFirestore("id", data, "a_b") { s, _ -> s }
        assertEquals(Message.MediaKind.ALBUM, m.mediaKind)
        assertFalse(m.album[0].isVideo); assertTrue(m.album[1].isVideo); assertEquals(meta2, m.album[1].videoEnc)
        val f = Message.fromFirestore("f", mapOf("type" to "file", "authorId" to "b", "text" to "", "fileName" to "a.pdf",
            "fileSize" to 1234L, "fileUrl" to "u"), "a_b") { s, _ -> s }
        assertEquals(Message.MediaKind.FILE, f.mediaKind); assertEquals(1234L, f.fileSize)
        val once = Message.fromFirestore("o", mapOf("type" to "image", "authorId" to "b", "text" to "", "viewOnce" to true,
            "consumed" to true), "a_b") { s, _ -> s }
        assertEquals(Message.MediaKind.VIEW_ONCE, once.mediaKind); assertTrue(once.consumed); assertNull(once.imageUrl)
        // The phone's copy keeps the new fields.
        val back = LocalJson.decodeMessage(LocalJson.encodeMessage(m))!!
        assertEquals(m.album, back.album)
        val fb = LocalJson.decodeMessage(LocalJson.encodeMessage(f.copy(consumed = true)))!!
        assertEquals(1234L, fb.fileSize); assertTrue(fb.consumed)
    }
}
