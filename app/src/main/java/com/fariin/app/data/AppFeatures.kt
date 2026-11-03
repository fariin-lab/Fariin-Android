package com.fariin.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Built-but-hidden features and their switches (iOS AppFeatures + RemoteFeatures). The admin panel
 * writes `config/features`, one Bool per key; signed-in phones may read it and nobody writes it.
 * A switch is the server's value when it has one, else the default this build ships with.
 *
 * Applies on the NEXT open, as iOS: the listener saves what the server says, the switches read a
 * snapshot taken once per launch, so a door never disappears under a screen already drawn.
 */
object AppFeatures {
    private const val FILE = "remote_features"
    private var snapshot: Map<String, Boolean> = emptyMap()
    private var prefs: android.content.SharedPreferences? = null
    private var reg: ListenerRegistration? = null

    /** Glow: the Glowing grid on Stories, the Glow button and "Glow Story" on a profile, the
     *  Glowers · Glowing card on My Profile. Off by default (owner 2026-10-10, hidden). */
    val glow: Boolean get() = value("glow", false)
    /** Insights: the card on My Profile, the only way into the Insights page. */
    val insights: Boolean get() = value("insights", false)
    /** The My Profile card under the header in Settings (owner 2026-10-11: hidden). */
    val myProfileCard: Boolean get() = value("myProfileCard", false)

    fun value(key: String, default: Boolean): Boolean = snapshot[key] ?: default

    /** Once per launch, before anything reads a switch. */
    fun init(c: Context) {
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        snapshot = p.all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()
    }

    /** Saves the server's switches for the next open. A missing document or an error leaves the
     *  saved values alone: a dropped listener is no reason to undo the owner's last choice. */
    fun start() {
        reg?.remove()
        reg = FirebaseFirestore.getInstance().collection("config").document("features")
            .addSnapshotListener { snap, _ ->
                if (snap == null || !snap.exists()) return@addSnapshotListener
                val e = prefs?.edit() ?: return@addSnapshotListener
                e.clear()
                snap.data.orEmpty().forEach { (k, v) -> if (v is Boolean) e.putBoolean(k, v) }
                e.apply()
            }
    }

    fun stop() { reg?.remove(); reg = null }
}
