package com.fariin.app.crypto

import com.fariin.app.crypto.TestSodium.aliceSk
import com.fariin.app.crypto.TestSodium.alicePk
import com.fariin.app.crypto.TestSodium.bobSk
import com.fariin.app.crypto.TestSodium.bobPk
import com.fariin.app.crypto.TestSodium.hex
import com.fariin.app.crypto.TestSodium.nonce
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Format tests built from the Swift construction (Kulan/Services/Crypto.swift). Each one either
 * rebuilds the iOS bytes independently with raw libsodium calls, or feeds in text shaped exactly
 * as Foundation would write it.
 */
class WireTest {
    private val nacl = TestSodium.nacl
    private val raw = TestSodium.raw
    private val wire = Wire(nacl)

    // ---------------------------------------------------------------- primitives are standard NaCl

    @Test fun x25519MatchesRfc7748() {
        assertArrayEquals(alicePk, nacl.boxPublicKey(aliceSk))
        assertArrayEquals(bobPk, nacl.boxPublicKey(bobSk))
    }

    @Test fun boxBeforenmMatchesNaclVector() {
        // NaCl's published "firstkey" for this pair: HSalsa20(X25519(aliceSk, bobPk), 0).
        val k = ByteArray(32)
        assertEquals(0, raw.crypto_box_beforenm(k, bobPk, aliceSk))
        assertArrayEquals(hex("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389"), k)
    }

    @Test fun boxEasyIsSecretboxWithSharedKey() {
        // crypto_box_easy == crypto_secretbox_easy under beforenm's key, MAC first. This is the
        // layout swift-sodium's box.seal returns as authenticatedCipherText (Crypto.swift:11).
        val msg = "salaan".toByteArray()
        val k = ByteArray(32).also { raw.crypto_box_beforenm(it, bobPk, aliceSk) }
        val viaBox = nacl.boxEasy(msg, nonce, bobPk, aliceSk)!!
        val viaSecretbox = nacl.secretBoxEasy(msg, nonce, k)!!
        assertArrayEquals(viaSecretbox, viaBox)
        assertEquals(msg.size + 16, viaBox.size)
    }

    // ---------------------------------------------------------------- enc1 (1:1 text)

    @Test fun enc1ExactConstruction() {
        val text = "Hello 👋 Soomaali"
        val sealed = wire.sealText(text, bobPk, aliceSk, nonce)
        // Rebuilt the Swift way: "enc1:" + base64(nonce) + ":" + base64(box), standard padded b64.
        val box = ByteArray(text.toByteArray().size + 16)
        raw.crypto_box_easy(box, text.toByteArray(), text.toByteArray().size.toLong(), nonce, bobPk, aliceSk)
        val expected = "enc1:" + java.util.Base64.getEncoder().encodeToString(nonce) + ":" +
            java.util.Base64.getEncoder().encodeToString(box)
        assertEquals(expected, sealed)
        assertTrue(sealed.startsWith("enc1:aWlu6VW2K3PNYr2odfxz1oIZ4ANregs3:"))   // 24-byte nonce = 32 b64 chars
        assertEquals(3, sealed.split(':').size)
        // Bob opens it with Alice's key.
        assertEquals(text to 0, wire.openText(sealed, listOf(alicePk), bobSk))
    }

    @Test fun enc1GoldenVector() {
        // Fixed keys + fixed nonce: this exact string is what Crypto.swift:687 produces for "hi".
        val golden = wire.sealText("hi", bobPk, aliceSk, nonce)
        assertEquals(GOLDEN_HI, golden)
        assertEquals("hi", wire.openText(GOLDEN_HI, listOf(alicePk), bobSk)?.first)
        // Symmetric DH: Alice can read her own sent copy back with Bob's key.
        assertEquals("hi", wire.openText(GOLDEN_HI, listOf(bobPk), aliceSk)?.first)
    }

    @Test fun enc1RejectsWhatSwiftRejects() {
        val ok = wire.sealText("x", bobPk, aliceSk, nonce)
        assertNull(wire.openText(ok + ":extra", listOf(alicePk), bobSk))          // 4 parts
        assertNull(wire.openText(ok.replace("enc1:", "enc1::"), listOf(alicePk), bobSk))
        val urlSafe = ok.replace('+', '-').replace('/', '_')
        if (urlSafe != ok) assertNull(wire.openText(urlSafe, listOf(alicePk), bobSk))
        val padded = wire.sealText("abc", bobPk, aliceSk, nonce)     // 19-byte box -> ends in "=="
        assertTrue(padded.endsWith("=="))
        assertNull(wire.openText(padded.trimEnd('='), listOf(alicePk), bobSk))   // unpadded: rejected
        // Tampered MAC
        val p = ok.split(':')
        val b = B64.decode(p[2])!!.also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(wire.openText("enc1:${p[1]}:${B64.encode(b)}", listOf(alicePk), bobSk))
        // Not UTF-8 (Swift String(bytes:encoding:.utf8) is nil -> lock)
        val bad = nacl.boxEasy(byteArrayOf(0xC3.toByte(), 0x28), nonce, bobPk, aliceSk)!!
        assertNull(wire.openText("enc1:${B64.encode(nonce)}:${B64.encode(bad)}", listOf(alicePk), bobSk))
    }

