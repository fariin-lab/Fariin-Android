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
        val alertBody = message.notification?.body ?: message.data["body"]
        if (message.data["kind"] != "call" && (alertBody == "Incoming call" || alertBody == "Incoming video call")) {
            FirebaseAuth.getInstance().currentUser?.uid?.let { com.fariin.app.call.IncomingCallWatcher.onCallPush(applicationContext, it) }
        }
        if (message.data["kind"] == "call") {
            val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
            com.fariin.app.call.IncomingCallWatcher.onCallPush(applicationContext, me)
            return
        }
        // A group chat's call ringing me (server onGroupCallStarted: data {cid, type: "groupcall"}).
        // With the app running the ring screen comes up (GroupRing); closed, Android drew the banner.
        if (message.data["type"] == "groupcall") {
            val cid = message.data["cid"].orEmpty()
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.fariin.app.call.GroupRing.onPush(applicationContext, cid) }
            return
        }
        // "Posted a new story" (server notifyStoryFollowers; no cid, so it is not a chat push). With
        // the app closed the system draws it from the notification part; with the app open it comes
        // here and is drawn the same way. A tap opens the Stories tab.
        if (message.data["kind"] == "story") {
            if (Push.pushOn(applicationContext)) NotificationPoster.postStory(applicationContext,
                message.notification?.title ?: "Fariin", message.notification?.body ?: "New story.", message.data["aid"].orEmpty())
            return
        }
        // A reply alert (owner 2026-11-06): its own notification, whose tap opens that post's Replies.
        // Not while that channel is open: its sheet refreshes there on its own.
        com.fariin.app.data.PostReplyRules.alertTarget(message.data["kind"], message.data["cid"], message.data["mid"])?.let { (cid, mid) ->
            if (Push.pushOn(applicationContext) && PushRouter.activeChatId != cid) NotificationPoster.postReplyAlert(applicationContext,
                message.notification?.title ?: "Fariin", message.notification?.body ?: "New reply", cid, mid,
                message.data["rid"].orEmpty(), message.data["aid"].orEmpty())
            return
        }
        val p = PushData.parse(message.data, message.notification?.title, message.notification?.body)
        runBlocking { handle(applicationContext, p) }
    }

    companion object {
        private const val ACK_URL = "https://me-central1-kulan-2ef85.cloudfunctions.net/ackDelivered"
        private const val PHOTO_TIMEOUT_MS = 4_000L
        private const val KEY_TIMEOUT_MS = 3_000L

        suspend fun handle(c: Context, p: PushData) {
            DeviceRegistry.init(c)
            val ack = kotlinx.coroutines.coroutineScope { async(kotlinx.coroutines.Dispatchers.IO) { sendAck(p) } }
            val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
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
            val route = InAppRules.route(decision, com.fariin.app.AppLifecycle.inFront, locked = appLockShowing(c),
                prefs = InAppNotify.prefs(c), localPhoto = p.cid?.let { NotifStore.chat(c, it)?.photo })
            if (route is InAppRoute.InApp) {
                InAppNotify.present(c, route)
                val s = (decision as NotifDecision.Show).spec
                if (s.opensChat) com.fariin.app.system.FariinWidget.notePush(c, s.key, if (s.plain) null else s.line)
            } else if (decision is NotifDecision.Show) {
                val s = decision.spec
                val photos = if (s.plain) listOf(null, null) else kotlinx.coroutines.coroutineScope {
                    listOf(s.senderPhoto, s.groupPhoto).map { url -> async(kotlinx.coroutines.Dispatchers.IO) { url?.let { loadAvatar(c, it) } } }
                        .map { it.await() }
                }
                NotificationPoster.post(c, s, photos[0], photos[1], me)
                // The home screen widget: one more unread for that chat (words only as the notification showed them).
                if (s.opensChat) com.fariin.app.system.FariinWidget.notePush(c, s.key, if (s.plain) null else s.line)
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

        /** Only our own storage hosts: a sender photo pointing anywhere else would tell that server the
         *  reader's IP and the time of every notification (review 2026-11-05). */
        private fun photoHostAllowed(url: String): Boolean {
            val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
            return host == "firebasestorage.googleapis.com" || host == "storage.googleapis.com" ||
                host.endsWith(".firebasestorage.app") || host.endsWith(".appspot.com")
        }

        private fun download(url: String): ByteArray? {
            val conn = URL(url).openConnection() as HttpURLConnection
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
