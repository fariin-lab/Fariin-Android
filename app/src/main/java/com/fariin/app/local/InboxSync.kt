package com.fariin.app.local

import android.content.Context
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.Message
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Device-storage plan phase 2 (same rule as iOS): every private chat that moved since its mark is
 * filed into the phone's history without being opened, so a chat nobody opens still reaches its
 * cursor and nothing waits on the server only. On sign-in and every return to the front.
 *
 * Only chats that HAVE a mark (the no-gap rule): the pull continues from the mark, oldest first,
 * pages of 200, so what is filed always joins what was filed before; the mark and the cursor move
 * after each page commits. A chat without a mark is the history pull's (phase 4) to start.
 */
object InboxSync {
    private const val PAGE = 200L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun kick(ctx: Context, me: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            val crypto = CryptoHub.get(ctx)
            for (c in CryptoHub.listedAll()) {
                if (c.isChannel || (c.isGroup && c.isPublicGroup) || !c.users.contains(me)) continue
                val mark = LocalDb.syncedUpTo(me, c.id) ?: continue
                if (c.updatedAtMillis.toLong() <= mark) continue
                runCatching { follow(me, c.id, mark, crypto) }
            }
        }
    }

    private suspend fun follow(me: String, cid: String, from: Long, crypto: com.fariin.app.crypto.FariinCrypto) {
        var mark = from
        val msgs = FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("messages")
        while (true) {
            val snap = msgs.orderBy("createdAt", Query.Direction.ASCENDING)
                .whereGreaterThan("createdAt", com.google.firebase.Timestamp(java.util.Date(mark)))
                .limit(PAGE).get(Source.SERVER).await()
            if (snap.isEmpty) return
            val page = snap.documents.map { d ->
                Message.fromFirestore(d.id, d.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
            }
            if (page.any { (it.text == "…" || it.text == com.fariin.app.crypto.Wire.LOCKED) && !it.deleted }) return   // keys not in yet: the next kick tries again
            if (!LocalDb.saveNow(me, cid, page)) return
            val newest = snap.documents.mapNotNull { it.getTimestamp("createdAt")?.toDate()?.time }.maxOrNull() ?: return
            mark = maxOf(mark, newest)
            LocalDb.setSyncedUpTo(me, cid, mark)
            Cursors.advance(cid, mark)
            if (snap.size() < PAGE) return
        }
    }
}
