package com.fariin.app.call

import com.fariin.app.data.AuthRepository
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Every Firestore and callable touch of a 1:1 call, with the iOS shapes from [CallWire]. Nothing
 * here decides; [CallManager] does.
 */
class CallSignaling(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    private val ts get() = FieldValue.serverTimestamp()
    private fun call(id: String) = db.collection(CallWire.CALLS).document(id)

    fun newCallId(): String = db.collection(CallWire.CALLS).document().id

    /** The `iceServers` callable (me-central1). Empty on any failure. */
    suspend fun fetchIceServers(): List<IceServerSpec> = try {
        val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("iceServers").call().await()
        CallWire.parseIceServers(r.getData())
    } catch (_: Exception) { emptyList() }

    /** Accepted 1:1 chat = established contact (iOS resolvePeerTrust). Any failure = stranger. */
    suspend fun isEstablishedContact(cid: String): Boolean = try {
        db.collection("conversations").document(cid).get().await().getBoolean("accepted") == true
    } catch (_: Exception) { false }

    /**
     * Online-only create, as iOS: a transaction is never queued offline, so a dial without network
     * fails in seconds instead of ringing somebody later. Returns null on success, or the error.
     */
    suspend fun create(id: String, fields: Map<String, Any>): Exception? = try {
        db.runTransaction { txn -> txn.set(call(id), fields); null }.await(); null
    } catch (e: Exception) { e }

    fun isPermissionDenied(e: Exception) =
        (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

    fun update(id: String, fields: Map<String, Any>, done: (Boolean) -> Unit = {}) {
        call(id).update(fields).addOnCompleteListener { done(it.isSuccessful) }
    }

    suspend fun readServer(id: String): Map<String, Any?>? = try {
        call(id).get(com.google.firebase.firestore.Source.SERVER).await().data
    } catch (_: Exception) { null }

    fun listen(id: String, onDoc: (CallDoc) -> Unit): ListenerRegistration =
        call(id).addSnapshotListener { snap, _ -> snap?.data?.let { onDoc(CallWire.parse(id, it)) } }

    /** New remote candidates only (each document once). */
    fun listenCandidates(id: String, sub: String, onDoc: (Map<String, Any?>) -> Unit): ListenerRegistration {
        val seen = HashSet<String>()
        return call(id).collection(sub).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                if (ch.type == DocumentChange.Type.ADDED && seen.add(ch.document.id)) onDoc(ch.document.data)
            }
        }
    }

    fun writeCandidate(id: String, sub: String, doc: Map<String, Any>, attempt: Int = 1) {
        call(id).collection(sub).add(doc).addOnFailureListener {
            if (attempt < 3) writeCandidate(id, sub, doc, attempt + 1)
        }
    }

    /** Rings for me: callee == me and status == ringing, server answers only (iOS observeIncoming). */
    fun listenIncoming(me: String, onDocs: (List<CallDoc>, Map<String, Long>) -> Unit): ListenerRegistration =
        db.collection(CallWire.CALLS).whereEqualTo("callee", me).whereEqualTo("status", "ringing")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null || snap.metadata.isFromCache) return@addSnapshotListener
                val created = HashMap<String, Long>()
                val docs = snap.documents.mapNotNull { d ->
                    val data = d.data ?: return@mapNotNull null
                    d.getTimestamp("createdAt")?.let { created[d.id] = it.toDate().time }
                    CallWire.parse(d.id, data)
                }
                onDocs(docs, created)
            }

    /** The server's privacy screen for this ring: users/{me}/callScreens/{id} {allowed: false} = refused. */
    suspend fun screenRefused(me: String, id: String): Boolean = try {
        val s = db.collection("users").document(me).collection("callScreens").document(id).get().await()
        s.exists() && s.getBoolean("allowed") == false
    } catch (_: Exception) { false }

    /** iOS claimAnswer: one device wins the answer. Returns null (claimed) or "taken"/"cancelled"/"ended". */
    suspend fun claimAnswer(id: String, device: String): String? = try {
        db.runTransaction { txn ->
            val d = txn.get(call(id)).data
            val out = CallWire.claimOutcome(d, device)
            if (out == null) txn.update(call(id), CallWire.claimField(device))
            out
        }.await()
    } catch (_: Exception) { null }

    /** The caller's end of a call the callee never accepted (iOS finishCall). */
    suspend fun callerEnd(id: String, reason: EndReason): CallWire.CallerEnd? = try {
        db.runTransaction { txn ->
            val r = CallWire.callerEnd(txn.get(call(id)).data)
            when (r) {
                CallWire.CallerEnd.AlreadyEnded -> {}
                CallWire.CallerEnd.AcceptedMeanwhile -> txn.update(call(id), CallWire.endFields(EndReason.HANGUP))
                CallWire.CallerEnd.Cancel -> txn.update(call(id), CallWire.cancelFields(reason, ts))
            }
            r
        }.await()
    } catch (_: Exception) {
        try { call(id).update(CallWire.endFields(reason)).await() } catch (_: Exception) {}
        null
    }

    /** The callee's end of a ring it never answered: only while no device answered it. */
    suspend fun calleeEndRing(id: String, fields: Map<String, Any>, device: String) {
        try {
            db.runTransaction { txn ->
                if (CallWire.calleeMayEndRing(txn.get(call(id)).data, device)) txn.update(call(id), fields)
                null
            }.await()
        } catch (_: Exception) {}
    }

    fun endPlain(id: String, reason: EndReason) { call(id).update(CallWire.endFields(reason)) }

    // ------------------------------------------------------------------ the chat row

    private fun row(cid: String, id: String) =
        db.collection("conversations").document(cid).collection("messages").document(CallWire.ringRowId(id))

    suspend fun writeRingRow(cid: String, id: String, callerUid: String, video: Boolean) {
        try { row(cid, id).set(CallWire.ringRowFields(callerUid, video, ts), SetOptions.merge()).await() } catch (_: Exception) {}
    }

    fun markOngoing(cid: String, id: String) { row(cid, id).update("callOutcome", "ongoing") }

    suspend fun deleteRingRow(cid: String, id: String) {
        try { row(cid, id).delete().await() } catch (_: Exception) {}
    }

    /** ChatService.recordCall, step for step. */
    suspend fun recordCall(me: String, cid: String, id: String, callerUid: String, outcome: String, video: Boolean, durationSec: Int) {
        val ref = row(cid, id)
        val existing = try { ref.get().await() } catch (_: Exception) { null }
        if (CallWire.recordSkipped(outcome, existing?.getString("callOutcome"))) return
        val isNew = existing?.exists() != true
        val fields = CallWire.recordFields(me, callerUid, outcome, video, durationSec, isNew, ts)
        try { ref.set(fields, SetOptions.merge()).await() } catch (_: Exception) {
            if (isNew) try { ref.set(fields - "createdAt", SetOptions.merge()).await() } catch (_: Exception) {}
        }
        try {
            db.collection("conversations").document(cid)
                .set(CallWire.convMarkerFields(outcome, video, callerUid, ts), SetOptions.merge()).await()
        } catch (_: Exception) {}
    }

    val serverTime: Any get() = ts
}
