package com.fariin.app.crypto

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which key we have seen for each person, and whether it has changed since (iOS SafetyKeyLog).
 *
 * Without it a key the server hands over replaces the old one in silence: a new phone, a reinstall,
 * or someone swapping the key all look the same, and whoever swapped it reads what is sent after.
 * The working key cache cannot answer "what did we see before?" (it is cleared on purpose when a
 * message fails to open), so this record is append-only: the first key seen for a person is kept,
 * and every later key is compared with it. Going BACK to an older key counts as a change too.
 *
 * A change is not a verdict. Reinstalling is by far the commonest cause, so the notice only says
 * what happened and offers the safety number; nothing is blocked.
 */
object SafetyKeyLog {
    private const val SEEN = "seen."
    private const val PENDING = "pending."
    private const val LAST = "last."

    @Volatile private var prefs: SharedPreferences? = null
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** People whose key changed and whose notice has not been seen yet. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    fun init(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("fariin_safety_keys", Context.MODE_PRIVATE)
        prefs = p
        _pending.value = p.all.keys.filter { it.startsWith(PENDING) }.map { it.removePrefix(PENDING) }.toSet()
    }

    /** Record a key in use for [uid]. True only when it replaces a DIFFERENT key seen before. */
    fun note(uid: String, key: ByteArray): Boolean {
        val p = prefs ?: return false
        if (uid.isEmpty()) return false
        val b64 = B64.encode(key)
        synchronized(this) {
            val previous = p.getString(SEEN + uid, null)
            if (previous == null) { p.edit().putString(SEEN + uid, b64).apply(); return false }
            if (previous == b64) return false
            val now = System.currentTimeMillis()
            // The pending notice and the permanent record are written together and cleared apart:
            // dismissing the notice must never erase that the change happened.
            p.edit().putString(SEEN + uid, b64).putLong(PENDING + uid, now).putLong(LAST + uid, now).apply()
            _pending.value = _pending.value + uid
            return true
        }
    }

    /** When [uid]'s key last changed, even after the notice was dismissed. */
    fun lastChangedAt(uid: String): Long? = prefs?.getLong(LAST + uid, 0L)?.takeIf { it > 0 }

    /** The notice was seen (or the numbers were just compared). */
    fun acknowledge(uid: String) {
        val p = prefs ?: return
        if (uid !in _pending.value) return
        p.edit().remove(PENDING + uid).apply()
        _pending.value = _pending.value - uid
    }

    /** Signing out: the next account on this phone must not inherit this one's key record. */
    fun wipe() {
        prefs?.edit()?.clear()?.apply()
        _pending.value = emptySet()
    }
}
