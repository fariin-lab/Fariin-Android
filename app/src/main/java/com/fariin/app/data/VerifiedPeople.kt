package com.fariin.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Who is verified, answered from memory for the chat list's tick (iOS VerificationIndex). Kept on the
 * phone so a tick seen before is there on the first frame. A person is looked up at most once an hour
 * (was a day: a badge the admin removed stayed all day, owner 2026-11-13), and only when their row is
 * on screen. Any read of their profile elsewhere ([note]) updates it at once. A miss means no tick: never a badge we are not sure of.
 */
object VerifiedPeople {
    private const val PREFS = "fariin_verified_people"
    private const val FRESH_MS = 60L * 60 * 1000
    /** uid to the badge's type ("" for a plain one), or "-" for not verified. */
    private val _kinds = MutableStateFlow<Map<String, String>>(emptyMap())
    val kinds: StateFlow<Map<String, String>> = _kinds.asStateFlow()
    private val checkedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val asking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null

    private fun load(c: Context) {
        app = c.applicationContext
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val m = HashMap<String, String>()
            for ((k, v) in p.all) {
                val o = runCatching { JSONObject(v as? String ?: return@runCatching null) }.getOrNull() ?: continue
                m[k] = o.optString("k", "-"); checkedAt[k] = o.optLong("t", 0L)
            }
            _kinds.value = m
            loaded = true
        }
    }

    /** A fresh read of [uid]'s user doc (here or anywhere else): its `verification` field decides. */
    fun note(uid: String, verification: Any?) {
        if (uid.isEmpty()) return
        val v = verification as? Map<*, *>
        val status = v?.let { it["status"] as? String ?: if (it["isVerified"] == true) "active" else "revoked" }
        val kind = if (status == "active") (v?.get("type") as? String ?: "") else "-"
        val now = System.currentTimeMillis()
        checkedAt[uid] = now
        if (_kinds.value[uid] != kind) _kinds.value = _kinds.value + (uid to kind)
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(uid, JSONObject().put("k", kind).put("t", now).toString())?.apply()
    }

    /** The badge for [uid]: null when none, else its type ("" plain). */
    fun badge(kinds: Map<String, String>, uid: String): String? = kinds[uid]?.takeIf { it != "-" }

    /** Gold for a company's badge (iOS Verification.Kind.isGold). */
    fun isGold(kind: String?): Boolean = kind == "business" || kind == "organization"

    /** Reads [uid]'s verification once a day while their row shows. */
    suspend fun warm(c: Context, uid: String) {
        if (uid.isEmpty()) return
        load(c)
        if (System.currentTimeMillis() - (checkedAt[uid] ?: 0L) < FRESH_MS || !asking.add(uid)) return
        try {
            val d = FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
            if (!d.exists()) return
            note(uid, d.get("verification"))
        } catch (_: Exception) {
        } finally { asking.remove(uid) }
    }
}
