package com.fariin.app.call

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.data.CryptoHub
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.VideoTrack
import java.util.UUID

/** What the call screens draw. */
data class CallUi(
    val status: CallStatus = CallStatus(),
    val callId: String? = null,
    val peerUid: String = "",
    val peerName: String = "",
    val peerPhoto: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    val remoteCameraOn: Boolean = false,
    val remoteMuted: Boolean = false,
    val remoteVideo: VideoTrack? = null,
    val localVideo: VideoTrack? = null,
    /** A sentence to show over the ended screen ("Can't call right now." ...). */
    val notice: String? = null,
)

/**
 * The 1:1 call, after Kulan-iOS CallService: the same documents, the same sealed signalling, the
 * same relay rule and the same chat row, so it interoperates with the iPhone app. One call at a
 * time; everything runs on the main thread (WebRTC callbacks hop over).
 *
 * Left out for now (iOS has them): the early answer before Accept, screen sharing, moving a call
 * into a group room (`moveTo`), call waiting / glare between two crossing dials, hold, the data
 * saver, network-change restarts on a timer, Opus DTX/RED munging and telemetry.
 */
object CallManager {
    private const val TAG = "CallManager"
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui

    /** One id per app run, as iOS deviceClaim: tells my answer from my other phone's. */
    private val deviceClaim = UUID.randomUUID().toString()

    private lateinit var app: Context
    private val signaling by lazy { CallSignaling() }
    private val crypto: FariinCrypto get() = CryptoHub.get(app)
    private val sealer: SignalSealer by lazy { CryptoSealer(crypto) }
    private var engine: RtcEngine? = null

    private val me: String get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private var callId: String? = null
    private var peer = ""
    private val cid get() = CallWire.convId(me, peer)
    private var recordWritten = false
    private var ringRowWritten = false
    private var answerApplied = false
    private val finished = LinkedHashSet<String>()
    private val pendingRemote = ArrayList<Candidate>()
    private val localBuffer = ArrayList<Candidate>()
    private val listeners = ArrayList<ListenerRegistration>()
    private val timers = ArrayList<Job>()
    private var iceServers: List<IceServerSpec>? = null
    private var iceAt = 0L
    private var contact = false
    private var ringtone: Ringtone? = null
    private var offerSdp: String? = null
    private var negotiationVersion = 0
    private var appliedRestart = 0
    private var restartRequestsSeen = 0
    private var restartRequestsSent = 0
    private var lastPeerBeat = 0.0

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    private val status get() = _ui.value.status
    private fun move(e: CallEvent) { _ui.value = _ui.value.copy(status = CallMachine.reduce(status, e)) }
    private fun edit(f: (CallUi) -> CallUi) { _ui.value = f(_ui.value) }

    // ================================================================== outgoing

