package com.fariin.app.data

import com.fariin.app.crypto.EncMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Forwarding somebody else's sticker: opened from the source chat, sealed again for the target. */
class StickerForwardRulesTest {
    private val mine = StickerRef(StickerFavorites.MINE, "s1", "😂", false)
    private val pack = StickerRef("kulu", "k1", "👍", true)
    private val cipherInSource = "sealed-for-source".toByteArray()
    private val clear = "PNG clear bytes".toByteArray()
    private val meta = EncMeta(v = 1, n = "n", k = "k", kn = "kn")
    private val src = Message.ForwardSource("g_source", "m1", "Secret Group Name", null, false, false)

    @Test fun groupTargetUsesGroupSealWithMembers() = runBlocking {
        var gotMembers: List<String>? = null
        var directCalled = false
        val out = StickerForwardRules.sealPng(clear, listOf("a", "b", "c"),
            { _, ms -> gotMembers = ms; "G".toByteArray() to meta },
            { _ -> directCalled = true; "D".toByteArray() to meta })
        assertEquals(listOf("a", "b", "c"), gotMembers)
        assertFalse(directCalled)
        assertArrayEquals("G".toByteArray(), out.first)
        assertSame(meta, out.second)
    }

    @Test fun directTargetUsesConversationSeal() = runBlocking {
        var groupCalled = false
        val out = StickerForwardRules.sealPng(clear, null,
            { _, _ -> groupCalled = true; "G".toByteArray() to meta },
            { _ -> "D".toByteArray() to meta })
        assertFalse(groupCalled)
        assertArrayEquals("D".toByteArray(), out.first)
    }

    @Test fun sealGetsTheDecryptedSourceBytes() = runBlocking {
        var opened = 0
        val png = StickerForwardRules.sourcePng(mine) { opened++; clear }
        assertEquals(1, opened)
        var sealedInput: ByteArray? = null
        StickerForwardRules.sealPng(png!!, null, { _, _ -> fail(); clear to meta }, { p -> sealedInput = p; "D".toByteArray() to meta })
        assertArrayEquals(clear, sealedInput)
        assertFalse(sealedInput!!.contentEquals(cipherInSource))
    }

    @Test fun throwingPngSealIsThrownNeverPlain() = runBlocking {
        try {
            StickerForwardRules.sealPng(clear, listOf("a"), { _, _ -> throw IllegalStateException("no key") }, { _ -> clear to meta })
            fail("a failed seal must be thrown")
        } catch (e: IllegalStateException) {
            assertEquals("no key", e.message)
        }
        // The message written before the seal carries no picture, no enc and no bytes.
        val f = StickerForwardRules.mineFields(mine, 300.0, 200.0)
        assertFalse(f.containsKey("imageUrl"))
        assertFalse(f.containsKey("enc"))
        assertTrue(f.values.none { it is ByteArray })
        assertEquals(true, f["uploading"])
        assertEquals(300.0, f["width"])
        assertEquals("😂", f["emoji"])
    }

    @Test fun throwingFwdSealDropsFwdAndNeverWritesThePlainName() = runBlocking {
        val f = StickerForwardRules.forwardFields(src) { throw IllegalStateException("no key") }
        assertEquals(mapOf<String, Any>("forwarded" to true), f)
        assertFalse(f.containsKey("fwd"))
        assertFalse(f.containsKey("enc"))
        assertTrue(f.values.none { it.toString().contains("Secret Group Name") })
    }

    @Test fun fwdIsTheSealedJson() = runBlocking {
        var input: String? = null
        val f = StickerForwardRules.forwardFields(src) { input = it; "CIPHER" }
        assertEquals(src.json(), input)
        assertEquals("CIPHER", f["fwd"])
        assertEquals(true, f["forwarded"])
    }

    @Test fun plainGroupSealGivesTheMap() = runBlocking {
        val f = StickerForwardRules.forwardFields(src) { it }
        assertEquals(src.map(), f["fwd"])
    }

    @Test fun noSourceGivesOnlyForwarded() = runBlocking {
        assertEquals(mapOf<String, Any>("forwarded" to true), StickerForwardRules.forwardFields(null) { "x" })
    }

    @Test fun packStickerIsCopiedByLinkWithoutReseal() = runBlocking {
        assertFalse(StickerForwardRules.needsSourcePng(pack))
        assertTrue(StickerForwardRules.needsSourcePng(mine))
        assertNull(StickerForwardRules.sourcePng(pack) { fail("a pack sticker is never opened"); clear })
        val f = StickerForwardRules.packFields(pack)
        assertEquals(StickerGifRules.stickerPublicUrl("kulu", "k1"), f["imageUrl"])
        assertEquals("sticker", f["type"])
        assertEquals("kulu", f["stickerPack"])
        assertEquals("k1", f["stickerId"])
        assertEquals(true, f["animated"])
        assertEquals(512.0, f["width"])
        assertEquals("👍", f["emoji"])
        assertFalse(f.containsKey("enc"))
        assertFalse(f.containsKey("uploading"))
    }

    @Test fun noEmojiNoEmojiField() {
        assertFalse(StickerForwardRules.packFields(pack.copy(emoji = "")).containsKey("emoji"))
        assertFalse(StickerForwardRules.mineFields(mine.copy(emoji = ""), 1.0, 1.0).containsKey("emoji"))
    }
}
