package com.fariin.app.call

import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.Wire

/** iOS CallService.CallState. */
enum class CallPhase { IDLE, OUTGOING, INCOMING, ACTIVE, RECONNECTING, ENDED }

/**
 * What the call screen shows, moved only by [CallMachine.reduce]. Pure, so the order of events
 * from Firestore, WebRTC and the buttons is unit tested.
 */
data class CallStatus(
    val phase: CallPhase = CallPhase.IDLE,
    val isCaller: Boolean = false,
    val video: Boolean = false,
    /** The callee's phone rang (ringingAt on the doc). */
    val calleeRinging: Boolean = false,
    /** Accepted by the callee (acceptedAt, or my own Accept on this side). */
    val accepted: Boolean = false,
    /** Media up (ICE connected) at this time; null = not yet. */
    val connectedAtMs: Long? = null,
    val endReason: EndReason = EndReason.NONE,
    val callDocCreated: Boolean = false,
    val micDenied: Boolean = false,
) {
    val live get() = phase != CallPhase.IDLE && phase != CallPhase.ENDED
}

sealed class CallEvent {
    data class Dial(val video: Boolean) : CallEvent()
    data object DocCreated : CallEvent()
    data class Ring(val video: Boolean) : CallEvent()
    data object RemoteRinging : CallEvent()
    /** The callee tapped Accept here. */
    data object Accept : CallEvent()
    /** The caller saw acceptedAt or the answer (or "accepted" on the data channel). */
    data object RemoteAccepted : CallEvent()
    data class MediaUp(val atMs: Long) : CallEvent()
    data object MediaLost : CallEvent()
    data object MediaBack : CallEvent()
    data class RemoteEnded(val reason: EndReason) : CallEvent()
    /** This side ends: hang up, decline, a timeout or a failure. */
    data class LocalEnd(val reason: EndReason, val byUser: Boolean) : CallEvent()
    data object MicDenied : CallEvent()
    data object Reset : CallEvent()
}

object CallMachine {
    fun reduce(s: CallStatus, e: CallEvent): CallStatus = when (e) {
        is CallEvent.Dial -> if (s.phase != CallPhase.IDLE && s.phase != CallPhase.ENDED) s
            else CallStatus(phase = CallPhase.OUTGOING, isCaller = true, video = e.video)
        CallEvent.DocCreated -> if (s.phase == CallPhase.OUTGOING) s.copy(callDocCreated = true) else s
        is CallEvent.Ring -> if (s.phase != CallPhase.IDLE && s.phase != CallPhase.ENDED) s
            else CallStatus(phase = CallPhase.INCOMING, isCaller = false, video = e.video, callDocCreated = true)
        CallEvent.RemoteRinging -> if (s.isCaller && s.phase == CallPhase.OUTGOING) s.copy(calleeRinging = true) else s
        CallEvent.Accept -> if (!s.isCaller && s.phase == CallPhase.INCOMING) s.copy(phase = CallPhase.ACTIVE, accepted = true) else s
        CallEvent.RemoteAccepted -> if (s.isCaller && s.phase == CallPhase.OUTGOING)
            s.copy(phase = CallPhase.ACTIVE, accepted = true, calleeRinging = true) else s
        is CallEvent.MediaUp -> when {
            !s.live || !s.accepted -> s
            s.connectedAtMs == null -> s.copy(connectedAtMs = e.atMs, phase = CallPhase.ACTIVE)
            else -> s.copy(phase = CallPhase.ACTIVE)
        }
        CallEvent.MediaLost -> if (s.phase == CallPhase.ACTIVE && s.connectedAtMs != null) s.copy(phase = CallPhase.RECONNECTING) else s
        CallEvent.MediaBack -> if (s.phase == CallPhase.RECONNECTING) s.copy(phase = CallPhase.ACTIVE) else s
        is CallEvent.RemoteEnded -> if (!s.live) s else s.copy(
            phase = CallPhase.ENDED,
            endReason = CallWire.finalReason(e.reason, s.isCaller, s.callDocCreated, s.connectedAtMs != null, s.accepted, localUser = false),
        )
        is CallEvent.LocalEnd -> if (!s.live) s else s.copy(
            phase = CallPhase.ENDED,
            endReason = CallWire.finalReason(e.reason, s.isCaller, s.callDocCreated, s.connectedAtMs != null, s.accepted, e.byUser),
        )
        CallEvent.MicDenied -> s.copy(micDenied = true)
        CallEvent.Reset -> CallStatus()
    }