    /**
     * iOS startCall, after the mic (and camera) permission is settled by the call screen.
     * The doc is written only once the offer is sealed for the callee's current key.
     */
    fun dial(context: Context, uid: String, name: String, photo: String?, video: Boolean) {
        init(context)
        if (uid.isEmpty() || me.isEmpty() || uid == me) return
        if (status.live) return
        resetCallVars()
        peer = uid
        _ui.value = CallUi(peerUid = uid, peerName = name, peerPhoto = photo, cameraOn = video, speaker = video)
        move(CallEvent.Dial(video))
        CallService.start(app, CallService.Mode.CALL, video)
        CallAudio.start(app, speaker = video)
        val id = signaling.newCallId()
        callId = id
        after(CallWire.NO_ANSWER_MS) { if (callId == id && status.phase == CallPhase.OUTGOING) ringOut(id) }
        main.launch {
            val servers = awaitIceServers()
            contact = withTimeoutOrNull(1500) { signaling.isEstablishedContact(cid) } ?: false
            val keyOk = ensurePeerKey(uid)
            if (callId != id || status.phase != CallPhase.OUTGOING) return@launch
            if (CallWire.strangerWithoutRelay(contact, alwaysRelay(), servers)) { fail(id); return@launch }
            if (keyOk != KeyCheck.READY) {
                edit { it.copy(notice = if (keyOk == KeyCheck.NO_KEY)
                    "Can't call right now. Their app hasn't finished setting up. Ask them to open Fariin."
                    else "Can't call right now. Try again.") }
                fail(id); return@launch
            }
            val e = newEngine(servers, isCaller = true, camera = video) ?: run { fail(id); return@launch }
            val offer = try { e.createOffer().also { e.setLocal(SessionDescription.Type.OFFER, it) } }
                catch (x: Exception) { Log.w(TAG, "offer failed", x); fail(id); return@launch }
            val enc = sealer.seal(cid, offer) ?: run { fail(id); return@launch }   // never a plaintext offer
            val my = myProfile()
            val fields = CallWire.createFields(me, uid, my.first, my.second, video, enc, _ui.value.muted, signaling.serverTime)
            val err = signaling.create(id, fields)
            if (err != null) {
                if (callId != id) return@launch
                if (signaling.isPermissionDenied(err)) {
                    // The rules refused (they blocked me, or their privacy says no): it ends like a
                    // call nobody took, and no row goes into their chat (iOS audit M-013).
                    recordWritten = true
                    finish(EndReason.DECLINED, updateRemote = false, byUser = false)
                } else fail(id)
                return@launch
            }
            if (callId != id || status.phase != CallPhase.OUTGOING) {
                // Hung up while the create was in flight: end what now exists.
                signaling.update(id, CallWire.cancelFields(EndReason.MISSED, signaling.serverTime))
                return@launch
            }
            move(CallEvent.DocCreated)
            signaling.writeRingRow(cid, id, me, video)
            ringRowWritten = true
            flushLocalCandidates()
            listeners += signaling.listen(id) { onCallDoc(it) }
            listeners += signaling.listenCandidates(id, CallWire.CALLEE_CANDIDATES) { onRemoteCandidateDoc(it) }
        }
    }

    private fun ringOut(id: String) {
        main.launch {
            when (signaling.callerEnd(id, EndReason.MISSED)) {
                CallWire.CallerEnd.AcceptedMeanwhile -> {}
                CallWire.CallerEnd.AlreadyEnded -> {}
                else -> if (callId == id && status.phase == CallPhase.OUTGOING) finish(EndReason.MISSED, updateRemote = false, byUser = false)
            }
        }
    }

    private fun fail(id: String) {
        if (callId == id && status.live) finish(EndReason.FAILED, updateRemote = true, byUser = false)
    }

    // ================================================================== incoming

    /** From [IncomingCallWatcher]: the rings for me, fresh from the server. */
    fun onRingingDocs(context: Context, docs: List<CallDoc>, createdMs: Map<String, Long>) {
        init(context)
        val now = System.currentTimeMillis()
        val fresh = docs.filter { now - (createdMs[it.id] ?: now) <= CallWire.STALE_RING_MS }
        val newest = HashMap<String, CallDoc>()
        for (d in fresh.sortedBy { createdMs[it.id] ?: now }) {
            newest[d.caller]?.let { old -> if (old.id != callId) signaling.update(old.id, CallWire.endFields(EndReason.MISSED)) }
            newest[d.caller] = d
        }
        for (d in newest.values) ring(d)
    }

    private fun ring(d: CallDoc) {
        if (d.id == callId || d.id in finished || d.accepted) return
        if (!d.answeredDevice.isNullOrEmpty() && d.answeredDevice != deviceClaim) return
        if (d.offerEnc == null) {
            // No plaintext offers are answered (iOS G2): ends quietly as failed.
            finished += d.id
            if (d.plainOffer) main.launch { signaling.calleeEndRing(d.id, CallWire.endFields(EndReason.FAILED), deviceClaim) }
            return
        }
        if (status.live) {
            // Already in a call: busy (iOS endRingIfUnclaimed with busy).
            finished += d.id
            main.launch { signaling.calleeEndRing(d.id, CallWire.endFields(EndReason.BUSY), deviceClaim) }
            return
        }
        finished += d.id
        main.launch {
            if (signaling.screenRefused(me, d.id)) return@launch   // the server's privacy screen said no
            if (status.live) return@launch
            resetCallVars()
            callId = d.id
            peer = d.caller
            _ui.value = CallUi(peerUid = d.caller, peerName = d.callerName ?: "Caller", peerPhoto = d.callerPhoto,
                cameraOn = d.video, speaker = d.video, remoteCameraOn = d.cams[d.caller] ?: d.video)
            move(CallEvent.Ring(d.video))
            signaling.update(d.id, CallWire.ringingFields(signaling.serverTime))   // their "Ringing…"
            startRingtone()
            CallNotifications.showIncoming(app, d.id, _ui.value.peerName, d.video)
            // In front: open the ring screen at once (a full-screen intent only shows a banner then).
            if (appInFront()) try { com.fariin.app.ui.call.CallActivity.startRing(app) } catch (_: Exception) {}
            listeners += signaling.listen(d.id) { onCallDoc(it) }
            after(CallWire.CALLEE_RING_MS) { if (callId == d.id && status.phase == CallPhase.INCOMING) finish(EndReason.MISSED, true, byUser = false) }
            // Warm what Accept needs: relay list, contact check, the caller's key, the offer.
            awaitIceServers()
            contact = withTimeoutOrNull(1500) { signaling.isEstablishedContact(cid) } ?: false
            withTimeoutOrNull(3000) { crypto.preloadKey(d.caller) }
            offerSdp = sealer.open(cid, d.offerEnc)
        }
    }

