package com.fariin.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
    /** Where the sound goes and what else this phone offers (Telecom, or the speaker switch alone). */
    val route: RouteState = RouteState(),
    /** A phone call was answered over this one: the mic is off until it ends. */
    val held: Boolean = false,
    /** The No answer page is up over the ended call (iOS noAnswerHold). */
    val noAnswer: Boolean = false,
    /** Where MY screen share is (iOS screenSharePhase). */
    val sharePhase: SharePhase = SharePhase.OFF,
    /** THEIR screen is what their video carries (iOS remoteScreenSharing): drawn whole, never cropped. */
    val remoteScreen: Boolean = false,
    /** One short line when my share ended without my Stop, or never got going (iOS screenShareNotice). */
    val shareNotice: String? = null,
) {
    /** My share has begun and owns my video (iOS screenSharing). */
    val sharing: Boolean get() = sharePhase == SharePhase.STARTING || sharePhase == SharePhase.LIVE
    /** A share can be started now: a connected call and nobody else sharing (iOS shareCircle canStart). */
    val canStartShare: Boolean get() = status.phase == CallPhase.ACTIVE && status.connectedAtMs != null && !remoteScreen && !held
}

/**
 * iOS ScreenSharePhase. PICKING: the system's consent dialog is up, nothing in the call has changed.
 * STARTING: the capture runs, no frame yet. LIVE: frames are going out.
 */
enum class SharePhase { OFF, PICKING, STARTING, LIVE }

