package com.fariin.app.call

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call document and chat row shapes, field for field against Kulan-iOS CallService/ChatService. */
class CallWireTest {
    private val TS = "SERVER_TS"

    // ------------------------------------------------------------------ create / answer

    @Test fun createHasTheIosFieldsAndNoPlaintextOffer() {
        val f = CallWire.createFields("alice", "bob", "Alice", "https://p/a.jpg", video = true, offerEnc = "enc1:n:b", muted = false, serverTime = TS)
        assertEquals(setOf("caller", "callee", "callerName", "callerPhoto", "type", "status", "cams", "createdAt", "offerEnc", "sig", "caps"), f.keys)
        // The early answer (iOS earlyCap): an iPhone callee may answer during the ring.
        assertEquals(mapOf("alice" to listOf("early1")), f["caps"])
        assertEquals("alice", f["caller"]); assertEquals("bob", f["callee"]); assertEquals("video", f["type"])
        assertEquals("ringing", f["status"]); assertEquals(mapOf("alice" to true), f["cams"]); assertEquals(2, f["sig"])
        assertEquals(TS, f["createdAt"]); assertEquals("enc1:n:b", f["offerEnc"])
        assertFalse("never a plaintext offer", f.containsKey("offer"))
        // The rules refuse a create that carries these (callee-only fields).
        assertFalse(f.keys.any { it in setOf("acceptedAt", "answeredTokenHash", "answeredDevice", "screenedAt") })
    }

    @Test fun createDefaultsAndMuted() {
        val f = CallWire.createFields("a", "b", null, null, video = false, offerEnc = "enc1:x:y", muted = true, serverTime = TS)
        assertEquals("Caller", f["callerName"]); assertEquals("", f["callerPhoto"]); assertEquals("voice", f["type"])
        assertEquals(mapOf("a" to false), f["cams"]); assertEquals(mapOf("a" to true), f["muted"])
    }

    @Test fun answerAndSmallWrites() {
        assertEquals(mapOf("answerEnc" to "enc1:a:b", "cams.bob" to false, "status" to "active"), CallWire.answerFields("bob", "enc1:a:b", false))
        assertEquals(mapOf("acceptedAt" to TS), CallWire.acceptFields(TS))
        assertEquals(mapOf("status" to "ended", "endReason" to "declined"), CallWire.endFields(EndReason.DECLINED))
        assertEquals(mapOf("status" to "ended", "endReason" to "missed", "cancelledAt" to TS), CallWire.cancelFields(EndReason.MISSED, TS))
        assertEquals(mapOf("ringingAt" to TS), CallWire.ringingFields(TS))
        assertEquals(mapOf("muted.me" to true), CallWire.mutedField("me", true))
        assertEquals(mapOf("cams.me" to false), CallWire.camsField("me", false))
        assertEquals(mapOf("hb.me" to 1700000000.5), CallWire.heartbeatField("me", 1_700_000_000_500))
        assertEquals(mapOf("restartOffer" to mapOf("enc" to "e", "version" to 3)), CallWire.restartField("restartOffer", "e", 3))
        assertEquals("callerCandidates", CallWire.candidatesOf(true)); assertEquals("calleeCandidates", CallWire.remoteCandidatesOf(true))
        assertEquals("a_b", CallWire.convId("b", "a"))
    }

    // ------------------------------------------------------------------ reading

    @Test fun parsesAnIosDoc() {
        val d = CallWire.parse("c1", mapOf(
            "caller" to "alice", "callee" to "bob", "callerName" to "Alice", "callerPhoto" to "", "type" to "video",
            "status" to "active", "offerEnc" to "enc1:o:o", "answerEnc" to "enc1:a:a", "ringingAt" to Any(), "acceptedAt" to Any(),
            "answeredDevice" to "dev", "cams" to mapOf("alice" to true, "bob" to false), "muted" to mapOf("bob" to true),
            "hb" to mapOf("alice" to 1700000000.25), "caps" to mapOf("alice" to listOf("screen2", "early1")),
            "restartOffer" to mapOf("enc" to "enc1:r:r", "version" to 2L), "restartRequest" to 1L,
        ))
        assertEquals("alice", d.caller); assertTrue(d.video); assertEquals("active", d.status); assertNull(d.callerPhoto)
        assertTrue(d.ringing); assertTrue(d.accepted); assertEquals("dev", d.answeredDevice)
        assertEquals(true, d.cams["alice"]); assertEquals(true, d.muted["bob"]); assertEquals(1700000000.25, d.heartbeats["alice"]!!, 0.0)
        assertEquals(Versioned("enc1:r:r", 2), d.restartOffer); assertEquals(1, d.restartRequest)
        assertEquals("bob", d.other("alice")); assertFalse(d.ended)
    }

