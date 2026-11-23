package com.fariin.app.local

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable outgoing TEXT queue, after Kulan-iOS SendQueue: a text send is written to disk BEFORE
 * the network call and removed only once the server has it, so a message typed just before the
 * app was killed (or the phone went offline) still goes out on the next start or reconnect,
 * instead of vanishing with the bubble.
 *
 * Duplicate-safe as on iOS: before a re-drive the message document (id = clientId) is looked for,
 * first in Firestore's own offline store (a write already queued there is sent by Firestore
 * itself) and then on the server; found = nothing to do. A refusal by the rules is final: the
 * entry is kept, marked refused, so the failed bubble is still there after a restart; nothing sends
 * it again on its own, and the user's Retry is a new send.
 *
 * One file per account (local-v1/<uid>/sendqueue.bin), sealed like the other local stores, and
 * wiped at sign-out with them.
 */
object SendQueue {
    data class Entry(
        val clientId: String,
        val cid: String,
        val text: String,
        val ts: Double,
        val replyId: String? = null,
        val replyAuthor: String? = null,
        val replyText: String? = null,
        /** Group @mentions (iOS SendQueue.Entry.mentions), so a resend still tags them. */
        val mentions: List<String> = emptyList(),
        /** A channel post's bold runs as the wire JSON (TextFormat), sealed only at the send. */
        val fmt: String? = null,
        /** Fariin Plus send effect (hearts, confetti, balloons, sparkles), plain `fx` like `bs`. */
        val fx: String? = null,
        /** The server refused it (iOS Entry.refused): kept only so the chat still draws it failed,
         *  with Retry and Delete, after a restart. Nothing sends it again on its own. */
        val refused: Boolean = false,
        /** When it was refused, in ms (a refused one left 30 days goes, iOS drainAll). */
        val refusedAt: Double = 0.0,
    )

    @Volatile private var root: File? = null
    private val entries = LinkedHashMap<String, Entry>()
    @Volatile private var loadedFor: String? = null
    /** Sends being driven right now (iOS beginSending / endSending), so two drivers never race. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun init(c: Context) {
        if (root == null) root = File(c.applicationContext.noBackupFilesDir, "local-v1")
    }

    private fun file(uid: String): File? = root?.let { File(File(it, uid.filter { ch -> ch.isLetterOrDigit() }), "sendqueue.bin") }

    @Synchronized private fun ensure(uid: String) {
        if (loadedFor == uid) return
        entries.clear()
        val f = file(uid)
        if (f != null && f.exists()) {
            val text = runCatching { AtRest.open(f.readBytes())?.toString(Charsets.UTF_8) }.getOrNull()
            val read = text?.let { runCatching { decode(it) }.getOrNull() }
            // A file that will not open is kept aside, never written over by the next save (L-83).
            if (read == null) SafeWrite.setAside(f) else read.forEach { entries[it.clientId] = it }
        }
        loadedFor = uid
    }

    /** Written synchronously: the whole point is that it is on disk before the send starts. */
    @Synchronized private fun save(uid: String) {
        val f = file(uid) ?: return
        try {
            if (entries.isEmpty()) { f.delete(); return }
            val sealed = AtRest.seal(encode(entries.values.toList()).toByteArray(Charsets.UTF_8)) ?: return
            SafeWrite.replace(f, sealed)   // temp file, then rename (L-83): never a torn queue
        } catch (_: Exception) {}
    }

    @Synchronized fun add(uid: String, e: Entry) { ensure(uid); entries[e.clientId] = e; save(uid) }

    @Synchronized fun remove(uid: String, clientId: String) {
        ensure(uid)
        if (entries.remove(clientId) != null) save(uid)
    }

    /** Keeps the entry but takes it out of every automatic send (iOS markRefused). */
    @Synchronized fun markRefused(uid: String, clientId: String) {
        ensure(uid)
        val e = entries[clientId] ?: return
        entries[clientId] = e.copy(refused = true, refusedAt = System.currentTimeMillis().toDouble())
        save(uid)
    }

    /** Refused sends older than [beforeMs] go: their chat may be one nobody can resend from. */
    @Synchronized fun pruneRefused(uid: String, beforeMs: Double) {
        ensure(uid)
        if (entries.values.removeAll { it.refused && it.refusedAt < beforeMs }) save(uid)
    }

    @Synchronized fun pending(uid: String, cid: String): List<Entry> { ensure(uid); return entries.values.filter { it.cid == cid } }

    @Synchronized fun all(uid: String): List<Entry> { ensure(uid); return entries.values.toList() }

    fun begin(clientId: String): Boolean = inFlight.add(clientId)
    fun end(clientId: String) { inFlight.remove(clientId) }
    fun isInFlight(clientId: String): Boolean = clientId in inFlight

    /** The rules said no (or the text broke a limit): never sent again on its own. Anything else
     *  (offline, a key not fetched yet, a timeout) stays queued for the next reconnect. */
    fun isPermanent(e: Throwable): Boolean = when (e) {
        is IllegalArgumentException -> true
        is FirebaseFirestoreException -> e.code in setOf(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
            FirebaseFirestoreException.Code.NOT_FOUND,
        )
        else -> false
    }

    @Synchronized fun wipe() {
        entries.clear(); loadedFor = null; inFlight.clear()
    }

    fun encode(list: List<Entry>): String = JSONArray().also { a ->
        for (e in list) a.put(JSONObject().put("id", e.clientId).put("cid", e.cid).put("t", e.text).put("ts", e.ts).apply {
            e.replyId?.let { put("rid", it) }
            e.replyAuthor?.let { put("ra", it) }
            e.replyText?.let { put("rt", it) }
            if (e.mentions.isNotEmpty()) put("mn", JSONArray(e.mentions))
            e.fmt?.let { put("fm", it) }
            e.fx?.let { put("fx", it) }
            if (e.refused) put("rf", e.refusedAt)
        })
    }.toString()

    fun decode(s: String): List<Entry> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val cid = o.optString("cid"); val t = o.optString("t")
            if (id.isEmpty() || cid.isEmpty() || t.isEmpty()) return@mapNotNull null
            Entry(id, cid, t, o.optDouble("ts", 0.0),
                o.optString("rid").ifEmpty { null }, o.optString("ra").ifEmpty { null }, if (o.has("rt")) o.getString("rt") else null,
                o.optJSONArray("mn")?.let { m -> (0 until m.length()).mapNotNull { j -> m.optString(j).ifEmpty { null } } } ?: emptyList(),
                o.optString("fm").ifEmpty { null }, o.optString("fx").ifEmpty { null },
                refused = o.has("rf"), refusedAt = o.optDouble("rf", 0.0))
        }
    }
}
