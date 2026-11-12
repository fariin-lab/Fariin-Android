package com.fariin.app.local

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivery cursors (device-storage plan phase 2, same as iOS a32f109de): "this phone has stored
 * every message of this chat up to here". Written to conversations/{cid}/cursors/{me} as
 * { deliveredUpTo: ms, at: server time }, only forward, at most once per chat every 20 s, never for
 * a channel. Harmless while the server's deleteDelivered switch is off; it is what lets the server
 * delete a private chat's messages once every member's phone has them.
 */
object Cursors {
    private const val EVERY_MS = 20_000L
    private val sent = ConcurrentHashMap<String, Long>()
    private val lastAt = ConcurrentHashMap<String, Long>()
    private val waiting = ConcurrentHashMap<String, Long>()

    fun advance(cid: String, upToMs: Long) {
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (upToMs - 1 <= (sent[cid] ?: 0L)) return
        val now = System.currentTimeMillis()
        val since = now - (lastAt[cid] ?: 0L)
        if (since < EVERY_MS) {
            // Too soon: keep the newest and write it when the 20 s are up.
            val had = waiting.put(cid, maxOf(upToMs, waiting[cid] ?: 0L))
            if (had == null) java.util.Timer().schedule(object : java.util.TimerTask() {
                override fun run() { waiting.remove(cid)?.let { write(me, cid, it) } }
            }, EVERY_MS - since)
            return
        }
        write(me, cid, upToMs)
    }

    private fun write(me: String, cid: String, upToMs: Long) {
        if (upToMs - 1 <= (sent[cid] ?: 0L)) return
        val now = System.currentTimeMillis()
        // Never ahead of the server's clock check (the rules allow a minute of drift).
        // One ms under the newest filed row: a row 0.7 ms after a whole ms can never fall under it
        // (the server also deletes strictly below the line). Same as iOS.
        val value = minOf(upToMs - 1, now)
        sent[cid] = value; lastAt[cid] = now
        FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("cursors").document(me)
            .set(mapOf("deliveredUpTo" to value, "at" to FieldValue.serverTimestamp()))
            .addOnFailureListener { if (sent[cid] == value) sent.remove(cid) }
    }

    fun reset() { sent.clear(); lastAt.clear(); waiting.clear() }
}