    @Test fun screenShareWritesAndReads() {
        // Sharing: screen, cams and the mode in one write; stopping drops the mode (iOS broadcastScreenState).
        assertEquals(mapOf("screen.me" to true, "cams.me" to true, "screenMode.me" to "camera"), CallWire.screenFields("me", true, true))
        assertEquals(mapOf("screen.me" to false, "cams.me" to false), CallWire.screenFields("me", false, false))
        val d = CallWire.parse("c1", mapOf("caller" to "alice", "callee" to "bob",
            "screen" to mapOf("alice" to true), "screenMode" to mapOf("alice" to "camera", "bob" to 3)))
        assertEquals(true, d.screen["alice"]); assertNull(d.screen["bob"])
        assertEquals("camera", d.screenMode["alice"]); assertNull(d.screenMode["bob"])
        assertTrue(CallWire.parse("c2", mapOf("caller" to "a")).screen.isEmpty())
    }

    @Test fun endReasonFromWire() {
        assertEquals(EndReason.BUSY, EndReason.from("busy"))
        assertEquals(EndReason.HANGUP, EndReason.from(null))
        assertEquals(EndReason.HANGUP, EndReason.from("none"))
        assertEquals(EndReason.HANGUP, EndReason.from("weird"))
    }

    // ------------------------------------------------------------------ candidates

    @Test fun candidateJsonRoundTrip() {
        val c = Candidate("candidate:1 1 udp 2122260223 192.168.1.2 54321 typ host generation 0 ufrag ab/c network-id 1", 0, "0")
        val json = CallWire.candidateJson(c)
        assertEquals("""{"candidate":"candidate:1 1 udp 2122260223 192.168.1.2 54321 typ host generation 0 ufrag ab/c network-id 1","sdpMLineIndex":0,"sdpMid":"0"}""", json)
        val back = CallWire.parseCandidateDoc(mapOf("enc" to "X")) { if (it == "X") json else null }
        assertEquals(CandidateRead.Ok(c), back)
    }

    @Test fun readsIosJsonSerializationOutput() {
        // iOS JSONSerialization escapes "/" and may order keys differently.
        val ios = """{"sdpMid":"audio","sdpMLineIndex":1,"candidate":"candidate:2 1 udp 1 10.0.0.1 9 typ relay raddr 0.0.0.0 rport 0 ufrag A\/b"}"""
        val r = CallWire.parseCandidateDoc(mapOf("enc" to "s")) { ios } as CandidateRead.Ok
        assertEquals("audio", r.candidate.mid); assertEquals(1, r.candidate.mLineIndex)
        assertTrue(r.candidate.sdp.endsWith("ufrag A/b"))
    }

    @Test fun plainOldCandidateAndNotYet() {
        val plain = CallWire.parseCandidateDoc(mapOf("candidate" to "candidate:x", "sdpMLineIndex" to 2L, "sdpMid" to null)) { null }
        assertEquals(CandidateRead.Ok(Candidate("candidate:x", 2, null)), plain)
        assertEquals(CandidateRead.NotYet("enc1:z:z"), CallWire.parseCandidateDoc(mapOf("enc" to "enc1:z:z")) { null })
        assertEquals(CandidateRead.Bad, CallWire.parseCandidateDoc(mapOf("x" to 1)) { null })
        val noMid = CallWire.candidateJson(Candidate("c", 0, null))
        assertEquals("""{"candidate":"c","sdpMLineIndex":0}""", noMid)
    }

    @Test fun ufragMatching() {
        val remote = "v=0\r\na=ice-ufrag:Ab12\r\na=ice-pwd:x\r\n"
        assertTrue(CallWire.matchesUfrag("candidate:1 1 udp 1 1.2.3.4 5 typ host ufrag Ab12", remote))
        assertFalse(CallWire.matchesUfrag("candidate:1 1 udp 1 1.2.3.4 5 typ host ufrag Old9", remote))
        assertTrue("no ufrag on the candidate", CallWire.matchesUfrag("candidate:1 1 udp 1 1.2.3.4 5 typ host", remote))
        assertTrue("no ufrag in the SDP", CallWire.matchesUfrag("candidate:1 ufrag Z", "v=0\r\n"))
    }

    // ------------------------------------------------------------------ the chat row

