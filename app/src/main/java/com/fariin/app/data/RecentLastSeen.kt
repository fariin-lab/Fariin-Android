package com.fariin.app.data

import android.content.Context
import com.fariin.app.crypto.Wire
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Last seen for a Recent person row on the search page (iOS RecentLastSeen). Read once per person
 * when the row shows, kept for the run; no live listener. The rules are Presence.recentLine.
 */
object RecentLastSeen {
    private val memo = ConcurrentHashMap<String, Presence.Seen>()

    fun cached(me: String, uid: String): Presence.Seen? = memo["$me/$uid"]

    suspend fun load(context: Context, me: String, uid: String): Presence.Seen {
        val db = com.fariin.app.Fb.db
        val mine = Presence.myPrivacyNow() ?: try {
            Presence.privacyOf(db.collection("users").document(me).get().await().data).also { Presence.noteMyPrivacy(it) }
        } catch (_: Exception) { emptyMap() }
        val legacy = context.getSharedPreferences("fariin_privacy", Context.MODE_PRIVATE).getBoolean("shareLastSeen", true)
        val now = System.currentTimeMillis().toDouble()
        // Mine hidden: nobody's, and no reads (iOS returns before asking the server).
        val out = if (!Presence.shares(mine, legacy)) Presence.recentLine(mine, emptyMap(), false, false, false, null, now, legacy) else {
            val user = db.collection("users").document(uid)
            val theirs = try { Presence.privacyOf(user.get().await().data) } catch (_: Exception) { emptyMap() }
            // iOS PrivacyPrefs.isContact: a request chat counts once accepted; an old chat once it has a message.
            val contact = CryptoHub.listedConversation(Wire.directCid(me, uid))?.let {
                !it.isGroup && (if (it.startedBy.isNotEmpty()) it.accepted else it.lastMessageCipher.isNotEmpty())
            } ?: false
            val d = if (!Person.allows(theirs, "lastSeen", contact)) null else try {
                user.collection("presence").document("state").get().await().takeIf { it.exists() }?.data
            } catch (_: Exception) { null }
            Presence.recentLine(mine, theirs, contact, present = d != null, online = d?.get("online") == true,
                lastActiveMillis = d?.get("lastActive")?.let { Conversation.millis(it) }?.takeIf { it > 0 },
                nowMillis = now, legacySwitch = legacy)
        }
        memo["$me/$uid"] = out
        return out
    }
}
