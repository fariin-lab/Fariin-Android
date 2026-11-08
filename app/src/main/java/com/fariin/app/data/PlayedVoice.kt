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
        if (createdAtMs <= (_upTo.value[cid] ?: 0.0)) return
        val next = _upTo.value + (cid to createdAtMs)
        _upTo.value = next
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONObject(next as Map<*, *>).toString()).apply()
    }

    /** Chat list: the newest message is someone else's voice note not played yet. */
    fun lastVoiceUnplayed(conv: Conversation, me: String, upTo: Map<String, Double>): Boolean {
        if (!conv.lastMessageCipher.startsWith("🎤 Voice message") || conv.lastIsMine(me) || conv.leaksBlocked(me)) return false
        return conv.updatedAtMillis > (upTo[conv.id] ?: 0.0)
    }
}
