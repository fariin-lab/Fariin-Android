package com.fariin.app.push

import android.content.Context
import com.fariin.app.crypto.Wire
import com.fariin.app.data.CryptoHub
import com.fariin.app.device.DeviceRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.storage.FirebaseStorage
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Every push for this app lands here when Fariin draws it itself: always for a data-only push, and
 * for a push with a `notification` block only while the app is in front (Android draws those itself
 * in the background, title and body as sent; see PROGRESS.md "Server changes needed").
 *
 * Same two jobs as the iOS NotificationService extension:
 * 1. The delivered tick: post the signed token to `ackDelivered` (1:1 pushes carry it).
 * 2. The readable notification: open `ct` with this account's key, the sender's name and face.
 * Any miss (no key yet, sender key unknown, photo slow) shows the server's hint, never nothing.
 *
 * This runs in the app's own process, so unlike iOS it can use the signed-in Firebase: the crypto
 * from the Keystore, a missing sender key fetched, a `fariin-photo://` face downloaded under the
 * storage rules. Each with a short limit: FCM gives a high-priority push about 10 seconds.
 */
class FariinMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Push.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // A ring for a call (data-only `kind: "call"`, see PROGRESS "Server changes needed"): start the
        // ring watcher, which reads the call document and shows the incoming screen.
        // The server's "Incoming call" alert (not data-only) also means a ring: wake the watcher; the
        // alert itself is not shown (NotifPayload skips it).
        val alertBody = message.notification?.body ?: message.data["body"] ?: message.data["b"]
        if (message.data["kind"] != "call" && (alertBody == "Incoming call" || alertBody == "Incoming video call")) {
            com.fariin.app.Fb.auth.currentUser?.uid?.let { com.fariin.app.call.IncomingCallWatcher.onCallPush(applicationContext, it) }
        }
        // Nobody signed in (K-43: an offline Log Out could not take the token off the account): no
        // story, reply, mention or reaction alert for the old account. A push means the network is
        // back, so the token's delete is tried again.
        if (com.fariin.app.Fb.auth.currentUser == null) {
            Push.retireIfPending(applicationContext)
            return
        }
        // A call ROW's push (a missed call, server onNewMessage) is kind "call" too, but carries the
        // row's `mid`: it is a message notification like any other, not a ring.
        if (message.data["kind"] == "call" && message.data["mid"].isNullOrEmpty()) {
            val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
            com.fariin.app.call.IncomingCallWatcher.onCallPush(applicationContext, me)
            return
        }
        // A group chat's call ringing me (server onGroupCallStarted: data {cid, type: "groupcall"}).
        // With the app running the ring screen comes up (GroupRing); closed, Android drew the banner.
        // Added to a call link's call (server linkCallInvite): the same ring screen, joining the link.
        if (message.data["type"] == "linkring") {
            val d = message.data.toMap()
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.fariin.app.call.GroupRing.onLinkPush(applicationContext, d) }
            return
        }
        // "<name> wants to talk" (server linkCallNudge) with the app open: the system does not draw
        // a notification part then, so we show the invitation screen at once (iOS in-app banner + Join).
        if (message.data["type"] == "linknudge") {
            val d = message.data.toMap()
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.fariin.app.call.GroupRing.onLinkNudge(applicationContext, d) }
            return
        }
        // "<name> wants you back in the call" (server groupCallNudge). In front: our banner with Join
        // (iOS showNudge); behind: our notification. A tap or Join opens the call's invitation.
        if (message.data["type"] == com.fariin.app.data.GroupNudgeRules.TYPE) {
            val n = com.fariin.app.data.GroupNudgeRules.parse(message.data) ?: return
            runBlocking { withTimeoutOrNull(NUDGE_TIMEOUT_MS) { showGroupNudge(applicationContext, n, message.notification?.title, message.notification?.body) } }
            return
        }
        // Added to a multi-person call with no group chat (server ringAdhocRecipients). In front: the
        // invitation at once, no banner (iOS willPresent); behind: our notification with Join.
        if (message.data["type"] == com.fariin.app.data.AdhocInviteRules.TYPE) {
            val p = com.fariin.app.data.AdhocInviteRules.parse(message.data) ?: return
            val title = message.notification?.title?.takeIf { it.isNotEmpty() } ?: com.fariin.app.data.AdhocInviteRules.FALLBACK_TITLE
            val body = message.notification?.body?.takeIf { it.isNotEmpty() } ?: com.fariin.app.data.AdhocInviteRules.FALLBACK_BODY
            val c = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).post { showAdhocInvite(c, p, title, body) }
            return
        }
        if (message.data["type"] == "groupcall") {
            val cid = message.data["cid"].orEmpty()
            // Only the ring itself (data-only, kind "groupring") rings full screen (F-36). The plain
            // "call started" notice of a group too big to ring is a normal notification.
            if (message.data["kind"] == "groupring") {
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.fariin.app.call.GroupRing.onPush(applicationContext, cid, message.data["kind"]) }
            } else if (cid.isNotEmpty() && Push.pushOn(applicationContext)) {
                NotificationPoster.postChatNotice(applicationContext, NotificationPoster.groupCallTag(cid),
                    message.notification?.title ?: message.data["t"] ?: "Group call",
                    message.notification?.body ?: message.data["b"] ?: "A call started", message.data.toMap(), call = true)
            }
            return
        }
        // "Request to join" (server onJoinRequest, L-42): its own notification, so the group's
        // message lines stay, and the widget does not count it.
        if (message.data["type"] == "joinrequest") {
            val cid = message.data["cid"].orEmpty()
            if (cid.isNotEmpty() && Push.pushOn(applicationContext)) {
                NotificationPoster.postChatNotice(applicationContext, NotificationPoster.joinRequestTag(cid),
                    message.notification?.title ?: message.data["t"] ?: "Request to join",
                    message.notification?.body ?: message.data["b"] ?: "Someone asked to join", message.data.toMap(), call = false)
            }
            return
        }
        // "Posted a new story" (server notifyStoryFollowers; no cid, so it is not a chat push). With
        // the app closed the system draws it from the notification part; with the app open it comes
        // here and is drawn the same way. A tap opens the Stories tab.
        if (message.data["kind"] == "story") {
            // The name this phone shows for the poster (a saved contact name wins), as iOS titles it.
            // Only with Message Preview on (`sn` sent); off, the title stays "Fariin".
            val me = com.fariin.app.Fb.auth.currentUser?.uid.orEmpty()
            val author = message.data["aid"].orEmpty()
            val saved = if (me.isEmpty() || author.isEmpty() || message.data["sn"].isNullOrEmpty()) null else
                (NotifStore.chat(applicationContext, "${me}_$author") ?: NotifStore.chat(applicationContext, "${author}_$me"))?.name?.takeIf { it.isNotEmpty() }
            if (Push.pushOn(applicationContext)) NotificationPoster.postStory(applicationContext,
                saved ?: message.notification?.title ?: message.data["t"] ?: "Fariin", message.notification?.body ?: message.data["b"] ?: "New story.", author)
            return
        }
        // A reply alert (owner 2026-11-06): its own notification, whose tap opens that post's Replies.
        // Shown even while that channel is open (fix contract 2026-11-09): the tap opens the sheet there.
        com.fariin.app.data.PostReplyRules.alertTarget(message.data["kind"], message.data["cid"], message.data["mid"])?.let { (cid, mid) ->
            // In front the In-App switches apply to it too (iOS willPresent): Preview off keeps it in
            // the list without the drop-down, Sounds off plays nothing. A channel cannot drop down
            // without its tone, so either one off posts it quietly and the tone and buzz are ours.
            val inApp = if (com.fariin.app.AppLifecycle.inFront) InAppNotify.prefs(applicationContext) else null
            val quiet = inApp != null && !(inApp.preview && inApp.sound)
            if (Push.pushOn(applicationContext)) NotificationPoster.postReplyAlert(applicationContext,
                message.notification?.title ?: message.data["t"] ?: "Fariin", message.notification?.body ?: message.data["b"] ?: "New reply", cid, mid,
                message.data["rid"].orEmpty(), message.data["aid"].orEmpty(), message.data["kind"].orEmpty(),
                act = message.data["act"].orEmpty(), wcid = message.data["wcid"].orEmpty(), wmid = message.data["wmid"].orEmpty(),
                quiet = quiet)
            if (quiet && Push.pushOn(applicationContext)) InAppNotify.present(applicationContext, InAppRoute.InApp(null, inApp!!.sound, inApp.vibrate))
            return
        }
        val p = PushData.parse(message.data, message.notification?.title, message.notification?.body)
        // One cap on the whole push: the key fetch, ack and photo each have their own timeouts, but
        // together they could pass the ~20 s Android gives a push before it is killed.
        runBlocking { withTimeoutOrNull(HANDLE_TIMEOUT_MS) { handle(applicationContext, p) } }
    }

    companion object {
        private const val ACK_URL = "https://me-central1-kulan-2ef85.cloudfunctions.net/ackDelivered"
        private const val PHOTO_TIMEOUT_MS = 4_000L
        private const val KEY_TIMEOUT_MS = 3_000L
        private const val HANDLE_TIMEOUT_MS = 15_000L
        private const val NUDGE_TIMEOUT_MS = 5_000L

        /** The group nudge (GroupNudgeRules.route): skipped when I am in that call, it ended, or the group is muted. */
        private suspend fun showGroupNudge(c: Context, n: com.fariin.app.data.GroupNudge, title: String?, body: String?) {
            val me = com.fariin.app.Fb.auth.currentUser?.uid.orEmpty()
            if (me.isEmpty()) return
            val db = com.fariin.app.Fb.db
            val call = try { db.collection("groupCalls").document(n.cid).get().await() } catch (_: Exception) { null }
            val conv = try { db.collection("conversations").document(n.cid).get().await() } catch (_: Exception) { null }
            val active = call?.let { it.exists() && it.getBoolean("active") == true }
            val muted = ((conv?.get("mutedBy") as? Map<*, *>)?.get(me) as? Number)?.toLong() ?: 0L
            val inFront = com.fariin.app.AppLifecycle.inFront
            val prefs = InAppNotify.prefs(c)
            val show = com.fariin.app.data.GroupNudgeRules.route(n, me, Push.pushOn(c),
                blocked = com.fariin.app.data.BlockList.state.value.contains(n.callerUid),
                myCallCid = com.fariin.app.call.GroupCall.ui.value.cid, callActive = active,
                mutedUntilMs = muted, nowMs = System.currentTimeMillis(),
                inFront = inFront, locked = appLockShowing(c), preview = prefs.preview)
            val head = title?.takeIf { it.isNotEmpty() } ?: n.title ?: "Group call"
            val words = body?.takeIf { it.isNotEmpty() } ?: com.fariin.app.data.GroupNudgeRules.fallbackBody(n)
            when (show) {
                com.fariin.app.data.NudgeShow.NOTHING -> {}
                com.fariin.app.data.NudgeShow.NOTIFICATION -> NotificationPoster.postGroupNudge(c, head, words, n, quiet = false)
                com.fariin.app.data.NudgeShow.QUIET_NOTIFICATION -> {
                    NotificationPoster.postGroupNudge(c, head, words, n, quiet = true)
                    InAppNotify.present(c, InAppRoute.InApp(null, prefs.sound, prefs.vibrate))
                }
                com.fariin.app.data.NudgeShow.BANNER -> {
                    // iOS InAppBannerCenter.showNudge: their face, "NAME" over "wants to talk", Join.
                    val face = (conv?.get("photos") as? Map<*, *>)?.get(n.callerUid) as? String
                    InAppNotify.present(c, InAppRoute.InApp(InAppBanner(n.cid, n.callerName, "wants to talk", face?.ifEmpty { null }, nudge = n),
                        prefs.sound, prefs.vibrate))
                }
            }
        }

        /** The multi-person call invite (AdhocInviteRules.route), on the main thread. */
        private fun showAdhocInvite(c: Context, p: com.fariin.app.data.AdhocCallPush, title: String, body: String) {
            val me = com.fariin.app.Fb.auth.currentUser?.uid.orEmpty()
            val show = com.fariin.app.data.AdhocInviteRules.route(p, me, Push.pushOn(c),
                myCallId = com.fariin.app.call.GroupCall.ui.value.cid,
                otherCallLive = com.fariin.app.call.CallManager.ui.value.status.live,
                inFront = com.fariin.app.AppLifecycle.inFront, locked = appLockShowing(c))
            when (show) {
                com.fariin.app.data.AdhocShow.NOTHING -> {}
                com.fariin.app.data.AdhocShow.BUSY -> com.fariin.app.call.GroupRing.answer("busy", p.roomId, null, roomKind = "adhoc")
                com.fariin.app.data.AdhocShow.NOTIFICATION -> NotificationPoster.postAdhocInvite(c, title, body, p, quiet = false)
                com.fariin.app.data.AdhocShow.QUIET_NOTIFICATION -> {
                    NotificationPoster.postAdhocInvite(c, title, body, p, quiet = true)
                    val prefs = InAppNotify.prefs(c)
                    InAppNotify.present(c, InAppRoute.InApp(null, prefs.sound, prefs.vibrate))
                }
                com.fariin.app.data.AdhocShow.INVITE -> com.fariin.app.call.GroupRing.onAdhocInvite(c, p.roomId, ring = true)
            }
        }

        suspend fun handle(c: Context, p: PushData) {
            DeviceRegistry.init(c)
            // The delivered tick runs on its own (H-155): a coroutineScope here waited for it before
            // anything showed. Awaited at the end, so the push is not killed before it is sent.
            val ack = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { sendAck(p) }
            val me = com.fariin.app.Fb.auth.currentUser?.uid ?: ""
            val opened = HashMap<String, String?>()
            // A sender whose key this phone has not seen yet: one short fetch, then open again.
            p.cipher?.let { ct ->
                val cid = p.cid ?: return@let
                val aid = p.aid ?: return@let
                if (me.isEmpty()) return@let
                var text = open(c, ct, cid, aid)
                if (text == null) {
                    val crypto = CryptoHub.get(c)
                    if (!crypto.hasCachedKey(aid)) {
                        withTimeoutOrNull(KEY_TIMEOUT_MS) { crypto.preloadKey(aid) }
                        text = open(c, ct, cid, aid)
                    }
                }
                opened[ct] = text
            }
            val decision = NotifBuilder.decide(
                p, me,
                chats = { NotifStore.chat(c, it) },
                open = { ct, _, _ -> opened[ct] },
                activeChat = PushRouter.activeChatId,
                pushOn = Push.pushOn(c),
                blocked = { com.fariin.app.data.BlockList.state.value.contains(it) },
            )
            if (decision is NotifDecision.Skip) android.util.Log.d("FariinPush", "not shown: ${decision.why}")
            // In front, a chat message is our banner, not the system's (iOS PushManager.willPresent).
            val sharing = ScreenShareLive.on()
            val route = InAppRules.route(decision, com.fariin.app.AppLifecycle.inFront, locked = appLockShowing(c),
                prefs = InAppNotify.prefs(c), localPhoto = p.cid?.let { NotifStore.chat(c, it)?.photo }, sharing = sharing)
            if (route is InAppRoute.InApp) {
                InAppNotify.present(c, route)
                val s = (decision as NotifDecision.Show).spec
                // Only a real message moves the widget row (K-76: not a reaction or an accept).
                if (s.opensChat && s.noticeTag == null) com.fariin.app.system.FariinWidget.notePush(c, s.key)
            } else if (decision is NotifDecision.Show) {
                // While my screen is shared in a call, the shade shows no name or words (K-23).
                val s = if (sharing) NotifBuilder.hidden(decision.spec) else decision.spec
                val photos = if (s.plain) listOf(null, null) else kotlinx.coroutines.coroutineScope {
                    listOf(s.senderPhoto, s.groupPhoto).map { url -> async(kotlinx.coroutines.Dispatchers.IO) { url?.let { loadAvatar(c, it) } } }
                        .map { it.await() }
                }
                // The app icon number: the push has no count on Android, so last known + this one
                // (a channel post's count lives in the app, as on iOS; in front the list syncs it).
                AppBadge.notePush(c, me, s.opensChat && !s.isChannel && s.noticeTag == null && !com.fariin.app.AppLifecycle.inFront,
                    com.fariin.app.data.AppBadgeRules.messageKey(p.cid, p.cipher))
                NotificationPoster.post(c, s, photos[0], photos[1], me)
                // The home screen widget: that chat moves to the top (names only, no words or counts).
                if (s.opensChat && s.noticeTag == null) com.fariin.app.system.FariinWidget.notePush(c, s.key)
            }
            ack.await()
        }

        /** The lock page is up (or about to be): on, and not unlocked since the app came back. */
        private fun appLockShowing(c: Context): Boolean = try {
            com.fariin.app.security.AppLock.enabled(c) && !com.fariin.app.security.AppLock.unlocked
        } catch (_: Exception) { false }

        /** The crypto's decrypt, the "could not open" answers turned into null. */
        private fun open(c: Context, ct: String, cid: String, aid: String): String? = try {
            val crypto = CryptoHub.get(c)
            val raw = if (ct.startsWith(Wire.ENCG1)) crypto.decryptGroup(ct, aid, cid) else crypto.decrypt(ct, cid)
            NotifBuilder.usable(raw)
        } catch (_: Exception) { null }

        /** The copy the app saved, then a download with a short limit (saved for next time). */
        suspend fun loadAvatar(c: Context, url: String): ByteArray? {
            NotifStore.cachedAvatar(c, url)?.let { return it }
            val bytes = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
                try {
                    val path = NotifStore.storagePath(url)
                    when {
                        path != null -> FirebaseStorage.getInstance().reference.child(path).getBytes(2_000_000).await()
                        url.startsWith("https://") && photoHostAllowed(url) -> download(url)
                        else -> null
                    }
                } catch (_: Exception) { null }
            } ?: return null
            NotifStore.saveAvatar(c, url, bytes)
            return bytes
        }

        /** Only our own buckets (NotifPhotoHost, J-44): a sender photo pointing anywhere else would
         *  tell that server the reader's IP and the time of every notification. */
        private fun photoHostAllowed(url: String): Boolean = com.fariin.app.data.NotifPhotoHost.allowed(url)

        private fun download(url: String): ByteArray? {
            val conn = URL(url).openConnection() as HttpURLConnection
            // A redirect could leave our bucket: not followed.
            conn.instanceFollowRedirects = false
            return try {
                conn.connectTimeout = PHOTO_TIMEOUT_MS.toInt(); conn.readTimeout = PHOTO_TIMEOUT_MS.toInt()
                if (conn.responseCode != 200) return null
                val len = conn.contentLengthLong
                if (len > 2_000_000) return null
                // Read in a loop that stops at 2 MB: a reply with no length was read whole (review).
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); if (out.size() > 2_000_000) return null
                    }
                    out.toByteArray()
                }.takeIf { it.isNotEmpty() }
            } finally { conn.disconnect() }
        }

        /** The delivered tick (NotificationService.sendAck): 1:1 pushes only. */
        private fun sendAck(p: PushData) {
            val cid = p.cid ?: return
            val uid = p.ackUid ?: return
            val ack = p.ack ?: return
            try {
                val conn = URL(ACK_URL).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = JSONObject().put("cid", cid).put("uid", uid).put("ack", ack).toString()
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    conn.responseCode
                } finally { conn.disconnect() }
            } catch (_: Exception) {}
        }
    }
}
