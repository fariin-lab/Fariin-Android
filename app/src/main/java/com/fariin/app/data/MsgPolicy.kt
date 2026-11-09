package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The admin panel's message policy (iOS MsgPolicy): Message Requests on or off for everybody
 * (`config/msgPolicy.requests`) or for one person (`users/{uid}.msgPolicy.requests`).
 * A person's own setting beats the everybody setting, which beats the shipped default (on).
 *
 * THE RECIPIENT'S SETTING DECIDES whether a stranger's first message to them is a request. The
 * rules are the enforcement; this only decides what the screen offers, so both must agree.
 *
 * Live, not once per launch: one listener on the config document, one on my own user document;
 * other people's overrides arrive on the user listener an open chat already holds ([record]).
 * The last global answer is kept on the phone and used from the first frame, so a cold launch
 * does not show the request bar for a split second while the listener answers.
 */
object MsgPolicy {
    private const val FILE = "msg_policy"
    private const val KEY = "requests"

    @Volatile private var global = true
    private val overrides = ConcurrentHashMap<String, Boolean>()
    private var prefs: android.content.SharedPreferences? = null
    private var configReg: ListenerRegistration? = null
    private var meReg: ListenerRegistration? = null

    /** Bumped on every change; Compose readers touch it so a flip in the panel redraws them. */
    var revision by mutableIntStateOf(0)
        private set

    fun init(c: Context) {
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        global = p.getBoolean(KEY, true)
    }

    fun start(me: String) {
        stop()
        val db = FirebaseFirestore.getInstance()
        configReg = db.collection("config").document("msgPolicy").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            setGlobal(snap.getBoolean(KEY) ?: true)
        }
        if (me.isEmpty()) return
        meReg = db.collection("users").document(me).addSnapshotListener { snap, _ ->
            if (snap != null) record(me, snap.data)
        }
    }

    /** On sign-out. The overrides go with the account; the global stays (it describes the app). */
    fun stop() {
        configReg?.remove(); configReg = null
        meReg?.remove(); meReg = null
        overrides.clear()
    }

    /** Somebody's user document arrived. */
    fun record(uid: String, data: Map<String, Any?>?) {
        if (uid.isEmpty()) return
        val o = ((data?.get("msgPolicy") as? Map<*, *>)?.get(KEY)) as? Boolean
        val before = overrides[uid]
        if (o == null) overrides.remove(uid) else overrides[uid] = o
        if (before != o) bump()
    }

    /** Does a stranger's first message to [uid] arrive as a request? */
    fun requestsOn(uid: String): Boolean = overrides[uid] ?: global

    /** iOS `confirm(for:)`: before a first message, ask the server once (2 s at most) for the
     *  switch and the recipient's own override, since the rules read the live values. */
    suspend fun confirm(other: String) {
        val db = FirebaseFirestore.getInstance()
        read(db.collection("config").document("msgPolicy"))?.let { setGlobal(it.getBoolean(KEY) ?: true) }
        if (other.isNotEmpty()) read(db.collection("users").document(other))?.let { record(other, it.data) }
    }

    private suspend fun read(ref: DocumentReference) = withTimeoutOrNull(2000) {
        runCatching { ref.get(Source.SERVER).await() }.getOrNull()
    } ?: runCatching { ref.get(Source.CACHE).await() }.getOrNull()

    private fun setGlobal(v: Boolean) {
        if (v == global) return
        global = v
        prefs?.edit()?.putBoolean(KEY, v)?.apply()
        bump()
    }

    private fun bump() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { revision++ }
    }
}
