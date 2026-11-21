package com.fariin.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The origin field's wire rules, shared with iOS MediaOrigin (2026-11-15 spec). */
class MediaOriginTest {
    @Test fun keyMatchesTheSharedFormula() {
        // base64url(SHA-256("fariin-origin|" + path)), first 32, no padding (computed independently).
        assertEquals("z4G3gR9dOD8s_5pFfFwyXlYLTYu6W6sG", MediaOrigin.key("chat/abc_def/m1.mp4.enc"))
    }

    @Test fun storagePathFromDownloadUrl() {
        val url = "https://firebasestorage.googleapis.com/v0/b/x.appspot.com/o/chat%2Fabc_def%2Fm1.mp4.enc?alt=media&token=t"
        assertEquals("chat/abc_def/m1.mp4.enc", MediaOrigin.storagePath(url))
        assertNull(MediaOrigin.storagePath("https://example.com/o/chat%2Fa"))
        assertNull(MediaOrigin.storagePath(null))
        assertEquals("chat/grp1/m1.mp4.enc", MediaOrigin.storagePath("r2:chat/grp1/m1.mp4.enc"))
        assertNull(MediaOrigin.storagePath("r2:other/x"))
    }

    @Test fun payloadRoundTrip() {
        val k = MediaOrigin.key("chat/a/b.jpg")
        assertEquals(MediaOrigin.Claim(k, 5482113L), MediaOrigin.parse(MediaOrigin.payload(k, 5482113L)))
        assertEquals(MediaOrigin.Claim(k, null), MediaOrigin.parse(MediaOrigin.payload(k, null)))
        assertEquals(k, MediaOrigin.payload(k, 0L))
    }

    @Test fun malformedIsNoOrigin() {
        val k = MediaOrigin.key("chat/a/b.jpg")
        for (bad in listOf("", "short", "$k.", "$k.0", "$k.-5", "$k.+5", "$k.1.2", "$k.12a", "$k.1234567890123456",
            k.dropLast(1) + "!", "…", "🔒")) {
            assertNull(bad, MediaOrigin.parse(bad))
        }
    }

    @Test fun sealedValueOpensThroughTheChatSeal() {
        val k = MediaOrigin.key("chat/a/b.jpg")
        assertEquals(MediaOrigin.Claim(k, 10L), MediaOrigin.open("enc1:xyz") { "$k.10" })
        assertNull(MediaOrigin.open("enc1:xyz") { "…" })
        assertNull(MediaOrigin.open("plain words") { "$k.10" })
        assertEquals(MediaOrigin.Claim(k, null), MediaOrigin.open(k) { error("not sealed") })
    }
}
