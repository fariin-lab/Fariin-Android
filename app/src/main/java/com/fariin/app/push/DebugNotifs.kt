package com.fariin.app.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.KeyDirectory
import com.fariin.app.crypto.MemoryKeyValueStore
import com.fariin.app.crypto.MemorySecretStore
import com.fariin.app.crypto.NaCl
import com.fariin.app.crypto.PeerKeys
import com.fariin.app.crypto.Wire
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * DEBUG BUILDS ONLY (MainActivity checks FLAG_DEBUGGABLE): `adb shell am start -n
 * com.fariin.app/.MainActivity --ez notifDemo true` posts made-up notifications through the real
 * path: two made-up accounts with real keys (libsodium on this phone), a real enc1 and encg1
 * envelope, [PushData.parse] on the server's keys, [NotifBuilder.decide], [NotificationPoster].
 * No Firebase, no sign-in, nothing leaves the phone.
 */
object DebugNotifs {
    private const val ME = "demo-me"

    private class Dir : KeyDirectory {
        val keys = HashMap<String, String>()
        override suspend fun fetch(uid: String, serverOnly: Boolean): KeyDirectory.Fetch =
            keys[uid]?.let { KeyDirectory.Fetch.Key(it, emptyList()) } ?: KeyDirectory.Fetch.NoKey
        override suspend fun publishIfChanged(uid: String, b64: String) { keys[uid] = b64 }
    }

    /**
     * DEBUG BUILDS ONLY: `--es notifChat <cid>` builds a REAL notification for a real chat of the
     * signed-in account (the local backend): the newest message from someone else in that chat,
     * its sealed text, sender and group exactly as a push carries them, through
     * [FariinMessagingService.handle] (decrypt, NotifBuilder, NotificationPoster). Push delivery
     * is the only part skipped. Then Reply / Mark as read in the shade are the real thing.
     */
    fun postReal(c: Context, cid: String) {
        val app = c.applicationContext
        thread(name = "notif-real") {
            runBlocking {
                android.util.Log.d("FariinPush", "debug push for $cid")
                val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@runBlocking
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val q = db.collection("conversations").document(cid).collection("messages")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(20)
                // A server read can stall on the emulator; the phone's own copy is enough for a test push.
                val docs = try {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) { q.get().await() }
                        ?: q.get(com.google.firebase.firestore.Source.CACHE).await()
                } catch (e: Exception) { android.util.Log.d("FariinPush", "debug push: read failed $e"); return@runBlocking }
                val m = docs.documents.firstOrNull { it.getString("authorId").let { a -> a != null && a != me } }
                    ?: run { android.util.Log.d("FariinPush", "debug push: no message from them"); return@runBlocking }
                val aid = m.getString("authorId")!!
                val info = NotifStore.chat(app, cid)
                val group = Wire.isGroupCid(cid)
                val data = hashMapOf("cid" to cid, "aid" to aid, "mid" to m.id, "ct" to (m.getString("text") ?: ""))
                if (group) data["g"] = "1"
                // A disappearing message's stamp rides in the push as ms (the server's onNewMessage).
                m.getTimestamp("expiresAt")?.let { data["expiresAt"] = it.toDate().time.toString() }
                val sender = if (group) "Member" else info?.name ?: "Fariin"
                data["sn"] = sender
                val title = if (group) info?.name ?: "Group" else sender
                FariinMessagingService.handle(app, PushData.parse(data, title, "New message"))
            }
        }
    }

    fun post(c: Context) {
        val app = c.applicationContext
        thread(name = "notif-demo") { runBlocking { postAll(app) } }
    }

    private suspend fun postAll(c: Context) {
        val dir = Dir()
        val nacl = NaCl.android()
        fun device(uid: String) = FariinCrypto(nacl, MemorySecretStore(), PeerKeys(MemoryKeyValueStore()), dir, { uid })
        val amina = device("amina"); val me = device(ME); val omar = device("omar")
        // publishPublicKey waits for the write (ensureReady publishes in the background, and a
        // fetch racing it found no key: the first run showed the 1:1 lines as the hint).
        for (d in listOf(amina, me, omar)) { d.ensureReady(); d.publishPublicKey() }
        me.preloadKey("amina"); me.preloadKey("omar")
        val open = { ct: String, cid: String, aid: String ->
            NotifBuilder.usable(if (ct.startsWith(Wire.ENCG1)) me.decryptGroup(ct, aid, cid) else me.decrypt(ct, cid))
        }
        val dm = Wire.directCid("amina", ME)
        val chats = mapOf(
            dm to ChatInfo(false, "Amina Yusuf", "demo://amina"),
            "demo-group" to ChatInfo(true, "Family", null, mapOf("omar" to "demo://omar")),
        )
        val faces = mapOf("demo://amina" to face(Color.rgb(0xF5, 0x8B, 0x5B), Color.rgb(0xD9, 0x4F, 0x7A), "A"))

        val pushes = listOf(
            // 1:1, opened on this phone.
            PushData.parse(mapOf("cid" to dm, "aid" to "amina", "sn" to "Amina", "mid" to "m1", "ackUid" to ME,
                "ct" to amina.encryptForConversation(dm, "Are you coming to dinner tonight?")), "Amina", "New message"),
            PushData.parse(mapOf("cid" to dm, "aid" to "amina", "sn" to "Amina", "mid" to "m2", "ackUid" to ME, "kind" to "image",
                "ct" to amina.encryptForConversation(dm, "The new house")), "Amina", "📷 Photo"),
            // Group, sealed to me and Omar; Omar has no photo saved for the group.
            PushData.parse(mapOf("cid" to "demo-group", "aid" to "omar", "sn" to "Omar", "g" to "1", "mid" to "m3",
                "ct" to omar.encryptForGroup("I booked the hall for Friday", listOf("omar", ME, "amina"))),
                "Family", "Omar: New message"),
            // A stranger's first message: a request, no ciphertext, the server's hint.
            PushData.parse(mapOf("cid" to Wire.directCid("hodan", ME), "aid" to "hodan", "sn" to "Hodan"), "Hodan", "New message request"),
            // Message Preview off: nothing personal.
            PushData.parse(mapOf("cid" to Wire.directCid("zz", ME), "aid" to "zz", "ackUid" to ME), "Fariin", "New message"),
        )
        for (p in pushes) {
            val d = NotifBuilder.decide(p, ME, { chats[it] }, open)
            if (d !is NotifDecision.Show) continue
            val s = d.spec
            NotificationPoster.post(c, s, s.senderPhoto?.let { faces[it] }, s.groupPhoto?.let { faces[it] }, ME)
            Thread.sleep(400)
        }
    }

    /** A made-up photo: a soft gradient with an initial. */
    private fun face(a: Int, b: Int, letter: String): ByteArray {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, n.toFloat(), n.toFloat(), a, b, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
        p.shader = null; p.color = Color.WHITE; p.textSize = 130f; p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        cv.drawText(letter, n / 2f, n / 2f + 46f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
