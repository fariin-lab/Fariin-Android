package com.fariin.app.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Who can see or reach me, after Kulan-iOS PrivacyPages.swift (Audience, PrivacyPrefs).
 *
 * The stored values are the iOS ones and are read by every other client: `users/{me}.privacy` is a
 * map of strings, audiences "everyone" / "contacts" / "nobody" for lastSeen, photo, bio, groups,
 * calls, messages, and the switches readReceipts / typingIndicators / shareLastSeen as "true" /
 * "false". The case is still `contacts` although the label says "My Chats" (renaming it would
 * reopen a door on older phones). Calls default to contacts, everything else to everyone.
 */
enum class Audience(val raw: String, val label: String) {
    EVERYONE("everyone", "Everyone"), CONTACTS("contacts", "My Chats"), NOBODY("nobody", "No One");

    /** iOS label(for:): calls say "My Chats" for both closed values; messages name the key. */
    fun label(key: String): String = when {
        key == "calls" && this != EVERYONE -> "My Chats"
        key == "messages" && this == CONTACTS -> "People who know my key"
        else -> label
    }

    companion object {
        fun of(raw: String?): Audience? = entries.firstOrNull { it.raw == raw }
    }
}

object PrivacyRules {
    val audienceKeys = listOf("lastSeen", "photo", "bio", "groups", "messages", "calls")
    val flagKeys = listOf("readReceipts", "typingIndicators", "shareLastSeen")

    fun default(key: String): Audience = if (key == "calls") Audience.CONTACTS else Audience.EVERYONE

    /** Calls have two choices; an old "nobody" there is read as contacts (iOS normalized). */
    fun normalized(a: Audience, key: String): Audience = if (key == "calls" && a == Audience.NOBODY) Audience.CONTACTS else a

    fun options(key: String): List<Audience> =
        if (key == "calls" || key == "messages") listOf(Audience.EVERYONE, Audience.CONTACTS) else Audience.entries

    /** My audience for [key] from the published map (absent or unknown = the default). */
    fun audience(map: Map<String, String>, key: String): Audience =
        normalized(Audience.of(map[key]) ?: default(key), key)

    /** A switch from the map: "false" is off, anything else (absent too) is on. */
    fun flag(map: Map<String, String>, key: String): Boolean = map[key] != "false"

    fun header(key: String): String? = when (key) {
        "lastSeen" -> "Who can see my last seen"
        "photo" -> "Who can see my profile photo"
        "bio" -> "Who can see my bio"
        "groups" -> "Who can add me to groups and channels"
        else -> null
    }

    private const val THREE = "My Chats means people you have a one-to-one chat with. A shared group doesn't count."

    fun footer(key: String): String = when (key) {
        "lastSeen" -> "Who can see when you're online and when you were last active. $THREE"
        "photo" -> "Who can see your profile photo when they find you on Fariin. $THREE"
        "bio" -> "Who can see the few words about you. $THREE"
        "groups" -> "Who can add you to groups and channels. My Chats means people you have a one-to-one chat with. A shared group or channel doesn't count."
        "calls" -> "My Chats: people you already chat with, including anyone who used your Chat Key."
        else -> ""
    }

    fun title(key: String): String = when (key) {
        "lastSeen" -> "Last seen & online"
        "photo" -> "Profile photo"
        "bio" -> "Bio"
        "groups" -> "Groups & Channels"
        "messages" -> "Messages"
        "calls" -> "Calls"
        else -> key
    }
}

/**
 * The live copy of my privacy map (one listener on users/{me}) with a mirror in prefs
 * "fariin_privacy" so code without a listener (presence, read receipts, typing) answers at once,
 * offline too. iOS keeps the same mirror in UserDefaults ("priv.<key>", "readReceipts", ...).
 */
object PrivacySettings {
    private const val PREFS = "fariin_privacy"
    private const val MAP = "privacy.map"
    @Volatile private var app: Context? = null
    private var reg: ListenerRegistration? = null
    private var uid: String? = null
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        _map.value = decode(prefs()?.getString(MAP, null))
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Start (or move) the listener for the signed-in account; null stops it and forgets the copy. */
    @Synchronized fun follow(newUid: String?) {
        if (newUid == uid) return
        reg?.remove(); reg = null
        uid = newUid
        if (newUid == null) { publish(emptyMap()); return }
        reg = FirebaseFirestore.getInstance().collection("users").document(newUid).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            if (uid == newUid) publish(Presence.privacyOf(d))
        }
    }

    private fun publish(m: Map<String, String>) {
        _map.value = m
        Presence.noteMyPrivacy(m)
        prefs()?.edit()?.putString(MAP, encode(m))
            // The old on-device switch the presence code reads.
            ?.putBoolean("shareLastSeen", PrivacyRules.flag(m, "shareLastSeen"))?.apply()
    }

    fun audience(key: String): Audience = PrivacyRules.audience(_map.value, key)
    fun flag(key: String): Boolean = PrivacyRules.flag(_map.value, key)

    /** Am I sending read receipts? (Privacy > Messages > Read Receipts; off = no lastRead stamp.) */
    fun readReceipts(): Boolean = flag("readReceipts")
    fun typingIndicators(): Boolean = flag("typingIndicators")

    /**
     * iOS setMine / setFlag: this phone's copy first, then `users/{me}` merge {privacy: {key: v}}.
     * A refusal puts the old value back and returns the sentence to show; offline is not a failure
     * (the write waits in the queue), so the call returns as soon as the server or cache answers.
     */
    suspend fun set(key: String, value: String): String? {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return "You are logged out."
        val before = _map.value
        publish(before + (key to value))
        return try {
            FirebaseFirestore.getInstance().collection("users").document(me)
                .set(mapOf("privacy" to mapOf(key to value)), SetOptions.merge()).await()
            if (key == "lastSeen") Presence.set(true, app)
            // Who may see my photo changed: it gets a new name so cached copies are checked again.
            if (key == "photo") PhotoRepublish.launch()
            null
        } catch (e: Exception) {
            if (_map.value[key] == value) {
                val back = _map.value.toMutableMap()
                val old = before[key]
                if (old == null) back.remove(key) else back[key] = old
                publish(back)
            }
            "This setting could not be saved. ${e.localizedMessage ?: ""}".trim()
        }
    }

    suspend fun setAudience(key: String, a: Audience) = set(key, a.raw)
    suspend fun setFlag(key: String, on: Boolean) = set(key, if (on) "true" else "false")

    internal fun encode(m: Map<String, String>): String =
        m.entries.joinToString("\n") { "${it.key}=${it.value}" }

    internal fun decode(s: String?): Map<String, String> =
        s.orEmpty().lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
}
