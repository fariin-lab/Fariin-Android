package com.fariin.app.call

import android.telecom.CallAudioState
import android.telecom.DisconnectCause

/** Where the call's sound goes (the iOS audio route picker: iPhone, Speaker, headphones, Bluetooth). */
enum class AudioRoute { EARPIECE, SPEAKER, HEADSET, BLUETOOTH }

/** The routes this phone has right now and the one in use; [bluetoothName] names the car or headset. */
data class RouteState(
    val current: AudioRoute = AudioRoute.EARPIECE,
    val available: Set<AudioRoute> = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    val bluetoothName: String? = null,
)

/**
 * Pure rules for audio routing, the proximity sensor and the system call states, so they are unit
 * tested without a phone. The Android pieces that use them are in CallTelecom.kt.
 */
object CallRouting {
    /** What a tap on the speaker button does. */
    sealed class Tap {
        data class Switch(val to: AudioRoute) : Tap()
        /** More than phone and speaker (a headset or Bluetooth): show the route list. */
        data object Picker : Tap()
    }

    fun speakerTap(s: RouteState): Tap {
        val extra = s.available.any { it == AudioRoute.BLUETOOTH || it == AudioRoute.HEADSET }
        if (extra) return Tap.Picker
        return Tap.Switch(if (s.current == AudioRoute.SPEAKER) quietRoute(s) else AudioRoute.SPEAKER)
    }

    /** The route that is not the speaker: a wired headset when one is in, else the earpiece. */
    fun quietRoute(s: RouteState): AudioRoute =
        if (AudioRoute.HEADSET in s.available) AudioRoute.HEADSET else AudioRoute.EARPIECE

    /** The routes in the order the picker lists them. */
    fun pickerList(s: RouteState): List<AudioRoute> {
        val order = listOf(AudioRoute.BLUETOOTH, AudioRoute.HEADSET, AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        // A wired headset replaces the earpiece on Android, so the phone is not offered then.
        return order.filter { it in s.available && !(it == AudioRoute.EARPIECE && AudioRoute.HEADSET in s.available) }
    }

    fun label(r: AudioRoute, bluetoothName: String?): String = when (r) {
        AudioRoute.EARPIECE -> "Phone"
        AudioRoute.SPEAKER -> "Speaker"
        AudioRoute.HEADSET -> "Headphones"
        AudioRoute.BLUETOOTH -> bluetoothName?.takeIf { it.isNotBlank() } ?: "Bluetooth"
    }

    /** The speaker button lights up for any route that is not the phone at the ear. */
    fun speakerLit(s: RouteState) = s.current != AudioRoute.EARPIECE

    fun fromTelecom(route: Int): AudioRoute = when (route) {
        CallAudioState.ROUTE_SPEAKER -> AudioRoute.SPEAKER
        CallAudioState.ROUTE_WIRED_HEADSET -> AudioRoute.HEADSET
        CallAudioState.ROUTE_BLUETOOTH -> AudioRoute.BLUETOOTH
        else -> AudioRoute.EARPIECE
    }

    fun toTelecom(r: AudioRoute): Int = when (r) {
        AudioRoute.SPEAKER -> CallAudioState.ROUTE_SPEAKER
        AudioRoute.HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
        AudioRoute.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
        AudioRoute.EARPIECE -> CallAudioState.ROUTE_EARPIECE
    }

    fun fromMask(mask: Int, route: Int, bluetoothName: String?): RouteState {
        val all = listOf(CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER,
            CallAudioState.ROUTE_WIRED_HEADSET, CallAudioState.ROUTE_BLUETOOTH)
        val avail = all.filter { mask and it != 0 }.map { fromTelecom(it) }.toMutableSet()
        avail += AudioRoute.SPEAKER
        return RouteState(fromTelecom(route), avail, bluetoothName)
    }

    /**
     * Video calls start on the speaker as on iOS, unless a headset or Bluetooth is in: then the
     * sound stays there.
     */
    fun startRoute(video: Boolean, s: RouteState): AudioRoute = when {
        AudioRoute.BLUETOOTH in s.available -> AudioRoute.BLUETOOTH
        AudioRoute.HEADSET in s.available -> AudioRoute.HEADSET
        video -> AudioRoute.SPEAKER
        else -> AudioRoute.EARPIECE
    }

    /**
     * The screen goes dark at the ear: a voice call (no camera on either side shown) while the sound
     * comes from the earpiece, once the call is answered. Not while it rings out: people watch the
     * screen then, and an under-screen sensor (Pixel 5, 2026-11-03 on the owner's phone) read "near"
     * in the hand and blacked the whole ring.
     */
    fun proximityWanted(phase: CallPhase, cameraOn: Boolean, route: AudioRoute): Boolean =
        (phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING) &&
            !cameraOn && route == AudioRoute.EARPIECE

    /** The system call's end cause, so the car screen and the phone's call log read right. */
    fun disconnectCode(reason: EndReason, byUser: Boolean, isCaller: Boolean, wasIncomingRing: Boolean): Int = when {
        reason == EndReason.BUSY -> DisconnectCause.BUSY
        reason == EndReason.FAILED -> DisconnectCause.ERROR
        wasIncomingRing && byUser -> DisconnectCause.REJECTED
        wasIncomingRing && reason == EndReason.HANGUP -> DisconnectCause.ANSWERED_ELSEWHERE
        wasIncomingRing -> DisconnectCause.MISSED
        isCaller && reason == EndReason.MISSED && byUser -> DisconnectCause.CANCELED
        byUser -> DisconnectCause.LOCAL
        else -> DisconnectCause.REMOTE
    }

    /**
     * Picture in picture when I leave the app: any call I am in (calling, on, reconnecting), with
     * their video or mine, else their photo, name and clock. Not a ring (the system screen answers it).
     */
    fun pipWanted(phase: CallPhase, cameraOn: Boolean, remoteCameraOn: Boolean): Boolean =
        phase == CallPhase.OUTGOING || phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING

    /** The window's shape: tall for a video, square for a voice call's photo and name. */
    fun pipVideo(cameraOn: Boolean, remoteCameraOn: Boolean) = cameraOn || remoteCameraOn
}
