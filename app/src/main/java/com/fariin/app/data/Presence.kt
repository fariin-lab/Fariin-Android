package com.fariin.app.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.floor

/**
 * Online / last seen, after Kulan-iOS PresenceService and the chat header's rules.
 *
 * Written to users/{uid}/presence/state {online, lastActive}, NOT the user document (that one is
 * readable by any signed-in account; this one has its own rule, so the server enforces the
 * Last Seen audience). My audience "nobody" (privacy.lastSeen on my user document, which iOS
 * keeps in step with its setting) publishes offline and never moves lastActive, so the timestamp
 * cannot leak "was just online". Every write also deletes the old `online` / `lastActive` fields
 * from the user document, as iOS does.
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

    suspend fun set(online: Boolean, context: Context? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val user = db.collection("users").document(uid)
        // Read fresh each time (the setting can change on another phone); offline, the last copy.
        val privacy = try {
            privacyOf(user.get().await().data).also { myPrivacy = it }
        } catch (_: Exception) { myPrivacy ?: emptyMap() }
        val share = shares(privacy, legacySwitch(context))
        val data = fields(online, share).mapValues { (_, v) -> v ?: FieldValue.serverTimestamp() }
        try { user.collection("presence").document("state").set(data, SetOptions.merge()).await() } catch (_: Exception) {}
        try {
            user.set(mapOf("online" to FieldValue.delete(), "lastActive" to FieldValue.delete()), SetOptions.merge()).await()
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
