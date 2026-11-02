package com.fariin.app.call

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.fariin.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fariin calls as real phone calls (the Android side of CallKit): a self-managed Telecom account,
 * so the system knows a call is on. That brings the headset, car and Bluetooth buttons (answer,
 * hang up, mute), the audio routes (earpiece, speaker, wired headset, Bluetooth), the busy answer
 * while a phone call can not be put on hold, and hold when a phone call is answered over ours.
 *
 * Telecom is a bonus, never a gate: a phone without it (or one that refuses the account) rings
 * through the full-screen notification and routes sound with AudioManager, as before.
 */
object CallTelecom {
    private const val TAG = "CallTelecom"
    private const val SCHEME = "fariin"
    const val EXTRA_CALL_ID = "fariin.callId"
    const val EXTRA_NAME = "fariin.name"
    const val EXTRA_VIDEO = "fariin.video"

    enum class Report { STARTED, BUSY, UNAVAILABLE }

    @Volatile var connection: FariinConnection? = null
        private set
    private var registered = false

    private val _route = MutableStateFlow(RouteState())
    val route: StateFlow<RouteState> = _route

    fun handle(c: Context) = PhoneAccountHandle(ComponentName(c, FariinConnectionService::class.java), "fariin")

    private fun telecom(c: Context): TelecomManager? = try { c.getSystemService(TelecomManager::class.java) } catch (_: Exception) { null }

    /** The self-managed account, registered once per process (registering again is harmless). */
    fun register(c: Context): Boolean {
        if (registered) return true
        if (!c.packageManager.hasSystemFeature("android.software.telecom") &&
            !c.packageManager.hasSystemFeature("android.hardware.telephony")) return false
        val tm = telecom(c) ?: return false
        return try {
            val acct = PhoneAccount.builder(handle(c), "Fariin")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING or
                    PhoneAccount.CAPABILITY_VIDEO_CALLING)
                .setIcon(Icon.createWithResource(c, R.drawable.ic_chat))
                .setSupportedUriSchemes(listOf(SCHEME))
                .build()
            tm.registerPhoneAccount(acct)
            registered = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "no telecom account", e); false
        }
    }

    private fun address(callId: String) = Uri.fromParts(SCHEME, callId, null)

    private fun info(callId: String, name: String, video: Boolean) = Bundle().apply {
        putString(EXTRA_CALL_ID, callId); putString(EXTRA_NAME, name); putBoolean(EXTRA_VIDEO, video)
    }

    /**
     * A ring for me. STARTED: Telecom will call back [FariinConnection.onShowIncomingCallUi] (or
     * [CallManager.onSystemBusy] when it can not take the call). BUSY: a call in another app can
     * not be held, so the caller hears busy. UNAVAILABLE: ring with the notification alone.
     */
    fun reportIncoming(c: Context, callId: String, name: String, video: Boolean): Report {
        if (!register(c)) return Report.UNAVAILABLE
        val tm = telecom(c) ?: return Report.UNAVAILABLE
        return try {
            if (!tm.isIncomingCallPermitted(handle(c))) return Report.BUSY
            tm.addNewIncomingCall(handle(c), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address(callId))
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, info(callId, name, video))
                if (video) putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
            })
            Report.STARTED
        } catch (e: Exception) {
            Log.w(TAG, "incoming not reported", e); Report.UNAVAILABLE
        }
    }

    /** My dial. False: the call goes on without Telecom (no system call, AudioManager routing). */
    fun placeOutgoing(c: Context, callId: String, name: String, video: Boolean): Boolean {
        if (!register(c)) return false
        val tm = telecom(c) ?: return false
        return try {
            if (!tm.isOutgoingCallPermitted(handle(c))) return false
            tm.placeCall(address(callId), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(c))
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, info(callId, name, video))
                if (video) putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "outgoing not placed", e); false
        }
    }

    internal fun attach(conn: FariinConnection) {
        connection?.takeIf { it !== conn }?.let { end(it, DisconnectCause.OTHER) }
        connection = conn
    }

    /** The call is on (callee answered here, or the caller's media came up). */
    fun setActive(callId: String?) {
        val conn = connection ?: return
        if (conn.callId == callId && conn.state != Connection.STATE_ACTIVE) try { conn.setActive() } catch (_: Exception) {}
    }

    fun end(code: Int) { connection?.let { end(it, code) } }

    private fun end(conn: FariinConnection, code: Int) {
        try { conn.setDisconnected(DisconnectCause(code)); conn.destroy() } catch (_: Exception) {}
        if (connection === conn) { connection = null; _route.value = RouteState() }
    }

    /** A route chosen on the call screen. False: no system call, the caller routes by itself. */
    fun setRoute(r: AudioRoute): Boolean {
        val conn = connection ?: return false
        return try {
            @Suppress("DEPRECATION") conn.setAudioRoute(CallRouting.toTelecom(r))
            true
        } catch (_: Exception) { false }
    }

    /** Without Telecom: what the call screen chose is all there is. */
    fun setLocalRoute(r: AudioRoute) { _route.value = _route.value.copy(current = r) }

    @Suppress("DEPRECATION")
    internal fun onAudioState(conn: FariinConnection, s: CallAudioState) {
        if (conn !== connection) return
        val bt = try { s.activeBluetoothDevice?.name } catch (_: SecurityException) { null }
        val r = CallRouting.fromMask(s.supportedRouteMask, s.route, bt)
        _route.value = r
        CallManager.onSystemAudio(conn.callId, r, s.isMuted)
        if (!conn.startRouteApplied) {
            conn.startRouteApplied = true
            val want = CallRouting.startRoute(conn.video, r)
            if (want != r.current) setRoute(want)
        }
    }
}

