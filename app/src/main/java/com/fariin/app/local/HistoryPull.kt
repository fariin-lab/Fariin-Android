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
        // Not while the restore question is open: the gate kicks again once it is answered.
        if (com.fariin.app.backup.ChatBackup.restoreQuestionOpen(me)) return
        job = scope.launch {
            delay(10_000)   // after the start's own work
            val crypto = CryptoHub.get(ctx)
            for (c in CryptoHub.listedAll()) {
                // 1:1 only (owner 2026-11-12): groups and channels stay on the server.
                if (c.isChannel || c.isGroup || !c.users.contains(me)) continue
                if (LocalDb.meta(me, "pull.${c.id}") == "done") continue
                runCatching { pull(ctx, me, c.id, crypto) }
                delay(500)
            }
        }
    }

    private suspend fun pull(ctx: Context, me: String, cid: String, crypto: com.fariin.app.crypto.FariinCrypto) {
        val msgs = FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("messages")
        // When I joined (groups): only a message from before that may be "not for me" for good.
        val joinedAt = runCatching {
            val conv = FirebaseFirestore.getInstance().collection("conversations").document(cid).get().await()
            com.fariin.app.data.Conversation.millis((conv.get("memberSince") as? Map<*, *>)?.get(me))
        }.getOrDefault(0.0)
        val note = LocalDb.meta(me, "pull.$cid")?.split("|")
        var t0 = note?.getOrNull(1)?.toLongOrNull()
        var oldest = note?.getOrNull(0)?.toLongOrNull()
        // The oldest message whose file is still not on this phone (iOS safety review item 6): kept in
        // the note across pages and a kill; the first mark is set one millisecond below it.
        var hold = note?.getOrNull(2)?.toLongOrNull()
        fun progress() = "$oldest|$t0" + (hold?.let { "|$it" } ?: "")
        if (t0 == null) {
            val top = msgs.orderBy("createdAt", Query.Direction.DESCENDING).limit(1).get(Source.SERVER).await()
            val newest = top.documents.firstOrNull()?.getTimestamp("createdAt")?.toDate()?.time
            if (newest == null) {   // an empty chat: nothing to vouch for yet
                LocalDb.setSyncedUpTo(me, cid, 0L); LocalDb.setMeta(me, "pull.$cid", "done"); return
            }
            t0 = newest; oldest = newest + 1
            LocalDb.setMeta(me, "pull.$cid", progress())
        }
        while (true) {
            val snap = msgs.orderBy("createdAt", Query.Direction.DESCENDING)
                .whereLessThan("createdAt", com.google.firebase.Timestamp(java.util.Date(oldest ?: (t0 + 1))))
                .limit(PAGE).get(Source.SERVER).await()
            val page = snap.documents.map { d ->
                Message.fromFirestore(d.id, d.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
            }
            // A group message sealed without me from before I joined can never open: it never holds the
            // mark (iOS 3c04a99d7), or a late-joined group would never get one.
            val notForMe = snap.documents.filter { d -> val raw = d.getString("text") ?: ""
                joinedAt > 0 && (d.getTimestamp("createdAt")?.toDate()?.time ?: Long.MAX_VALUE) < joinedAt + 5000 &&
                raw.startsWith(com.fariin.app.crypto.Wire.ENCG1) && crypto.groupEnvelopeSkipsMe(raw, cid) }.mapTo(HashSet()) { it.id }
            if (page.any { it.id !in notForMe && (it.text == "…" || it.text == com.fariin.app.crypto.Wire.LOCKED) && !it.deleted }) return   // keys not in yet: try again later
            if (!LocalDb.saveNow(me, cid, page)) return
            page.filter { MediaHold.missing(ctx, it) }.forEach { MediaHold.fetch(ctx, cid, crypto, it) }
            val created = snap.documents.associate { it.id to (it.getTimestamp("createdAt")?.toDate()?.time ?: Long.MAX_VALUE) }
            page.filter { MediaHold.missing(ctx, it) }.minOfOrNull { created[it.id] ?: Long.MAX_VALUE }
                ?.let { h -> hold = minOf(hold ?: Long.MAX_VALUE, h) }
            val pageOldest = snap.documents.mapNotNull { it.getTimestamp("createdAt")?.toDate()?.time }.minOrNull()
            if (pageOldest != null) { oldest = pageOldest; LocalDb.setMeta(me, "pull.$cid", progress()) }
            if (snap.size() < PAGE) break
        }
        // The whole history up to T0 is on this phone: the mark starts, and the cursor with it,
        // below the oldest message whose file is still missing (InboxSync goes on from there).
        val start = hold?.let { minOf(t0!!, it - 1) } ?: t0!!
        LocalDb.setSyncedUpTo(me, cid, maxOf(LocalDb.syncedUpTo(me, cid) ?: 0L, start))
        LocalDb.setMeta(me, "pull.$cid", "done")
        if (start > 0) Cursors.advance(cid, start)
    }
}