    @Test fun enc1TriesEarlierKeys() {
        val oldAlice = nacl.boxKeyPair()
        val sealed = wire.sealText("old", bobPk, oldAlice.secretKey)
        val r = wire.openText(sealed, listOf(alicePk, oldAlice.publicKey), bobSk)
        assertEquals("old" to 1, r)
    }

    // ---------------------------------------------------------------- encg1 (group text)

    @Test fun encg1RoundTripAndShape() {
        val carol = nacl.boxKeyPair()
        val keys = mapOf("alice" to alicePk, "bob" to bobPk, "carol" to carol.publicKey)
        val sealed = wire.sealGroupText("kulan", "alice", listOf("alice", "bob", "carol", "dave"), keys, aliceSk)
        assertTrue(sealed.startsWith("encg1:"))
        val json = String(B64.decode(sealed.removePrefix("encg1:"))!!)
        val obj = MiniJson.parse(json) as Map<*, *>
        assertEquals(setOf("n", "c", "w"), obj.keys)
        val w = obj["w"] as Map<*, *>
        assertEquals(setOf("alice", "bob", "carol"), w.keys)         // keyless dave skipped
        for (v in w.values) {
            val parts = (v as String).split('.')
            assertEquals(2, parts.size)
            assertEquals(32 + 16, B64.decode(parts[0])!!.size)        // boxed 32-byte key
            assertEquals(24, B64.decode(parts[1])!!.size)
        }
        assertEquals(24, B64.decode(obj["n"] as String)!!.size)
        assertEquals("kulan", wire.openGroupText(sealed, "bob", listOf(alicePk), bobSk)?.first)
        assertEquals("kulan", wire.openGroupText(sealed, "carol", listOf(alicePk), carol.secretKey)?.first)
        assertEquals("kulan", wire.openGroupText(sealed, "alice", listOf(alicePk), aliceSk)?.first)
        assertNull(wire.openGroupText(sealed, "dave", listOf(alicePk), nacl.boxKeyPair().secretKey))
    }

    @Test fun encg1ParsesFoundationStyleJson() {
        // JSONSerialization escapes '/' as "\/", orders keys as it likes and may add spaces.
        // Build such an envelope by hand from raw libsodium, then open it.
        val msgKey = ByteArray(32) { it.toByte() }
        val dn = ByteArray(24) { (100 + it).toByte() }
        val kn = nonce
        val ct = ByteArray(5 + 16).also { raw.crypto_secretbox_easy(it, "salam".toByteArray(), 5, dn, msgKey) }
        val wrapped = ByteArray(48).also { raw.crypto_box_easy(it, msgKey, 32, kn, bobPk, aliceSk) }
        fun esc(s: String) = s.replace("/", "\\/")
        val wrapStr = esc(B64.encode(wrapped) + "." + B64.encode(kn))
        val json = "{\"w\":{\"bob\":\"$wrapStr\"},\"c\":\"${esc(B64.encode(ct))}\", \"n\" : \"${esc(B64.encode(dn))}\"}"
        val raw = "encg1:" + B64.encode(json.toByteArray())
        assertEquals("salam", wire.openGroupText(raw, "bob", listOf(alicePk), bobSk)?.first)
    }

    @Test fun encg1GoldenVector() {
        val sealed = wire.sealGroupText(
            "hi", "alice", listOf("alice", "bob"), mapOf("alice" to alicePk, "bob" to bobPk), aliceSk,
            msgKey = ByteArray(32) { 7 }, dataNonce = nonce, wrapNonce = { if (it == "alice") ByteArray(24) { 1 } else ByteArray(24) { 2 } },
        )
        assertEquals(GOLDEN_GROUP_HI, sealed)
        assertEquals("hi", wire.openGroupText(GOLDEN_GROUP_HI, "bob", listOf(alicePk), bobSk)?.first)
    }

    @Test fun encg1NeedsSomeoneElse() {
        try {
            wire.sealGroupText("x", "alice", listOf("alice", "bob"), mapOf("alice" to alicePk), aliceSk)
            fail("expected MissingRecipientKeyException")
        } catch (e: MissingRecipientKeyException) { assertEquals("bob", e.uid) }
    }

    @Test fun encg1ReleasedKeyFallback() {
        val key = ByteArray(32) { 9 }
        val sealed = wire.sealGroupText("open", "alice", listOf("alice", "bob"), mapOf("alice" to alicePk, "bob" to bobPk), aliceSk, msgKey = key)
        val env = wire.parseEncg1(sealed)!!
        val stranger = nacl.boxKeyPair()
        assertNull(wire.openGroupText(sealed, "eve", listOf(alicePk), stranger.secretKey))
        assertEquals("open", wire.openGroupText(sealed, "eve", listOf(alicePk), stranger.secretKey) { n -> if (n == env.n) key else null }?.first)
    }

