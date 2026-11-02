package com.fariin.app.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The stateful layer against an in-memory `users/{uid}` directory. */
class FariinCryptoTest {

    /** `users/{uid}` with `publicKey` and `publicKeyHistory`, as Firestore holds them. */
    class FakeDirectory : KeyDirectory {
        val keys = HashMap<String, String>()
        val history = HashMap<String, MutableList<String>>()
        var failing = false
        var publishes = 0
        override suspend fun fetch(uid: String, serverOnly: Boolean): KeyDirectory.Fetch = when {
            failing -> KeyDirectory.Fetch.Failed
            keys[uid] != null -> KeyDirectory.Fetch.Key(keys[uid]!!, history[uid].orEmpty().toList())
            else -> KeyDirectory.Fetch.NoKey
        }
        override suspend fun publishIfChanged(uid: String, b64: String) {
            val old = keys[uid]
            if (old == b64) return
            publishes++
            val h = history.getOrPut(uid) { mutableListOf() }
            for (k in listOfNotNull(old, b64)) if (k !in h) h.add(k)    // arrayUnion
            keys[uid] = b64
        }
    }

    private val dir = FakeDirectory()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun device(uid: String, secrets: SecretStore = MemorySecretStore(), plainGroups: Set<String> = emptySet()) =
        FariinCrypto(TestSodium.nacl, secrets, PeerKeys(MemoryKeyValueStore()), dir, { uid },
            groupIsPlain = { it in plainGroups }, scope = scope)

    private val cid = Wire.directCid("alice", "bob")

    @Test fun oneToOneTextBothWays() = runBlocking {
        val alice = device("alice"); val bob = device("bob")
        alice.ensureReady(); bob.ensureReady()
        assertEquals(alice.myPublicKeyB64, dir.keys["alice"])           // published as b64
        val sealed = alice.encryptForConversation(cid, "Nabad")
        assertTrue(sealed.startsWith("enc1:"))
        assertEquals(Wire.NOT_READY, bob.decrypt(sealed, cid))         // Alice's key not fetched yet
        bob.preloadKey("alice")
        assertEquals("Nabad", bob.decrypt(sealed, cid))
        assertEquals("Nabad", alice.decrypt(sealed, cid))              // own copy, sealed to Bob
        assertEquals("plain", bob.decrypt("plain", cid))
        assertEquals(Wire.OLD_MESSAGE, bob.decrypt("enc:whatever", cid))
        assertEquals(Wire.LOCKED, bob.decrypt("enc1:bad:bad", cid))
    }

    @Test fun missingRecipientKeyNeverLeaksPlaintext() = runBlocking {
        val alice = device("alice")
        try { alice.encryptForConversation(cid, "secret"); fail() } catch (e: MissingRecipientKeyException) {
            assertEquals("bob", e.uid)
        }
        assertNull(alice.encryptForConversationIfCached(cid, "secret"))
    }

    @Test fun keyKeptAcrossRestartAndNotRegenerated() = runBlocking {
        val store = MemorySecretStore()
        val first = device("alice", store); first.ensureReady()
        val pk = first.myPublicKeyB64
        val second = device("alice", store); second.ensureReady()
        assertEquals(pk, second.myPublicKeyB64)
        assertEquals(1, dir.publishes)
        // Stored under the iOS names.
        assertTrue(store.lookup("fariin_secret_key_v1.alice") is SecretStore.Lookup.Found)
        assertTrue(store.lookup("fariin_public_key_v1.alice") is SecretStore.Lookup.Found)
    }

    @Test fun unreadableStoreDoesNotMakeANewKey() = runBlocking {
        val store = MemorySecretStore().apply { failReads = true }
        val c = device("alice", store)
        try { c.ensureReady(); fail() } catch (_: CryptoException) {}
        assertFalse(c.isReady)
        assertNull(dir.keys["alice"])
    }

    @Test fun failedWriteIsNotPublished() = runBlocking {
        val store = MemorySecretStore().apply { failWrites = true }
        val c = device("alice", store)
        try { c.ensureReady(); fail() } catch (_: CryptoException) {}
        assertNull(dir.keys["alice"])
    }