    /** Accept on this phone (the incoming screen or the notification). */
    fun accept() {
        val id = callId ?: return
        if (status.phase != CallPhase.INCOMING) return
        stopRingtone()
        CallNotifications.cancelIncoming(app)
        move(CallEvent.Accept)
        CallService.start(app, CallService.Mode.CALL, _ui.value.cameraOn)
        CallAudio.start(app, speaker = _ui.value.speaker)
        signaling.update(id, CallWire.acceptFields(signaling.serverTime))
        after(30_000) { if (callId == id && status.connectedAtMs == null && status.live) finish(EndReason.FAILED, true, byUser = false) }
        main.launch {
            when (signaling.claimAnswer(id, deviceClaim)) {
                "taken" -> { standDown(); return@launch }
                "cancelled", "ended" -> { finish(EndReason.MISSED, updateRemote = false, byUser = false); return@launch }
            }
            var sdp = offerSdp
            var tries = 0
            while (sdp == null && tries < 4 && callId == id) {
                val d = signaling.readServer(id)?.let { CallWire.parse(id, it) }
                withTimeoutOrNull(3000) { crypto.preloadKey(peer) }
                sdp = sealer.open(cid, d?.offerEnc)
                if (sdp == null) delay(1000)
                tries++
            }
            if (callId != id || !status.live) return@launch
            if (sdp == null) { finish(EndReason.FAILED, true, byUser = false); return@launch }
            val servers = awaitIceServers()
            if (CallWire.strangerWithoutRelay(contact, alwaysRelay(), servers)) { finish(EndReason.FAILED, true, byUser = false); return@launch }
            val e = newEngine(servers, isCaller = false, camera = _ui.value.cameraOn) ?: run { finish(EndReason.FAILED, true, false); return@launch }
            try {
                e.setRemote(SessionDescription.Type.OFFER, sdp)
                flushPendingRemote()
                val answer = e.createAnswer()
                e.setLocal(SessionDescription.Type.ANSWER, answer)
                val enc = sealWithRetry(answer) ?: run { finish(EndReason.FAILED, true, false); return@launch }
                signaling.update(id, CallWire.answerFields(me, enc, _ui.value.cameraOn))
            } catch (x: Exception) {
                Log.w(TAG, "answer failed", x); finish(EndReason.FAILED, true, false); return@launch
            }
            listeners += signaling.listenCandidates(id, CallWire.CALLER_CANDIDATES) { onRemoteCandidateDoc(it) }
            flushLocalCandidates()
        }
    }

    fun decline() {
        if (status.phase == CallPhase.INCOMING) finish(EndReason.DECLINED, updateRemote = true, byUser = true)
    }

    /** Another of my devices answered: this one stops ringing, no record (iOS standDownAnsweredElsewhere). */
    private fun standDown() {
        recordWritten = true
        finish(EndReason.HANGUP, updateRemote = false, byUser = false, quiet = true)
    }

    // ================================================================== the doc, both sides

