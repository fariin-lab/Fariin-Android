package com.fariin.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** One page of the list: the raw answer, or throws (a not-found FirebaseFunctionsException = off). */
    suspend fun list(cursor: String?): Map<*, *> =
        fns.getHttpsCallable("plusVisitorsList").call(if (cursor != null) mapOf("cursor" to cursor) else emptyMap<String, Any>())
            .await().getData() as? Map<*, *> ?: emptyMap<String, Any>()
}