    @Test fun rotatedSenderKeyStillOpensOldMessages() = runBlocking {
        val bobStore = MemorySecretStore()
        val bob = device("bob", bobStore); bob.ensureReady()
        val alice1 = device("alice"); alice1.ensureReady()
        val old = alice1.encryptForConversation(cid, "before")
        bob.preloadKey("alice")
        assertEquals("before", bob.decrypt(old, cid))
        // Alice moves to a new phone: new keypair, republished with the old one in the history.
        val alice2 = device("alice"); alice2.ensureReady()
        assertNotEquals(alice1.myPublicKeyB64, alice2.myPublicKeyB64)
        assertEquals(listOf(alice1.myPublicKeyB64, alice2.myPublicKeyB64), dir.history["alice"])
        val fresh = alice2.encryptForConversation(cid, "after")
        // Bob's copy of Alice's key is stale: "after" fails once, the refetch heals it.
        assertEquals(Wire.LOCKED, bob.decrypt(fresh, cid))
        assertEquals("after", bob.decrypt(fresh, cid))
        assertEquals("before", bob.decrypt(old, cid))                  // via the ring
        // Bob on a fresh install (same identity, empty caches) learns the old key from publicKeyHistory.
        val bob2 = device("bob", bobStore); bob2.ensureReady(); bob2.preloadKey("alice")
        assertEquals("before", bob2.decrypt(old, cid))
        assertEquals("after", bob2.decrypt(fresh, cid))
    }

    @Test fun groupTextAndFiles() = runBlocking {
        val a = device("alice"); val b = device("bob"); val c = device("carol")
        a.ensureReady(); b.ensureReady(); c.ensureReady()
        val members = listOf("alice", "bob", "carol", "dave")         // dave has no key
        val sealed = a.encryptForGroup("Group salaan", members)
        b.preloadKey("alice"); c.preloadKey("alice")
        assertEquals("Group salaan", b.decrypt(sealed, "grp1", "alice"))
        assertEquals("Group salaan", c.decryptGroup(sealed, "alice", "grp1"))
        assertEquals("Group salaan", a.decrypt(sealed, "grp1", "alice"))
        val d = device("dave"); d.ensureReady(); d.preloadKey("alice")
        assertTrue(d.groupEnvelopeSkipsMe(sealed, "grp1"))
        assertEquals(Wire.LOCKED, d.decryptGroup(sealed, "alice", "grp1"))
        // Released key: Bob shares the item key; Dave reads it (groups only).
        val (n, k) = b.ownGroupTextKey(sealed, "alice")!!
        d.noteReleased("grp1", mapOf(n.replace('+', '-').replace('/', '_').trimEnd('=') to k))
        assertFalse(d.groupEnvelopeSkipsMe(sealed, "grp1"))
        assertEquals("Group salaan", d.decryptGroup(sealed, "alice", "grp1"))
        assertTrue(d.noteReleased("alice_bob", mapOf(n to k)).isEmpty())   // never for a 1:1

        val photo = ByteArray(1234) { it.toByte() }
        val (ct, meta) = a.encryptBytesForGroup(photo, members)
        assertArrayEquals(photo, b.decryptBytes("grp1", ct, meta))
        assertArrayEquals(photo, b.decryptBytes("grp1", ct, EncMeta.fromMap(meta.asMap())!!))
    }

    @Test fun oneToOneFilesAndPlainRules() = runBlocking {
        val a = device("alice", plainGroups = setOf("grpPlain")); val b = device("bob")
        a.ensureReady(); b.ensureReady()
        val data = "jpeg bytes".toByteArray()
        val (ct, meta) = a.encryptBytes(cid, data)
        assertArrayEquals(data, b.decryptBytes(cid, ct, meta))
        // A plain meta opens in a group, never in a 1:1.
        assertArrayEquals(data, b.decryptBytes("grpPlain", data, EncMeta.PLAIN))
        assertNull(b.decryptBytes(cid, data, EncMeta.PLAIN))
        assertEquals("as is", a.sealForGroup("as is", listOf("alice", "bob"), "grpPlain"))
        assertEquals(EncMeta.PLAIN, a.sealBytesForGroup(data, listOf("alice", "bob"), "grpPlain").second)
    }

    @Test fun groupSealQueuesWhenKeysUnreadable() = runBlocking {
        val a = device("alice"); a.ensureReady()
        dir.failing = true
        try { a.encryptForGroup("x", listOf("alice", "bob")); fail() } catch (_: GroupKeyReadException) {}
    }

    @Test fun liveSignalAdoptsOlderKey() = runBlocking {
        val bob = device("bob"); bob.ensureReady()
        val aliceOld = device("alice"); aliceOld.ensureReady()
        bob.preloadKey("alice")
        val aliceNew = device("alice"); aliceNew.ensureReady()
        assertTrue(bob.fetchFreshKey("alice") is FariinCrypto.FreshKey.Key)   // Bob now holds the new key
        assertEquals(aliceNew.myPublicKeyB64, bob.peers.currentB64("alice"))
        // A call signal from the phone still using the OLD key: opens via the ring, and that key
        // becomes current so the answer is sealed back to the phone that rang.
        val offer = aliceOld.encryptForConversation(cid, "offer")
        assertEquals("offer", bob.decryptLive(offer, cid))
        assertEquals(aliceOld.myPublicKeyB64, bob.peers.currentB64("alice"))
    }
}
