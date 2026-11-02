package com.fariin.app.data

import com.google.firebase.firestore.FirebaseFirestore

/**
 * iOS DeliveryReceipts: "it reached my phone". Every SERVER snapshot of the chat list confirms each
 * 1:1 chat whose newest message is theirs and newer than what I confirmed, as
 * `conversations/{cid}.delivered.<me>` = that chat's updatedAt in ms (one batch, at most 400).
 * Never for a group or a chat I blocked. The rule list is MessageRules.deliveredWrites.
 */
object DeliveryReceipts {
    private val written = HashMap<String, Double>()

    @Synchronized
    fun mark(convs: List<Conversation>, me: String, db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        if (me.isEmpty()) return
        val todo = MessageRules.deliveredWrites(convs, me, written)
        if (todo.isEmpty()) return
        val batch = db.batch()
        for ((cid, at) in todo) {
            batch.update(db.collection("conversations").document(cid), "delivered.$me", at)
            written[cid] = at
        }
        batch.commit()
    }

    @Synchronized
    fun reset() = written.clear()
}
