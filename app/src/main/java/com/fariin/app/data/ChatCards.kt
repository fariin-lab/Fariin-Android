package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * THE CHAT LIST CARD (iOS ChatListCards.swift, owner 2026-11-13). One small card at the top of the
 * All chats list. Two sources:
 *  - admin cards from `config/features.chatCards` (read live, like newBadges),
 *  - three built-in cards the phone decides itself: profile photo, username, notifications.
 * Admin cards first (priority high to low, then the panel's order), then the built-ins in that
 * order; the first that applies and was not closed on this phone is the one shown. The X closes a
 * card for good on this phone for this account. Same contract as iOS and the admin panel.
 */
data class ChatCard(
    val id: String,
    val title: String,
    val subtitle: String = "",
    /** "https://..." opens the in-app browser, "fariin://..." routes inside the app; null = text only. */
    val link: String? = null,
    /** An https picture on the left; null = none. */
    val image: String? = null,
    val platforms: List<String> = emptyList(),
    val minBuild: Int = 0,
    val maxBuild: Int = 0,
    val countries: List<String> = emptyList(),
    val ppm: Int = 1_000_000,
    val startsAt: Double? = null,
    val endsAt: Double? = null,
    val dismissible: Boolean = true,
    val priority: Int = 0,
    val builtin: Builtin? = null,
) {
    enum class Builtin { PHOTO, USERNAME, NOTIFICATIONS }

    /**
     * Is this admin card for me, on this phone, now? The official channel's rules plus the platform.
     * The build limits are iPhone build numbers unless the card is for Android alone (as
     * [Announcement.reaches]): a card limited to some builds and not Android-only is not shown here.
     */
    fun reaches(uid: String, build: Int, region: String?, now: Double): Boolean {
        if (startsAt != null && startsAt > now) return false
        if (endsAt != null && endsAt <= now) return false
        if (platforms.isNotEmpty() && "android" !in platforms) return false
        if (minBuild > 0 || maxBuild > 0) {
            if (platforms != listOf("android")) return false
            if (build < minBuild) return false
            if (maxBuild > 0 && build > maxBuild) return false
        }
        if (countries.isNotEmpty() && (region == null || region.uppercase() !in countries)) return false
        if (ppm < 1_000_000 && Announcement.bucket(id, uid) >= ppm) return false
        return true
    }

    companion object {
        private val ID = Regex("^[a-z0-9_-]{1,40}$")

        /** The panel's array; a malformed entry is skipped, never the whole list. */
        fun parse(raw: Any?): List<ChatCard> {
            val list = raw as? List<*> ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<ChatCard>()
            for (item in list) {
                val c = (item as? Map<*, *>)?.let(::from) ?: continue
                if (seen.add(c.id)) out.add(c)
            }
            return out
        }

        private fun from(m: Map<*, *>): ChatCard? {
            val id = m["id"] as? String ?: return null
            if (!ID.matches(id) || id.startsWith("builtin_")) return null   // the phone's own cards
            val title = (m["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val link = (m["link"] as? String)?.takeIf {
                val s = it.substringBefore(':').lowercase(); it.contains(':') && (s == "https" || s == "fariin")
            }
            val image = (m["image"] as? String)?.takeIf { it.startsWith("https://", ignoreCase = true) }
            return ChatCard(
                id = id, title = title.take(60), subtitle = ((m["subtitle"] as? String) ?: "").take(140),
                link = link, image = image,
                platforms = (m["platforms"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.lowercase() },
                minBuild = (m["minBuild"] as? Number)?.toInt() ?: 0,
                maxBuild = (m["maxBuild"] as? Number)?.toInt() ?: 0,
                countries = (m["countries"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.uppercase() },
                ppm = ((m["ppm"] as? Number)?.toInt() ?: 1_000_000).coerceIn(0, 1_000_000),
                startsAt = (m["startsAt"] as? Number)?.toDouble(),
                endsAt = (m["endsAt"] as? Number)?.toDouble(),
                dismissible = m["dismissible"] as? Boolean ?: true,
                priority = (m["priority"] as? Number)?.toInt() ?: 0,
            )
        }

        val PHOTO = ChatCard("builtin_photo", "Add a profile photo", "Help your friends recognise you.", builtin = Builtin.PHOTO)
        val USERNAME = ChatCard("builtin_username", "Choose a username", "People can find you without your phone number.", builtin = Builtin.USERNAME)
        val NOTIFICATIONS = ChatCard("builtin_notifications", "Turn on notifications", "So you don't miss messages and calls.", builtin = Builtin.NOTIFICATIONS)

        /**
         * The one card to show, or null. Built-ins not in the account's first day (the onboarding
         * card covers that day; owner 2026-11-13). [notificationsOn] null = not known yet (no card).
         */
        fun pick(
            admin: List<ChatCard>, closed: Set<String>, uid: String, build: Int, region: String?, now: Double,
            joinedAt: Double?, photoUrl: String?, handle: String?, notificationsOn: Boolean?,
        ): ChatCard? {
            if (uid.isEmpty()) return null
            admin.withIndex().sortedWith(compareByDescending<IndexedValue<ChatCard>> { it.value.priority }.thenBy { it.index })
                .map { it.value }
                .firstOrNull { it.id !in closed && it.reaches(uid, build, region, now) }
                ?.let { return it }
            if (joinedAt == null || now - joinedAt <= 86_400_000.0) return null
            val out = ArrayList<ChatCard>()
            if (photoUrl.isNullOrEmpty()) out.add(PHOTO)
            // A profile not read yet (null) asks nothing.
            if (handle != null && handle.isEmpty()) out.add(USERNAME)
            if (notificationsOn == false) out.add(NOTIFICATIONS)
            return out.firstOrNull { it.id !in closed }
        }
    }
}

/** The panel's live cards and what this phone closed, per account; Compose state so the list redraws. */
object ChatCardsStore {
    var admin by mutableStateOf<List<ChatCard>>(emptyList()); private set
    /** Bumped on every close, read by [closed] so the list redraws. */
    private var version by mutableIntStateOf(0)
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(c: Context) {
        if (prefs != null) return
        prefs = c.applicationContext.getSharedPreferences("chat_cards", Context.MODE_PRIVATE)
        admin = ChatCard.parse(runCatching { org.json.JSONArray(prefs?.getString(STORE, "[]") ?: "[]") }.getOrNull()?.let(::toList))
    }

    /** From the config/features listener: live, and kept for the next open. */
    fun update(raw: Any?) {
        val cards = ChatCard.parse(raw)
        if (cards != admin) admin = cards
        val p = prefs ?: return
        val arr = org.json.JSONArray()
        (raw as? List<*>)?.forEach { item -> (item as? Map<*, *>)?.let { m ->
            // Plain values only (a Timestamp would not round-trip); parse() reads it back.
            arr.put(org.json.JSONObject(m.filterValues { v -> v is String || v is Number || v is Boolean || v is List<*> }
                .mapKeys { it.key.toString() }.mapValues { (_, v) -> if (v is List<*>) org.json.JSONArray(v) else v }))
        } }
        p.edit().putString(STORE, arr.toString()).apply()
    }

    fun closed(uid: String): Set<String> {
        @Suppress("UNUSED_VARIABLE") val v = version
        return prefs?.getStringSet(key(uid), emptySet()).orEmpty()
    }

    fun dismiss(id: String, uid: String) {
        val p = prefs ?: return
        if (uid.isEmpty()) return
        val set = closed(uid).toMutableSet()
        if (!set.add(id)) return
        p.edit().putStringSet(key(uid), set).apply()
        version++
    }

    private fun key(uid: String) = "chatCardsDismissed-$uid"
    private const val STORE = "chatCards.v1"

    private fun toList(a: org.json.JSONArray): List<Any?> = (0 until a.length()).map { i ->
        when (val v = a.opt(i)) {
            is org.json.JSONObject -> v.keys().asSequence().associateWith { k ->
                when (val x = v.opt(k)) { is org.json.JSONArray -> toList(x); else -> x }
            }
            is org.json.JSONArray -> toList(v)
            else -> v
        }
    }
}
