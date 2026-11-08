package com.fariin.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * GROUP STORIES, the posting half (iOS GroupStoryViews.swift + StoriesService groupStoryAudience /
 * groupStoryFields). A group story is an ordinary story with `groupId` on it, sent to the group's
 * members. Who may post: the group's owner, or an admin holding the Add story right, in a group big
 * enough for stories (firestore.rules `groupStoryOK` / `groupPoster`).
 */

/** The group a story is being made for, as the last sheet shows it (iOS GroupStoryTarget). */
data class GroupStoryTarget(
    val id: String,
    val name: String,
    val photoUrl: String?,
    /** A group's members; a channel's followers (its users are only its admins). */
    val memberCount: Int,
    val isChannel: Boolean,
)

/** What the group sheet adds to a post: the group and its Allow Replies switch. */
data class GroupStoryPost(val target: GroupStoryTarget, val allowsReplies: Boolean)

/**
 * The hand-off from the Share Story page to the upload (iOS StoryAudienceTag.group): set only for the
 * length of the page's onPost call, so the post started inside that call reads it and nothing later
 * ever does.
 */
object GroupStoryCompose {
    @Volatile var pending: GroupStoryPost? = null

    /**
     * The group page's Add Story button (iOS GroupStoryCompose.open): the camera opens with this group
     * already the last sheet's audience. Cleared when that camera and editor close.
     */
    @Volatile var preset: GroupStoryTarget? = null

    /** Runs [post] with [group] handed over, and clears it again whatever happens. */
    inline fun <T> during(group: GroupStoryPost?, post: () -> T): T {
        pending = group
        try { return post() } finally { pending = null }
    }
}

/** iOS GroupStoryRights, read against the group's own document, the way the rules read it. */
object GroupStoryRights {
    /** firestore.rules groupStoryMin: appLimit('group_story_min_members', 50). */
    const val MIN_MEMBERS_FALLBACK = 50
    const val NOT_ALLOWED = "You can't post stories in this group any more."

    /**
     * iOS canPost / rules groupPoster: a group (or channel) I am in, not closed, big enough (a
     * channel counts its followers) unless I am an app admin or opened for it, and I own it or I am
     * an admin with Add story (an admin with no rights entry is a legacy admin and has it).
     */
    fun canPost(d: Map<String, Any?>, me: String, minMembers: Int, appAdmin: Boolean, open: Boolean): Boolean {
        if (me.isEmpty() || d["type"] != "group") return false
        val team = GroupAdmins.Team.from(d)
        if (me !in team.users || team.closed) return false
        val channel = d["channel"] == true
        val size = if (channel) (d["followerCount"] as? Number)?.toInt() ?: 0 else team.users.size
        if (size < minMembers && !appAdmin && !open) return false
        return team.can(me, GroupAdmins.Right.POST_STORIES)
    }

    /** The page button: [bigEnough] false = the tap explains when stories open ([need], [have]). */
    data class ButtonState(val target: GroupStoryTarget, val bigEnough: Boolean, val need: Int, val have: Int)

    /**
     * iOS hasRight / bigEnough for the Add Story button on a group's page: null = no button (not a
     * group I run with the Add story right). Read from the group's own document, as the rules read it.
     */
    suspend fun buttonState(me: String, cid: String): ButtonState? {
        if (me.isEmpty() || cid.isEmpty()) return null
        val db = FirebaseFirestore.getInstance()
        val d = runCatching { db.collection("conversations").document(cid).get().await().data }.getOrNull() ?: return null
        if (d["type"] != "group") return null
        val team = GroupAdmins.Team.from(d)
        if (me !in team.users || team.closed || !team.can(me, GroupAdmins.Right.POST_STORIES)) return null
        val channel = d["channel"] == true
        val size = if (channel) (d["followerCount"] as? Number)?.toInt() ?: 0 else team.users.size
        val g = gate(db, me)
        val target = GroupStoryTarget(cid, (d["title"] as? String).orEmpty(), (d["avatarUrl"] as? String)?.ifEmpty { null }, size, channel)
        return ButtonState(target, size >= g.minMembers || g.appAdmin || g.open, g.minMembers, size)
    }