/** One Fariin call as the system sees it; every system button lands in [CallManager]. */
class FariinConnection(val callId: String, val video: Boolean, val incoming: Boolean) : Connection() {
    internal var startRouteApplied = false

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        audioModeIsVoip = true
        if (video) videoState = VideoProfile.STATE_BIDIRECTIONAL
    }

    override fun onShowIncomingCallUi() { CallManager.showRingUi(callId) }
    override fun onAnswer() { CallManager.answerFromSystem(callId) }
    override fun onAnswer(videoState: Int) { CallManager.answerFromSystem(callId) }
    override fun onReject() { CallManager.endFromSystem(callId) }
    override fun onReject(rejectReason: Int) { CallManager.endFromSystem(callId) }
    override fun onReject(replyMessage: String?) { CallManager.endFromSystem(callId) }
    override fun onDisconnect() { CallManager.endFromSystem(callId) }
    override fun onAbort() { CallManager.endFromSystem(callId) }
    override fun onHold() { try { setOnHold() } catch (_: Exception) {}; CallManager.onSystemHold(callId, true) }
    override fun onUnhold() { try { setActive() } catch (_: Exception) {}; CallManager.onSystemHold(callId, false) }
    override fun onSilence() { CallManager.silenceRing(callId) }

    @Deprecated("CallAudioState is still what Telecom sends to every API level")
    override fun onCallAudioStateChanged(state: CallAudioState) { CallTelecom.onAudioState(this, state) }
}

/** Telecom binds this for each Fariin call it is told about (see CallTelecom). */
class FariinConnectionService : ConnectionService() {
    private fun build(request: ConnectionRequest?, incoming: Boolean): Connection {
        val ex = request?.extras
        val inner = ex?.getBundle(if (incoming) TelecomManager.EXTRA_INCOMING_CALL_EXTRAS else TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: ex
        val id = inner?.getString(CallTelecom.EXTRA_CALL_ID) ?: request?.address?.schemeSpecificPart
        if (id.isNullOrEmpty() || !CallManager.isCurrent(id)) {
            // The call is already over (cancelled while Telecom was binding).
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        }
        val video = inner?.getBoolean(CallTelecom.EXTRA_VIDEO) == true
        val name = inner?.getString(CallTelecom.EXTRA_NAME) ?: "Fariin"
        val conn = FariinConnection(id, video, incoming)
        conn.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        conn.extras = Bundle().apply { putString(CallTelecom.EXTRA_CALL_ID, id) }
        if (incoming) conn.setRinging() else conn.setDialing()
        CallTelecom.attach(conn)
        return conn
    }

    override fun onCreateIncomingConnection(acct: PhoneAccountHandle?, request: ConnectionRequest?) = build(request, incoming = true)
    override fun onCreateOutgoingConnection(acct: PhoneAccountHandle?, request: ConnectionRequest?) = build(request, incoming = false)

    override fun onCreateIncomingConnectionFailed(acct: PhoneAccountHandle?, request: ConnectionRequest?) {
        val id = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString(CallTelecom.EXTRA_CALL_ID)
            ?: request?.address?.schemeSpecificPart
        CallManager.onSystemBusy(id)
    }

    override fun onCreateOutgoingConnectionFailed(acct: PhoneAccountHandle?, request: ConnectionRequest?) {
        // The dial goes on without the system call (AudioManager routing, no car buttons).
        Log.w("CallTelecom", "outgoing connection refused")
    }
}

/** The screen off at the ear during a voice call (the proximity sensor). */
object CallProximity {
    private var lock: PowerManager.WakeLock? = null

    fun set(c: Context, on: Boolean) {
        if (on) {
            if (lock?.isHeld == true) return
            val pm = c.getSystemService(PowerManager::class.java) ?: return
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            lock = try {
                pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "fariin:call-proximity").apply {
                    setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L)
                }
            } catch (_: Exception) { null }
        } else {
            lock?.let { try { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } catch (_: Exception) {} }
            lock = null
        }
    }
}
