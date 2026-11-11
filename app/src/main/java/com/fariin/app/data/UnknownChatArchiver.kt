package com.fariin.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Settings > Chats > Automatically Archive (iOS UnknownChatArchiver). A chat someone else opened
 * with MY Chat Key (`acceptedVia` "pin", `acceptedBy` them) after the switch went on is moved to
 * Archived and muted, once per chat. The ledger stops a chat taken out of the archive by hand from
 * being put back on the next snapshot. Accepting the chat undoes only what this setting did.
 * The setting is per phone, as on iOS.
 */
object UnknownChatArchiver {
    private const val PREFS = "fariin_chats"
    /** The key the Settings switch already used on Android. */
    const val KEY = "unknownChats.autoArchive"
    private const val SINCE_KEY = "unknownChats.autoArchive.pinSince"
    private const val DONE_KEY = "unknownChats.autoArchive.done"
    private const val DONE_CAP = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private fun prefs(): SharedPreferences? = runCatching {
        com.google.firebase.FirebaseApp.getInstance().applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    /** The switch: from this moment on (chats already opened with the key stay where they are). */
    fun setOn(p: SharedPreferences, on: Boolean) {
        p.edit().putBoolean(KEY, on).putLong(SINCE_KEY, if (on) System.currentTimeMillis() else 0L).apply()
    }

    private fun handled(p: SharedPreferences): MutableList<String> =
        p.getString(DONE_KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableList()

    /** Claim a chat. False if this setting has already dealt with it once. */
    private fun claim(p: SharedPreferences, cid: String): Boolean = synchronized(lock) {
        val ids = handled(p)
        if (cid in ids) return false
        ids.add(cid)
        while (ids.size > DONE_CAP) ids.removeAt(0)
        p.edit().putString(DONE_KEY, ids.joinToString("\n")).apply()
        true
    }

    /** Every chat-list snapshot. Cheap, and nothing at all while the switch is off. */
    fun sweep(me: String, convs: List<Conversation>) {
        val p = prefs() ?: return
        if (!p.getBoolean(KEY, false) || me.isEmpty()) return
        var since = p.getLong(SINCE_KEY, 0L)
        if (since <= 0L) {
            since = System.currentTimeMillis()
            p.edit().putLong(SINCE_KEY, since).apply()
        }
        for (c in convs) {
            if (c.acceptedVia != "pin" || c.acceptedBy.isEmpty() || c.acceptedBy == me) continue
            if (c.acceptedAtMillis < since) continue
            if (c.isArchived(me)) continue
            if (!claim(p, c.id)) continue
            // Archive AND mute, as the footer says: archived alone would still buzz the phone.
            scope.launch {
                runCatching {
                    FirebaseFirestore.getInstance().collection("conversations").document(c.id).set(
                        mapOf("archivedBy" to mapOf(me to true), "mutedBy" to mapOf(me to GroupInfoCircles.FOREVER)),
                        SetOptions.merge()).await()
                }
            }
        }
    }

    /** Accepting the chat takes it back out, only when this setting put it away. */
    suspend fun undoAutoArchive(me: String, cid: String) {
        val p = prefs() ?: return
        val wasOurs = synchronized(lock) {
            val ids = handled(p)
            val ours = ids.remove(cid)
            if (ours) p.edit().putString(DONE_KEY, ids.joinToString("\n")).apply()
            ours
        }
        if (!wasOurs) return
        runCatching {
            FirebaseFirestore.getInstance().collection("conversations").document(cid).set(
                mapOf("archivedBy" to mapOf(me to false), "mutedBy" to mapOf(me to 0.0)), SetOptions.merge()).await()
        }
    }
}
