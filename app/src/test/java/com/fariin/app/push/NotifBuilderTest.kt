package com.fariin.app.push

import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.KeyDirectory
import com.fariin.app.crypto.MemoryKeyValueStore
import com.fariin.app.crypto.MemorySecretStore
import com.fariin.app.crypto.PeerKeys
import com.fariin.app.crypto.TestSodium
import com.fariin.app.crypto.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifBuilderTest {

    private class Dir : KeyDirectory {
        val keys = HashMap<String, String>()
        override suspend fun fetch(uid: String, serverOnly: Boolean): KeyDirectory.Fetch =
            keys[uid]?.let { KeyDirectory.Fetch.Key(it, emptyList()) } ?: KeyDirectory.Fetch.NoKey
        override suspend fun publishIfChanged(uid: String, b64: String) { keys[uid] = b64 }
    }

    private val dir = Dir()
    private fun device(uid: String) = FariinCrypto(TestSodium.nacl, MemorySecretStore(), PeerKeys(MemoryKeyValueStore()),
        dir, { uid }, scope = CoroutineScope(Dispatchers.Unconfined))

    /** The service's opener: the crypto's decrypt with its "could not open" answers as null. */
    private fun opener(c: FariinCrypto) = { ct: String, cid: String, aid: String ->
        NotifBuilder.usable(if (ct.startsWith(Wire.ENCG1)) c.decryptGroup(ct, aid, cid) else c.decrypt(ct, cid))
    }

    private val noChats: (String) -> ChatInfo? = { null }

    private fun show(d: NotifDecision): NotifSpec {
        assertTrue("expected Show, got $d", d is NotifDecision.Show)
        return (d as NotifDecision.Show).spec
    }

    // ------------------------------------------------------------------ parsing

    @Test fun parsesTheServerKeys() {
        val p = PushData.parse(mapOf(
            "cid" to "a_b", "aid" to "a", "mid" to "m1", "sn" to "Amina", "kind" to "image",
            "ct" to "enc1:x:y", "sp" to "https://x/p.jpg", "ackUid" to "b", "ack" to "tok", "expiresAt" to "123",
        ), "Amina", "📷 Photo")
        assertEquals("a_b", p.cid); assertEquals("a", p.aid); assertEquals("Amina", p.senderName)
        assertFalse(p.isGroup); assertEquals("image", p.kind); assertEquals("enc1:x:y", p.cipher)
        assertEquals("https://x/p.jpg", p.senderPhoto); assertEquals(123L, p.expiresAtMs)
        assertEquals("Amina", p.title); assertEquals("📷 Photo", p.body)
    }

    @Test fun dataOnlyTitleAndBodyAndDefaults() {
        val p = PushData.parse(mapOf("cid" to "g1", "g" to "1", "t" to "Family", "b" to "Amina: New message", "sp" to "http://insecure"))
        assertTrue(p.isGroup); assertEquals("Family", p.title); assertEquals("Amina: New message", p.body)
        assertNull("only https photos", p.senderPhoto)
        val bare = PushData.parse(emptyMap())
        assertEquals("Fariin", bare.title); assertEquals("New message", bare.body); assertEquals("text", bare.kind)
    }

    // ------------------------------------------------------------------ decrypt to notification

    @Test fun oneToOneCipherOpensWithMyKey() = runBlocking {
        val alice = device("alice"); val bob = device("bob")
        alice.ensureReady(); bob.ensureReady(); bob.preloadKey("alice")
        val cid = Wire.directCid("alice", "bob")
        val ct = alice.encryptForConversation(cid, "Nabad, sidee tahay?")
        val p = PushData.parse(mapOf("cid" to cid, "aid" to "alice", "sn" to "Alice", "ct" to ct, "ackUid" to "bob", "ack" to "t"),
            "Alice", "New message")
        val s = show(NotifBuilder.decide(p, "bob", noChats, opener(bob)))
        assertEquals("Alice", s.title); assertEquals("Nabad, sidee tahay?", s.line); assertEquals(s.line, s.body)
        assertFalse(s.plain); assertFalse(s.isGroup); assertEquals(NotifChannel.MESSAGES, s.channel)
        assertTrue(s.opensChat); assertEquals(cid, s.key)
    }

    @Test fun nothingFromSomebodyIBlocked() {
        val none: (String, String, String) -> String? = { _, _, _ -> null }
        val p = PushData.parse(mapOf("cid" to Wire.directCid("alice", "bob"), "aid" to "alice", "sn" to "Alice"), "Alice", "New message")
        assertTrue(NotifBuilder.decide(p, "bob", noChats, none, blocked = { it == "alice" }) is NotifDecision.Skip)
        assertTrue(NotifBuilder.decide(p, "bob", noChats, none, blocked = { it == "carol" }) is NotifDecision.Show)
        // An older push with no `aid`: the other half of the 1:1 id is the sender.
        val old = PushData.parse(mapOf("cid" to Wire.directCid("alice", "bob")), "Alice", "New message")
        assertTrue(NotifBuilder.decide(old, "bob", noChats, none, blocked = { it == "alice" }) is NotifDecision.Skip)
    }

    @Test fun unknownSenderKeyKeepsTheServerHint() = runBlocking {
        val alice = device("alice"); val bob = device("bob")
        alice.ensureReady(); bob.ensureReady()      // Bob never fetched Alice's key
        val cid = Wire.directCid("alice", "bob")
        val ct = alice.encryptForConversation(cid, "secret words")
        val p = PushData.parse(mapOf("cid" to cid, "aid" to "alice", "sn" to "Alice", "ct" to ct), "Alice", "New message")
        val s = show(NotifBuilder.decide(p, "bob", noChats, opener(bob)))
        assertEquals("New message", s.line)
        assertFalse(s.body.contains("…")); assertFalse(s.body.contains("secret"))
    }

    @Test fun tamperedCipherNeverShowsTheLock() = runBlocking {
        val bob = device("bob"); bob.ensureReady()
        val alice = device("alice"); alice.ensureReady(); bob.preloadKey("alice")
        val p = PushData.parse(mapOf("cid" to "alice_bob", "aid" to "alice", "sn" to "Alice", "kind" to "image", "ct" to "enc1:bad:bad"),
            "Alice", "📷 Photo")
        assertEquals("📷 Photo", show(NotifBuilder.decide(p, "bob", noChats, opener(bob))).line)
    }

    @Test fun groupCipherNamesTheGroupAndTheSender() = runBlocking {
        val a = device("alice"); val b = device("bob")
        a.ensureReady(); b.ensureReady(); b.preloadKey("alice")
        val ct = a.encryptForGroup("Shir ayaa jira berri", listOf("alice", "bob"))
        val p = PushData.parse(mapOf("cid" to "grp1", "aid" to "alice", "sn" to "Alice", "g" to "1", "ct" to ct),
            "Family", "Alice: New message")
        val chats = mapOf("grp1" to ChatInfo(true, "Family chat", "https://g/photo.jpg", mapOf("alice" to "fariin-photo://profiles/alice.jpg?v=1")))
        val s = show(NotifBuilder.decide(p, "bob", { chats[it] }, opener(b)))
        assertEquals("Family chat", s.title)          // the name this phone saved wins over the push
        assertEquals("Family chat", s.groupName)
        assertEquals("Shir ayaa jira berri", s.line)
        assertEquals("Alice: Shir ayaa jira berri", s.body)
        assertEquals("fariin-photo://profiles/alice.jpg?v=1", s.senderPhoto)
        assertEquals("https://g/photo.jpg", s.groupPhoto)
        assertTrue(s.isGroup)
    }

    @Test fun groupHintDropsTheNameFromTheLine() {
        val p = PushData.parse(mapOf("cid" to "grp1", "aid" to "alice", "sn" to "Alice", "g" to "1", "kind" to "audio"),
            "Family", "Alice: 🎤 Voice message")
        val s = show(NotifBuilder.decide(p, "bob", noChats, { _, _, _ -> null }))
        assertEquals("Family", s.title); assertEquals("🎤 Voice message", s.line); assertEquals("Alice: 🎤 Voice message", s.body)
    }

    @Test fun plainGroupWordsAndCaptionSign() {
        val p = PushData.parse(mapOf("cid" to "grp2", "aid" to "alice", "sn" to "Alice", "g" to "1", "kind" to "image", "tx" to "Look"),
            "Trip", "Alice: 📷 Look")
        val s = show(NotifBuilder.decide(p, "bob", noChats, { _, _, _ -> null }))
        assertEquals("📷 Look", s.line); assertEquals("Alice: 📷 Look", s.body)
    }

    @Test fun savedContactNameAndPhotoWinIn1to1() {
        val p = PushData.parse(mapOf("cid" to "alice_bob", "aid" to "alice", "sn" to "Alice Server", "sp" to "https://push/p.jpg"),
            "Alice Server", "New message")
        val chats = mapOf("alice_bob" to ChatInfo(false, "Hooyo", "fariin-photo://profiles/alice.jpg?v=2"))
        val s = show(NotifBuilder.decide(p, "bob", { chats[it] }, { _, _, _ -> null }))
        assertEquals("Hooyo", s.title); assertEquals("Hooyo", s.senderName)
        assertEquals("fariin-photo://profiles/alice.jpg?v=2", s.senderPhoto)
        // Not saved: the push's photo.
        val s2 = show(NotifBuilder.decide(p, "bob", noChats, { _, _, _ -> null }))
        assertEquals("Alice Server", s2.title); assertEquals("https://push/p.jpg", s2.senderPhoto)
    }

    // ------------------------------------------------------------------ the rules

    @Test fun previewOffShowsOnlyWhatTheServerSent() {
        val p = PushData.parse(mapOf("cid" to "alice_bob", "aid" to "alice", "ackUid" to "bob"), "Fariin", "New message")
        val s = show(NotifBuilder.decide(p, "bob", { ChatInfo(false, "Hooyo", null) }, { _, _, _ -> "never asked" }))
        assertTrue(s.plain); assertEquals("Fariin", s.title); assertEquals("New message", s.body)
    }

    @Test fun messageRequestCarriesNoCipherAndKeepsTheHint() {
        val p = PushData.parse(mapOf("cid" to "alice_bob", "aid" to "alice", "sn" to "Alice"), "Alice", "New message request")
        assertEquals("New message request", show(NotifBuilder.decide(p, "bob", noChats, { _, _, _ -> "x" })).line)
    }

    @Test fun skips() {
        val p = PushData.parse(mapOf("cid" to "alice_bob", "aid" to "alice", "sn" to "Alice", "ackUid" to "bob"), "Alice", "New message")
        val open = { _: String, _: String, _: String -> null }
        assertTrue(NotifBuilder.decide(p, "", noChats, open) is NotifDecision.Skip)                       // signed out
        assertTrue(NotifBuilder.decide(p, "alice", noChats, open) is NotifDecision.Skip)                  // own message
        assertTrue(NotifBuilder.decide(p, "carol", noChats, open) is NotifDecision.Skip)                  // another account
        assertTrue(NotifBuilder.decide(p, "bob", noChats, open, activeChat = "alice_bob") is NotifDecision.Skip)
        assertTrue(NotifBuilder.decide(p, "bob", noChats, open, pushOn = false) is NotifDecision.Skip)
        assertTrue(NotifBuilder.decide(p, "bob", noChats, open, activeChat = "other") is NotifDecision.Show)
    }

    @Test fun callFallbackGoesToTheCallsChannel() {
        val p = PushData.parse(mapOf("cid" to "alice_bob", "aid" to "alice"), "Alice", "Incoming video call")
        assertEquals(NotifChannel.CALLS, show(NotifBuilder.decide(p, "bob", noChats, { _, _, _ -> null })).channel)
    }

    @Test fun storyHasNoChat() {
        val p = PushData.parse(mapOf("aid" to "alice", "sn" to "Alice", "kind" to "story"), "Alice", "Posted a new story.")
        val chats = mapOf("alice_bob" to ChatInfo(false, "Hooyo", "fariin-photo://profiles/alice.jpg?v=3"))
        val s = show(NotifBuilder.decide(p, "bob", { chats[it] }, { _, _, _ -> null }))
        assertEquals("story-alice", s.key); assertFalse(s.opensChat)
        assertEquals("fariin-photo://profiles/alice.jpg?v=3", s.senderPhoto)
    }

    @Test fun prefixes() {
        assertEquals("📷 ", NotifBuilder.prefix("album")); assertEquals("📹 ", NotifBuilder.prefix("video"))
        assertEquals("📎 ", NotifBuilder.prefix("file")); assertEquals("", NotifBuilder.prefix("text"))
        assertNull(NotifBuilder.usable(Wire.NOT_READY)); assertNull(NotifBuilder.usable(Wire.LOCKED))
        assertNull(NotifBuilder.usable(Wire.OLD_MESSAGE)); assertEquals("hi", NotifBuilder.usable("hi"))
    }
}
