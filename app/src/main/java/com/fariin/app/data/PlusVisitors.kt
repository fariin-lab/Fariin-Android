package com.fariin.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * FARIIN PLUS: profile visitors (iOS PlusVisitors).
 *  - [record]: opening a person's profile tells the server (`plusVisit`). Every account does it, no
 *    answer is read and every failure is silent; the server keeps the visit only when the person
 *    looked at may see Plus. At most once an hour per person per run of the app.
 *  - [newCount]: the number on the Settings eye button, from `plusVisitorsList({countOnly})`, asked
 *    only while Plus is visible and the feature is not off.
 */
object PlusVisitors {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fns get() = FirebaseFunctions.getInstance(PlusStore.REGION)
    private val told = HashMap<String, Long>()

    var newCount by mutableIntStateOf(0); private set
    private var countFor: String? = null
    private var countAskedAt = 0L

    fun record(uid: String) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        if (uid.isEmpty() || uid == me || '/' in uid || '_' in uid) return
        val key = "$me|$uid"
        val now = System.currentTimeMillis()
        if (told[key]?.let { now - it < 3_600_000 } == true) return
        told[key] = now
        scope.launch { try { fns.getHttpsCallable("plusVisit").call(mapOf("uid" to uid)).await() } catch (_: Exception) {} }
    }

    val shows: Boolean get() = PlusStore.visible && PlusStore.state("profileVisitors") != PlusStore.State.OFF

    fun refreshCount(force: Boolean = false) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid
        if (!shows || me.isNullOrEmpty()) { countFor = null; if (newCount != 0) newCount = 0; return }
        if (countFor != me) { countFor = me; countAskedAt = 0L; if (newCount != 0) newCount = 0 }
        val now = System.currentTimeMillis()
        if (!force && now - countAskedAt <= 60_000) return
        countAskedAt = now
        scope.launch {
            val d = try { fns.getHttpsCallable("plusVisitorsList").call(mapOf("countOnly" to true)).await().getData() as? Map<*, *> }
                catch (_: Exception) { null } ?: return@launch
            if (countFor != me || com.fariin.app.Fb.auth.currentUser?.uid != me) return@launch
            val n = (d["newCount"] as? Number)?.toInt() ?: 0
            if (newCount != n) newCount = n
        }
    }

    fun markSeen() { if (newCount != 0) newCount = 0 }

    // ---- The faces on the Settings button (owner 2026-11-20, iOS PlusVisitors.faces) ----

    /** One of the latest visitors as the button draws it. */
    data class Face(val uid: String, val name: String, val photoUrl: String?)

    /** Up to 3 latest visitors, newest first. Empty = the button shows the eye. */
    var faces by androidx.compose.runtime.mutableStateOf<List<Face>>(emptyList()); private set
    private var facesFor: String? = null
    private var facesAskedAt = 0L

    /**
     * The latest faces, at most every 5 minutes unless [force]. Asked with `peek` (the newest 3, and
     * the server marks nothing seen, so the blue number stays). Rows come back only with Plus on;
     * locked or off leave the eye.
     */
    fun refreshFaces(force: Boolean = false) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid
        if (!shows || me.isNullOrEmpty()) { facesFor = null; if (faces.isNotEmpty()) faces = emptyList(); return }
        if (facesFor != me) { facesFor = me; facesAskedAt = 0L; if (faces.isNotEmpty()) faces = emptyList() }
        val now = System.currentTimeMillis()
        if (!force && now - facesAskedAt <= 300_000) return
        facesAskedAt = now
        scope.launch {
            val d = try { fns.getHttpsCallable("plusVisitorsList").call(mapOf("peek" to true)).await().getData() as? Map<*, *> }
                catch (_: Exception) { null } ?: return@launch
            val uids = if (d["state"] == "on") newest(d["rows"]) else emptyList()
            setFaces(uids, me)
        }
    }

    /** The visitors page loaded its first page (newest first): the button takes it, no extra read. */
    fun noteLatest(rows: Any?) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        facesFor = me
        facesAskedAt = System.currentTimeMillis()
        val uids = newest(rows)
        scope.launch { setFaces(uids, me) }
    }

    private fun newest(rows: Any?): List<String> {
        val out = ArrayList<String>()
        for (x in (rows as? List<*>).orEmpty()) {
            val u = (x as? Map<*, *>)?.get("uid") as? String ?: continue
            if (u.isNotEmpty() && u !in out) out.add(u)
            if (out.size == 3) break
        }
        return out
    }

    /** Names and photos, one read per person at once; a failed read still gets a face (the placeholder). */
    private suspend fun setFaces(uids: List<String>, me: String) {
        val got = kotlinx.coroutines.coroutineScope {
            uids.map { u -> async { u to try { com.fariin.app.Fb.db.collection("users").document(u).get().await().data } catch (_: Exception) { null } } }.map { it.await() }
        }.toMap()
        if (facesFor != me || com.fariin.app.Fb.auth.currentUser?.uid != me) return
        val out = uids.map { u ->
            val d = got[u]
            val name = (d?.get("name") as? String).orEmpty().ifEmpty { (d?.get("handle") as? String)?.takeIf { it.isNotEmpty() }?.let { "@$it" }.orEmpty() }
            Face(u, name, (d?.get("photoUrl") as? String)?.takeIf { it.isNotEmpty() })
        }
        if (faces != out) faces = out
    }

    /** One page of the list: the raw answer, or throws (a not-found FirebaseFunctionsException = off). */
    suspend fun list(cursor: String?): Map<*, *> =
        fns.getHttpsCallable("plusVisitorsList").call(if (cursor != null) mapOf("cursor" to cursor) else emptyMap<String, Any>())
            .await().getData() as? Map<*, *> ?: emptyMap<String, Any>()
}