    private fun onCallDoc(d: CallDoc) {
        if (d.id != callId || !status.live) return
        val s = status
        if (d.ended) {
            val reason = EndReason.from(d.endReason)
            if (!s.isCaller && s.phase == CallPhase.INCOMING) {
                // The ring is over (they cancelled, or another device took it): no "missed" row from
                // here when my other device answered.
                if (d.accepted || (!d.answeredDevice.isNullOrEmpty() && d.answeredDevice != deviceClaim)) recordWritten = true
            }
            if (s.isCaller && d.refused && s.connectedAtMs == null) recordWritten = true
            remoteEnded(reason)
            return
        }
        if (!s.isCaller && s.phase == CallPhase.INCOMING && !d.answeredDevice.isNullOrEmpty() && d.answeredDevice != deviceClaim) {
            standDown(); return
        }
        if (d.moveTo?.startsWith("adhoc_") == true) {
            // TODO: follow the call into its group room (GroupCall.join with roomId). Until then it ends here.
            finish(EndReason.HANGUP, updateRemote = false, byUser = false)
            return
        }
        if (s.isCaller && d.ringing) move(CallEvent.RemoteRinging)
        if (s.isCaller && d.accepted && !s.accepted) {
            move(CallEvent.RemoteAccepted)
            acceptedTimeout()
        }
        if (s.isCaller && !answerApplied && d.answerEnc != null) {
            val sdp = sealer.open(cid, d.answerEnc)
            if (sdp == null) main.launch { withTimeoutOrNull(3000) { crypto.preloadKey(peer) } }
            else {
                answerApplied = true
                main.launch {
                    try {
                        engine?.setRemote(SessionDescription.Type.ANSWER, sdp)
                        flushPendingRemote()
                    } catch (x: Exception) { Log.w(TAG, "answer apply failed", x); finish(EndReason.FAILED, true, false) }
                }
                if (status.phase == CallPhase.OUTGOING) { move(CallEvent.RemoteAccepted); acceptedTimeout() }
            }
        }
        // ICE restarts (iOS restartOffer / restartAnswer / restartRequest).
        if (!s.isCaller) d.restartOffer?.let { ro ->
            if (ro.version > appliedRestart) sealer.open(cid, ro.enc)?.let { sdp ->
                appliedRestart = ro.version
                main.launch {
                    try {
                        val e = engine ?: return@launch
                        e.setRemote(SessionDescription.Type.OFFER, sdp)
                        flushPendingRemote()
                        val ans = e.createAnswer()
                        e.setLocal(SessionDescription.Type.ANSWER, ans)
                        sealWithRetry(ans)?.let { enc -> callId?.let { signaling.update(it, CallWire.restartField("restartAnswer", enc, ro.version)) } }
                    } catch (x: Exception) { Log.w(TAG, "restart answer failed", x); appliedRestart = ro.version - 1 }
                }
            }
        }
        if (s.isCaller) {
            d.restartRequest?.let { rq -> if (rq > restartRequestsSeen) { restartRequestsSeen = rq; restartIce() } }
            d.restartAnswer?.let { ra ->
                if (ra.version == negotiationVersion && ra.version > appliedRestart) sealer.open(cid, ra.enc)?.let { sdp ->
                    appliedRestart = ra.version
                    main.launch { try { engine?.setRemote(SessionDescription.Type.ANSWER, sdp); flushPendingRemote() } catch (_: Exception) {} }
                }
            }
        }
        d.cams[peer]?.let { on -> if (on != _ui.value.remoteCameraOn) edit { it.copy(remoteCameraOn = on) } }
        d.muted[peer]?.let { m -> if (m != _ui.value.remoteMuted) edit { it.copy(remoteMuted = m) } }
        d.heartbeats[peer]?.let { lastPeerBeat = it }
    }

    private fun acceptedTimeout() {
        val id = callId
        after(30_000) { if (callId == id && status.connectedAtMs == null && status.live) finish(EndReason.FAILED, true, false) }
    }

    private fun remoteEnded(reason: EndReason) {
        if (!status.live) return
        finish(reason, updateRemote = false, byUser = false, remote = true)
    }

    // ================================================================== candidates

