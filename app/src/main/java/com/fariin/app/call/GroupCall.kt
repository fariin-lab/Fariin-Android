package com.fariin.app.call

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import com.google.firebase.functions.FirebaseFunctionsException
import com.fariin.app.data.Conversation
import com.fariin.app.data.GroupAdmins
import com.google.firebase.firestore.Query
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant

import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
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

    // ---- A multi-person call with no group chat behind it (iOS startAdhoc, groupCalls/{adhoc_...}) ----

    const val ADHOC_PREFIX = "adhoc_"
    /** The rules' cap on `members` (me included). */
    const val ADHOC_MAX_MEMBERS = 32

    fun isAdhoc(id: String?) = id?.startsWith(ADHOC_PREFIX) == true

    fun newAdhocId(uuid: String) = ADHOC_PREFIX + uuid.lowercase()

    /** iOS GroupCallService.title(for:): "A", "A & B", "A, B & 1 other", "A, B & 3 others". */
    fun adhocTitle(names: List<String>): String {
        val n = names.map { it.trim() }.filter { it.isNotEmpty() }
        return when (n.size) {
            0 -> ""
            1 -> n[0]
            2 -> "${n[0]} & ${n[1]}"
            else -> { val rest = n.size - 2; "${n[0]}, ${n[1]} & $rest ${if (rest == 1) "other" else "others"}" }
        }
    }

    /** The others to ring: no duplicates, never me, at most what the rules' member cap leaves. */
    fun adhocOthers(me: String, people: List<CallPerson>): List<CallPerson> =
        people.filter { it.uid.isNotEmpty() && it.uid != me }.distinctBy { it.uid }.take(ADHOC_MAX_MEMBERS - 1)

    /**
     * The starter's doc, the keys the rules' adhoc create allows: me first in `members`, `joined`
     * empty (I add myself once in the room), names and photos only for people on the list.
     */
    fun adhocStartDoc(me: CallPerson, others: List<CallPerson>, video: Boolean, stamp: Any): Map<String, Any> {
        val everyone = listOf(me) + others
        return mapOf(
            "kind" to "adhoc", "active" to true, "startedBy" to me.uid, "video" to video,
            "title" to adhocTitle(everyone.map { it.name }),
            "members" to everyone.map { it.uid },
            "names" to everyone.associate { it.uid to it.name },
            "photos" to everyone.filter { !it.photo.isNullOrEmpty() }.associate { it.uid to it.photo!! },
            "joined" to emptyList<String>(),
            "startedAt" to stamp,
        )
    }

    /** groupCallToken and groupCallClose name a multi-person call by `roomId`. */
    fun adhocTokenRequest(roomId: String): Map<String, Any> = mapOf("roomId" to roomId, "servers" to listOf("own"))
    fun adhocCloseBody(roomId: String): Map<String, Any> = mapOf("roomId" to roomId)

    /**
     * iOS invite: the people added to a running multi-person call, in ONE update (the rules allow an
     * `invitedAt` entry only for someone that same write adds). [arrayUnion] and [stamp] are the
     * Firestore sentinels.
     */
    fun adhocInviteFields(fresh: List<CallPerson>, title: String, arrayUnion: Any, stamp: Any): Map<String, Any> = buildMap {
        put("members", arrayUnion)
        put("title", title)
        for (p in fresh) {
            put("names.${p.uid}", p.name)
            if (!p.photo.isNullOrEmpty()) put("photos.${p.uid}", p.photo)
            put("invitedAt.${p.uid}", stamp)
        }
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

/**
 * One person in the room. [id] is the uid (the participant identity). [speaking] is LiveKit's raw
 * flag; the settled speaker the rings follow is [GroupCallUi.activeSpeaker].
 */
data class GroupMember(
    val id: String, val name: String, val speaking: Boolean, val micOn: Boolean, val video: VideoTrack?,
    val photo: String? = null, val role: CallRole = CallRole.PARTICIPANT, val poor: Boolean = false, val local: Boolean = false,
    /** Their shared screen (LiveKit's screen share track). Never mine: my own screen is not drawn back to me. */
    val screen: VideoTrack? = null,
    /** An admin muted them (the server-set `micBlocked` attribute): only an admin's "Allow to Speak" lifts it. */
    val micBlocked: Boolean = false,
    /** When this phone first saw them in the room (iOS joinedAt): the strip lists the newest first. */
    val joinedAtMs: Long = 0L,
    /** When their current screen share started here (iOS shareStartedAt): the newest share takes the stage. */
    val screenSinceMs: Long = 0L,
)

/** Someone at a call link's door (callLinks/{roomId}/requests/{uid}, status pending). */
data class LinkRequest(val uid: String, val name: String, val photo: String?)

data class GroupCallUi(
    val cid: String? = null,
    val title: String = "",
    val connecting: Boolean = false,
    val joined: Boolean = false,
    val micOn: Boolean = true,
    val cameraOn: Boolean = false,
    val members: List<GroupMember> = emptyList(),
    val notice: String? = null,
    /** A call link's room (callLinks/{roomId}); null for a group chat's call. */
    val linkRoom: String? = null,
    /** The link needs the host's yes and it has not come yet. */
    val waiting: Boolean = false,
    /** I started this call and nobody has come yet (iOS callingNow: "Ringing everyone…" and the pulse). */
    val calling: Boolean = false,
    val reconnecting: Boolean = false,
    /** The group's own photo, for the waiting screen (iOS groupPhotoUrl). */
    val groupPhoto: String? = null,
    /** The settled speaker (iOS GroupCallSpeakerTracker), a member id. */
    val activeSpeaker: String? = null,
    /** I made this link: the people asking to join come to me. */
    val linkCreator: Boolean = false,
    val requests: List<LinkRequest> = emptyList(),
    /** A short line the screen shows for a few seconds ("Couldn't answer. Try again."). */
    val toast: String? = null,
    /** My screen is published (iOS screenSharing follows the publish, not the button). */
    val sharing: Boolean = false,
    /** The system's consent dialog is up, or the share is being published. */
    val sharePending: Boolean = false,
    /** Where the sound goes (iOS group call Speaker button): speaker, phone, headset or Bluetooth. */
    val route: RouteState = RouteState(current = AudioRoute.SPEAKER),
    /** Raised hands, oldest first, mine included (iOS GroupCallSocial.raisedHands). */
    val hands: List<String> = emptyList(),
    val myHand: Boolean = false,
    /** Reactions on screen now, newest last (at most 5, each 4 s). */
    val reactions: List<CallReaction> = emptyList(),
    /** My role in this call (iOS myRole): the group as it is now, else the server's join pass. */
    val myRole: CallRole = CallRole.PARTICIPANT,
    /** An admin muted me: my mic stays off until an admin allows me to speak. */
    val micLocked: Boolean = false,
    /** A voice-only call (the pass's `voiceOnly`): the camera cannot come on (iOS cameraLocked). */
    val cameraLocked: Boolean = false,
    /** A call link's address, for Copy Link / Share Link (null on a group chat's call). */
    val linkUrl: String? = null,
    /** A group chat's members (Add People's list), names and photos from the group. */
    val groupPeople: List<CallPerson> = emptyList(),
    /** People I removed from this call (never offered as "Left"). */
    val removedHere: Set<String> = emptySet(),
    /** When I last rang each person from Add People (the server's one-minute wait). */
    val rangAt: Map<String, Long> = emptyMap(),
    /** A host's mic tap waiting for the server: uid -> muted (the button answers at once). */
    val pendingMute: Map<String, Boolean> = emptyMap(),
    val mutingAll: Boolean = false,
    /** A host action the server refused, for the people sheet's alert. */
    val adminError: String? = null,
)

data class CallReaction(val id: Long, val uid: String, val name: String, val emoji: String)

/**
 * A group chat's call, as iOS GroupCallService.start: ask `groupCallToken` for a pass, connect
 * LiveKit to the server it names, publish the mic (and the camera for a video call). An empty room
 * on a start (not a Join) means this tap STARTED the call: the starter writes groupCalls/{cid}
 * (which makes the server ring the group and shows everyone the Join bar) and the chat's call
 * bubble. A refused start asks the server to close a dead call once and tries again. The last
 * one out asks `groupCallClose` to end it (the old transaction when the server can not be reached).
 *
 * Screen share is LiveKit's own: the system's consent dialog, then a screen share track (the source
 * an iPhone's share arrives on too). Host controls (mute, mute all, allow to speak, remove, block,
 * end for everyone) go through the server's `callAdmin` (GroupCallAdmin has the rules). New Call
 * starts a multi-person `adhoc_` room ([startAdhoc]). Not on Android yet: growing a 1:1 into one,
 * and ringing for one somebody else started.
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
     * iOS `dialing`: the Call button (not Join) was tapped and nobody has come yet. The calling
     * sound plays from the tap, through the token and the room's connect, not only once the call's
     * doc is written (owner 2026-11-07: Android was silent first).
     */
    private var dialing = false
    /** When I got into the room, for the come-and-go sounds' quiet start (iOS joinedAt). */
    private var joinedAtMs = 0L
    /** Who else was in the room at the last refresh (iOS lastRemoteUids). */
    private var lastRemote: Set<String> = emptySet()

    /**
     * [requireLive] true is the Join bar (never starts a call); false is the call button, which
     * joins a running call or starts one.
     */
    fun join(context: Context, cid: String, title: String, video: Boolean, requireLive: Boolean = true, mic: Boolean = true) {
        if (_ui.value.cid != null || CallManager.ui.value.status.live) return
        recordId = null; startedHere = false; someoneCame = false; startedAtMs = null; micWish = mic; linkKey = null
        dialing = !requireLive; joinedAtMs = 0L; lastRemote = emptySet()
        _ui.value = GroupCallUi(cid = cid, title = title, connecting = true, cameraOn = video, micOn = mic, calling = dialing)
        // iOS start: updateRingback() "the calling sound from the tap".
        if (dialing) CallTonePlayer.startRingback()
        loadGroupFaces(cid)
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
            connectRoom(app, cid, title, video, requireLive, pass)
        }
    }

    /** I wrote this multi-person call's doc: until I am in the room, a leave must stop its ringing. */
    private var adhocWritten = false
    /** The people on this multi-person call's list (me included), for Add People. */
    private var adhocMembers: List<CallPerson> = emptyList()

    /**
     * A multi-person call with the people picked on New Call (iOS startAdhoc): the room's doc first
     * (groupCalls/adhoc_..., which makes the server ring them and which the token checks), then the
     * pass by `roomId`, then the room. No group chat and no chat bubble behind it.
     */
    fun startAdhoc(context: Context, people: List<CallPerson>, video: Boolean, mic: Boolean = true) {
        if (_ui.value.cid != null || CallManager.ui.value.status.live) return
        val meUid = FirebaseAuth.getInstance().currentUser?.uid ?: run { _ui.value = GroupCallUi(notice = "Call failed"); return }
        val others = GroupCallWire.adhocOthers(meUid, people)
        if (others.isEmpty()) { _ui.value = GroupCallUi(notice = "Call failed"); return }
        val roomId = GroupCallWire.newAdhocId(UUID.randomUUID().toString())
        val title = GroupCallWire.adhocTitle(others.map { it.name })
        recordId = null; startedHere = false; someoneCame = false; startedAtMs = null; micWish = mic; linkKey = null
        dialing = true; joinedAtMs = 0L; lastRemote = emptySet(); adhocWritten = false
        groupReg?.remove(); groupReg = null
        faces = others.associate { it.uid to (it.name to it.photo) }; asked.clear(); team = null
        _ui.value = GroupCallUi(cid = roomId, title = title, connecting = true, cameraOn = video, micOn = mic, calling = true,
            groupPhoto = others.first().photo)
        CallTonePlayer.startRingback()
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val mine = try { db.collection("users").document(meUid).get().await().data } catch (_: Exception) { null }
            val me = CallPerson(meUid, (mine?.get("name") as? String)?.takeIf { it.isNotBlank() }
                ?: FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "Member",
                (mine?.get("photoUrl") as? String)?.takeIf { it.isNotEmpty() })
            if (_ui.value.cid != roomId) return@launch
            faces = faces + (meUid to (me.name to me.photo))
            adhocMembers = listOf(me) + others
            val ref = db.collection("groupCalls").document(roomId)
            // iOS: the start write gets 8 s (offline it would never answer). A late one may still
            // land and ring everyone, so a timed-out write is followed by the end right behind it.
            val wrote = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                try { ref.set(GroupCallWire.adhocStartDoc(me, others, video, FieldValue.serverTimestamp())).await(); true } catch (_: Exception) { false }
            }
            if (wrote == null) ref.update(mapOf("active" to false, "endedAt" to FieldValue.serverTimestamp()))
            if (_ui.value.cid != roomId) { if (wrote == true) stopAdhocRing(roomId); return@launch }
            if (wrote != true) { fail("Call failed"); return@launch }
            adhocWritten = true
            startedAtMs = System.currentTimeMillis()
            val pass = try {
                GroupCallWire.parsePass(functions.getHttpsCallable("groupCallToken").call(GroupCallWire.adhocTokenRequest(roomId)).await().data)
            } catch (e: Exception) {
                if (_ui.value.cid == roomId) fail(GroupCallWire.joinNotice(e.message))
                return@launch
            }
            if (_ui.value.cid != roomId) return@launch
            if (pass == null) { fail("Call failed"); return@launch }
            connectRoom(app, roomId, title, video, requireLive = true, pass = pass)
        }
    }

    /** The starter switches the multi-person call's doc off (the rules let only the starter). */
    private fun stopAdhocRing(roomId: String) {
        db.collection("groupCalls").document(roomId).update(mapOf("active" to false, "endedAt" to FieldValue.serverTimestamp()))
    }

    /** In the room: me on `joined` (my other phones stop ringing), and the ring minute starts. */
    private fun adhocInRoom(roomId: String, r: Room) {
        startedHere = true
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("groupCalls").document(roomId).update("joined", FieldValue.arrayUnion(me))
        scope.launch {
            delay(GroupCallWire.RING_WINDOW_MS)
            if (room === r && !someoneCame && r.remoteParticipants.isEmpty()) leave()
        }
    }

    private var requestReg: ListenerRegistration? = null

    /**
     * A call link (iOS joinLink): groupCallToken {roomId, link: true}. A link that needs the host's
     * yes answers {pending: true}; the screen then says "Waiting for the host to let you in" and
     * my own request doc (callLinks/{roomId}/requests/{me}, the rules let me read it) is watched:
     * approved asks for the pass again, denied ends with "Request denied". The title is opened
     * from the link doc's sealed name alongside. Never writes a group chat's call doc.
     */
    fun joinLink(context: Context, key: CallLinkKey, video: Boolean, mic: Boolean = true) {
        if (_ui.value.cid != null || CallManager.ui.value.status.live) return
        recordId = null; startedHere = false; someoneCame = true; startedAtMs = null; micWish = mic
        dialing = false; joinedAtMs = 0L; lastRemote = emptySet()
        faces = emptyMap(); asked.clear(); team = null; linkKey = key
        val roomId = key.roomId
        val tag = "link:$roomId"
        _ui.value = GroupCallUi(cid = tag, title = CallLinkDefaults.NAME, connecting = true, cameraOn = video, micOn = mic, linkRoom = roomId)
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val d = try { db.collection("callLinks").document(roomId).get().await().data } catch (_: Exception) { null }
            val enc = d?.get("encName") as? String
            val name = if (!enc.isNullOrEmpty()) key.decryptName(enc) else null
            if (!name.isNullOrEmpty() && _ui.value.cid == tag) _ui.value = _ui.value.copy(title = name)
            // iOS isLinkCreator: the link's maker answers the people at the door.
            val me = FirebaseAuth.getInstance().currentUser?.uid
            if (me != null && d?.get("creatorUid") == me && _ui.value.cid == tag) {
                _ui.value = _ui.value.copy(linkCreator = true)
                listenRequests(tag, roomId)
            }
        }
        scope.launch { askLinkPass(app, tag, roomId, video) }
    }

    private suspend fun askLinkPass(app: Context, tag: String, roomId: String, video: Boolean) {
        val reply = try {
            functions.getHttpsCallable("groupCallToken").call(CallLinkWire.tokenRequest(roomId)).await().data
        } catch (e: Exception) {
            if (_ui.value.cid == tag) fail(linkNotice(e))
            return
        }
        if (_ui.value.cid != tag) return
        if ((reply as? Map<*, *>)?.get("pending") == true) { waitForYes(app, tag, roomId, video); return }
        val pass = GroupCallWire.parsePass(reply)
        if (pass == null) { fail("Call failed"); return }
        _ui.value = _ui.value.copy(waiting = false)
        connectRoom(app, tag, _ui.value.title, video, requireLive = true, pass = pass)
    }

    private fun waitForYes(app: Context, tag: String, roomId: String, video: Boolean) {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) { fail("Call failed"); return }
        _ui.value = _ui.value.copy(waiting = true)
        requestReg?.remove()
        requestReg = db.collection("callLinks").document(roomId).collection("requests").document(me)
            .addSnapshotListener { s, _ ->
                if (_ui.value.cid != tag || s == null || !s.exists()) return@addSnapshotListener
                when (s.getString("status")) {
                    "approved" -> { requestReg?.remove(); requestReg = null; scope.launch { askLinkPass(app, tag, roomId, video) } }
                    "denied" -> { requestReg?.remove(); requestReg = null; fail("Request denied") }
                }
            }
    }

    /** iOS joinNotice for a link: gone, full, turned away, blocked by the host. */
    private fun linkNotice(e: Exception): String {
        val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
        val m = e.message ?: ""
        return when {
            code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> "This call link is no longer valid"
            m.contains("full") -> "This call is full."
            m.contains("denied") -> "Request denied"
            m.contains("removed") -> "You can't join this call."
            else -> "Call failed"
        }
    }

    private suspend fun connectRoom(app: Context, cid: String, title: String, video: Boolean, requireLive: Boolean, pass: GroupPass) {
        run {
            try {
                val r = LiveKit.create(app)
                room = r
                watchRoutes(r)
                events = scope.launch {
                    r.events.collect { e ->
                        when (e) {
                            is RoomEvent.Disconnected -> leave()
                            is RoomEvent.Reconnecting -> { _ui.value = _ui.value.copy(reconnecting = true); refresh() }
                            is RoomEvent.Reconnected -> { _ui.value = _ui.value.copy(reconnecting = false); refresh() }
                            is RoomEvent.DataReceived -> if (e.topic == SOCIAL_TOPIC) social(e.data, e.participant?.identity?.value ?: "")
                            // A newcomer learns my raised hand (iOS participantArrived).
                            is RoomEvent.ParticipantConnected -> { if (_ui.value.myHand) sendSocial(handJson(true)); refresh() }
                            is RoomEvent.ParticipantDisconnected -> {
                                e.participant.identity?.value?.let { gone -> _ui.value = _ui.value.copy(hands = _ui.value.hands - gone) }
                                refresh()
                            }
                            else -> refresh()
                        }
                    }
                }
                r.connect(pass.url, pass.token)
                CallService.start(app, CallService.Mode.CALL, video && !pass.voiceOnly)
                val micOn = micWish && !pass.micBlocked
                r.localParticipant.setMicrophoneEnabled(micOn)
                if (video && !pass.voiceOnly) r.localParticipant.setCameraEnabled(true)
                // Muted by an admin in this call before I left: I come back muted (iOS noteJoinPass).
                sawMicBlocked = pass.micBlocked
                _ui.value = _ui.value.copy(connecting = false, joined = true, micOn = micOn, cameraOn = video && !pass.voiceOnly,
                    micLocked = pass.micBlocked, cameraLocked = pass.voiceOnly, linkUrl = linkKey?.url(video = !pass.voiceOnly))
                passRole = CallRole.of(pass.role)
                joinedAtMs = System.currentTimeMillis()
                startSpeakerTicks(r)
                // A multi-person call I started: the calling sound goes on until someone comes.
                if (GroupCallWire.isAdhoc(cid)) { adhocInRoom(cid, r); refresh(); return@run }
                val starting = !requireLive && r.remoteParticipants.isEmpty() && _ui.value.linkRoom == null
                // A call already running: the calling sound gives way to the joined tone.
                if (!starting) dialing = false
                refresh()
                if (!starting) CallTonePlayer.play(CallTone.GROUP_JOINED)   // iOS: the sound when I get in
                if (_ui.value.linkRoom != null) return@run
                if (starting) start(cid, title, video, r) else noteJoined(cid)
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
        recordId = id; startedHere = true; dialing = false; startedAtMs = System.currentTimeMillis()
        refresh()   // the calling sound goes on (iOS: from the tap until the first person joins)
        val conv = db.collection("conversations").document(cid)
        try {
            conv.collection("messages").document(id).set(GroupCallWire.recordFields(me, video, FieldValue.serverTimestamp())).await()
            conv.set(GroupCallWire.convMarker(me, video, FieldValue.serverTimestamp()), SetOptions.merge()).await()
        } catch (e: Exception) { Log.w(TAG, "record not written", e) }
        com.fariin.app.data.CallsRepository.touch(cid)
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
        // iOS GroupCallRinging.reportJoined: the server notes me as in the call, so my other
        // phones stop ringing and the call's record counts me. An older server refuses it.
        if (_ui.value.cid == cid) GroupRing.answer("joined", cid, startedAtMs)
    }

    /** The last one out: the server closes the call (room empty only), else the old transaction. */
    private fun closeIfLast(cid: String, wasAlone: Boolean) {
        if (!wasAlone) return
        if (GroupCallWire.isAdhoc(cid)) {
            // iOS closeCall(adhoc:): the server closes it when the room is really empty; unreachable,
            // the starter's own switch-off (the rules allow only the starter).
            val mine = startedHere
            scope.launch {
                val viaServer = try { functions.getHttpsCallable("groupCallClose").call(GroupCallWire.adhocCloseBody(cid)).await(); true } catch (_: Exception) { false }
                if (!viaServer && mine) stopAdhocRing(cid)
            }
            return
        }
        val rid = recordId
        val answered = someoneCame
        val started = startedAtMs
        scope.launch {
            val viaServer = try {
                functions.getHttpsCallable("groupCallClose").call(GroupCallWire.closeBody(cid, answered, rid)).await(); true
            } catch (_: Exception) { false }
            // The call row now says how it ended: the Calls list reads it again (the chat itself did not move).
            if (viaServer) com.fariin.app.data.CallsRepository.touch(cid)
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
            com.fariin.app.data.CallsRepository.touch(cid)
        }
    }

    /** uid -> when first seen in this room (iOS CallTile.joinedAt). Cleared with the call. */
    private val firstSeen = HashMap<String, Long>()
    private val screenSince = HashMap<String, Long>()

    private fun refresh() {
        val r = room ?: return
        if (r.remoteParticipants.isNotEmpty()) someoneCame = true
        val ringing = (startedHere || dialing) && !someoneCame
        if (ringing) CallTonePlayer.startRingback() else CallTonePlayer.stopRingback()
        // iOS noteRoomPeople: a sound when someone comes or goes, only while connected (a reconnect
        // empties and refills the room, which is nobody leaving).
        if (_ui.value.joined && !_ui.value.reconnecting) {
            val here = r.remoteParticipants.values.mapNotNull { it.identity?.value }.toSet()
            CallToneRules.presenceTone(lastRemote, here, System.currentTimeMillis() - joinedAtMs)?.let { CallTonePlayer.play(it) }
            lastRemote = here
        }
        val all: List<Participant> = listOf<Participant>(r.localParticipant) + r.remoteParticipants.values
        val members = all.map { p ->
            val uid = p.identity?.value ?: ""
            val face = faces[uid]
            val cam = p.getTrackPublication(Track.Source.CAMERA)
            val scr = if (p === r.localParticipant) null else p.getTrackPublication(Track.Source.SCREEN_SHARE)
            GroupMember(
                id = uid,
                name = p.name?.takeIf { it.isNotEmpty() } ?: face?.first ?: if (p === r.localParticipant) "You" else "Member",
                speaking = p.isSpeaking,
                micOn = p.isMicrophoneEnabled,
                video = (cam?.track as? VideoTrack)?.takeIf { cam?.muted != true },
                photo = face?.second,
                // iOS role(of:): a group chat's call reads roles from the group as it is now; the
                // attribute was frozen at join.
                role = GroupCallAdmin.groupRole(team, uid) ?: CallRole.of(p.attributes["role"]),
                poor = p.connectionQuality == ConnectionQuality.POOR || p.connectionQuality == ConnectionQuality.LOST,
                local = p === r.localParticipant,
                screen = (scr?.track as? VideoTrack)?.takeIf { scr?.muted != true },
                micBlocked = p.attributes["micBlocked"] == "1",
                joinedAtMs = firstSeen.getOrPut(uid) { System.currentTimeMillis() },
                screenSinceMs = if ((scr?.track as? VideoTrack) != null && scr?.muted != true)
                    screenSince.getOrPut(uid) { System.currentTimeMillis() } else { screenSince.remove(uid); 0L },
            )
        }
        firstSeen.keys.retainAll(all.mapNotNull { it.identity?.value }.toSet())
        val meUid = r.localParticipant.identity?.value ?: ""
        val myRole = GroupCallAdmin.groupRole(team, meUid)
            ?: r.localParticipant.attributes["role"]?.let { CallRole.of(it) } ?: passRole
        if (myRole != _ui.value.myRole) _ui.value = _ui.value.copy(myRole = myRole)
        // ⛔ An admin's mute holds (iOS attributesChanged): my own server-set `micBlocked` locks the
        // mic; its lifting is "Allow to Speak", and the mic comes back on at once.
        if (r.localParticipant.attributes["micBlocked"] == "1") {
            sawMicBlocked = true
            if (!_ui.value.micLocked) lockMic()
        } else if (sawMicBlocked) {
            sawMicBlocked = false
            if (_ui.value.micLocked) allowedToSpeak()
        }
        val mine = r.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)
        val sharing = mine?.track != null && mine.muted != true
        // Anyone with no photo from the group's lists, me included (owner 2026-11-08: a tile showed the
        // grey person in a screen-share call): their own profile's photo, read once per person.
        members.filter { it.id.isNotEmpty() && it.id !in asked && faces[it.id]?.second == null }.forEach { loadFace(it.id) }
        _ui.value = _ui.value.copy(members = members, calling = ringing, sharing = sharing)
        tickSpeaker()
    }

    // ------------------------------------------------------------ faces, speaker, link requests

    /** uid -> (name, photo): the group's own lists for a group call, profiles for a link call. */
    private var faces: Map<String, Pair<String?, String?>> = emptyMap()
    private val asked = mutableSetOf<String>()
    private var micWish = true
    private val tracker = SpeakerTracker()
    private var ticks: Job? = null

    /**
     * The group's names and photos, and (iOS followGroupRoles) its owner and admins, followed while
     * the call is up, so a handed-over owner or a demoted admin gets the right buttons at once.
     */
    private fun loadGroupFaces(cid: String) {
        faces = emptyMap(); asked.clear(); team = null
        groupReg?.remove()
        groupReg = db.collection("conversations").document(cid).addSnapshotListener { s, _ ->
            val d = s?.data ?: return@addSnapshotListener
            if (_ui.value.cid != cid) return@addSnapshotListener
            val names = (d["names"] as? Map<*, *>).orEmpty()
            val photos = (d["photos"] as? Map<*, *>).orEmpty()
            val fresh = (names.keys + photos.keys).mapNotNull { it as? String }.associateWith { u -> names[u] as? String to (photos[u] as? String)?.takeIf { it.isNotEmpty() } }
            // A photo read from someone's own profile (loadFace) stays when the group still has none.
            faces = fresh.mapValues { (u, v) -> if (v.second == null) v.first to faces[u]?.second else v } + faces.filterKeys { it !in fresh }
            val t = GroupAdmins.Team.from(d)
            team = t
            val people = t.users.map { u -> CallPerson(u, (names[u] as? String)?.takeIf { it.isNotEmpty() } ?: "Member", (photos[u] as? String)?.takeIf { it.isNotEmpty() }) }
            _ui.value = _ui.value.copy(groupPhoto = d["avatarUrl"] as? String, groupPeople = people)
            refresh()
        }
    }
    private var groupReg: ListenerRegistration? = null
    /** The group's owner and admins (null on a link call, or before it loads). */
    private var team: GroupAdmins.Team? = null
    private var linkKey: CallLinkKey? = null
    private var passRole = CallRole.PARTICIPANT
    /** My own `micBlocked` attribute has read "1" in this call. */
    private var sawMicBlocked = false

    /**
     * The bell next to someone in Not here yet (iOS nudge): "<me> wants to talk" on their phone
     * (`linkCallNudge` {roomId, key, uid}), once a minute per person. [done] gets the line to show.
     */
    fun nudge(uid: String, name: String, done: (ok: Boolean, line: String) -> Unit) {
        val key = linkKey ?: return done(false, "Can't nudge them now")
        scope.launch {
            try {
                functions.getHttpsCallable("linkCallNudge").call(mapOf("roomId" to key.roomId, "key" to key.text, "uid" to uid)).await()
                done(true, "Nudged $name")
            } catch (e: Exception) {
                val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
                done(code == com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED, when (code) {
                    com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Wait a minute before nudging again"
                    com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                    com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Can't nudge them now"
                    else -> "Couldn't nudge. Try again."
                })
            }
        }
    }

    /** This call's media session id: the invites of an earlier call on the same link are not shown. */
    fun roomSessionId(): String? = try { room?.sid?.sid } catch (_: Exception) { null }

    /** A person the group's lists do not know (a link call): their public profile, once. */
    private fun loadFace(uid: String) {
        asked += uid
        scope.launch {
            val d = try { db.collection("users").document(uid).get().await().data } catch (_: Exception) { null } ?: return@launch
            // The group's name for them stays; only the missing photo is filled in.
            faces = faces + (uid to ((faces[uid]?.first ?: d["name"] as? String) to (d["photoUrl"] as? String)?.takeIf { it.isNotEmpty() }))
            refresh()
        }
    }

    /** iOS: the speaker tracker is fed on every room change and on a 0.25 s tick. */
    private fun startSpeakerTicks(r: Room) {
        ticks?.cancel()
        ticks = scope.launch { while (room === r) { tickSpeaker(); delay(250) } }
    }

    private fun tickSpeaker() {
        val r = room ?: return
        // My own tile never takes the ring while anyone else is here (iOS: local is never ranked).
        val speaking = r.remoteParticipants.values.filter { it.isSpeaking }.mapNotNull { it.identity?.value }.toSet()
        if (tracker.update(speaking, System.currentTimeMillis()) || _ui.value.activeSpeaker != tracker.activeSpeakerId) {
            _ui.value = _ui.value.copy(activeSpeaker = tracker.activeSpeakerId)
        }
    }

    private var requestsReg: ListenerRegistration? = null

    /** iOS listenRequests: everyone at the link's door, oldest first. */
    private fun listenRequests(tag: String, roomId: String) {
        requestsReg?.remove()
        requestsReg = db.collection("callLinks").document(roomId).collection("requests").whereEqualTo("status", "pending")
            .addSnapshotListener { s, _ ->
                if (_ui.value.cid != tag || s == null) return@addSnapshotListener
                val list = s.documents.sortedBy { it.getTimestamp("at")?.toDate()?.time ?: 0L }
                    .map { LinkRequest(it.id, it.getString("name") ?: "Member", it.getString("photoUrl")) }
                _ui.value = _ui.value.copy(requests = list)
            }
    }

    /** iOS answerRequest: the card goes at once; it comes back with a note if the server says no. */
    fun answerRequest(uid: String, approve: Boolean) {
        val g = _ui.value
        val roomId = g.linkRoom ?: return
        val who = g.requests.firstOrNull { it.uid == uid } ?: return
        if (!g.linkCreator) return
        _ui.value = g.copy(requests = g.requests - who)
        scope.launch {
            try {
                functions.getHttpsCallable("answerCallLinkRequest").call(mapOf("roomId" to roomId, "uid" to uid, "approve" to approve)).await()
            } catch (e: Exception) {
                val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
                if (_ui.value.linkRoom != roomId) return@launch
                if (code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND ||
                    code == com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION) return@launch
                val now = _ui.value
                _ui.value = now.copy(requests = if (now.requests.any { it.uid == uid }) now.requests else now.requests + who,
                    toast = "Couldn't answer. Try again.")
            }
        }
    }

    /** iOS answerAllRequests: only the people that were on screen when the dialog opened. */
    fun answerAll(approve: Boolean, uids: List<String>) {
        val g = _ui.value
        val roomId = g.linkRoom ?: return
        if (!g.linkCreator) return
        val before = g.requests.filter { it.uid in uids }
        if (before.isEmpty()) return
        _ui.value = g.copy(requests = g.requests - before.toSet())
        scope.launch {
            try {
                functions.getHttpsCallable("answerAllCallLinkRequests")
                    .call(mapOf("roomId" to roomId, "approve" to approve, "uids" to before.map { it.uid })).await()
            } catch (_: Exception) {
                if (_ui.value.linkRoom != roomId) return@launch
                val now = _ui.value
                _ui.value = now.copy(requests = now.requests + before.filter { b -> now.requests.none { it.uid == b.uid } },
                    toast = "Couldn't answer everyone. Try again.")
            }
        }
    }

    /** A short line on the call screen (the group's "Amina is sharing their screen"). */
    fun say(text: String) { if (_ui.value.cid != null) _ui.value = _ui.value.copy(toast = text) }

    fun clearToast() { if (_ui.value.toast != null) _ui.value = _ui.value.copy(toast = null) }

    fun toggleMic() {
        if (_ui.value.micLocked && !_ui.value.micOn) { say("An admin muted you. Raise your hand to ask to speak."); return }
        val on = !_ui.value.micOn
        _ui.value = _ui.value.copy(micOn = on)
        scope.launch { room?.localParticipant?.setMicrophoneEnabled(on) }
    }

    fun toggleCamera() {
        if (_ui.value.cameraLocked) return
        val on = !_ui.value.cameraOn
        _ui.value = _ui.value.copy(cameraOn = on)
        scope.launch { room?.localParticipant?.setCameraEnabled(on); refresh() }
    }

    fun room(): Room? = room

    // ------------------------------------------------------------ sound route, camera flip

    private var routeHandler: io.livekit.android.audio.AudioSwitchHandler? = null
    private val routeListener: (List<com.twilio.audioswitch.AudioDevice>, com.twilio.audioswitch.AudioDevice?) -> Unit = { list, sel ->
        _ui.value = _ui.value.copy(route = routeOf(list, sel))
    }

    private fun routeKind(d: com.twilio.audioswitch.AudioDevice): AudioRoute = when (d) {
        is com.twilio.audioswitch.AudioDevice.BluetoothHeadset -> AudioRoute.BLUETOOTH
        is com.twilio.audioswitch.AudioDevice.WiredHeadset -> AudioRoute.HEADSET
        is com.twilio.audioswitch.AudioDevice.Earpiece -> AudioRoute.EARPIECE
        else -> AudioRoute.SPEAKER
    }

    private fun routeOf(list: List<com.twilio.audioswitch.AudioDevice>, sel: com.twilio.audioswitch.AudioDevice?): RouteState =
        RouteState(current = sel?.let { routeKind(it) } ?: AudioRoute.SPEAKER,
            available = list.map { routeKind(it) }.toSet().ifEmpty { setOf(AudioRoute.SPEAKER) },
            bluetoothName = list.firstOrNull { it is com.twilio.audioswitch.AudioDevice.BluetoothHeadset }?.name)

    /** The room's own audio switch tells the button what is plugged in and what is in use. */
    private fun watchRoutes(r: Room) {
        routeHandler?.unregisterAudioDeviceChangeListener(routeListener)
        val h = r.audioHandler as? io.livekit.android.audio.AudioSwitchHandler
        routeHandler = h
        h?.registerAudioDeviceChangeListener(routeListener)
    }

    /** iOS Speaker button: the sound to the speaker, the phone's ear, a headset or Bluetooth. */
    fun setRoute(to: AudioRoute) {
        val h = routeHandler ?: return
        val d = h.availableAudioDevices.firstOrNull { routeKind(it) == to } ?: return
        h.selectDevice(d)
        _ui.value = _ui.value.copy(route = _ui.value.route.copy(current = to))
    }

    /** iOS More > Flip Camera: front and back camera swap. */
    fun flipCamera() {
        val t = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.LocalVideoTrack ?: return
        try { t.switchCamera() } catch (e: Exception) { Log.w("GroupCall", "flip failed", e) }
    }

    /**
     * Share Screen / Stop Sharing in the capsule. True when the system's consent dialog should open
     * now; a share already on (or on its way) is stopped instead. Never while someone else shares.
     */
    fun shareTapped(): Boolean {
        val g = _ui.value
        if (!g.joined || room == null) return false
        if (g.sharing || g.sharePending) { stopShare(); return false }
        if (g.members.any { it.screen != null }) return false
        _ui.value = g.copy(sharePending = true)
        return true
    }

    /** The system dialog's answer: LiveKit publishes the screen from its own capture service. */
    fun shareConsent(resultCode: Int, data: Intent?) {
        if (!_ui.value.sharePending) return
        val r = room
        if (resultCode != Activity.RESULT_OK || data == null || r == null) { _ui.value = _ui.value.copy(sharePending = false); return }
        scope.launch {
            val ok = try {
                r.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(data, onStop = { scope.launch { stopShare() } }))
            } catch (e: Exception) { Log.w(TAG, "screen share failed", e); false }
            if (room !== r) return@launch
            _ui.value = _ui.value.copy(sharePending = false, toast = if (ok) _ui.value.toast else "Couldn't start screen sharing. Try again.")
            refresh()
        }
    }

    private fun stopShare() {
        _ui.value = _ui.value.copy(sharePending = false)
        val r = room ?: return
        scope.launch {
            try { r.localParticipant.setScreenShareEnabled(false) } catch (_: Exception) {}
            refresh()
        }
    }

    fun leave() {
        val cid = _ui.value.cid
        val r = room
        val link = _ui.value.linkRoom
        if (cid != null && r != null && _ui.value.joined && link == null) closeIfLast(cid, wasAlone = r.remoteParticipants.isEmpty())
        // Hung up (or failed) before the room was up: the doc already rings the others; stop it.
        else if (cid != null && adhocWritten && GroupCallWire.isAdhoc(cid)) stopAdhocRing(cid)
        adhocWritten = false; adhocMembers = emptyList()
        requestReg?.remove(); requestReg = null
        requestsReg?.remove(); requestsReg = null
        groupReg?.remove(); groupReg = null
        team = null; linkKey = null; passRole = CallRole.PARTICIPANT; sawMicBlocked = false; firstSeen.clear(); screenSince.clear()
        ticks?.cancel(); ticks = null
        tracker.update(emptySet(), Long.MAX_VALUE / 2)
        if (link != null && _ui.value.waiting) {
            // Giving up while still waiting withdraws the request (the rules allow it only while pending).
            FirebaseAuth.getInstance().currentUser?.uid?.let { me ->
                db.collection("callLinks").document(link).collection("requests").document(me).delete()
            }
        }
        events?.cancel(); events = null
        dialing = false; lastRemote = emptySet()
        CallTonePlayer.stopRingback()
        if (_ui.value.joined) CallTonePlayer.play(CallTone.GROUP_LEFT)   // iOS: the sound when I leave or am removed
        routeHandler?.unregisterAudioDeviceChangeListener(routeListener); routeHandler = null
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

    // ---- Raised hands and reactions (iOS GroupCallSocial, the same wire) ----
    // LiveKit data on the topic "kulan.social", small JSON: {"t":"hand","up":true} and
    // {"t":"react","e":"<emoji>"}. Who sent it is always the room's word (the sender's identity),
    // never anything in the payload. The server's own notes on this topic are not read here.
    private const val SOCIAL_TOPIC = "kulan.social"
    /** iOS quickEmojis: thumbs up, red heart, tears of joy, surprised, clapping, party. */
    val QUICK_EMOJIS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDC4F", "\uD83C\uDF89")
    private val socialStamps = HashMap<String, ArrayDeque<Long>>()
    private var nextReaction = 1L

    private fun handJson(up: Boolean) = org.json.JSONObject().put("t", "hand").put("up", up).toString()

    private fun allow(uid: String, limit: Int, perMs: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val q = socialStamps.getOrPut(uid) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > perMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now); return true
    }

    private fun sendSocial(json: String): Boolean {
        val r = room ?: return false
        scope.launch {
            runCatching {
                r.localParticipant.publishData(json.toByteArray(), io.livekit.android.room.track.DataPublishReliability.RELIABLE,
                    topic = SOCIAL_TOPIC)
            }
        }
        return true
    }

    /** Raise or lower my hand (iOS setHand). */
    fun setHand(up: Boolean) {
        val me = room?.localParticipant?.identity?.value ?: return
        if (up == _ui.value.myHand) return
        val hands = if (up) (_ui.value.hands + me).distinct() else _ui.value.hands - me
        _ui.value = _ui.value.copy(myHand = up, hands = hands)
        sendSocial(handJson(up))
    }

    /** Send a reaction (iOS react): 3 a second at most, the limit the others hold me to. */
    fun react(emoji: String) {
        val me = room?.localParticipant?.identity?.value ?: return
        if (emoji !in QUICK_EMOJIS || !allow(me, 3, 1_000)) return
        if (sendSocial(org.json.JSONObject().put("t", "react").put("e", emoji).toString())) showReaction(me, "You", emoji)
    }

    private fun social(data: ByteArray, uid: String) {
        if (data.size > 1024) return
        // The room names no sender: the server's own notes (callAdmin). The lock itself follows my
        // `micBlocked` attribute, which nobody in the call can forge; a note only says who did it.
        if (uid.isEmpty()) { serverNote(data); return }
        if (uid == room?.localParticipant?.identity?.value) return
        val m = runCatching { org.json.JSONObject(String(data)) }.getOrNull() ?: return
        val name = _ui.value.members.firstOrNull { it.id == uid }?.name ?: faces[uid]?.first ?: "Someone"
        when (m.optString("t")) {
            "hand" -> {
                if (!m.has("up")) return
                val up = m.optBoolean("up")
                val hands = _ui.value.hands
                if (up && uid !in hands) _ui.value = _ui.value.copy(hands = hands + uid)
                else if (!up && uid in hands) _ui.value = _ui.value.copy(hands = hands - uid)
            }
            "react" -> {
                val e = m.optString("e")
                if (e.isEmpty() || e.length > 16 || !allow(uid, 3, 1_000)) return
                showReaction(uid, name.take(40), e)
            }
        }
    }

    private fun showReaction(uid: String, name: String, emoji: String) {
        val r = CallReaction(nextReaction++, uid, name, emoji)
        _ui.value = _ui.value.copy(reactions = (_ui.value.reactions + r).takeLast(5))
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            _ui.value = _ui.value.copy(reactions = _ui.value.reactions.filter { it.id != r.id })
        }
    }

    // ---- An admin's mute (iOS micLockedByAdmin / allowedToSpeak, GroupCallSocial mutedBy) ----

    private fun lockMic() {
        _ui.value = _ui.value.copy(micLocked = true, micOn = false)
        val r = room ?: return
        scope.launch { runCatching { r.localParticipant.setMicrophoneEnabled(false) } }
    }

    /**
     * ⛔ Allowed to speak = the mic comes on (owner 2026-10-28, iOS allowedToSpeak). The note can beat
     * the restored publish permission, so a mic that is not live yet is asked again twice, half a
     * second apart, then turned off and on once more about 1.5 s after the lift.
     */
    private fun allowedToSpeak(force: Boolean = false) {
        if (!force && !_ui.value.micLocked && _ui.value.micOn) return
        _ui.value = _ui.value.copy(micLocked = false, micOn = true)
        val r = room ?: return
        fun live() = room === r && !_ui.value.micLocked && _ui.value.micOn
        scope.launch {
            runCatching { r.localParticipant.setMicrophoneEnabled(true) }
            for (i in 0 until 2) {
                delay(500)
                if (!live()) return@launch
                if (r.localParticipant.isMicrophoneEnabled) break
                runCatching { r.localParticipant.setMicrophoneEnabled(true) }
            }
            delay(500)
            if (!live()) return@launch
            runCatching { r.localParticipant.setMicrophoneEnabled(false) }
            if (!live()) return@launch
            runCatching { r.localParticipant.setMicrophoneEnabled(true) }
        }
    }

    private fun serverNote(data: ByteArray) {
        val m = runCatching { org.json.JSONObject(String(data)) }.getOrNull() ?: return
        val r = room ?: return
        val name = m.optString("name").trim().take(40)
        when (m.optString("t")) {
            "mutedBy" -> scope.launch {
                // The note and the mute travel on different channels; either can come first. The
                // note is believed only once my mic really is off (3 s window, as iOS).
                for (i in 0 until 15) {
                    if (room !== r) return@launch
                    if (m.optBoolean("hard") && r.localParticipant.attributes["micBlocked"] == "1") {
                        sawMicBlocked = true
                        if (!_ui.value.micLocked) lockMic()
                    }
                    if (_ui.value.micLocked || !r.localParticipant.isMicrophoneEnabled) {
                        if (name.isNotEmpty()) say("$name muted you")
                        return@launch
                    }
                    delay(200)
                }
            }
            "micAllowed" -> {
                // ⛔ Only while an admin's lock is on, or the media server muted my track while my
                // own button says on: from anyone else this note would open a self-muted mic.
                val pub = r.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
                val serverMuted = pub?.muted == true && _ui.value.micOn
                if (!_ui.value.micLocked && !serverMuted) return
                allowedToSpeak(force = true)
                say(if (name.isEmpty()) "You can speak now" else "$name allowed you to speak")
            }
        }
    }

    // ---- Host controls (iOS GroupCallService.admin, the server's callAdmin) ----

    /** One request to `callAdmin`. Null when done, else the line to show. */
    private suspend fun admin(action: CallAdminAction, target: String? = null): String? {
        val g = _ui.value
        val r = room
        val where = GroupCallAdmin.room(g.cid, g.linkRoom)
        if (r == null || where == null) return GroupCallAdmin.errorText("NOT_FOUND")
        return try {
            functions.getHttpsCallable("callAdmin").call(GroupCallAdmin.request(where, action, target)).await()
            if (room === r) {
                if (target != null && (action == CallAdminAction.REMOVE || action == CallAdminAction.BLOCK)) {
                    _ui.value = _ui.value.copy(removedHere = _ui.value.removedHere + target)
                }
                // The server closed the room and the call's record; I leave quietly, like any hang-up.
                if (action == CallAdminAction.END) leave()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "callAdmin ${action.wire} refused", e)
            GroupCallAdmin.errorText((e as? FirebaseFunctionsException)?.code?.name)
        }
    }

    private fun failAdmin(text: String?, r: Room?) {
        if (text != null && room === r && r != null) _ui.value = _ui.value.copy(adminError = text)
    }

    /** Remove from call, Remove and Block, End Call for Everyone (each after its confirmation). */
    fun act(action: CallAdminAction, target: String? = null) {
        val r = room ?: return
        scope.launch { failAdmin(admin(action, target), r) }
    }

    /**
     * The one-tap mic on a person's row (iOS tapMic): mute, or allow an admin-muted person to speak.
     * The button shows the new state at once and holds it 4 s while the room catches up.
     */
    fun tapMic(uid: String, mute: Boolean) {
        val r = room ?: return
        _ui.value = _ui.value.copy(pendingMute = _ui.value.pendingMute + (uid to mute))
        scope.launch {
            val err = admin(if (mute) CallAdminAction.MUTE else CallAdminAction.UNMUTE, uid)
            if (err == null) delay(4_000)
            if (room !== r) return@launch
            if (_ui.value.pendingMute[uid] == mute) _ui.value = _ui.value.copy(pendingMute = _ui.value.pendingMute - uid)
            failAdmin(err, r)
        }
    }

    /**
     * "Mute all" (iOS muteAll): one `muteAll` request. A server from before it says "Bad action"
     * (invalid argument); then one at a time, stopping at the first "too many".
     */
    fun muteAll() {
        val r = room ?: return
        val g = _ui.value
        if (g.mutingAll) return
        val me = r.localParticipant.identity?.value
        val fallback = GroupCallAdmin.mutable(me, g.myRole, g.members).map { it.id }
        _ui.value = g.copy(mutingAll = true)
        scope.launch {
            val where = GroupCallAdmin.room(g.cid, g.linkRoom)
            var err: String? = null
            try {
                if (where == null) throw IllegalStateException("no room")
                functions.getHttpsCallable("callAdmin").call(GroupCallAdmin.request(where, CallAdminAction.MUTE_ALL, null)).await()
            } catch (e: Exception) {
                val code = (e as? FirebaseFunctionsException)?.code
                if (code == FirebaseFunctionsException.Code.INVALID_ARGUMENT) {
                    for (uid in fallback) {
                        val one = admin(CallAdminAction.MUTE, uid) ?: continue
                        err = one
                        if (one == GroupCallAdmin.TOO_MANY) break
                    }
                } else err = GroupCallAdmin.errorText(code?.name)
            }
            if (room !== r) return@launch
            _ui.value = _ui.value.copy(mutingAll = false)
            failAdmin(err, r)
        }
    }

    fun clearAdminError() { if (_ui.value.adminError != null) _ui.value = _ui.value.copy(adminError = null) }

    /** Who has been in this group chat's call (the server's `joined`), for Add People's "Left". */
    fun watchJoined(onJoined: (Set<String>) -> Unit): ListenerRegistration? {
        val g = _ui.value
        val cid = g.cid?.takeIf { g.linkRoom == null } ?: return null
        return db.collection("groupCalls").document(cid).addSnapshotListener { s, _ ->
            onJoined(((s?.get("joined") as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSet())
        }
    }

    /**
     * Add People on a group chat's call (iOS ringPeople): `groupCallRingAgain` {cid, uids}, at most
     * 16 per request, so a bigger pick goes in turns. One line for the lot.
     */
    fun ringPeople(people: List<CallPerson>) {
        val g = _ui.value
        val cid = g.cid?.takeIf { g.linkRoom == null } ?: return
        val r = room ?: return
        if (people.isEmpty()) return
        val now = System.currentTimeMillis()
        _ui.value = g.copy(rangAt = g.rangAt + people.associate { it.uid to now })
        scope.launch {
            var rung = 0
            var lastRefusal: String? = null
            val clear = mutableSetOf<String>()
            for (part in people.chunked(GroupCallAdmin.RING_PART)) {
                try {
                    val d = functions.getHttpsCallable("groupCallRingAgain").call(mapOf("cid" to cid, "uids" to part.map { it.uid })).await().data as? Map<*, *>
                    rung += (d?.get("rung") as? List<*>)?.size ?: 0
                    for (s in (d?.get("skipped") as? List<*>).orEmpty()) {
                        val sm = s as? Map<*, *> ?: continue
                        val p = part.firstOrNull { it.uid == sm["uid"] } ?: continue
                        val reason = sm["reason"] as? String
                        if (!GroupCallAdmin.holdsWait(reason)) clear += p.uid
                        lastRefusal = GroupCallAdmin.ringRefusal(reason, p.name)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ring again refused", e)
                    clear += part.map { it.uid }
                    lastRefusal = "Couldn't ring. Try again."
                }
            }
            if (room !== r) return@launch
            _ui.value = _ui.value.copy(rangAt = _ui.value.rangAt - clear)
            GroupCallAdmin.ringToast(people.map { it.name }, rung, lastRefusal)?.let { say(it) }
        }
    }

    /** Add People on a call link (iOS invite): `linkCallInvite` {roomId, key, uids} rings each person. */
    fun invitePeople(people: List<CallPerson>) {
        val g = _ui.value
        if (GroupCallWire.isAdhoc(g.cid)) { inviteAdhoc(g.cid!!, people); return }
        val roomId = g.linkRoom ?: return
        val key = linkKey ?: return
        val r = room ?: return
        val me = r.localParticipant.identity?.value ?: ""
        val picked = people.filter { it.uid.isNotEmpty() && it.uid != me }.take(30)
        if (picked.isEmpty()) return
        scope.launch {
            val line = try {
                val d = functions.getHttpsCallable("linkCallInvite")
                    .call(mapOf("roomId" to roomId, "key" to key.text, "uids" to picked.map { it.uid })).await().data as? Map<*, *>
                val rung = (d?.get("rung") as? List<*>)?.size ?: 0
                val reasons = (d?.get("skipped") as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("reason") as? String }
                GroupCallAdmin.inviteToast(picked.size, rung, reasons) ?: if (rung > 0) {
                    if (picked.size == 1) "Ringing ${picked[0].name}" else "Ringing $rung people"
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "link invite refused", e)
                when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.UNIMPLEMENTED -> "Adding people isn't available yet"
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Couldn't add people. Try again."
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Too many calls. Wait a moment."
                    else -> "Couldn't add people. Check your connection and try again."
                }
            }
            if (room === r) line?.let { say(it) }
        }
    }

    /**
     * Add People on a multi-person call (iOS invite): the new people go on the doc's `members` in
     * one update and the server rings only them. Someone removed from the call is never added back.
     */
    private fun inviteAdhoc(roomId: String, people: List<CallPerson>) {
        val r = room ?: return
        val known = adhocMembers.map { it.uid }.toSet() + _ui.value.removedHere + _ui.value.members.map { it.id }
        val fresh = people.filter { it.uid.isNotEmpty() && it.uid !in known }.distinctBy { it.uid }
            .take(maxOf(0, GroupCallWire.ADHOC_MAX_MEMBERS - adhocMembers.size))
        if (fresh.isEmpty()) return
        val all = adhocMembers + fresh
        adhocMembers = all
        faces = faces + fresh.associate { it.uid to (it.name to it.photo) }
        val fields = GroupCallWire.adhocInviteFields(fresh, GroupCallWire.adhocTitle(all.map { it.name }),
            FieldValue.arrayUnion(*fresh.map { it.uid }.toTypedArray()), FieldValue.serverTimestamp())
        scope.launch {
            val ok = try { db.collection("groupCalls").document(roomId).update(fields).await(); true } catch (e: Exception) { Log.w(TAG, "adhoc add", e); false }
            if (room !== r) return@launch
            if (ok) say(if (fresh.size == 1) "Ringing ${fresh[0].name}" else "Ringing ${fresh.size} people")
            else {
                adhocMembers = adhocMembers.filter { m -> fresh.none { it.uid == m.uid } }
                say("Couldn't add people. Check your connection and try again.")
            }
        }
    }

    /** Add People's list on a call link: my 1:1 chats, newest first, none blocked either way. */
    suspend fun linkCandidates(): List<CallPerson> {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyList()
        val docs = try {
            db.collection("conversations").whereArrayContains("users", me)
                .orderBy("updatedAt", Query.Direction.DESCENDING).limit(200).get().await().documents
        } catch (e: Exception) { Log.w(TAG, "chats for add people", e); return emptyList() }
        return docs.mapNotNull { d ->
            val c = Conversation.fromFirestore(d.id, d.data ?: return@mapNotNull null)
            val other = c.otherUid(me)
            if (c.isGroup || c.isChannel || c.users.size != 2 || other.isEmpty() || c.blockedBy.values.any { it }) null
            else CallPerson(other, c.names[other]?.takeIf { it.isNotEmpty() } ?: "Member", c.photos[other]?.takeIf { it.isNotEmpty() })
        }.distinctBy { it.uid }
    }
}
