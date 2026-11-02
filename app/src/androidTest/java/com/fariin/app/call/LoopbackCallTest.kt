package com.fariin.app.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two peer connections in one process, a caller and a callee, talking only through the iOS
 * shapes: the create doc's offerEnc, the answer fields' answerEnc and sealed candidate docs
 * (sealed here with a stand-in that marks the text, to keep the test about the signalling shape;
 * the real enc1 sealing is covered by CallWireTest). Proves the WebRTC build loads on the device
 * and that an offer/answer/candidates exchange through CallWire reaches ICE connected.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackCallTest {
    private val seal = { t: String -> "enc1:" + android.util.Base64.encodeToString(t.toByteArray(), android.util.Base64.NO_WRAP) }
    private val open = { e: String -> String(android.util.Base64.decode(e.removePrefix("enc1:"), android.util.Base64.NO_WRAP)) }

    private class Side(val name: String) : RtcEngine.Listener {
        val candidateDocs = ArrayList<Map<String, Any>>()
        val connected = CompletableDeferred<Unit>()
        lateinit var seal: (String) -> String
        override fun onLocalCandidate(c: Candidate) { synchronized(candidateDocs) { candidateDocs += CallWire.sealedCandidateDoc(seal(CallWire.candidateJson(c))) } }
        override fun onIceState(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) connected.complete(Unit)
        }
        override fun onRemoteVideo(track: VideoTrack) {}
        override fun onAcceptMessage() {}
    }

    @Test fun offerAnswerCandidatesThroughTheCallDoc() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val a = Side("caller").also { it.seal = seal }
        val b = Side("callee").also { it.seal = seal }
        val caller = RtcEngine(ctx, a); val callee = RtcEngine(ctx, b)
        assertTrue(caller.open(emptyList(), relayOnly = false, isCaller = true, micOn = false, cameraOn = false))
        assertTrue(callee.open(emptyList(), relayOnly = false, isCaller = false, micOn = false, cameraOn = false))
        try {
            val offer = caller.createOffer()
            caller.setLocal(SessionDescription.Type.OFFER, offer)
            val create = CallWire.createFields("alice", "bob", "Alice", null, false, seal(offer), false, "TS")
            val doc = CallWire.parse("loop", create)
            assertEquals("ringing", doc.status)
            callee.setRemote(SessionDescription.Type.OFFER, open(doc.offerEnc!!))
            val answer = callee.createAnswer()
            callee.setLocal(SessionDescription.Type.ANSWER, answer)
            val answered = CallWire.parse("loop", create + CallWire.answerFields("bob", seal(answer), false))
            assertEquals("active", answered.status)
            caller.setRemote(SessionDescription.Type.ANSWER, open(answered.answerEnc!!))
            // Trade candidates as documents, both ways, until both report connected.
            withTimeout(20_000) {
                var ia = 0; var ib = 0
                while (!(a.connected.isCompleted && b.connected.isCompleted)) {
                    synchronized(a.candidateDocs) { while (ia < a.candidateDocs.size) { (CallWire.parseCandidateDoc(a.candidateDocs[ia++]) { open(it) } as? CandidateRead.Ok)?.let { callee.addCandidate(it.candidate) } } }
                    synchronized(b.candidateDocs) { while (ib < b.candidateDocs.size) { (CallWire.parseCandidateDoc(b.candidateDocs[ib++]) { open(it) } as? CandidateRead.Ok)?.let { caller.addCandidate(it.candidate) } } }
                    kotlinx.coroutines.delay(100)
                }
            }
            assertTrue(a.candidateDocs.isNotEmpty() && b.candidateDocs.isNotEmpty())
        } finally {
            caller.close(); callee.close()
        }
    }
}
