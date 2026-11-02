package com.fariin.app.call

import android.content.Context
import android.util.Log
import com.fariin.app.data.AuthRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.delay
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

    /** iOS: a group rings for 60 s; a call nobody joined by then ends. */
    const val RING_WINDOW_MS = 60_000L

    /**
     * Who may start a call in this chat, as the rules (groupCalls create: isGroupMember and
     * mayStartCall) and groupCallToken say: a member of a GROUP chat, and only an admin when the
     * group is set to "Start group call: Admins only" (`onlyAdminsCall`). The server checks too.
     */
    fun mayStart(conv: Map<String, Any?>?, me: String): Boolean {
        conv ?: return false
        if (conv["type"] != "group") return false
        if ((conv["users"] as? List<*>)?.contains(me) != true) return false
        if (conv["onlyAdminsCall"] == true && (conv["admins"] as? List<*>)?.contains(me) != true) return false
        return true
    }

    /** The starter's whole doc (iOS start; the rules' isStartWrite keys exactly). [stamp] is serverTimestamp(). */
    fun startDoc(me: String, video: Boolean, title: String, recordId: String, stamp: Any): Map<String, Any> = mapOf(
        "active" to true, "startedBy" to me, "video" to video, "title" to title, "startedAt" to stamp, "recordId" to recordId,
    )

    fun newRecordId(uuid: String) = "gcall_$uuid"

    /** The group chat's call bubble, opened "ongoing" by the starter (iOS writeRecord). */
    fun recordFields(me: String, video: Boolean, stamp: Any): Map<String, Any> = mapOf(
        "type" to "call", "authorId" to me, "callerUid" to me, "callOutcome" to "ongoing", "callVideo" to video,
        "text" to "", "createdAt" to stamp,
    )

    /** The chat list's marker for a started group call. */
    fun convMarker(me: String, video: Boolean, stamp: Any): Map<String, Any> = mapOf(
        "lastMessage" to if (video) "📹 Video call" else "📞 Call", "lastSender" to me, "updatedAt" to stamp,
    )

    /** groupCallClose: the server ends a group's call only when its room is empty. */
    fun closeBody(cid: String, answered: Boolean, recordId: String?): Map<String, Any> =
        buildMap { put("cid", cid); put("answered", answered); recordId?.let { put("recordId", it) } }

    /** The last one out closes the bubble: answered with the length, or missed. */
    fun recordClose(answered: Boolean, startedAtMs: Long?, nowMs: Long): Map<String, Any>? = when {
        answered && startedAtMs != null -> mapOf("callOutcome" to "answered", "callDuration" to maxOf(0L, (nowMs - startedAtMs) / 1000).toInt())
        answered -> null
        else -> mapOf("callOutcome" to "missed")
    }

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
 * A group chat's call, as iOS GroupCallService.start: ask `groupCallToken` for a pass, connect
 * LiveKit to the server it names, publish the mic (and the camera for a video call). An empty room
 * on a start (not a Join) means this tap STARTED the call: the starter writes groupCalls/{cid}
 * (which makes the server ring the group and shows everyone the Join bar) and the chat's call
 * bubble. A refused start asks the server to close a dead call once and tries again. The last
 * one out asks `groupCallClose` to end it (the old transaction when the server can not be reached).
 *
 * Not on Android yet: the group's ring screen on this side, the ringback sound, roles and admin
 * actions, raised hands, call links, and growing a 1:1 into an `adhoc_` room.
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

    private val db get() = FirebaseFirestore.getInstance()
    private val functions get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
    private var recordId: String? = null
    private var startedHere = false
    private var someoneCame = false
    private var startedAtMs: Long? = null

    /**
     * [requireLive] true is the Join bar (never starts a call); false is the call button, which
     * joins a running call or starts one.
     */
    fun join(context: Context, cid: String, title: String, video: Boolean, requireLive: Boolean = true) {
        if (_ui.value.cid != null || CallManager.ui.value.status.live) return
        recordId = null; startedHere = false; someoneCame = false; startedAtMs = null
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
                if (!requireLive && r.remoteParticipants.isEmpty()) start(cid, title, video, r)
                else noteJoined(cid)
            } catch (e: Exception) {
                Log.w(TAG, "join failed", e)
                fail("Call failed")
            }
        }
    }

    /** I am alone in the room on a start: the call is mine to announce. */
    private suspend fun start(cid: String, title: String, video: Boolean, r: Room) {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val id = GroupCallWire.newRecordId(UUID.randomUUID().toString().uppercase())
        val ref = db.collection("groupCalls").document(cid)
        val doc = GroupCallWire.startDoc(me, video, title, id, FieldValue.serverTimestamp())
        var ok = write(ref, doc)
        if (!ok && room === r && r.remoteParticipants.isEmpty()) {
            // Usually a dead call's doc still "active": the server closes it when its room is empty.
            try { functions.getHttpsCallable("groupCallClose").call(mapOf("cid" to cid)).await() } catch (_: Exception) {}
            if (room === r && r.remoteParticipants.isEmpty()) ok = write(ref, doc)
        }
        if (room !== r) return
        if (!ok) {
            if (r.remoteParticipants.isEmpty()) { fail("Couldn't start the call"); return }
            noteJoined(cid); return   // they started at the same moment: their call
        }
        recordId = id; startedHere = true; startedAtMs = System.currentTimeMillis()
        val conv = db.collection("conversations").document(cid)
        try {
            conv.collection("messages").document(id).set(GroupCallWire.recordFields(me, video, FieldValue.serverTimestamp())).await()
            conv.set(GroupCallWire.convMarker(me, video, FieldValue.serverTimestamp()), SetOptions.merge()).await()
        } catch (e: Exception) { Log.w(TAG, "record not written", e) }
        // Nobody came in the ring window: the call ends (iOS endIfNobodyAnswered).
        scope.launch {
            delay(GroupCallWire.RING_WINDOW_MS)
            if (room === r && !someoneCame && r.remoteParticipants.isEmpty()) leave()
        }
    }

    private suspend fun write(ref: com.google.firebase.firestore.DocumentReference, doc: Map<String, Any>): Boolean =
        try { ref.set(doc).await(); true } catch (_: Exception) { false }

    /** A joiner only reads which call it is in, so its leave closes that one. */
    private suspend fun noteJoined(cid: String) {
        someoneCame = true
        val d = try { db.collection("groupCalls").document(cid).get(Source.SERVER).await().data } catch (_: Exception) { null }
        if (_ui.value.cid == cid && d?.get("active") == true) {
            recordId = d["recordId"] as? String
            startedAtMs = (d["startedAt"] as? Timestamp)?.toDate()?.time
        }
    }

    /** The last one out: the server closes the call (room empty only), else the old transaction. */
    private fun closeIfLast(cid: String, wasAlone: Boolean) {
        if (!wasAlone) return
        val rid = recordId
        val answered = someoneCame
        val started = startedAtMs
        scope.launch {
            val viaServer = try {
                functions.getHttpsCallable("groupCallClose").call(GroupCallWire.closeBody(cid, answered, rid)).await(); true
            } catch (_: Exception) { false }
            if (viaServer || rid == null) return@launch
            val ref = db.collection("groupCalls").document(cid)
            val ended = try {
                db.runTransaction { t ->
                    val d = t.get(ref).data
                    if (d?.get("active") == true && d["recordId"] == rid) { t.update(ref, "active", false); true } else false
                }.await()
            } catch (_: Exception) { false }
            if (!ended) return@launch
            GroupCallWire.recordClose(answered, started, System.currentTimeMillis())?.let { f ->
                try { db.collection("conversations").document(cid).collection("messages").document(rid).update(f).await() } catch (_: Exception) {}
            }
        }
    }

    private fun refresh() {
        val r = room ?: return
        if (r.remoteParticipants.isNotEmpty()) someoneCame = true
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
        val cid = _ui.value.cid
        val r = room
        if (cid != null && r != null && _ui.value.joined) closeIfLast(cid, wasAlone = r.remoteParticipants.isEmpty())
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
