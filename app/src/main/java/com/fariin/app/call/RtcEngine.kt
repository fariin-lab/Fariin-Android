package com.fariin.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One 1:1 call's WebRTC side, built like iOS makePeerConnection: unified plan, max bundle, rtcp
 * mux, continual gathering, a candidate pool of 1, relay-only unless the peer is an accepted
 * contact (and "Always Relay" is off), one audio track "audio0" and one camera track "video0" on
 * stream "stream0" (the camera track is always there, disabled while the camera is off, so turning
 * it on mid-call needs no renegotiation, as on iOS). The caller also opens the "accept" data
 * channel. Uses the WebRTC build that ships inside the LiveKit SDK (package livekit.org.webrtc).
 */
class RtcEngine(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalCandidate(c: Candidate)
        fun onIceState(state: PeerConnection.IceConnectionState)
        fun onRemoteVideo(track: VideoTrack)
        fun onAcceptMessage()
    }

    companion object {
        private const val TAG = "RtcEngine"
        @Volatile private var factory: PeerConnectionFactory? = null
        val egl: EglBase by lazy { EglBase.create() }

        fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
            factory ?: run {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
                    .also { factory = it }
            }
        }

        fun rtcConfig(servers: List<IceServerSpec>, relayOnly: Boolean): PeerConnection.RTCConfiguration {
            val ice = servers.map { s ->
                val b = PeerConnection.IceServer.builder(s.url)
                if (s.username != null && s.credential != null) b.setUsername(s.username).setPassword(s.credential)
                b.createIceServer()
            }
            return PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceCandidatePoolSize = 1
                iceBackupCandidatePairPingInterval = 2000
            }
        }
    }

    private val f = factory(context)
    var pc: PeerConnection? = null; private set
    private var audioTrack: AudioTrack? = null
    var localVideo: VideoTrack? = null; private set
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturing = false
    var frontCamera = true; private set
    private var acceptChannel: DataChannel? = null

    fun open(servers: List<IceServerSpec>, relayOnly: Boolean, isCaller: Boolean, micOn: Boolean, cameraOn: Boolean): Boolean {
        val conn = f.createPeerConnection(rtcConfig(servers, relayOnly), observer) ?: return false
        pc = conn
        val src = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("audio0", src).also {
            it.setEnabled(micOn)
            conn.addTrack(it, listOf(CallWire.STREAM_ID))
        }
        val vs = f.createVideoSource(false)
        videoSource = vs
        localVideo = f.createVideoTrack("video0", vs).also {
            it.setEnabled(cameraOn)
            conn.addTrack(it, listOf(CallWire.STREAM_ID))
        }
        if (cameraOn) startCamera()
        if (isCaller) {
            acceptChannel = conn.createDataChannel(CallWire.ACCEPT_CHANNEL, DataChannel.Init().apply { ordered = true })
                ?.also { watch(it) }
        }
        return true
    }

    // ------------------------------------------------------------------ SDP

    suspend fun createOffer(): String = sdpCall { o -> pc!!.createOffer(o, MediaConstraints()) }
    suspend fun createAnswer(): String = sdpCall { o -> pc!!.createAnswer(o, MediaConstraints()) }
    suspend fun setLocal(type: SessionDescription.Type, sdp: String) = setCall { o -> pc!!.setLocalDescription(o, SessionDescription(type, sdp)) }
    suspend fun setRemote(type: SessionDescription.Type, sdp: String) = setCall { o -> pc!!.setRemoteDescription(o, SessionDescription(type, sdp)) }
    val remoteSdp: String? get() = pc?.remoteDescription?.description

    fun restartIce() { pc?.restartIce() }

    fun addCandidate(c: Candidate) {
        pc?.addIceCandidate(IceCandidate(c.mid ?: "", c.mLineIndex, c.sdp))
    }

    private suspend fun sdpCall(start: (SdpObserver) -> Unit): String = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d.description) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(e)) }
            override fun onSetFailure(e: String?) {}
        })
    }

    private suspend fun setCall(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(e)) }
        })
    }

    // ------------------------------------------------------------------ media controls

    fun setMic(on: Boolean) { audioTrack?.setEnabled(on) }

    fun setCamera(on: Boolean) {
        localVideo?.setEnabled(on)
        if (on) startCamera() else stopCamera()
    }

    fun flipCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { frontCamera = front }
            override fun onCameraSwitchError(e: String?) { Log.w(TAG, "flip failed: $e") }
        })
    }

    private fun startCamera() {
        if (capturing) return
        val cap = capturer ?: makeCapturer()?.also { c ->
            surfaceHelper = SurfaceTextureHelper.create("call-camera", egl.eglBaseContext)
            c.initialize(surfaceHelper, context.applicationContext, videoSource!!.capturerObserver)
            capturer = c
        } ?: return
        try { cap.startCapture(1280, 720, 30); capturing = true } catch (e: Exception) { Log.w(TAG, "camera start failed", e) }
    }

    private fun stopCamera() {
        if (!capturing) return
        try { capturer?.stopCapture() } catch (_: InterruptedException) {}
        capturing = false
    }

    private fun makeCapturer(): CameraVideoCapturer? {
        val en = Camera2Enumerator(context)
        val names = en.deviceNames
        val pick = names.firstOrNull { en.isFrontFacing(it) } ?: names.firstOrNull() ?: return null
        frontCamera = en.isFrontFacing(pick)
        return en.createCapturer(pick, null)
    }

    // ------------------------------------------------------------------ the accept channel

    private fun watch(ch: DataChannel) {
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {}
            override fun onMessage(b: DataChannel.Buffer) {
                val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
                if (String(bytes, Charsets.UTF_8) == CallWire.ACCEPT_MESSAGE) listener.onAcceptMessage()
            }
        })
    }

    /** The callee tells the caller "accepted" on the caller's channel (iOS sendAcceptOverChannel). */
    fun sendAccept() {
        val ch = acceptChannel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(CallWire.ACCEPT_MESSAGE.toByteArray()), false))
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { listener.onIceState(s) }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) { listener.onLocalCandidate(Candidate(c.sdp, c.sdpMLineIndex, c.sdpMid)) }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(ch: DataChannel) { if (ch.label() == CallWire.ACCEPT_CHANNEL) { acceptChannel = ch; watch(ch) } }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            // The first remote video is the camera; an iPhone's second (screen) transceiver is not shown.
            if (track is VideoTrack && track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND && remoteVideoSeen++ == 0) listener.onRemoteVideo(track)
        }
    }
    private var remoteVideoSeen = 0

    fun close() {
        stopCamera()
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        try { acceptChannel?.dispose() } catch (_: Exception) {}
        acceptChannel = null
        pc?.dispose(); pc = null
        localVideo = null; audioTrack = null
        videoSource?.dispose(); videoSource = null
    }
}

/** Speaker / earpiece, as the iOS speaker button. */
object CallAudio {
    fun start(context: Context, speaker: Boolean) {
        val am = context.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(context, speaker)
    }

    fun setSpeaker(context: Context, on: Boolean) = setRoute(context, if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE)

    /**
     * This app's own choice of where the call sound goes. Android 12 and later follow the request
     * of the app that owns the call's audio mode, which is this one: when only Telecom moved the
     * route, the app's older earpiece request still held, so the speaker button lit up while the
     * sound stayed at the ear. Every route change is mirrored here so the two always agree.
     */
    fun setRoute(context: Context, r: AudioRoute) {
        val am = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val want = when (r) {
                AudioRoute.SPEAKER -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.EARPIECE -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.HEADSET -> setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
                AudioRoute.BLUETOOTH -> setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            val dev = am.availableCommunicationDevices.firstOrNull { it.type in want }
            val now = am.communicationDevice
            if (dev != null) { if (now?.id != dev.id) am.setCommunicationDevice(dev) } else am.clearCommunicationDevice()
        } else if (r == AudioRoute.SPEAKER || r == AudioRoute.EARPIECE) {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = r == AudioRoute.SPEAKER
        }
    }

    fun stop(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }
}