    private fun onRemoteCandidateDoc(doc: Map<String, Any?>) {
        when (val r = CallWire.parseCandidateDoc(doc) { sealer.open(cid, it) }) {
            is CandidateRead.Ok -> addOrBuffer(r.candidate)
            is CandidateRead.NotYet -> main.launch {
                repeat(4) {
                    withTimeoutOrNull(3000) { crypto.preloadKey(peer) }
                    val again = CallWire.parseCandidateDoc(doc) { sealer.open(cid, it) }
                    if (again is CandidateRead.Ok) { addOrBuffer(again.candidate); return@launch }
                    delay(1000)
                }
            }
            CandidateRead.Bad -> {}
        }
    }

    private fun addOrBuffer(c: Candidate) {
        val remote = engine?.remoteSdp
        if (remote == null || !CallWire.matchesUfrag(c.sdp, remote)) {
            if (pendingRemote.size >= 100) pendingRemote.removeAt(0)
            pendingRemote += c
            return
        }
        engine?.addCandidate(c)
    }

    private fun flushPendingRemote() {
        val remote = engine?.remoteSdp ?: return
        val ready = pendingRemote.filter { CallWire.matchesUfrag(it.sdp, remote) }
        pendingRemote.removeAll(ready.toSet())
        ready.forEach { engine?.addCandidate(it) }
    }

    private fun onLocalCandidate(c: Candidate) {
        val id = callId ?: return
        val docReady = if (status.isCaller) status.callDocCreated else status.accepted
        if (!docReady) { localBuffer += c; return }
        writeCandidate(id, c)
    }

    private fun flushLocalCandidates() {
        val id = callId ?: return
        val all = localBuffer.toList(); localBuffer.clear()
        all.forEach { writeCandidate(id, it) }
    }

    private fun writeCandidate(id: String, c: Candidate) {
        main.launch {
            val enc = sealWithRetry(CallWire.candidateJson(c)) ?: return@launch   // never a plaintext route
            if (callId == id) signaling.writeCandidate(id, CallWire.candidatesOf(status.isCaller), CallWire.sealedCandidateDoc(enc))
        }
    }

    // ================================================================== media

    private fun newEngine(servers: List<IceServerSpec>, isCaller: Boolean, camera: Boolean): RtcEngine? {
        val relayOnly = CallWire.relayOnly(contact, alwaysRelay())
        val e = RtcEngine(app, object : RtcEngine.Listener {
            override fun onLocalCandidate(c: Candidate) { main.launch { this@CallManager.onLocalCandidate(c) } }
            override fun onIceState(state: PeerConnection.IceConnectionState) { main.launch { onIce(state) } }
            override fun onRemoteVideo(track: VideoTrack) { main.launch { edit { it.copy(remoteVideo = track) } } }
            override fun onAcceptMessage() { main.launch { if (status.isCaller && status.phase == CallPhase.OUTGOING) { move(CallEvent.RemoteAccepted); acceptedTimeout() } } }
        })
        if (!e.open(servers.ifEmpty { CallWire.FALLBACK_STUN }, relayOnly, isCaller, micOn = !_ui.value.muted, cameraOn = camera)) return null
        engine = e
        edit { it.copy(localVideo = e.localVideo, frontCamera = e.frontCamera) }
        return e
    }

