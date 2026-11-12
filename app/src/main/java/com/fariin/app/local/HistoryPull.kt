package com.fariin.app.local

import android.content.Context
import com.fariin.app.data.AppFeatures
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.Message
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Device-storage plan phase 4 (same logic as iOS 79b95163e): every PRIVATE chat (a 1:1 or a
 * non-public group, never a channel) is copied into the phone's history once, all the way to its
 * first message, so the no-gap mark can start and the server may later delete delivered messages.
 *
 * Per chat with no mark: T0 = the newest message's time when the pull starts; pages of 100 going
 * back, server only; each page committed before the progress note ("pull.<cid>" = "<oldest>|<T0>")
 * moves, so a kill resumes where it stopped. A page with a message this phone cannot open yet ("…")
 * stops that chat for now (keys still coming). At the start of the chat: mark = T0, the note says
 * done, the cursor goes to T0. An empty chat gets mark 0 and no cursor. One chat at a time, off the
 * main thread, only while config/features.historyPull is on.
 */
object HistoryPull {
    private const val PAGE = 100L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun kick(ctx: Context, me: String) {
        if (!AppFeatures.historyPull || job?.isActive == true) return
        job = scope.launch {
            delay(10_000)   // after the start's own work
            val crypto = CryptoHub.get(ctx)
            for (c in CryptoHub.listedAll()) {
                if (c.isChannel || (c.isGroup && c.isPublicGroup) || !c.users.contains(me)) continue
                if (LocalDb.meta(me, "pull.${c.id}") == "done") continue
                runCatching { pull(me, c.id, crypto) }
                delay(500)
            }
        }
    }

    private suspend fun pull(me: String, cid: String, crypto: com.fariin.app.crypto.FariinCrypto) {
        val msgs = FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("messages")
        val note = LocalDb.meta(me, "pull.$cid")?.split("|")
        var t0 = note?.getOrNull(1)?.toLongOrNull()
        var oldest = note?.getOrNull(0)?.toLongOrNull()
        if (t0 == null) {
            val top = msgs.orderBy("createdAt", Query.Direction.DESCENDING).limit(1).get(Source.SERVER).await()
            val newest = top.documents.firstOrNull()?.getTimestamp("createdAt")?.toDate()?.time
            if (newest == null) {   // an empty chat: nothing to vouch for yet
                LocalDb.setSyncedUpTo(me, cid, 0L); LocalDb.setMeta(me, "pull.$cid", "done"); return
            }
            t0 = newest; oldest = newest + 1
            LocalDb.setMeta(me, "pull.$cid", "$oldest|$t0")
        }
        while (true) {
            val snap = msgs.orderBy("createdAt", Query.Direction.DESCENDING)
                .whereLessThan("createdAt", com.google.firebase.Timestamp(java.util.Date(oldest ?: (t0 + 1))))
                .limit(PAGE).get(Source.SERVER).await()
            val page = snap.documents.map { d ->
                Message.fromFirestore(d.id, d.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
            }
            if (page.any { (it.text == "\u2026" || it.text == com.fariin.app.crypto.Wire.LOCKED) && !it.deleted }) return   // keys not in yet: try again later
            if (!LocalDb.saveNow(me, cid, page)) return
            val pageOldest = snap.documents.mapNotNull { it.getTimestamp("createdAt")?.toDate()?.time }.minOrNull()
            if (pageOldest != null) { oldest = pageOldest; LocalDb.setMeta(me, "pull.$cid", "$oldest|$t0") }
            if (snap.size() < PAGE) break
        }
        // The whole history up to T0 is on this phone: the mark starts, and the cursor with it.
        LocalDb.setSyncedUpTo(me, cid, maxOf(LocalDb.syncedUpTo(me, cid) ?: 0L, t0!!))
        LocalDb.setMeta(me, "pull.$cid", "done")
        Cursors.advance(cid, t0)
    }
}
