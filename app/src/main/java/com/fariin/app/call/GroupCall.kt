package com.fariin.app.call

import android.content.Context
import android.util.Log
import com.fariin.app.data.AuthRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** The `groupCallToken` answer: {token, room, voiceOnly, role, url, micBlocked}. */
data class GroupPass(val token: String, val room: String, val url: String, val voiceOnly: Boolean, val role: String?, val micBlocked: Boolean)

object GroupCallWire {
    /** iOS GroupCallService: the two media servers a pass may point at, else the hosted one. */
    val MEDIA_SERVERS = setOf("wss://rtc.fariin.com", "wss://kulan-irgnsxba.livekit.cloud")
    const val DEFAULT_SERVER = "wss://kulan-irgnsxba.livekit.cloud"

    /** iOS tokenAsk: `servers: ["own"]` tells the server this build follows `url`. */
    fun tokenRequest(cid: String): Map<String, Any> = mapOf("cid" to cid, "servers" to listOf("own"))

    /** The LiveKit room the server names for a group chat's call. */
    fun roomName(cid: String) = "group_$cid"

    fun parsePass(reply: Any?): GroupPass? {
        val m = reply as? Map<*, *> ?: return null
        val token = m["token"] as? String ?: return null
        val url = (m["url"] as? String)?.takeIf { it in MEDIA_SERVERS } ?: DEFAULT_SERVER
        return GroupPass(token, m["room"] as? String ?: "", url, m["voiceOnly"] == true, m["role"] as? String, m["micBlocked"] == true)
    }

    /** groupCalls/{cid}: a call is going when `active == true`. */
    fun isActive(doc: Map<String, Any?>?) = doc?.get("active") == true

    /** iOS joinNotice words for the server's refusals. */
    fun joinNotice(message: String?): String = when {
        message == null -> "Call failed"
        message.contains("full") -> "This call is full."
        message.contains("admins-only") -> "Only admins can start a call in this group."
        message.contains("group-ended") || message.contains("group-restricted") -> "Calls are off in this group."
        message.contains("Not a member") || message.contains("removed") -> "You can't join this call."
        else -> "Call failed"
    }
}

data class GroupMember(val id: String, val name: String, val speaking: Boolean, val micOn: Boolean, val video: VideoTrack?)

data class GroupCallUi(
    val cid: String? = null,
    val title: String = "",
    val connecting: Boolean = false,
    val joined: Boolean = false,
    val micOn: Boolean = true,
    val cameraOn: Boolean = false,
    val members: List<GroupMember> = emptyList(),
    val notice: String? = null,
)

/**
 * Joining a group chat's call, as iOS GroupCallService.join(requireLive: true): ask
 * `groupCallToken` for a pass, connect LiveKit to the server it names, publish the mic (and the
 * camera for a video join). Starting a new group call (the groupCalls doc, the ring, the record)
 * is not done on Android yet; the Join bar shows only while one is going.
 */
object GroupCall {
    private const val TAG = "GroupCall"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(GroupCallUi())
    val ui: StateFlow<GroupCallUi> = _ui
    private var room: Room? = null
    private var events: Job? = null
    private var appContext: Context? = null

    /** The live state of a group's call for its Join bar (read only). */
    fun watchActive(cid: String, onActive: (Boolean) -> Unit): ListenerRegistration =
        FirebaseFirestore.getInstance().collection("groupCalls").document(cid)
            .addSnapshotListener { snap, _ -> onActive(GroupCallWire.isActive(snap?.data)) }

    fun join(context: Context, cid: String, title: String, video: Boolean) {
        if (_ui.value.cid != null || CallManager.ui.value.status.live) return
        _ui.value = GroupCallUi(cid = cid, title = title, connecting = true, cameraOn = video)
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val pass = try {
                val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("groupCallToken").call(GroupCallWire.tokenRequest(cid)).await()
                GroupCallWire.parsePass(r.data)
            } catch (e: Exception) {
                fail(GroupCallWire.joinNotice(e.message)); return@launch
            }
            if (pass == null) { fail("Call failed"); return@launch }
            try {
                val r = LiveKit.create(app)
                room = r
                events = scope.launch { r.events.collect { e -> if (e is RoomEvent.Disconnected) leave() else refresh() } }
                r.connect(pass.url, pass.token)
                CallService.start(app, CallService.Mode.CALL, video && !pass.voiceOnly)
                r.localParticipant.setMicrophoneEnabled(!pass.micBlocked)
                if (video && !pass.voiceOnly) r.localParticipant.setCameraEnabled(true)
                _ui.value = _ui.value.copy(connecting = false, joined = true, micOn = !pass.micBlocked, cameraOn = video && !pass.voiceOnly)
                refresh()
            } catch (e: Exception) {
                Log.w(TAG, "join failed", e)
                fail("Call failed")
            }
        }
    }

    private fun refresh() {
        val r = room ?: return
        val all: List<Participant> = listOf<Participant>(r.localParticipant) + r.remoteParticipants.values
        _ui.value = _ui.value.copy(members = all.map { p ->
            GroupMember(
                id = p.identity?.value ?: "",
                name = p.name?.takeIf { it.isNotEmpty() } ?: if (p === r.localParticipant) "You" else "Member",
                speaking = p.isSpeaking,
                micOn = p.isMicrophoneEnabled,
                video = p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack,
            )
        })
    }

    fun toggleMic() {
        val on = !_ui.value.micOn
        _ui.value = _ui.value.copy(micOn = on)
        scope.launch { room?.localParticipant?.setMicrophoneEnabled(on) }
    }

    fun toggleCamera() {
        val on = !_ui.value.cameraOn
        _ui.value = _ui.value.copy(cameraOn = on)
        scope.launch { room?.localParticipant?.setCameraEnabled(on); refresh() }
    }

    fun room(): Room? = room

    fun leave() {
        events?.cancel(); events = null
        room?.let { try { it.disconnect(); it.release() } catch (_: Exception) {} }
        room = null
        appContext?.let { CallService.stop(it) }
        _ui.value = GroupCallUi()
    }

    private fun fail(notice: String) {
        leave()
        _ui.value = GroupCallUi(notice = notice)
    }

    fun clearNotice() { if (_ui.value.cid == null) _ui.value = GroupCallUi() }
}
