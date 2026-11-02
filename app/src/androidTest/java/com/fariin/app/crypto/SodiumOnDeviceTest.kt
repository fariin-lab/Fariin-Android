package com.fariin.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a phone or the emulator: proves libsodium really loads through SodiumAndroid (JNA aar +
 * the .so files), gives the same answers as the JVM unit tests, and that the Keystore-wrapped
 * identity store keeps a key. `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SodiumOnDeviceTest {
    private val nacl = NaCl.android()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun publicKeyMatchesRfc7748() {
        // RFC 7748 section 6.1, Alice.
        val sk = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pk = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertArrayEquals(pk, nacl.boxPublicKey(sk))
    }

    @Test fun boxRoundTrip() {
        val a = nacl.boxKeyPair()
        val b = nacl.boxKeyPair()
        val wire = Wire(nacl)
        val sealed = wire.sealText("Salaan, how are you?", b.publicKey, a.secretKey)
        assertTrue(sealed.startsWith("enc1:"))
        assertEquals("Salaan, how are you?", wire.openText(sealed, listOf(a.publicKey), b.secretKey)?.first)
        // The sender reads their own copy with the recipient's key.
        assertEquals("Salaan, how are you?", wire.openText(sealed, listOf(b.publicKey), a.secretKey)?.first)
        // A stranger cannot.
        assertNull(wire.openText(sealed, listOf(a.publicKey), nacl.boxKeyPair().secretKey))
    }

    @Test fun groupRoundTrip() {
        val me = nacl.boxKeyPair()
        val other = nacl.boxKeyPair()
        val wire = Wire(nacl)
        val keys = mapOf("me" to me.publicKey, "o" to other.publicKey)
        val sealed = wire.sealGroupText("Family dinner at 7", "me", listOf("me", "o"), keys, me.secretKey)
        assertTrue(sealed.startsWith("encg1:"))
        val opened = wire.openGroupText(sealed, "o", listOf(me.publicKey), other.secretKey) { null }
        assertEquals("Family dinner at 7", opened?.first)
    }

    @Test fun keystoreStoreKeepsAKey() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreSecretStore(ctx)
        val name = "selftest_${System.nanoTime()}"
        try {
            assertTrue(store.set(name, "c2VjcmV0"))
            val got = store.lookup(name)
            assertTrue(got is SecretStore.Lookup.Found)
            assertEquals("c2VjcmV0", (got as SecretStore.Lookup.Found).value)
            // A fresh instance (as after an app restart) reads it too.
            assertEquals("c2VjcmV0", (KeystoreSecretStore(ctx).lookup(name) as SecretStore.Lookup.Found).value)
        } finally {
            store.delete(name)
        }
        assertTrue(store.lookup(name) is SecretStore.Lookup.NotFound)
    }

    /** The whole FariinCrypto on device: a key is made once, kept, and two accounts talk. */
    @Test fun twoAccountsTalkThroughFariinCrypto() = runBlocking {
        val dir = object : KeyDirectory {
            val keys = HashMap<String, String>()
            override suspend fun fetch(uid: String, serverOnly: Boolean): KeyDirectory.Fetch =
                keys[uid]?.let { KeyDirectory.Fetch.Key(it, emptyList()) } ?: KeyDirectory.Fetch.NoKey
            override suspend fun publishIfChanged(uid: String, b64: String) { keys[uid] = b64 }
        }
        val a = FariinCrypto(nacl, MemorySecretStore(), PeerKeys(MemoryKeyValueStore()), dir, { "aaa" })
        val b = FariinCrypto(nacl, MemorySecretStore(), PeerKeys(MemoryKeyValueStore()), dir, { "bbb" })
        a.ensureReady(); b.ensureReady()
        // ensureReady publishes in the background; publish again here so the test does not race it.
        a.publishPublicKey(); b.publishPublicKey()
        val cid = Wire.directCid("bbb", "aaa")
        val sealed = a.encryptForConversation(cid, "hello from a")
        assertNotNull(b.preloadKey("aaa"))
        assertEquals("hello from a", b.decrypt(sealed, cid))
        assertEquals("hello from a", a.decrypt(sealed, cid))
    }
}