    // ---------------------------------------------------------------- files / EncMeta

    @Test fun fileRoundTripAndMeta() {
        val data = ByteArray(5000) { (it * 31).toByte() }
        val fileKey = ByteArray(32) { 3 }
        val dn = ByteArray(24) { 4 }
        val (ct, meta) = wire.sealFile(data, bobPk, aliceSk, fileKey, dn, nonce)
        assertEquals(data.size + 16, ct.size)                 // Storage holds MAC||ciphertext only
        val expectCt = ByteArray(data.size + 16).also { raw.crypto_secretbox_easy(it, data, data.size.toLong(), dn, fileKey) }
        assertArrayEquals(expectCt, ct)
        assertEquals(1, meta.v)
        assertEquals(B64.encode(dn), meta.n)
        assertEquals(B64.encode(nonce), meta.kn)
        assertEquals(48, B64.decode(meta.k)!!.size)
        assertEquals(mapOf("v" to 1, "n" to meta.n, "k" to meta.k, "kn" to meta.kn), meta.asMap())
        assertArrayEquals(data, wire.openFile(ct, meta, listOf(alicePk), bobSk)!!.first)
        // From Firestore: v comes back as a Long.
        val back = EncMeta.fromMap(mapOf("v" to 1L, "n" to meta.n, "k" to meta.k, "kn" to meta.kn))!!
        assertArrayEquals(data, wire.openFile(ct, back, listOf(alicePk), bobSk)!!.first)
        assertNull(wire.openFile(ct, back.copy(v = 2), listOf(alicePk), bobSk))
    }

    @Test fun groupFileMeta() {
        val data = "photo".toByteArray()
        val (ct, meta) = wire.sealGroupFile(data, "alice", listOf("alice", "bob"), mapOf("alice" to alicePk, "bob" to bobPk), aliceSk)
        assertEquals("", meta.k); assertEquals("", meta.kn); assertEquals("alice", meta.a)
        assertEquals(setOf("alice", "bob"), meta.w!!.keys)
        val m = meta.asMap()
        assertEquals(setOf("v", "n", "k", "kn", "w", "a"), m.keys)
        assertArrayEquals(data, wire.openGroupFile(ct, EncMeta.fromMap(m)!!, "bob", listOf(alicePk), bobSk))
        assertTrue(EncMeta.fromMap(EncMeta.PLAIN.asMap())!!.isPlain)
        assertNull(EncMeta.fromMap(mapOf("v" to 1, "n" to "")))   // Codable needs v, n, k, kn
    }

    // ---------------------------------------------------------------- helpers

    @Test fun cidHelpers() {
        assertEquals("a1_b2", Wire.directCid("b2", "a1"))
        assertEquals("b2", Wire.otherUid("a1_b2", "a1"))
        assertEquals("a1", Wire.otherUid("a1_b2", "b2"))
        assertEquals("", Wire.otherUid("group123", "a1"))
        assertTrue(Wire.isGroupCid("group123")); assertFalse(Wire.isGroupCid("a_b")); assertFalse(Wire.isGroupCid(""))
    }

    @Test fun base64Variants() {
        assertEquals("+/8=", B64.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertNull(B64.decode("-_8="))
        assertNull(B64.decode("+/8"))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), B64.decodeLoose("-_8"))
        assertNotNull(MiniJson.parse("{\"a\":[1,2.5,true,null,\"\\u00e9\"]}"))
        assertNull(MiniJson.parse("{\"a\":1} x"))
    }

    companion object {
        // Computed OUTSIDE this code base: a Python script rebuilding the Swift construction with
        // PyNaCl (Crypto.swift:672-688 and 931-965); keys RFC 7748, nonce from the NaCl box test.
        const val GOLDEN_HI = "enc1:aWlu6VW2K3PNYr2odfxz1oIZ4ANregs3:z0mxd0yMsR0lmRD/5kk9yVj3"
        const val GOLDEN_GROUP_HI = "encg1:eyJuIjoiYVdsdTZWVzJLM1BOWXIyb2RmeHoxb0laNEFOcmVnczMiLCJjIjoiYmNGQUNwTXBiTVlUSFNmMFd4TjV2bmlaIiwidyI6eyJhbGljZSI6InZURkRMZXJPNEZOM2Q1UUV6TGg3RlREOEJMb3lqSUdxRVJRa0l2clloNzVsWjBWTjNDWFVVYXdGdmZjSy9SMmQuQVFFQkFRRUJBUUVCQVFFQkFRRUJBUUVCQVFFQkFRRUIiLCJib2IiOiJKRnB2NEVUNGIzTkVLVjR2OHlBbFpySUlWNVB0UTRpVHAzVUVaZHcvVFdEQURVcFJoVUdkdEdIRzZLQnFnNXNCLkFnSUNBZ0lDQWdJQ0FnSUNBZ0lDQWdJQ0FnSUNBZ0lDIn19"
    }
}