    /** iOS CallStageWords.progress + the ended words of CallView. Null = show the clock. */
    fun label(s: CallStatus, nowMs: Long): String = when (s.phase) {
        CallPhase.OUTGOING -> if (s.accepted) "Connecting…" else if (s.calleeRinging) "Ringing…" else "Calling…"
        CallPhase.INCOMING -> if (s.video) "Fariin video call" else "Fariin voice call"
        CallPhase.ACTIVE -> s.connectedAtMs?.let { clock(((nowMs - it) / 1000).toInt()) } ?: "Connecting…"
        CallPhase.RECONNECTING -> "Reconnecting…"
        CallPhase.ENDED -> endedWords(s)
        CallPhase.IDLE -> ""
    }

    fun endedWords(s: CallStatus): String {
        val neverRang = !s.calleeRinging && !s.accepted
        if (s.micDenied) return "Microphone access is off"
        return when (s.endReason) {
            EndReason.BUSY -> "Busy"
            EndReason.DECLINED, EndReason.MISSED -> if (s.isCaller && neverRang) "Couldn't reach them" else if (s.isCaller) "No answer" else "Call ended"
            EndReason.FAILED -> if (s.connectedAtMs == null) "Couldn't connect" else "Call failed"
            else -> "Call ended"
        }
    }

    /**
     * iOS finishCall's No answer page: a call I placed that nobody answered (or that never
     * connected) stays on screen with Call again / Try again, Voice message and Close, while the
     * app is in front. My own End closes at once instead.
     */
    fun noAnswerPage(s: CallStatus, byUser: Boolean, inFront: Boolean): Boolean =
        s.isCaller && !byUser && !s.micDenied && inFront && (s.endReason == EndReason.MISSED || s.endReason == EndReason.DECLINED ||
            (s.endReason == EndReason.FAILED && s.connectedAtMs == null))

    /**
     * How long the ended words stay before the screen closes (iOS finishCall): none after my own
     * End, 2 s for the busy tone or the mic line, 1 s otherwise.
     */
    fun endedHoldMs(s: CallStatus, byUser: Boolean): Long = when {
        s.micDenied -> 2000
        byUser -> 0
        s.endReason == EndReason.BUSY -> 2000
        else -> 1000
    }

    /** iOS CallDuration.clock: m:ss, then h:mm:ss. */
    fun clock(sec: Int): String {
        val t = sec.coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val ss = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
}

/** Seals and opens call signalling with the 1:1 chat crypto (iOS sealSignal / openSignal). */
interface SignalSealer {
    /** Null = no key in memory yet (the caller retries after a key warm; never plaintext). */
    fun seal(cid: String, text: String): String?
    /** Null = not sealed, or not openable yet. */
    fun open(cid: String, raw: String?): String?
}

class CryptoSealer(private val crypto: FariinCrypto) : SignalSealer {
    override fun seal(cid: String, text: String): String? = crypto.encryptForConversationIfCached(cid, text)

    override fun open(cid: String, raw: String?): String? {
        if (raw == null || !raw.startsWith(Wire.ENC1)) return null
        val out = crypto.decryptLive(raw, cid)
        if (out == raw || out == Wire.NOT_READY || out == Wire.LOCKED || out == Wire.OLD_MESSAGE) return null
        return out
    }
}
