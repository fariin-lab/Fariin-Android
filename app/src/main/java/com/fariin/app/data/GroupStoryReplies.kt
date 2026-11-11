package com.fariin.app.data

import com.fariin.app.channel.ChannelFollows
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/**
 * One reply in a group story's shared thread (iOS GroupStoryReply): text, or an emoji for a
 * reaction. Stored at `stories/{id}/replies/{replyId}` with only uid, text, emoji and createdAt
 * (firestore.rules). Everyone the story reached may write their own; the author and the group's
 * owner and admins read them; the writer or a group admin deletes.
 */
data class GroupStoryReply(val id: String, val uid: String, val text: String, val emoji: String, val createdAtMs: Long) {
    val isReaction: Boolean get() = text.isEmpty() && emoji.isNotEmpty()
}

object GroupStoryReplies {
    private fun col(storyId: String) =
        FirebaseFirestore.getInstance().collection("stories").document(storyId).collection("replies")

    /** iOS sendGroupReply: text up to 1000, an emoji up to 16; nothing when both are empty. */
    suspend fun send(me: String, storyId: String, text: String = "", emoji: String = "") {
        val data = payload(me, text, emoji) ?: return
        if (storyId.isEmpty()) return
        col(storyId).add(data + ("createdAt" to FieldValue.serverTimestamp())).await()
    }

    /** What the document holds besides createdAt, or null when there is nothing to send. Pure, tested. */
    fun payload(me: String, text: String, emoji: String): Map<String, Any>? {
        if (me.isEmpty()) return null
        val t = text.trim().take(1000)
        val e = emoji.take(16)
        if (t.isEmpty() && e.isEmpty()) return null
        return buildMap {
            put("uid", me)
            if (t.isNotEmpty()) put("text", t)
            if (e.isNotEmpty()) put("emoji", e)
        }
    }

    /** Oldest first, as a chat reads (iOS listenGroupReplies, the last 500). */
    fun listen(storyId: String, onChange: (List<GroupStoryReply>) -> Unit): ListenerRegistration =
        col(storyId).orderBy("createdAt", Query.Direction.ASCENDING).limitToLast(500)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                onChange(snap.documents.map { d ->
                    GroupStoryReply(
                        d.id, d.getString("uid").orEmpty(), d.getString("text").orEmpty(), d.getString("emoji").orEmpty(),
                        d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                    )
                })
            }

    suspend fun delete(storyId: String, replyId: String) {
        col(storyId).document(replyId).delete().await()
    }
}

/** Who may do what with a group story (iOS GroupStoryRights / canDeleteGroupReply), from the chat list. */
object GroupStoryRules {
    /** The rules' groupAdminOf: the group's creator, or anyone in `admins`. */
    fun isStaff(c: Conversation?, me: String): Boolean =
        c != null && me.isNotEmpty() && (c.createdBy == me || me in c.admins)

    /**
     * iOS GroupStoryStaff.mayDelete, the rules' groupStoryModerator word for word: a member who is the
     * owner, or an admin with no rights entry (legacy, all rights) or one that lists deleteMessages.
     */
    fun mayDelete(groupId: String?, me: String, convs: List<Conversation>): Boolean {
        if (groupId.isNullOrEmpty() || me.isEmpty()) return false
        val c = convs.firstOrNull { it.id == groupId && it.isGroup } ?: return false
        if (me !in c.users) return false
        if (c.createdBy == me) return true
        if (me !in c.admins) return false
        return c.adminRights[me]?.contains("deleteMessages") ?: true
    }

    /**
     * iOS othersMaySave: never a screenshot-blocked story; Fariin's own; a group story only from a
     * public group that does not forbid copying, not one-time, and not from someone who blocked me.
     */
    fun othersMaySave(s: Story, official: Boolean, convs: List<Conversation>, me: String): Boolean {
        if (s.captureProtected) return false
        if (official) return true
        val gid = s.groupId?.takeIf { it.isNotEmpty() } ?: return false
        val c = convs.firstOrNull { it.id == gid } ?: return false
        if (!c.isPublicGroup || s.oneTime || c.restrictLegacy) return false
        val blockedMe = convs.any { !it.isGroup && it.users.contains(s.authorUid) && it.users.contains(me) && it.isBlockedByMe(s.authorUid) }
        return !blockedMe
    }

    /** iOS GroupStoryStaff.noReport: the group switched reporting off. */
    fun noReport(groupId: String?, convs: List<Conversation>): Boolean =
        !groupId.isNullOrEmpty() && convs.firstOrNull { it.id == groupId }?.noReport == true

    /** The poster, or the group's owner and admins: they get the poster's bar and the Views | Replies sheet. */
    fun canSeeViews(s: Story, me: String, convs: List<Conversation>): Boolean {
        if (me.isEmpty()) return false
        if (s.authorUid == me) return true
        val gid = s.groupId ?: return false
        return isStaff(convs.firstOrNull { it.id == gid }, me)
    }

    fun isChannel(gid: String, convs: List<Conversation>): Boolean =
        convs.firstOrNull { it.id == gid }?.isChannel == true || ChannelFollows.entries.value.containsKey(gid)

    /** A Replies tab: a group story with replies on, never a channel's (iOS audienceFor). */
    fun hasRepliesTab(s: Story, convs: List<Conversation>): Boolean {
        val gid = s.groupId ?: return false
        return s.allowsReplies && !isChannel(gid, convs)
    }

    /** A member's reply field on a group story (iOS canMessage for a group story): it reached me, replies are on. */
    fun memberCanReply(s: Story, me: String, convs: List<Conversation>): Boolean =
        s.groupId != null && s.allowsReplies && !canSeeViews(s, me, convs) && !isChannel(s.groupId, convs)

    /** iOS canDeleteGroupReply: its writer, or the group's owner or an admin. */
    fun canDelete(r: GroupStoryReply, groupId: String, me: String, convs: List<Conversation>): Boolean =
        r.uid == me || isStaff(convs.firstOrNull { it.id == groupId }, me)
}
