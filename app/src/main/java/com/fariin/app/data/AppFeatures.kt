package com.fariin.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Built-but-hidden features and their switches (iOS AppFeatures + RemoteFeatures). The admin panel
 * writes `config/features`, one Bool per key; signed-in phones may read it and nobody writes it.
 * A switch is the server's value when it has one, else the default this build ships with.
 *
 * Applies on the NEXT open, as iOS: the listener saves what the server says, the switches read a
 * snapshot taken once per launch, so a door never disappears under a screen already drawn.
 * The same listener also feeds the live Settings "New" pills ([NewBadges]) and the admin panel's
 * custom emoji ([UploadedEmoji]).
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

    // ⛔ THE ADMIN PANEL'S DOORS (iOS Flags / CallFeatures, 2026-11-10 parity): these close a door the
    // panel switched off. `offOnly`: the server can only turn a shipped-on feature OFF.
    /** Groups at all (iOS Flags.groupsEnabled). */
    val groups: Boolean get() = offOnly("groups", true)
    /** Making new groups and channels (iOS Flags.groupCreateEnabled; the rules refuse one when off). */
    val groupCreate: Boolean get() = groups && value("groupCreate", true)
    /** The group Statistics row (iOS Flags.groupStatsEnabled). */
    val groupStats: Boolean get() = value("groupStats", true)
    /** Group calls (iOS CallFeatures.groupCalls). */
    val groupCalls: Boolean get() = offOnly("groupCalls", true)
    /** Call links: the Create a Call Link row (iOS CallFeatures.callLinks). */
    val callLinks: Boolean get() = offOnly("callLinks", true)
    /** Plain group and channel videos play while they come down (VideoStream); off = the whole
     *  file first, as before. */
    val videoStream: Boolean get() = offOnly("videoStream", true)

    fun value(key: String, default: Boolean): Boolean = snapshot[key] ?: default
    /** A switch the server may only turn off (iOS RemoteFeatures.offOnly). */
    fun offOnly(key: String, default: Boolean): Boolean = default && snapshot[key] != false
    const val CREATE_OFF = "Creating new groups and channels isn't available right now."

    /** Once per launch, before anything reads a switch. */
    fun init(c: Context) {
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        snapshot = p.all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()
        UploadedEmoji.init(c)
        HiddenLooks.init(c)
    }

    /** Saves the server's switches for the next open. A missing document or an error leaves the
     *  saved values alone: a dropped listener is no reason to undo the owner's last choice. */
    fun start() {
        MyLanguage.sync()
        reg?.remove()
        reg = FirebaseFirestore.getInstance().collection("config").document("features")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                // The Settings "New" pills are live, not next-open (a deleted doc clears them).
                NewBadges.update(if (snap.exists()) snap.get("newBadges") else null)
                if (!snap.exists()) return@addSnapshotListener
                // Custom emoji from the admin panel are live too, as iOS.
                UploadedEmoji.update(snap.get("customEmoji"))
                // Built-in ones the panel hid leave the picker, live (owner 2026-11-06).
                UploadedEmoji.updateHidden(snap.get("customEmojiHidden"))
                // Built-in wallpapers and bubble styles the panel hid leave the pickers, live.
                HiddenLooks.update(snap.get("wallpapersHidden"), snap.get("bubblesHidden"))
                val e = prefs?.edit() ?: return@addSnapshotListener
                e.clear()
                snap.data.orEmpty().forEach { (k, v) -> if (v is Boolean) e.putBoolean(k, v) }
                e.apply()
            }
    }

    fun stop() { reg?.remove(); reg = null }
}

/**
 * Built-in wallpapers and bubble styles the admin panel hid (`config/features.wallpapersHidden` and
 * `bubblesHidden`, the same ids the apps save: "g-blaze", "water", "pink", "sakura"...). Hidden ones
 * leave the pickers at once; a chat or user already using one keeps it and keeps drawing it. Ids the
 * build does not know are ignored by [visible], which only ever removes.
 */
/**
 * The phone's language on my profile (iOS ProfileStore.syncMyLanguage, owner 2026-10-26: for Group
 * Statistics). `users/{me}.lang`, a 2-letter code, written once per launch and only when it differs
 * from what the server holds, read fresh first so a cached copy never causes a write.
 */
object MyLanguage {
    @Volatile private var doneFor: String? = null
    fun sync() {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (doneFor == uid) return
        doneFor = uid
        val code = java.util.Locale.getDefault().language.lowercase()
        if (code.length != 2) return
        val ref = FirebaseFirestore.getInstance().collection("users").document(uid)
        ref.get(com.google.firebase.firestore.Source.SERVER).addOnSuccessListener { snap ->
            if (snap.getString("lang") == code) return@addOnSuccessListener
            if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnSuccessListener
            ref.set(mapOf("lang" to code), com.google.firebase.firestore.SetOptions.merge())
        }
    }
}

object HiddenLooks {
    private const val FILE = "hidden_looks"
    private const val WALLS = "walls.v1"
    private const val BUBBLES = "bubbles.v1"
    private var prefs: android.content.SharedPreferences? = null
    private val _wallpapers = MutableStateFlow<Set<String>>(emptySet())
    private val _bubbles = MutableStateFlow<Set<String>>(emptySet())
    val wallpapers: StateFlow<Set<String>> = _wallpapers
    val bubbles: StateFlow<Set<String>> = _bubbles

    /** The panel's list: non-empty strings of a sane length; anything else is dropped. */
    fun parse(raw: Any?): Set<String> = (raw as? List<*>).orEmpty()
        .mapNotNull { (it as? String)?.takeIf { s -> s.isNotEmpty() && s.length <= 64 } }
        .toSet()

    /** [all] minus the hidden ones, in their order. */
    fun <T> visible(all: List<T>, hidden: Set<String>, id: (T) -> String): List<T> =
        if (hidden.isEmpty()) all else all.filter { id(it) !in hidden }

    fun init(c: Context) {
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _wallpapers.value = p.getStringSet(WALLS, null)?.toSet() ?: emptySet()
        _bubbles.value = p.getStringSet(BUBBLES, null)?.toSet() ?: emptySet()
    }

    /** From the config/features listener. A missing field means nothing is hidden. */
    fun update(rawWalls: Any?, rawBubbles: Any?) {
        val w = parse(rawWalls)
        val b = parse(rawBubbles)
        if (w == _wallpapers.value && b == _bubbles.value) return
        _wallpapers.value = w
        _bubbles.value = b
        prefs?.edit()?.putStringSet(WALLS, w)?.putStringSet(BUBBLES, b)?.apply()
    }
}
