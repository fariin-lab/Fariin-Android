package com.fariin.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Which incoming voice notes have been PLAYED, not just seen (iOS PlayedVoice): per chat, the send
 * time of the newest note played on this phone. The chat list draws an unheard newest note's mic in
 * the accent colour. Opening a chat is not hearing a note, so this is apart from the unread count.
 */
object PlayedVoice {
    private const val PREFS = "fariin_played_voice"
    private const val KEY = "upTo"
    private val _upTo = MutableStateFlow<Map<String, Double>>(emptyMap())
    val upTo: StateFlow<Map<String, Double>> = _upTo.asStateFlow()
    @Volatile private var loaded = false

    fun init(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            _upTo.value = runCatching {
                val o = JSONObject(raw ?: "{}")
                o.keys().asSequence().associateWith { o.getDouble(it) }
            }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    /** A note in [cid] sent at [createdAtMs] was played. */
    fun markPlayed(c: Context, cid: String, createdAtMs: Double) {
        init(c)
        tellServer(cid, createdAtMs)
        if (createdAtMs <= (_upTo.value[cid] ?: 0.0)) return
        val next = _upTo.value + (cid to createdAtMs)
        _upTo.value = next
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONObject(next as Map<*, *>).toString()).apply()
    }

    // The sender's side (iOS ChatService.markVoicePlayedThrottled): my mark in the chat's
    // `lastPlayedVoice.<me>`, the send time of the newest note I played, so their phone can show a
    // one-time note as Opened. Only with my read receipts on; never moved back; at most one write per
    // chat every 2 s (the last mark in that window goes out at its end); not for the official chat.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun tellServer(cid: String, createdAtMs: Double) {
        if (createdAtMs <= 0 || cid.isEmpty() || cid == OfficialChannel.CID || !PrivacySettings.readReceipts()) return
        pending.merge(cid, createdAtMs) { a, b -> maxOf(a, b) }
        val now = android.os.SystemClock.elapsedRealtime()
        if ((coolUntil[cid] ?: 0L) > now) return
        coolUntil[cid] = now + 2000
        flush(cid)
        main.postDelayed({ if (pending.containsKey(cid)) { coolUntil.remove(cid); flush(cid) } }, 2050)
    }

    private fun flush(cid: String) {
        val ms = pending.remove(cid) ?: return
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("conversations").document(cid)
            .set(mapOf("lastPlayedVoice" to mapOf(me to ms)), com.google.firebase.firestore.SetOptions.merge())
    }

    /** iOS Conversation.voicePlayedByOther: anyone but me played up to [createdAtMs] (a group: anybody). */
    fun playedByOther(conv: Conversation?, me: String, createdAtMs: Double): Boolean =
        conv != null && conv.lastPlayedVoice.any { (k, v) -> k != me && v >= createdAtMs }

    /** Chat list: the newest message is someone else's voice note not played yet. */
    fun lastVoiceUnplayed(conv: Conversation, me: String, upTo: Map<String, Double>): Boolean {
        if (!conv.lastMessageCipher.startsWith("🎤 Voice message") || conv.lastIsMine(me) || conv.leaksBlocked(me)) return false
        return conv.updatedAtMillis > (upTo[conv.id] ?: 0.0)
    }
}