    @Test fun ringRowAndRecord() {
        assertEquals("call_c1", CallWire.ringRowId("c1"))
        assertEquals(mapOf("type" to "call", "authorId" to "alice", "callerUid" to "alice", "callOutcome" to "ringing",
            "callVideo" to false, "text" to "", "createdAt" to TS), CallWire.ringRowFields("alice", false, TS))
        val mine = CallWire.recordFields("alice", "alice", "answered", true, 83, isNew = false, serverTime = TS)
        assertEquals(83, mine["callDuration"]); assertFalse(mine.containsKey("createdAt"))
        val theirs = CallWire.recordFields("bob", "alice", "missed", false, 0, isNew = true, serverTime = TS)
        assertFalse("only the caller writes the duration", theirs.containsKey("callDuration"))
        assertEquals(TS, theirs["createdAt"]); assertEquals("alice", theirs["authorId"])
        assertTrue(CallWire.recordSkipped("missed", "answered")); assertFalse(CallWire.recordSkipped("answered", "missed"))
        assertEquals("📞 Call", CallWire.marker("answered", false)); assertEquals("📹 Video call", CallWire.marker("answered", true))
        assertEquals("📞 Missed call", CallWire.marker("missed", false)); assertEquals("📹 Missed video call", CallWire.marker("missed", true))
        assertEquals(mapOf("lastMessage" to "📞 Call", "lastSender" to "alice", "updatedAt" to TS), CallWire.convMarkerFields("answered", false, "alice", TS))
    }

    // ------------------------------------------------------------------ ending rules

    @Test fun finalReasonsAsIosFinishCall() {
        fun r(cur: EndReason = EndReason.NONE, caller: Boolean, doc: Boolean = true, conn: Boolean = false, acc: Boolean = false, user: Boolean) =
            CallWire.finalReason(cur, caller, doc, conn, acc, user)
        assertEquals(EndReason.HANGUP, r(caller = true, conn = true, acc = true, user = true))
        assertEquals(EndReason.MISSED, r(caller = true, user = true))                // I cancelled my ring
        assertEquals(EndReason.DECLINED, r(caller = false, user = true))             // I declined
        assertEquals(EndReason.FAILED, r(caller = false, user = false))              // broke while ringing here
        assertEquals(EndReason.FAILED, r(EndReason.MISSED, caller = true, doc = false, user = false)) // never placed
        assertEquals(EndReason.MISSED, r(caller = true, doc = false, user = true))   // cancelled before the doc existed
        assertEquals(EndReason.BUSY, r(EndReason.BUSY, caller = true, user = false))
        assertEquals(EndReason.HANGUP, r(caller = false, acc = true, user = false))
    }

    @Test fun recordOutcomes() {
        assertNull(CallWire.recordOutcome(EndReason.FAILED, isCaller = true, callDocCreated = false, connected = false, accepted = false))
        assertEquals("missed", CallWire.recordOutcome(EndReason.MISSED, true, true, false, false))
        assertEquals("answered", CallWire.recordOutcome(EndReason.HANGUP, true, true, true, true))
        assertEquals("answered", CallWire.recordOutcome(EndReason.FAILED, false, true, false, true))
    }

    @Test fun endTransactions() {
        assertEquals(CallWire.CallerEnd.AlreadyEnded, CallWire.callerEnd(mapOf("status" to "ended")))
        assertEquals(CallWire.CallerEnd.AcceptedMeanwhile, CallWire.callerEnd(mapOf("status" to "ringing", "acceptedAt" to 1)))
        assertEquals(CallWire.CallerEnd.Cancel, CallWire.callerEnd(mapOf("status" to "ringing")))
        assertTrue(CallWire.calleeMayEndRing(mapOf("status" to "ringing"), "me"))
        assertTrue(CallWire.calleeMayEndRing(mapOf("status" to "ringing", "answeredDevice" to "me"), "me"))
        assertFalse(CallWire.calleeMayEndRing(mapOf("status" to "ringing", "answeredDevice" to "other"), "me"))
        assertFalse(CallWire.calleeMayEndRing(mapOf("status" to "active", "acceptedAt" to 1), "me"))
        assertNull(CallWire.claimOutcome(mapOf("status" to "ringing"), "me"))
        assertEquals("taken", CallWire.claimOutcome(mapOf("status" to "active", "answeredDevice" to "x"), "me"))
        assertEquals("cancelled", CallWire.claimOutcome(mapOf("status" to "ended", "cancelledAt" to 1), "me"))
        assertEquals("cancelled", CallWire.claimOutcome(mapOf("status" to "ended", "endReason" to "missed"), "me"))
        assertEquals("ended", CallWire.claimOutcome(mapOf("status" to "ended", "endReason" to "hangup", "acceptedAt" to 1), "me"))
    }

    // ------------------------------------------------------------------ ICE servers and relay

