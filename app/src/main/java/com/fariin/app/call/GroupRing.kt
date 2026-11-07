package com.fariin.app.call

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.fariin.app.R
import com.fariin.app.data.AuthRepository
import com.fariin.app.push.NotifChannel
import com.fariin.app.ui.call.CallActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A face on the ring screen. */
data class RingFace(val uid: String, val name: String, val photo: String?)

/**
 * A group chat's call ringing me (iOS AdhocInvite as GroupCallRinging builds it for a group ring):
 * the group's title, voice or video, who started it and when, and their face (the starter first).
 */
data class GroupInvite(val cid: String, val title: String, val video: Boolean, val startedBy: String, val startedAtMs: Long, val faces: List<RingFace>)

/** The pure rules of a group ring, for the tests. */
object GroupRingRules {
    /**
     * iOS reevaluateInvites / handlePush for a group chat's call: rings only while the call is on,
     * not mine, not answered or declined by me on any phone, and inside the 60 s ring minute.
     */
    fun rings(doc: Map<String, Any?>?, me: String, startedAtMs: Long?, nowMs: Long): Boolean {
        doc ?: return false
        if (doc["active"] != true) return false
        val by = doc["startedBy"] as? String ?: return false
        if (by == me) return false
        if ((doc["joined"] as? List<*>)?.contains(me) == true) return false
        if ((doc["declined"] as? List<*>)?.contains(me) == true) return false
        val at = startedAtMs ?: return true
        return nowMs - at < GroupCallWire.RING_WINDOW_MS
    }

    /** iOS GroupCallRinging.sendAnswer's payload ("decline", "busy", "joined"); a group's room also as `cid`. */
    fun answerBody(answer: String, cid: String, startedAtMs: Long?): Map<String, Any> = buildMap {
        put("roomKind", "group"); put("roomId", cid); put("answer", answer); put("cid", cid)
        startedAtMs?.let { put("startedAt", it) }
    }

    /** How long the ring stays up from now: what is left of the minute (at least 5 s, iOS's floor). */
    fun ringLeftMs(startedAtMs: Long?, nowMs: Long): Long =
        maxOf(5_000L, GroupCallWire.RING_WINDOW_MS - (nowMs - (startedAtMs ?: nowMs)))
}

/**
 * The incoming group ring on Android (iOS IncomingGroupCallLayer + GroupCallRinging for a group
 * chat's call). The server's ring for a group (onGroupCallStarted) reaches an Android phone as an
 * FCM push with data {cid, type: "groupcall"}; when the app is running, [onPush] reads the call
 * and the group, then rings: the looping ringtone and a call notification with a full-screen intent
 * that opens CallActivity's ring screen (the group's name, "Group call", the faces, Mute, Ignore and
 * Join). The ring stops when I answer, when the minute is over, when the call ends, or when I
 * answered or declined on another phone (the call doc's `joined` / `declined`). Ignore and busy
 * go to the server as groupRingAnswer, as iOS sends them.
 *
 * With the app closed, the server's push carries a `notification` block, so Android draws a plain
 * banner itself and nothing here runs (PROGRESS "Server changes needed": a data-only push).
 */
object GroupRing {
    private const val TAG = "GroupRing"
    private const val NOTIF_ID = 7310
    const val ACTION_RING = "com.fariin.app.call.GROUP_RING"
    const val ACTION_ACCEPT = "com.fariin.app.call.GROUP_ACCEPT"
    const val ACTION_DECLINE = "com.fariin.app.call.GROUP_DECLINE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _invite = MutableStateFlow<GroupInvite?>(null)
    val invite: StateFlow<GroupInvite?> = _invite
    private val declinedHere = mutableSetOf<String>()
    private var reg: ListenerRegistration? = null
    private var timer: Job? = null
    private var ringtone: Ringtone? = null
    private var app: Context? = null
    private val db get() = FirebaseFirestore.getInstance()

    /** The `groupcall` push (FariinMessagingService). */
    fun onPush(context: Context, cid: String) {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (cid.isEmpty() || cid.contains("/")) return
        app = context.applicationContext
        if (_invite.value?.cid == cid || GroupCall.ui.value.cid == cid || cid in declinedHere) return
        scope.launch {
            val doc = try { db.collection("groupCalls").document(cid).get().await().data } catch (_: Exception) { null }
            val started = (doc?.get("startedAt") as? Timestamp)?.toDate()?.time
            if (!GroupRingRules.rings(doc, me, started, System.currentTimeMillis())) return@launch
            // In another call, or another ring is up: this one does not ring and the caller is told (iOS reportBusy).
            if (GroupCall.ui.value.cid != null || CallManager.ui.value.status.live || _invite.value != null) {
                answer("busy", cid, started); return@launch
            }
            val by = doc?.get("startedBy") as? String ?: return@launch
            val conv = try { db.collection("conversations").document(cid).get().await().data } catch (_: Exception) { null }
            val names = (conv?.get("names") as? Map<*, *>).orEmpty()
            val photos = (conv?.get("photos") as? Map<*, *>).orEmpty()
            val title = (conv?.get("title") as? String)?.takeIf { it.isNotEmpty() } ?: (doc["title"] as? String)?.takeIf { it.isNotEmpty() } ?: "Group"
            val face = RingFace(by, names[by] as? String ?: "Someone", photos[by] as? String)
            ring(GroupInvite(cid, title, doc["video"] == true, by, started ?: System.currentTimeMillis(), listOf(face)))
        }
    }

