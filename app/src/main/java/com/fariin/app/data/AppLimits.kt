package com.fariin.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Every ceiling the app enforces, from the one document the owner edits in the admin panel
 * (iOS AppLimits): `config/limits`. The rules and the group functions read the same keys with the
 * same fallbacks, so one edit moves the app and the server together.
 *
 * One snapshot listener, attached on the first read while signed in. It answers from the SDK's disk
 * cache first, then on every change. An error keeps the last good values (a dropped listener is no
 * reason to take a raised limit away mid-session) and lets the next read attach again.
 *
 * THE FALLBACKS ARE iOS's AND THE RULES' NUMBERS. Change them together.
 */
object AppLimits {
    object Fallback {
        const val GROUP_MEMBERS_MAX = 30   // appLimit('group_members_max', 30) in firestore.rules
        const val PINNED_CHATS_MAX = 5
        const val GROUPS_OWNED_MAX = 10    // groups_owned_max, functions-groups groupCreateCheck
        const val GROUP_DESC_MAX = 200     // group_desc_max
    }

    @Volatile private var values: Map<String, Any?> = emptyMap()
    @Volatile private var reg: ListenerRegistration? = null

    /** Attaches the listener once, signed in only (the rules let signed-in phones read it). */
    @Synchronized private fun ensure() {
        if (reg != null) return
        // No Firebase (a unit test) or nobody signed in: the fallbacks stand and the next read asks again.
        try {
            if (FirebaseAuth.getInstance().currentUser == null) return
            reg = FirebaseFirestore.getInstance().collection("config").document("limits")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) { synchronized(this) { reg?.remove(); reg = null }; return@addSnapshotListener }
                    values = snap.data ?: emptyMap()
                }
        } catch (_: Exception) {}
    }

    private fun read(): Map<String, Any?> { ensure(); return values }

    /** The most people a group may hold, me included (2 to 500, as functions-groups clamps it). */
    val groupMembersMax: Int get() = membersMax(read())
    /** How many chats one person may pin (`pinned_chats_max`). */
    val pinnedChatsMax: Int get() = number(read(), "pinned_chats_max", Fallback.PINNED_CHATS_MAX)
    /** How many groups and channels one person may own at once (`groups_owned_max`). */
    val groupsOwnedMax: Int get() = number(read(), "groups_owned_max", Fallback.GROUPS_OWNED_MAX)
    /** How long a group description may be (`group_desc_max`). */
    val groupDescMax: Int get() = number(read(), "group_desc_max", Fallback.GROUP_DESC_MAX)

    /**
     * iOS AppLimits.number: only a whole number above zero counts. The console types a new field as
     * text unless told otherwise, and the rules ignore anything that is not an int, so the app must
     * too; a zero is a mistake that would lock everyone out.
     */
    fun number(v: Map<String, Any?>, key: String, fallback: Int): Int {
        val n = when (val x = v[key]) { is Long -> x; is Int -> x.toLong(); else -> return fallback }
        return if (n > 0) n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else fallback
    }

    fun membersMax(v: Map<String, Any?>): Int = number(v, "group_members_max", Fallback.GROUP_MEMBERS_MAX).coerceIn(2, 500)

    /**
     * NewGroupView / NewChannelView: the server counts the groups I own (`groupCreateCheck`,
     * {ok, count, max}). Returns the ceiling when I am at it, else null. A check that cannot be
     * answered lets the create go; the server's own check on the new group still holds the line.
     */
    suspend fun ownedGroupsFull(): Int? = try {
        withTimeoutOrNull(10_000) {
            val d = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("groupCreateCheck")
                .withTimeout(10, TimeUnit.SECONDS).call().await().getData() as? Map<*, *>
            ownedFull(d, groupsOwnedMax)
        }
    } catch (_: Exception) { null }

    /** The pure half of [ownedGroupsFull]: refused only on an explicit `ok: false`. */
    fun ownedFull(d: Map<*, *>?, fallbackMax: Int): Int? {
        if (d?.get("ok") != false) return null
        return (d["max"] as? Number)?.toInt()?.takeIf { it > 0 } ?: fallbackMax
    }

    /** NewGroupView's sentence at the ceiling. */
    fun ownedGroupsSentence(max: Int) = "You can own up to $max groups. Delete one, or make someone else its owner, to create a new one."
    /** NewChannelView's (a channel counts as one). */
    fun ownedChannelsSentence(max: Int) = "You can own up to $max groups and channels. Delete one to create a new one."
    /** ChatService.addGroupMembers' cap sentence. */
    fun membersCapSentence(max: Int) = "A group can have at most $max members."
    /** AddToGroupView: a group with no room left. Channels are exempt (their users are admins). */
    fun groupIsFull(isChannel: Boolean, memberCount: Int, max: Int = groupMembersMax): Boolean = !isChannel && memberCount >= max
}
