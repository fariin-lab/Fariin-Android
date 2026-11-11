package com.fariin.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * iOS StoryReceiptPrefs: the Story View Receipts switch (`users/{me}/private/storyPrefs.receipts`),
 * kept as a local value by a live listener started at sign-in, so marking a story seen never waits
 * on the network. `null` means not known yet (fresh sign-in, nothing cached): every caller treats
 * that as OFF, so a receipt is never written before we know the person allows it.
 */
object StoryReceiptPrefs {
    private const val PREFS = "fariin_stories"
    private const val KEY = "storyViewReceipts"
    private val _on = MutableStateFlow<Boolean?>(null)
    val on: StateFlow<Boolean?> = _on
    private var reg: ListenerRegistration? = null
    private var uid: String? = null

    /** Start (or keep) the listener for this account. Safe to call from every screen. */
    @Synchronized
    fun start(ctx: Context, me: String) {
        if (me.isEmpty() || (uid == me && reg != null)) return
        reg?.remove()
        uid = me
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _on.value = if (p.contains(KEY)) p.getBoolean(KEY, false) else null
        reg = FirebaseFirestore.getInstance().collection("users").document(me).collection("private").document("storyPrefs")
            // Metadata changes too: "no doc in the cache" then "no doc on the server" is the same data, and
            // only the second may count as "never switched off".
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null || uid != me) return@addSnapshotListener
                val v = when {
                    snap.exists() -> snap.getBoolean("receipts") ?: true
                    // A cache miss says nothing about the switch; only the server may say "never set".
                    snap.metadata.isFromCache -> return@addSnapshotListener
                    else -> true
                }
                _on.value = v
                p.edit().putBoolean(KEY, v).apply()
            }
    }

    /** The settings switch moved (or rolled back): the viewer follows at once. */
    fun set(ctx: Context, v: Boolean) {
        _on.value = v
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply()
    }

    /** Sign-out: the next account must not inherit this one's choice. */
    @Synchronized
    fun stop() {
        reg?.remove(); reg = null; uid = null
        _on.value = null
    }

    /** The switch once known, or null after [timeoutMs] (callers then treat it as OFF). */
    suspend fun awaitKnown(timeoutMs: Long): Boolean? =
        _on.value ?: withTimeoutOrNull(timeoutMs) { on.filterNotNull().first() }
}