    /** Debug demo only: the ring screen with made-up people, no Firebase, no sound. */
    fun demo(invite: GroupInvite) { _invite.value = invite }

    private fun ring(i: GroupInvite) {
        val c = app ?: return
        _invite.value = i
        startRingtone(c)
        notifyRing(c, i)
        if (inFront()) try { c.startActivity(ringIntent(c, ACTION_RING)) } catch (_: Exception) {}
        // The call doc: ended, or answered / declined on my other phone, stops this ring.
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        reg?.remove()
        reg = db.collection("groupCalls").document(i.cid).addSnapshotListener { s, _ ->
            if (_invite.value?.cid != i.cid || s == null) return@addSnapshotListener
            if (!GroupRingRules.rings(s.data, me, null, 0)) stop()
        }
        timer?.cancel()
        timer = scope.launch {
            delay(GroupRingRules.ringLeftMs(i.startedAtMs, System.currentTimeMillis()))
            if (_invite.value?.cid == i.cid) stop()
        }
    }

    /** Ignore: the ring stops and the server hears "decline" (iOS settleDecline). */
    fun decline() {
        val i = _invite.value ?: return
        declinedHere += i.cid
        stop()
        answer("decline", i.cid, i.startedAtMs, tries = 3)
    }

    /**
     * Join (iOS acceptFromScreen for a group chat's call): only a call that is still on. A video
     * ring joins with the camera only when access was already given (no camera prompt in the
     * middle of answering); the Mute pill joins with the mic off.
     */
    fun accept(context: Context, muted: Boolean, cameraAllowed: Boolean): GroupInvite? {
        val i = _invite.value ?: return null
        declinedHere += i.cid   // never ring again for a room I answered
        stop()
        GroupCall.join(context, i.cid, i.title, video = i.video && cameraAllowed, requireLive = true, mic = !muted)
        return i
    }

    fun stop() {
        reg?.remove(); reg = null
        timer?.cancel(); timer = null
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        RingVibration.stop()
        val i = _invite.value
        _invite.value = null
        val c = app
        if (i != null && c != null) NotificationManagerCompat.from(c).cancel(i.cid, NOTIF_ID)
    }

    /** groupRingAnswer (functions-groups); an offline decline is tried again a few times. */
    fun answer(answer: String, cid: String, startedAtMs: Long?, tries: Int = 2) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val body = GroupRingRules.answerBody(answer, cid, startedAtMs)
        scope.launch {
            repeat(maxOf(1, tries)) { attempt ->
                try {
                    FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("groupRingAnswer").call(body).await()
                    return@launch
                } catch (e: Exception) {
                    // An older server that does not know this answer says so every time.
                    if ((e as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT) return@launch
                    Log.w(TAG, "groupRingAnswer $answer failed", e)
                    if (attempt + 1 < tries) delay((attempt + 1) * 4_000L)
                }
            }
        }
    }

    fun ringIntent(c: Context, action: String): Intent =
        Intent(c, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun pending(c: Context, action: String, req: Int): PendingIntent =
        PendingIntent.getActivity(c, req, ringIntent(c, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The system's call notification: the group's name, "Incoming group call", Decline and Join. */
    private fun notifyRing(c: Context, i: GroupInvite) {
        val person = Person.Builder().setName(i.title).setImportant(true).build()
        val n = NotificationCompat.Builder(c, NotifChannel.CALLS.id)
            .setSmallIcon(R.drawable.ic_stat_fariin)
            .setContentTitle(i.title)
            .setContentText("${i.faces.firstOrNull()?.name ?: "Someone"} is ringing you")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true)
            .setTimeoutAfter(GroupRingRules.ringLeftMs(i.startedAtMs, System.currentTimeMillis()))
            .setFullScreenIntent(pending(c, ACTION_RING, 11), true)
            .setContentIntent(pending(c, ACTION_RING, 11))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, pending(c, ACTION_DECLINE, 12), pending(c, ACTION_ACCEPT, 13)))
            .build()
        try { NotificationManagerCompat.from(c).notify(i.cid, NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    private fun startRingtone(c: Context) {
        RingVibration.start(c)
        try {
            val r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 28) r.isLooping = true else loopRingtone(r)
            ringtone = r
            r.play()
        } catch (_: Exception) {}
    }

    /** Android 8 has no Ringtone looping: start it again each time it ends, while it is still ours (as CallManager). */
    private fun loopRingtone(r: Ringtone) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed(object : Runnable {
            override fun run() {
                if (ringtone !== r) return
                if (!r.isPlaying) try { r.play() } catch (_: Exception) {}
                h.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun inFront(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
