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
    private const val IDS = "ids"
    private val _upTo = MutableStateFlow<Map<String, Double>>(emptyMap())
    val upTo: StateFlow<Map<String, Double>> = _upTo.asStateFlow()
    /** The notes played on this phone, by message id (iOS playedVoiceIds, the last 600 kept). */
    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids.asStateFlow()
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
            val rawIds = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(IDS, null)
            _ids.value = runCatching {
                val a = org.json.JSONArray(rawIds ?: "[]")
                List(a.length()) { a.getString(it) }
            }.getOrDefault(emptyList())
            loaded = true
        }
    }

    /** A note in [cid] sent at [createdAtMs] was played; [messageId] is kept for the bubble's dot. */
    fun markPlayed(c: Context, cid: String, createdAtMs: Double, messageId: String? = null) {
        init(c)
        tellServer(cid, createdAtMs)
        if (messageId != null && messageId !in _ids.value) {
            val list = (_ids.value + messageId).takeLast(600)
            _ids.value = list
            c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(IDS, org.json.JSONArray(list).toString()).apply()
        }
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
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        com.fariin.app.Fb.db.collection("conversations").document(cid)
            .set(mapOf("lastPlayedVoice" to mapOf(me to ms)), com.google.firebase.firestore.SetOptions.merge())
    }

    /** iOS Conversation.voicePlayedByOther: anyone but me played up to [createdAtMs] (a group: anybody). */
    fun playedByOther(conv: Conversation?, me: String, createdAtMs: Double): Boolean =
        conv != null && conv.lastPlayedVoice.any { (k, v) -> k != me && v >= createdAtMs }

    /**
     * The bubble's "not heard yet" dot (iOS isUnplayed): this note was never played here. Anything at or
     * before the newest played note of the chat counts as heard, so old history stays quiet.
     */
    fun isUnplayed(cid: String, messageId: String, createdAtMs: Double, upTo: Map<String, Double>, ids: List<String>): Boolean =
        messageId !in ids && createdAtMs > (upTo[cid] ?: 0.0)

    /** Sign-out: the played marks go with the account (iOS PlayedVoice.clear). */
    fun clear(c: Context) {
        _upTo.value = emptyMap()
        _ids.value = emptyList()
        pending.clear()
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        loaded = true
    }

    /** Chat list: the newest message is someone else's voice note not played yet. */
    fun lastVoiceUnplayed(conv: Conversation, me: String, upTo: Map<String, Double>): Boolean {
        if (!ForwardMark.strip(conv.lastMessageCipher).startsWith("🎤 Voice message") || conv.lastIsMine(me) || conv.leaksBlocked(me)) return false
        return conv.updatedAtMillis > (upTo[conv.id] ?: 0.0)
    }
}