/**
 * The 1:1 call, after Kulan-iOS CallService: the same documents, the same sealed signalling, the
 * same relay rule and the same chat row, so it interoperates with the iPhone app. One call at a
 * time; everything runs on the main thread (WebRTC callbacks hop over).
 *
 * The system side (headset / car / Bluetooth buttons, audio routes, hold, busy while a phone call
 * can not be held) goes through [CallTelecom]; the proximity sensor follows the route.
 *
 * Screen sharing rides on the camera's own track (iOS fallback mode, `screenMode` "camera"); the
 * iPhone's second screen track is not offered, so an iPhone shares to this phone the same way.
 *
 * The early answer (iOS `earlyCap`, 2026-11-05): the create says `caps.<me>` = ["early1"], so an
 * iPhone callee sends its answer DURING THE RING; it is applied at once and the path comes up while
 * it rings, but the call stays "Ringing…" until the real accept, which then connects straight away.
 * Without it every Android-to-iPhone call sat on "Connecting…" for the answer's trip and the checks
 * after the pick-up (owner's 13:47 call to Africa: 4.7 s).
 *
 * Left out for now (iOS has them): moving a call
 * into a group room (`moveTo`), glare between two crossing dials, the data saver,
 * network-change restarts on a timer, Opus DTX/RED munging and telemetry.
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
    /** The media path came up (it can, during the ring, with the early answer). */
    private var mediaReady = false
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
    private var ringShown: String? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // The screen goes dark at the ear during a voice call on the earpiece.
        main.launch {
            ui.collect { u -> CallProximity.set(app, CallRouting.proximityWanted(u.status.phase, u.cameraOn || u.sharing || u.remoteScreen, u.route.current)) }
        }
        // The calling sound from the dial to the pick-up, and the chime when their camera comes on (iOS).
        main.launch {
            var theirCam = false
            var ringJob: kotlinx.coroutines.Job? = null
            ui.collect { u ->
                // The calling sound waits for the route to settle (owner 2026-11-03 on the Pixel: the
                // first beeps came out loud, as on the speaker, then dropped to the ear). The call's
                // own route is set again first, then the ring starts.
                if (CallToneRules.ringback(u.status)) {
                    if (ringJob == null) ringJob = main.launch {
                        kotlinx.coroutines.delay(RINGBACK_SETTLE_MS)
                        val now = _ui.value
                        if (CallToneRules.ringback(now.status)) {
                            CallAudio.setRoute(app, now.route.current)
                            CallTonePlayer.startRingback()
                        }
                    }
                } else { ringJob?.cancel(); ringJob = null; CallTonePlayer.stopRingback() }
                if (CallToneRules.videoChime(theirCam, u.remoteCameraOn, u.status, u.held, System.currentTimeMillis())) CallTonePlayer.play(CallTone.VIDEO_ON)
                theirCam = u.remoteCameraOn
            }
        }
    }

    private val status get() = _ui.value.status
    private val RINGBACK_SETTLE_MS = 500L
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
        setStartRoute(video)
        CallTelecom.placeOutgoing(app, id, name, video)
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
            when (CallTelecom.reportIncoming(app, d.id, _ui.value.peerName, d.video)) {
                // A phone call in another app that can not be held: busy, as iOS does while CallKit
                // refuses the report.
                CallTelecom.Report.BUSY -> { finish(EndReason.BUSY, updateRemote = true, byUser = false); return@launch }
                // Telecom shows the ring through onShowIncomingCallUi; this is the safety net.
                CallTelecom.Report.STARTED -> after(2500) { showRingUi(d.id) }
                CallTelecom.Report.UNAVAILABLE -> showRingUi(d.id)
            }
            signaling.update(d.id, CallWire.ringingFields(signaling.serverTime))   // their "Ringing…"
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
        CallTelecom.setActive(id)
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
        if (s.isCaller && d.accepted && !s.accepted) remoteAccepted()
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
                // An answer sent during the ring (early1) is not a pick-up: applied so the path comes
                // up while it rings; "Ringing…", the ringback and the no-answer timer stay until the
                // accept (iOS promoteEarlyAnsweredCall).
                val early = !d.accepted && d.status != "active"
                if (!early) remoteAccepted()
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
        // Their screen share, read BEFORE `cams`: the sharer writes both in one update (iOS).
        if (d.screen.isNotEmpty()) {
            val on = d.screen[peer] ?: false
            if (on != _ui.value.remoteScreen) {
                edit { it.copy(remoteScreen = on) }
                // One sharer at a time (iOS, owner 2026-10-10): theirs arriving while mine is only being
                // picked cancels mine. Both live: the share of the lower uid stays, the same on both phones.
                if (on && _ui.value.sharePhase != SharePhase.OFF) {
                    if (!_ui.value.sharing) stopScreenShare(signal = false)
                    else if (me > peer) stopScreenShare(notice = "${_ui.value.peerName} is sharing their screen")
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
            override fun onAcceptMessage() { main.launch { remoteAccepted() } }
        })
        if (!e.open(servers.ifEmpty { CallWire.FALLBACK_STUN }, relayOnly, isCaller, micOn = !_ui.value.muted, cameraOn = camera)) return null
        engine = e
        edit { it.copy(localVideo = e.localVideo, frontCamera = e.frontCamera) }
        return e
    }

    /** The caller learns the callee picked up (acceptedAt, the answer, or "accepted" on the channel). */
    private fun remoteAccepted() {
        if (!status.isCaller || status.phase != CallPhase.OUTGOING) return
        move(CallEvent.RemoteAccepted)
        acceptedTimeout()
        // The path already came up during the ring: start the call now, not on a media event that
        // has already been and gone (iOS beginConnectedCallIfAccepted).
        if (mediaReady) onIce(PeerConnection.IceConnectionState.CONNECTED)
    }

    private fun onIce(state: PeerConnection.IceConnectionState) {
        val id = callId ?: return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                mediaReady = true
                // Up during the ring (early answer): nothing to start until the person accepts;
                // remoteAccepted() comes back here then.
                if (status.isCaller && !status.accepted) return
                val first = status.connectedAtMs == null
                val back = status.phase == CallPhase.RECONNECTING
                move(if (back) CallEvent.MediaBack else CallEvent.MediaUp(System.currentTimeMillis()))
                // iOS: back from Reconnecting, say again whether they can hear me (a write made while
                // the link was down may never have landed).
                if (back) broadcastMute()
                if (first && status.connectedAtMs != null) {
                    CallTelecom.setActive(id)
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
        engine?.setMic(!m && !_ui.value.held)
        broadcastMute()
    }

    /**
     * iOS broadcastMuteState: "can they hear me right now", which is mute OR hold, on the call doc's
     * `muted.{me}` (the field the iPhone reads). A failed write tries again after 2 s, up to 3 times.
     */
    private fun broadcastMute(attempt: Int = 0) {
        val id = callId ?: return
        if (!status.callDocCreated) return
        val value = _ui.value.muted || _ui.value.held
        signaling.update(id, CallWire.mutedField(me, value)) { ok ->
            if (!ok && attempt < 3) main.launch {
                delay(2000)
                if (callId == id && status.live && (_ui.value.muted || _ui.value.held) == value) broadcastMute(attempt + 1)
            }
        }
    }

    /** The speaker button with only the phone and the speaker to choose from. */
    fun toggleSpeaker() {
        val r = _ui.value.route
        setRoute(if (r.current == AudioRoute.SPEAKER) CallRouting.quietRoute(r) else AudioRoute.SPEAKER)
    }

    /** A route from the speaker button or its list: through Telecom when the system call is up. */
    fun setRoute(to: AudioRoute) {
        if (CallTelecom.setRoute(to)) {
            if (::app.isInitialized) CallAudio.setRoute(app, to)
            return
        }
        edit { it.copy(route = it.route.copy(current = to), speaker = to == AudioRoute.SPEAKER) }
        CallTelecom.setLocalRoute(to)
        if (::app.isInitialized && (to == AudioRoute.SPEAKER || to == AudioRoute.EARPIECE)) CallAudio.setSpeaker(app, to == AudioRoute.SPEAKER)
    }

    private fun setStartRoute(video: Boolean) {
        edit { it.copy(route = RouteState(current = if (video) AudioRoute.SPEAKER else AudioRoute.EARPIECE), speaker = video) }
    }

    // ================================================================== the system call (CallTelecom)

    /** Telecom asks whether this call still exists before it makes the system call. */
    fun isCurrent(id: String) = id == callId && status.live

    /** The ring's sound and screen: once, from Telecom's go-ahead or the fallback timer. */
    fun showRingUi(id: String) {
        if (id != callId || status.phase != CallPhase.INCOMING || ringShown == id) return
        ringShown = id
        startRingtone()
        CallNotifications.showIncoming(app, id, _ui.value.peerName, status.video)
        // In front: open the ring screen at once (a full-screen intent only shows a banner then).
        if (appInFront()) try { com.fariin.app.ui.call.CallActivity.startRing(app) } catch (_: Exception) {}
    }

    /** Answer from a headset, the car or a watch: the call screen asks for the mic when it must. */
    fun answerFromSystem(id: String) {
        if (id != callId || status.phase != CallPhase.INCOMING) return
        val ready = granted(Manifest.permission.RECORD_AUDIO) && (!status.video || granted(Manifest.permission.CAMERA))
        if (ready) accept()
        try {
            val i = Intent(app, com.fariin.app.ui.call.CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!ready) i.action = CallNotifications.ACTION_ACCEPT
            app.startActivity(i)
        } catch (_: Exception) {}
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    /** Hang up or decline from the system (headset button, car screen, the notification shade). */
    fun endFromSystem(id: String) {
        if (id != callId) return
        if (status.phase == CallPhase.INCOMING) decline() else hangUp()
    }

    /** Telecom could not take this ring (a call elsewhere that can not be held): busy. */
    fun onSystemBusy(id: String?) {
        if (id == null || id != callId || status.phase != CallPhase.INCOMING) return
        finish(EndReason.BUSY, updateRemote = true, byUser = false)
    }

    /** A phone call answered over this one puts it on hold: my mic goes off until it ends. */
    fun onSystemHold(id: String, on: Boolean) {
        if (id != callId || !status.live) return
        // A share ENDS on hold (iOS): the person is on a phone call now, and their screen would show it.
        if (on) stopScreenShare(notice = if (_ui.value.sharing) "Screen sharing stopped" else null)
        edit { it.copy(held = on) }
        engine?.setMic(!on && !_ui.value.muted)
        broadcastMute()
    }

    /** The volume key or the system's silence while ringing: the ring stays, the sound stops. */
    fun silenceRing(id: String) { if (id == callId) stopRingtone() }

    /** The routes from Telecom, and the system mute (a car's or a headset's mute button). */
    fun onSystemAudio(id: String, r: RouteState, systemMuted: Boolean) {
        if (id != callId) return
        // Telecom moved it (a headset in, Bluetooth on): this app's own request follows, see CallAudio.setRoute.
        if (r.current != _ui.value.route.current && ::app.isInitialized) CallAudio.setRoute(app, r.current)
        edit { it.copy(route = r, speaker = CallRouting.speakerLit(r)) }
        if (status.phase != CallPhase.INCOMING && systemMuted && !_ui.value.muted) toggleMute()
    }

    /** The camera button; the call screen asks for the camera permission first. */
    fun toggleCamera() {
        // Owner 2026-10-10 (iOS shareBlocksCamera): my share and my camera never run together.
        if (!_ui.value.cameraOn && _ui.value.sharePhase != SharePhase.OFF) return
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

    // ================================================================== screen share (iOS 1:1 share)
    //
    //   Share Screen -> PICKING while the system's consent dialog is up; nothing in the call changes.
    //   Allowed -> the call service takes the media projection type, the camera goes off (and stays
    //       off after, as on iOS), the screen feeds the camera's own source, `screen` / `cams` /
    //       `screenMode` "camera" go out in one write. LIVE on the first captured frame.
    //   Stop Sharing / the system ending the capture / hold / their share winning / call end ->
    //       the capture stops, `screen` false and `cams` the real camera state go out.

    /** The capsule's share button. True when the system's consent dialog should open now. */
    fun shareTapped(): Boolean {
        val u = _ui.value
        if (u.sharePhase != SharePhase.OFF) { stopScreenShare(); return false }
        if (!u.canStartShare) return false
        edit { it.copy(sharePhase = SharePhase.PICKING, shareNotice = null) }
        return true
    }

    /** The system dialog's answer. Closing it is not a failure: back to off, silently (iOS). */
    fun shareConsent(resultCode: Int, data: Intent?) {
        val u = _ui.value
        if (u.sharePhase != SharePhase.PICKING) return
        if (resultCode != Activity.RESULT_OK || data == null || !status.live) { stopScreenShare(signal = false); return }
        if (u.remoteScreen) { stopScreenShare(notice = "${u.peerName} is sharing their screen"); return }
        val id = callId
        CallService.start(app, CallService.Mode.CALL, u.cameraOn, screen = true) { ok ->
            if (callId == id && _ui.value.sharePhase == SharePhase.PICKING) {
                if (ok) beginScreenShare(data) else stopScreenShare(notice = SHARE_FAILED)
            }
        }
    }

    private const val SHARE_FAILED = "Couldn't start screen sharing. Try again."

    private fun beginScreenShare(data: Intent) {
        val e = engine
        if (e == null || !(status.phase == CallPhase.ACTIVE || status.phase == CallPhase.RECONNECTING) || _ui.value.held) {
            stopScreenShare(); return
        }
        if (_ui.value.remoteScreen) { stopScreenShare(notice = "${_ui.value.peerName} is sharing their screen"); return }
        // The camera goes OFF as the share begins and stays off after it (iOS, owner 2026-10-10).
        if (_ui.value.cameraOn) toggleCamera()
        val id = callId
        val started = e.startScreen(data) {
            main.launch { if (callId == id && _ui.value.sharing) stopScreenShare(notice = "Screen sharing stopped") }
        }
        if (!started) { stopScreenShare(notice = SHARE_FAILED); return }
        edit { it.copy(sharePhase = SharePhase.STARTING) }
        broadcastScreen()
        // LIVE on the first frame; none in 10 s is a failed start (iOS noFirstFrame).
        main.launch {
            val t0 = System.currentTimeMillis()
            while (callId == id && _ui.value.sharePhase == SharePhase.STARTING) {
                if (e.screenFrames() > 0) { edit { it.copy(sharePhase = SharePhase.LIVE) }; break }
                if (System.currentTimeMillis() - t0 > 10_000) { stopScreenShare(notice = SHARE_FAILED); break }
                delay(100)
            }
        }
    }

    /**
     * Ends a running share or abandons a pending one; safe from every exit (iOS stopScreenShare).
     * [signal] false once the call is over. [notice] only when the user did not press Stop.
     */
    fun stopScreenShare(notice: String? = null, signal: Boolean = true) {
        val u = _ui.value
        if (u.sharePhase == SharePhase.OFF) return
        val wasSharing = u.sharing
        edit { it.copy(sharePhase = SharePhase.OFF, shareNotice = notice ?: it.shareNotice) }
        if (!wasSharing) return
        engine?.stopScreen(camera = false)
        if (signal) broadcastScreen()
        // The call service lets go of the projection type; the call itself goes on.
        if (signal && status.live && ::app.isInitialized) CallService.start(app, CallService.Mode.CALL, _ui.value.cameraOn)
    }

    fun clearShareNotice() { if (_ui.value.shareNotice != null) edit { it.copy(shareNotice = null) } }

    /** `screen`, `cams` and `screenMode` in one write, retried like the mute (iOS broadcastScreenState). */
    private fun broadcastScreen(attempt: Int = 0) {
        val id = callId ?: return
        if (!status.callDocCreated) return
        val sharing = _ui.value.sharing
        signaling.update(id, CallWire.screenFields(me, sharing, cams = sharing || _ui.value.cameraOn)) { ok ->
            if (!ok && attempt < 3) after(2000) {
                if (callId == id && status.live && _ui.value.sharing == sharing) broadcastScreen(attempt + 1)
            }
        }
    }

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
        stopScreenShare(signal = false)
        val outcome = if (!recordWritten && id != null && peerUid.isNotEmpty())
            CallWire.recordOutcome(s.endReason, before.isCaller, before.callDocCreated, connected, before.accepted) else null
        recordWritten = true
        val hadRow = ringRowWritten
        CallTelecom.end(CallRouting.disconnectCode(s.endReason, byUser, before.isCaller,
            wasIncomingRing = !before.isCaller && !before.accepted))
        // The other phone hears "ended" FIRST, sent the moment End is pressed (owner 2026-11-03: the
        // iPhone showed "Reconnecting..." for a long while after Android hung up, because this write
        // waited behind the chat's call row while the connection was already closed). The chat
        // record goes alongside it, not before it.
        if (id != null) main.launch {
            val caller = if (before.isCaller) myUid else peerUid
            if (outcome != null) signaling.recordCall(myUid, convId, id, caller, outcome, video, dur)
            else if (hadRow) signaling.deleteRingRow(convId, id)
        }
        main.launch {
            if (id == null) return@launch
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
        CallTonePlayer.stopRingback()
        val tone = if (quiet) null else CallToneRules.endTone(s, byUser)
        tone?.let { CallTonePlayer.play(it) }
        teardown(audioHoldMs = tone?.let { CallToneRules.endToneMs(it) } ?: 0L)
        val hold = CallMachine.endedHoldMs(s, byUser)
        if (quiet || hold == 0L) { reset(); return }
        if (CallMachine.noAnswerPage(s, byUser, appInFront())) { edit { it.copy(noAnswer = true) }; return }
        main.launch { delay(hold); if (status.phase == CallPhase.ENDED && !_ui.value.noAnswer && callId == id) reset() }
    }

    /** The No answer page's Close (and the page going away with the app). */
    fun closeNoAnswer() { if (status.phase == CallPhase.ENDED && _ui.value.noAnswer) reset() }

    /** The No answer page's Call again / Try again: the same person, the same kind of call. */
    fun callAgain(context: Context) {
        val u = _ui.value
        if (status.phase != CallPhase.ENDED || !u.noAnswer || u.peerUid.isEmpty()) return
        val video = u.status.video
        reset()
        dial(context, u.peerUid, u.peerName, u.peerPhoto, video)
    }

    /** [audioHoldMs]: the end tone's length; the call audio stays up until it has played. */
    private fun teardown(audioHoldMs: Long = 0L) {
        stopRingtone()
        timers.forEach { it.cancel() }; timers.clear()
        listeners.forEach { it.remove() }; listeners.clear()
        engine?.close(); engine = null
        edit { it.copy(remoteVideo = null, localVideo = null) }
        if (::app.isInitialized) {
            if (audioHoldMs > 0) main.launch {
                delay(audioHoldMs)
                CallTonePlayer.stopTone()
                // A new call dialled inside the tone keeps its own audio.
                if (!status.live && !GroupCall.ui.value.joined) CallAudio.stop(app)
            } else CallAudio.stop(app)
            CallNotifications.cancelIncoming(app)
            CallService.stop(app)
        }
    }

    private fun reset() {
        _ui.value = CallUi()
        callId = null
        ringShown = null
    }

    private fun resetCallVars() {
        recordWritten = false; ringRowWritten = false; answerApplied = false; mediaReady = false
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

    /** Privacy > Always Relay Calls, the setting iOS keeps under `calls.alwaysRelay`. */
    private fun alwaysRelay(): Boolean = CallPrefs.alwaysRelay(app)

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(app, uri) ?: return
            ringtone = r
            if (android.os.Build.VERSION.SDK_INT >= 28) r.isLooping = true else loopRingtone(r)
            r.play()
        } catch (_: Exception) {}
    }

    /** Android 8 has no Ringtone looping: start it again each time it ends, while it is still ours. */
    private fun loopRingtone(r: android.media.Ringtone) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed(object : Runnable {
            override fun run() {
                if (ringtone !== r) return
                if (!r.isPlaying) try { r.play() } catch (_: Exception) {}
                h.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun appInFront(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun stopRingtone() { try { ringtone?.stop() } catch (_: Exception) {}; ringtone = null }

    // ================================================================== debug demo

    /** Debug builds only: a made-up call on screen, no Firebase, no WebRTC. */
    fun demo(context: Context, phase: CallPhase, video: Boolean, route: RouteState = RouteState(), held: Boolean = false,
             share: SharePhase = SharePhase.OFF, theirScreen: Boolean = false) {
        init(context)
        _ui.value = CallUi(route = route, speaker = CallRouting.speakerLit(route), held = held, sharePhase = share, remoteScreen = theirScreen,
            status = CallStatus(phase = phase, isCaller = phase != CallPhase.INCOMING, video = video, accepted = phase == CallPhase.ACTIVE,
                calleeRinging = true, connectedAtMs = if (phase == CallPhase.ACTIVE) System.currentTimeMillis() - 83_000 else null),
            peerUid = "demo", peerName = "Amina Yusuf", cameraOn = video, remoteCameraOn = video || theirScreen, remoteMuted = phase == CallPhase.ACTIVE && !video,
        )
    }
}
