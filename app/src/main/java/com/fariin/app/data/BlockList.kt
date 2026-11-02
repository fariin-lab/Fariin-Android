package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private var uid: String? = null

    fun contains(uid: String) = snapshot.contains(uid)
    fun hides(author: String, atMillis: Double) = snapshot.hides(author, atMillis)

    @Synchronized fun follow(newUid: String?) {
        if (newUid == uid) return
        regs.forEach { it.remove() }; regs = emptyList()
        uid = newUid
        _snap.value = Snapshot(owner = newUid)
        if (newUid == null) return
        val me = FirebaseFirestore.getInstance().collection("users").document(newUid)
        regs = listOf(
            me.collection("blocked").addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener
                if (uid != newUid) return@addSnapshotListener
                val e = snap.documents.associate { d ->
                    // A just-made block has no server time yet: "from now".
                    d.id to ((d.get("at") as? Timestamp)?.toDate()?.time?.toDouble() ?: System.currentTimeMillis().toDouble())
                }
                _snap.value = _snap.value.copy(owner = newUid, entries = e)
            },
            me.collection("blockHistory").addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener
                if (uid != newUid) return@addSnapshotListener
                val s = HashMap<String, MutableList<ClosedFloatingPointRange<Double>>>()
                for (d in snap.documents) {
                    val other = d.getString("other") ?: continue
                    val from = (d.get("from") as? Timestamp)?.toDate()?.time?.toDouble() ?: continue
                    val to = (d.get("to") as? Timestamp)?.toDate()?.time?.toDouble() ?: continue
                    if (to >= from) s.getOrPut(other) { mutableListOf() }.add(from..to)
                }
                _snap.value = _snap.value.copy(owner = newUid, spans = s)
            },
        )
    }
}
