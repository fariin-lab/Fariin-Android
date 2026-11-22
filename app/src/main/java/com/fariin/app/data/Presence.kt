package com.fariin.app.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.math.floor

/**
 * Online / last seen, after Kulan-iOS PresenceService and the chat header's rules.
 *
 * Written to users/{uid}/presence/state {online, lastActive}, NOT the user document (that one is
 * readable by any signed-in account; this one has its own rule, so the server enforces the
 * Last Seen audience). My audience "nobody" (privacy.lastSeen on my user document, which iOS
 * keeps in step with its setting) publishes offline and never moves lastActive, so the timestamp
 * cannot leak "was just online". The same state is written once (iOS lastWritten), and the old
 * `online` / `lastActive` fields on the user document are deleted once per account, as iOS does.
 */
object Presence {
    /** iOS: share = my audience is not "nobody" and the old local switch is on (default on). */
    fun shares(myPrivacy: Map<String, String>, legacySwitch: Boolean = true): Boolean =
        myPrivacy["lastSeen"] != "nobody" && legacySwitch

    /** The fields of one write (lastActive is a server time; `null` here stands for it). */
    fun fields(online: Boolean, share: Boolean): Map<String, Any?> =
        if (share) mapOf("online" to online, "lastActive" to null) else mapOf("online" to false)

    /** The old on-device switch iOS kept ("shareLastSeen"); no Android page sets it yet. */
    private fun legacySwitch(c: Context?): Boolean =
        c?.getSharedPreferences("fariin_privacy", Context.MODE_PRIVATE)?.getBoolean("shareLastSeen", true) ?: true

    @Volatile private var myPrivacy: Map<String, String>? = null

    /** My privacy map as last read (the profile listener or a write below). */
    fun noteMyPrivacy(p: Map<String, String>) { myPrivacy = p }
    /** The last of my own privacy settings seen this run (a chat seeds its presence rules with it). */
    fun myPrivacyNow(): Map<String, String>? = myPrivacy

    /** One write is worked out at a time: a quick out-and-back (two calls) used to race, and the
     *  older "online" or "offline" could land last and stay. */
    private val turn = kotlinx.coroutines.sync.Mutex()
    /** iOS lastWritten: "uid|online|share" of the last write, so the same state is not sent twice. */
    @Volatile private var lastWritten: String? = null

    suspend fun set(online: Boolean, context: Context? = null) = setNow({ online }, context)

    /** [online] is asked when this write's turn comes (the app's front/back hooks pass "am I in
     *  front now"), so the last write always says the latest state. */
    suspend fun setNow(online: () -> Boolean, context: Context? = null) {
        val uid = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        val db = com.fariin.app.Fb.db
        val user = db.collection("users").document(uid)
        val (key, write) = turn.withLock {
            // Read fresh each time (the setting can change on another phone); offline, the last copy.
            val privacy = try {
                privacyOf(user.get().await().data).also { myPrivacy = it }
            } catch (_: Exception) { myPrivacy ?: emptyMap() }
            if (com.fariin.app.Fb.auth.currentUser?.uid != uid) return   // signed out meanwhile
            val share = shares(privacy, legacySwitch(context))
            val on = online()
            val k = "$uid|${share && on}|$share"
            if (lastWritten == k) return
            lastWritten = k
            val data = fields(on, share).mapValues { (_, v) -> v ?: FieldValue.serverTimestamp() }
            // Started inside the turn (Firestore keeps the order writes are started in), waited
            // for outside it: offline the wait never ends and must not hold up the next one.
            k to user.collection("presence").document("state").set(data, SetOptions.merge())
        }
        try { write.await() } catch (_: Exception) {
            // Not written: forget it, so the next call with the same state tries again.
            if (lastWritten == key) lastWritten = null
            return
        }
        // The old fields on the user document go once per account on this phone (iOS), not on
        // every write.
        val prefs = context?.getSharedPreferences("fariin_presence", Context.MODE_PRIVATE)
        val cleared = "legacyCleared.$uid"
        if (prefs?.getBoolean(cleared, false) == true) return
        try {
            user.set(mapOf("online" to FieldValue.delete(), "lastActive" to FieldValue.delete()), SetOptions.merge()).await()
            prefs?.edit()?.putBoolean(cleared, true)?.apply()
        } catch (_: Exception) {}
    }

    fun privacyOf(d: Map<String, Any?>?): Map<String, String> =
        (d?.get("privacy") as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }?.toMap() ?: emptyMap()

    /**
     * The 1:1 header line (iOS ThreadView subtitle): nothing unless MY audience shares (it is
     * reciprocal) and THEIR audience allows me; then "online", or "last seen <time> ago".
     */
    fun line(
        mine: Map<String, String>, theirs: Map<String, String>, contactOfMine: Boolean,
        online: Boolean, lastActiveMillis: Double?, nowMillis: Double, legacySwitch: Boolean = true,
    ): String? {
        if (!shares(mine, legacySwitch)) return null
        if (!Person.allows(theirs, "lastSeen", contactOfMine)) return null
        if (online) return "online"
        val la = lastActiveMillis ?: return null
        return "last seen " + relative(la, nowMillis)
    }

    /** One Recent row's grey line on the search page; `online` draws it in the action colour. */
    data class Seen(val text: String, val online: Boolean)

    /**
     * iOS RecentLastSeen: the header's rules (mine must share, theirs must allow me), but a refused
     * or missing answer reads "last seen recently" instead of nothing. `present` = their presence
     * doc was read.
     */
    fun recentLine(
        mine: Map<String, String>, theirs: Map<String, String>, contactOfMine: Boolean,
        present: Boolean, online: Boolean, lastActiveMillis: Double?, nowMillis: Double, legacySwitch: Boolean = true,
    ): Seen {
        val text = if (present) line(mine, theirs, contactOfMine, online, lastActiveMillis, nowMillis, legacySwitch) else null
        return text?.let { Seen(it, it == "online") } ?: Seen("last seen recently", false)
    }

    /** RelativeDateTimeFormatter, short style, English: "5 min. ago", "2 hr. ago", "3 days ago". */
    fun relative(thenMillis: Double, nowMillis: Double): String {
        val s = floor((nowMillis - thenMillis) / 1000).toLong().coerceAtLeast(0)
        val m = s / 60; val h = m / 60; val d = h / 24
        return when {
            s < 60 -> "$s sec. ago"
            m < 60 -> "$m min. ago"
            h < 24 -> "$h hr. ago"
            d < 7 -> if (d == 1L) "1 day ago" else "$d days ago"
            d < 30 -> "${d / 7} wk. ago"
            d < 365 -> "${d / 30} mo. ago"
            else -> "${d / 365} yr. ago"
        }
    }
}