    @Test fun iceServersOnePerUrl() {
        val reply = mapOf("iceServers" to listOf(
            mapOf("urls" to listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")),
            mapOf("urls" to listOf("turn:turn.example:3478?transport=udp", "turns:turn.example:443?transport=tcp"), "username" to "u", "credential" to "p"),
            mapOf("urls" to "turn:one.example:80", "username" to "u2", "credential" to "p2"),
        ))
        val l = CallWire.parseIceServers(reply)
        assertEquals(5, l.size)
        assertEquals(IceServerSpec("turns:turn.example:443?transport=tcp", "u", "p"), l[3])
        assertEquals(IceServerSpec("stun:stun.l.google.com:19302"), l[0])
        assertTrue(CallWire.hasRelay(l)); assertFalse(CallWire.hasRelay(CallWire.FALLBACK_STUN))
        assertTrue(CallWire.parseIceServers(null).isEmpty())
    }

    @Test fun relayPolicyAsIos() {
        assertFalse(CallWire.relayOnly(establishedContact = true, alwaysRelay = false))
        assertTrue(CallWire.relayOnly(establishedContact = false, alwaysRelay = false))
        assertTrue(CallWire.relayOnly(establishedContact = true, alwaysRelay = true))
        assertTrue("a stranger with STUN only is refused", CallWire.strangerWithoutRelay(false, false, CallWire.FALLBACK_STUN))
        assertFalse("a contact may go direct on STUN", CallWire.strangerWithoutRelay(true, false, CallWire.FALLBACK_STUN))
        assertFalse(CallWire.strangerWithoutRelay(false, false, listOf(IceServerSpec("turn:x", "u", "p"))))
    }

    // ------------------------------------------------------------------ sealed signalling, real crypto

    private class Dir : KeyDirectory {
        val keys = HashMap<String, String>()
        override suspend fun fetch(uid: String, serverOnly: Boolean): KeyDirectory.Fetch =
            keys[uid]?.let { KeyDirectory.Fetch.Key(it, emptyList()) } ?: KeyDirectory.Fetch.NoKey
        override suspend fun publishIfChanged(uid: String, b64: String) { keys[uid] = b64 }
    }

    @Test fun offerAnswerAndCandidateSealedBothWays() = runBlocking {
        val dir = Dir()
        fun device(uid: String) = FariinCrypto(TestSodium.nacl, MemorySecretStore(), PeerKeys(MemoryKeyValueStore()), dir, { uid },
            scope = CoroutineScope(Dispatchers.Unconfined))
        val alice = device("alice"); val bob = device("bob")
        alice.ensureReady(); alice.publishPublicKey(); bob.ensureReady(); bob.publishPublicKey()
        val sa = CryptoSealer(alice); val sb = CryptoSealer(bob)
        val cid = CallWire.convId("alice", "bob")
        assertNull("no key in memory yet: no seal, never plaintext", sa.seal(cid, "v=0"))
        alice.preloadKey("bob"); bob.preloadKey("alice")
        val offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=fingerprint:sha-256 AB:CD\r\n"
        val enc = sa.seal(cid, offer)!!
        assertTrue(enc.startsWith(Wire.ENC1))
        val doc = CallWire.parse("c", CallWire.createFields("alice", "bob", "Alice", null, false, enc, false, TS))
        assertEquals(offer, sb.open(cid, doc.offerEnc))
        assertNull("the caller cannot open its own box as the callee would", sb.open(cid, "enc1:bad:bad"))
        assertNull("plain text is never taken as sealed", sb.open(cid, offer))
        val answerEnc = sb.seal(cid, "v=0 answer")!!
        assertEquals("v=0 answer", sa.open(cid, answerEnc))
        val cand = Candidate("candidate:9 1 udp 5 203.0.113.9 3478 typ relay raddr 0.0.0.0 rport 0 ufrag Q", 0, "0")
        val candDoc = CallWire.sealedCandidateDoc(sb.seal(cid, CallWire.candidateJson(cand))!!)
        assertEquals(setOf("enc"), candDoc.keys)   // rules: hasOnly candidate, sdpMLineIndex, sdpMid, enc
        assertEquals(CandidateRead.Ok(cand), CallWire.parseCandidateDoc(candDoc) { sa.open(cid, it) })
        assertNotNull(enc)
    }

    // ------------------------------------------------------------------ group calls

    @Test fun groupPass() {
        assertEquals(mapOf("cid" to "g1", "servers" to listOf("own")), GroupCallWire.tokenRequest("g1"))
        val p = GroupCallWire.parsePass(mapOf("token" to "t", "room" to "group_g1", "url" to "wss://rtc.fariin.com", "role" to "owner"))!!
        assertEquals("wss://rtc.fariin.com", p.url); assertEquals("group_g1", p.room); assertEquals("owner", p.role)
        assertEquals(GroupCallWire.DEFAULT_SERVER, GroupCallWire.parsePass(mapOf("token" to "t", "url" to "wss://evil.example"))!!.url)
        assertNull(GroupCallWire.parsePass(mapOf("url" to "wss://rtc.fariin.com")))
        assertEquals("group_g1", GroupCallWire.roomName("g1"))
        assertTrue(GroupCallWire.isActive(mapOf("active" to true))); assertFalse(GroupCallWire.isActive(null))
        assertEquals("This call is full.", GroupCallWire.joinNotice("full"))
    }
}