    private fun onIce(state: PeerConnection.IceConnectionState) {
        val id = callId ?: return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                val first = status.connectedAtMs == null
                move(if (status.phase == CallPhase.RECONNECTING) CallEvent.MediaBack else CallEvent.MediaUp(System.currentTimeMillis()))
                if (first && status.connectedAtMs != null) {
                    if (!status.isCaller) engine?.sendAccept()
                    if (status.isCaller && ringRowWritten) signaling.markOngoing(cid, id)
                    startHeartbeat(id)
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                move(CallEvent.MediaLost)
                after(30_000) { if (callId == id && status.phase == CallPhase.RECONNECTING) finish(EndReason.FAILED, true, false) }
            }
            PeerConnection.IceConnectionState.FAILED -> {
                move(CallEvent.MediaLost)
                if (status.isCaller) restartIce()
                else { restartRequestsSent++; signaling.update(id, mapOf("restartRequest" to restartRequestsSent)) }
                after(30_000) { if (callId == id && status.phase == CallPhase.RECONNECTING) finish(EndReason.FAILED, true, false) }
            }
            else -> {}
        }
    }

    /** The caller's ICE restart: a fresh offer as restartOffer {enc, version}. */
    private fun restartIce() {
        val id = callId ?: return
        val e = engine ?: return
        main.launch {
            try {
                e.restartIce()
                val offer = e.createOffer()
                e.setLocal(SessionDescription.Type.OFFER, offer)
                negotiationVersion = maxOf(negotiationVersion, appliedRestart) + 1
                sealWithRetry(offer)?.let { enc -> if (callId == id) signaling.update(id, CallWire.restartField("restartOffer", enc, negotiationVersion)) }
            } catch (x: Exception) { Log.w(TAG, "restart failed", x) }
        }
    }

    private fun startHeartbeat(id: String) {
        timers += main.launch {
            while (callId == id && status.live) {
                signaling.update(id, CallWire.heartbeatField(me, System.currentTimeMillis()))
                delay(CallWire.HEARTBEAT_MS)
            }
        }
    }

    // ================================================================== buttons

    fun toggleMute() {
        val m = !_ui.value.muted
        edit { it.copy(muted = m) }
        engine?.setMic(!m)
        callId?.let { if (status.callDocCreated) signaling.update(it, CallWire.mutedField(me, m)) }
    }

    fun toggleSpeaker() {
        val s = !_ui.value.speaker
        edit { it.copy(speaker = s) }
        if (::app.isInitialized) CallAudio.setSpeaker(app, s)
    }

    /** The camera button; the call screen asks for the camera permission first. */
    fun toggleCamera() {
        val on = !_ui.value.cameraOn
        edit { it.copy(cameraOn = on) }
        engine?.setCamera(on)
        if (on && ::app.isInitialized) CallService.start(app, CallService.Mode.CALL, true)
        callId?.let { if (status.callDocCreated) signaling.update(it, CallWire.camsField(me, on)) }
    }

    fun flipCamera() {
        engine?.flipCamera()
        edit { it.copy(frontCamera = !it.frontCamera) }
    }

    fun hangUp() { if (status.live) finish(EndReason.NONE, updateRemote = true, byUser = true) }

    fun micDenied() {
        move(CallEvent.MicDenied)
        if (status.live) finish(EndReason.FAILED, updateRemote = true, byUser = false)
    }

    // ================================================================== the end

    /** iOS finishCall: the reason, the chat record, the doc's end, then the teardown. */
    private fun finish(reason: EndReason, updateRemote: Boolean, byUser: Boolean, remote: Boolean = false, quiet: Boolean = false) {
        if (!status.live) return
        val before = status
        move(if (remote) CallEvent.RemoteEnded(reason) else CallEvent.LocalEnd(reason, byUser))
        val s = status
        val id = callId
        val peerUid = peer
        val myUid = me
        val convId = cid
        val connected = before.connectedAtMs != null
        val dur = before.connectedAtMs?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
        val video = before.video || _ui.value.cameraOn
        val outcome = if (!recordWritten && id != null && peerUid.isNotEmpty())
            CallWire.recordOutcome(s.endReason, before.isCaller, before.callDocCreated, connected, before.accepted) else null
        recordWritten = true
        val hadRow = ringRowWritten
        main.launch {
            if (id == null) return@launch
            val caller = if (before.isCaller) myUid else peerUid
            if (outcome != null) signaling.recordCall(myUid, convId, id, caller, outcome, video, dur)
            else if (hadRow) signaling.deleteRingRow(convId, id)
            if (!updateRemote) return@launch
            when {
                before.isCaller && before.callDocCreated && !before.accepted && !connected -> {
                    if (signaling.callerEnd(id, s.endReason) == CallWire.CallerEnd.AcceptedMeanwhile && outcome == "missed")
                        signaling.recordCall(myUid, convId, id, myUid, "answered", video, 0)
                }
                before.isCaller && !before.callDocCreated -> {}   // nothing exists yet; the create path ends it
                !before.isCaller && !before.accepted -> signaling.calleeEndRing(id, CallWire.endFields(s.endReason), deviceClaim)
                else -> signaling.endPlain(id, s.endReason)
            }
        }
        teardown()
        if (quiet) { reset(); return }
        main.launch { delay(1500); if (status.phase == CallPhase.ENDED) reset() }
    }

    private fun teardown() {
        stopRingtone()
        timers.forEach { it.cancel() }; timers.clear()
        listeners.forEach { it.remove() }; listeners.clear()
        engine?.close(); engine = null
        edit { it.copy(remoteVideo = null, localVideo = null) }
        if (::app.isInitialized) {
            CallAudio.stop(app)
            CallNotifications.cancelIncoming(app)
            CallService.stop(app)
        }
    }

    private fun reset() {
        _ui.value = CallUi()
        callId = null
    }

    private fun resetCallVars() {
        recordWritten = false; ringRowWritten = false; answerApplied = false
        pendingRemote.clear(); localBuffer.clear(); offerSdp = null
        negotiationVersion = 0; appliedRestart = 0; restartRequestsSeen = 0; restartRequestsSent = 0
        lastPeerBeat = 0.0
    }

    // ================================================================== helpers

    private fun after(ms: Long, block: () -> Unit) { timers += main.launch { delay(ms); block() } }

    /** iOS awaitIceServers: a warm list returns at once; a cold one waits up to 2 s, then STUN only. */
    private suspend fun awaitIceServers(): List<IceServerSpec> {
        val held = iceServers
        val age = System.currentTimeMillis() - iceAt
        if (held != null && age < 30 * 60_000 && CallWire.hasRelay(held)) return held
        val fresh = withTimeoutOrNull(if (held == null) 6000 else 1000) { signaling.fetchIceServers() }
        if (!fresh.isNullOrEmpty() && (CallWire.hasRelay(fresh) || held == null || !CallWire.hasRelay(held))) {
            iceServers = fresh; iceAt = System.currentTimeMillis()
        }
        return iceServers ?: CallWire.FALLBACK_STUN
    }

    /** Warm the relay list at sign-in (iOS observeIncoming does the same). */
    fun warm() { main.launch { awaitIceServers() } }

    private enum class KeyCheck { READY, NO_KEY, UNAVAILABLE }

    /** iOS ensureSignalKey: the callee's key read fresh (3 s), the cached one after that. */
    private suspend fun ensurePeerKey(uid: String): KeyCheck {
        val r = withTimeoutOrNull(3000) { crypto.fetchFreshKey(uid) }
        return when (r) {
            is FariinCrypto.FreshKey.Key -> KeyCheck.READY
            FariinCrypto.FreshKey.NoKey -> KeyCheck.NO_KEY
            else -> if (withTimeoutOrNull(2000) { crypto.preloadKey(uid) } != null) KeyCheck.READY else KeyCheck.UNAVAILABLE
        }
    }

    private suspend fun sealWithRetry(text: String): String? {
        repeat(4) {
            sealer.seal(cid, text)?.let { return it }
            withTimeoutOrNull(3000) { crypto.preloadKey(peer) }
        }
        return null
    }

    private suspend fun myProfile(): Pair<String?, String?> = try {
        val d = withTimeoutOrNull(2000) { FirebaseFirestore.getInstance().collection("users").document(me).get().await() }
        d?.getString("name") to d?.getString("photoUrl")
    } catch (_: Exception) { null to null }

    /** Privacy > Always Relay Calls (iOS `calls.alwaysRelay`); no switch on Android yet, so off. */
    private fun alwaysRelay(): Boolean =
        app.getSharedPreferences("fariin_calls", Context.MODE_PRIVATE).getBoolean("calls.alwaysRelay", false)

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(app, uri)?.apply { isLooping = true; play() }
        } catch (_: Exception) {}
    }

    private fun appInFront(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun stopRingtone() { try { ringtone?.stop() } catch (_: Exception) {}; ringtone = null }

    // ================================================================== debug demo

    /** Debug builds only: a made-up call on screen, no Firebase, no WebRTC. */
    fun demo(context: Context, phase: CallPhase, video: Boolean) {
        init(context)
        _ui.value = CallUi(
            status = CallStatus(phase = phase, isCaller = phase != CallPhase.INCOMING, video = video, accepted = phase == CallPhase.ACTIVE,
                calleeRinging = true, connectedAtMs = if (phase == CallPhase.ACTIVE) System.currentTimeMillis() - 83_000 else null),
            peerUid = "demo", peerName = "Amina Yusuf", cameraOn = video, remoteCameraOn = video, remoteMuted = phase == CallPhase.ACTIVE && !video,
        )
    }
}
