package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * My private block list, after Kulan-iOS BlockList.swift:
 *  - `users/{me}/blocked/{uid}` {at}: who I block now, since when;
 *  - `users/{me}/blockHistory/{uid_ms}` {other, from, to}: every block that has ended.
 * A message from someone, sent while I had them blocked (now or in any past block), is never shown,
 * before or after an unblock. Every answer is empty unless the list is the signed-in account's.
 */
object BlockList {
    data class Snapshot(
        val owner: String? = null,
        val entries: Map<String, Double> = emptyMap(),
        val spans: Map<String, List<ClosedFloatingPointRange<Double>>> = emptyMap(),
        /** The blocked list has been read for [owner]: from the server, or a saved copy that holds
         *  someone (iOS isLoaded). The chat list and delivered marks wait for it (F-61). */
        val loaded: Boolean = false,
    ) {
        fun contains(uid: String) = entries.containsKey(uid)

        /** iOS hides(author:atMillis:). */
        fun hides(author: String, atMillis: Double): Boolean {
            if (author.isEmpty()) return false
            entries[author]?.let { if (atMillis >= it) return true }
            return spans[author]?.any { atMillis in it } == true
        }
    }

    private val _snap = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _snap.asStateFlow()
    val snapshot: Snapshot get() = _snap.value

    private var regs: List<ListenerRegistration> = emptyList()
    @Volatile private var uid: String? = null
    // Both lists have come from the server once for [uid] (iOS isLoaded + loadedFor).
    @Volatile private var listLoaded = false
    @Volatile private var historyLoaded = false
    // Chats whose old block was already moved this run (iOS migrated).
    private val migrated = HashSet<String>()
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun contains(uid: String) = snapshot.contains(uid)
    fun hides(author: String, atMillis: Double) = snapshot.hides(author, atMillis)

    @Synchronized fun follow(newUid: String?) {
        if (newUid == uid) return
        regs.forEach { it.remove() }; regs = emptyList()
        uid = newUid
        listLoaded = false; historyLoaded = false; migrated.clear()
        _snap.value = Snapshot(owner = newUid)
        if (newUid == null) return
        val me = com.fariin.app.Fb.db.collection("users").document(newUid)
        regs = listOf(
            // Read on the listen thread; the state is set on the main thread, as follow() sets it.
            me.collection("blocked").addSnapshotListener(com.fariin.app.perf.ListenThread.exec) { snap, _ ->
                snap ?: return@addSnapshotListener
                if (uid != newUid) return@addSnapshotListener
                val e = snap.documents.associate { d ->
                    // A just-made block has no server time yet: "from now".
                    d.id to ((d.get("at") as? Timestamp)?.toDate()?.time?.toDouble() ?: System.currentTimeMillis().toDouble())
                }
                val server = !snap.metadata.isFromCache
                com.fariin.app.perf.ListenThread.toMain {
                    if (uid == newUid) {
                        _snap.value = _snap.value.copy(owner = newUid, entries = e, loaded = _snap.value.loaded || server || e.isNotEmpty())
                        if (server && !listLoaded) { listLoaded = true; migrate(CryptoHub.listedAll()) }
                    }
                }
            },
            me.collection("blockHistory").addSnapshotListener(com.fariin.app.perf.ListenThread.exec) { snap, _ ->
                snap ?: return@addSnapshotListener
                if (uid != newUid) return@addSnapshotListener
                val s = HashMap<String, MutableList<ClosedFloatingPointRange<Double>>>()
                for (d in snap.documents) {
                    val other = d.getString("other") ?: continue
                    val from = (d.get("from") as? Timestamp)?.toDate()?.time?.toDouble() ?: continue
                    val to = (d.get("to") as? Timestamp)?.toDate()?.time?.toDouble() ?: continue
                    if (to >= from) s.getOrPut(other) { mutableListOf() }.add(from..to)
                }
                val server = !snap.metadata.isFromCache
                com.fariin.app.perf.ListenThread.toMain {
                    if (uid == newUid) {
                        _snap.value = _snap.value.copy(owner = newUid, spans = s)
                        if (server && !historyLoaded) { historyLoaded = true; migrate(CryptoHub.listedAll()) }
                    }
                }
            },
        )
    }

    /**
     * iOS BlockList.migrate: a block kept the old way, on the shared 1:1 chat (where the blocked
     * person could read it), moves to my private list or history, and only then leaves the chat, so
     * hiding never has a gap. Once per chat per run, only for the signed-in account's loaded list.
     * Called when the chat list changes (CryptoHub.noteListed) and when this list first loads.
     */
    @Synchronized fun migrate(convs: List<Conversation>) {
        val me = uid ?: return
        if (!listLoaded || !historyLoaded || com.fariin.app.Fb.auth.currentUser?.uid != me) return
        for (c in convs) {
            if (c.isGroup || c.id in migrated) continue
            val blocked = c.blockedBy[me]
            val at = c.blockedAt[me] ?: 0.0
            val cleared = c.blockClearedAt[me] ?: 0.0
            if (blocked == null && at <= 0 && cleared <= 0) continue
            val other = c.otherUid(me)
            if (other.isEmpty() || other == me) continue
            migrated.add(c.id)
            io.launch { moveOldBlock(me, other, c.id, blocked == true, at, cleared) }
        }
    }

    private suspend fun moveOldBlock(me: String, other: String, cid: String, blocked: Boolean, at: Double, cleared: Double) {
        val current = snapshot
        val db = com.fariin.app.Fb.db
        val meRef = db.collection("users").document(me)
        val now = System.currentTimeMillis().toDouble()
        fun stamp(ms: Double) = Timestamp(java.util.Date(minOf(ms, now).toLong()))
        try {
            if (blocked) {
                if (!current.contains(other)) {
                    meRef.collection("blocked").document(other).set(mapOf("at" to stamp(if (at > 0) at else now))).await()
                }
            } else if (at > 0 && cleared >= at && current.spans[other]?.any { kotlin.math.abs(it.start - at) < 1 } != true) {
                // History is write-once: a span moved by an earlier run is not written again.
                meRef.collection("blockHistory").document("${other}_${at.toLong()}")
                    .set(mapOf("other" to other, "from" to stamp(at), "to" to stamp(cleared))).await()
            }
            db.collection("conversations").document(cid).update(mapOf(
                "blockedBy.$me" to FieldValue.delete(),
                "blockedAt.$me" to FieldValue.delete(),
                "blockClearedAt.$me" to FieldValue.delete(),
            )).await()
        } catch (_: Exception) {
            // Left as it was; the old copy is still honoured and the next run tries again.
        }
    }
}