    /** iOS GroupStoryRow's grey line: "1 member" / "N members", a channel "1 follower" / "N followers". */
    fun countLine(n: Int, channel: Boolean): String =
        if (channel) (if (n == 1) "1 follower" else "$n followers") else (if (n == 1) "1 member" else "$n members")

    private class Gate(val minMembers: Int, val appAdmin: Boolean, val open: Boolean)

    /** The three things besides the group that the rules ask: the minimum, app admin, groupStoryOpen. */
    private suspend fun gate(db: FirebaseFirestore, me: String): Gate = coroutineScope {
        val limits = async { runCatching { db.collection("config").document("limits").get().await().data }.getOrNull().orEmpty() }
        val admin = async { runCatching { db.collection("admins").document(me).get().await().exists() }.getOrDefault(false) }
        val open = async { runCatching { db.collection("users").document(me).get().await().getBoolean("groupStoryOpen") }.getOrNull() == true }
        Gate(AppLimits.number(limits.await(), "group_story_min_members", MIN_MEMBERS_FALLBACK), admin.await(), open.await())
    }

    /**
     * The Groups & Channels list (iOS eligibleGroups): every group in my chat list I run, each read
     * from its document so the size, the rights and the channel's followers are the rules' own.
     * Sorted by name, as iOS sorts it.
     */
    suspend fun eligible(me: String, convs: List<Conversation>): List<GroupStoryTarget> {
        if (me.isEmpty()) return emptyList()
        val db = FirebaseFirestore.getInstance()
        val mine = convs.filter { it.isGroup && GroupStoryRules.isStaff(it, me) }.distinctBy { it.id }
        if (mine.isEmpty()) return emptyList()
        val g = gate(db, me)
        return coroutineScope {
            mine.map { c ->
                async {
                    val d = runCatching { db.collection("conversations").document(c.id).get().await().data }.getOrNull()
                    if (d == null || !canPost(d, me, g.minMembers, g.appAdmin, g.open)) null
                    else {
                        val channel = d["channel"] == true
                        GroupStoryTarget(c.id, c.displayName(me), c.displayPhoto(me),
                            if (channel) (d["followerCount"] as? Number)?.toInt() ?: 0
                            else ((d["users"] as? List<*>)?.size ?: c.users.size),
                            channel)
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.name.lowercase() }
        }
    }

    /**
     * iOS groupStoryAudience: the members as the SERVER has them, read fresh so somebody removed a
     * minute ago is not in it, less me and anyone I blocked. Refused when I may no longer post there.
     */
    suspend fun audience(me: String, groupId: String): Set<String> {
        val db = FirebaseFirestore.getInstance()
        val d = db.collection("conversations").document(groupId).get(Source.SERVER).await().data
        val g = gate(db, me)
        if (d == null || !canPost(d, me, g.minMembers, g.appAdmin, g.open)) throw StoryService.PostRefused(NOT_ALLOWED)
        val blocked = BlockList.snapshot.entries.keys
        return (d["users"] as? List<*>)?.filterIsInstance<String>().orEmpty().filter { it != me && it !in blocked }.toSet()
    }

    /**
     * iOS groupStoryFields: which group, the name and photo a member's row shows before their chat
     * list catches up, never public, and the poster's country. Laid over the ordinary payload.
     */
    fun fields(g: GroupStoryTarget): Map<String, Any> = mapOf(
        "groupId" to g.id,
        "groupName" to g.name.take(128),
        "groupPhoto" to (g.photoUrl ?: ""),
        "public" to false,
        "audienceLabel" to "group",
        "oneTime" to false,
        "posterCountry" to java.util.Locale.getDefault().country.uppercase(),
    )
}
