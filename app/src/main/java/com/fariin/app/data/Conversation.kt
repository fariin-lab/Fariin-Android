package com.fariin.app.data

import com.google.firebase.Timestamp

/**
 * One chat as the chat list sees it. Mirrors the fields of `Conversation` in the iOS app
 * (Kulan-iOS/Kulan/Models/Models.swift, `init(id:data:)`) that the list needs. Firestore field
 * names are kept exactly as iOS reads them; the Kotlin name is noted where it differs.
 *
 * Not ported yet (TODO, see Models.swift): typing/recording, reactions preview, album thumbnails,
 * channel follower state (users/{me}/channels), the private block list (BlockList), saved contact
 * names, group moderation (`mod`), roles, restrictions, timers.
 */
@androidx.compose.runtime.Immutable
data class Conversation(
    val id: String,
    val users: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val photos: Map<String, String> = emptyMap(),
    /** Firestore `lastMessage`: sealed text (or plain words in a group with e2ee off). */
    val lastMessageCipher: String = "",
    val lastImageUrl: String? = null,
    /** The thumbnail's envelope (`lastImageEnc`), sealed for this chat like the photo itself. */
    val lastImageEnc: com.fariin.app.crypto.EncMeta? = null,
    val lastSender: String = "",
    val unreadCount: Map<String, Int> = emptyMap(),
    val replyUnread: Map<String, Int> = emptyMap(),
    val mutedBy: Map<String, Double> = emptyMap(),
    val pinnedBy: Map<String, Boolean> = emptyMap(),
    val archivedBy: Map<String, Boolean> = emptyMap(),
    val clearedAt: Map<String, Double> = emptyMap(),
    /** The old shared copy of a block; the private BlockList is not ported yet. */
    val blockedBy: Map<String, Boolean> = emptyMap(),
    val blockedAt: Map<String, Double> = emptyMap(),
    val blockClearedAt: Map<String, Double> = emptyMap(),
    val pinOrder: Map<String, Double> = emptyMap(),
    val markedUnread: Map<String, Boolean> = emptyMap(),
    val delivered: Map<String, Double> = emptyMap(),
    val lastHeld: Boolean = false,
    /** Group admins and "Start group call: Admins only" (for the New Call page's group rows). */
    val admins: List<String> = emptyList(),
    val onlyAdminsCall: Boolean = false,
    /** This phone's own write to the chat doc (a send made offline) has not reached the server yet. */
    val localPending: Boolean = false,
    /** Firestore `type`: "group" for a group chat, "" or "direct" for a 1:1. */
    val convType: String = "",
    val title: String = "",
    val avatarUrl: String? = null,
    /** Firestore `channel`. */
    val isChannel: Boolean = false,
    /** Verified by Fariin (channels; a moderation label cancels it, as ChannelInfo). */
    val verified: Boolean = false,
    /** A group whose document says `e2ee == false`. */
    val e2eeOff: Boolean = false,
    val lastPlainTag: String? = null,
    val disappearSeconds: Int = 0,
    /** When the newest message falls due on the timer (server `lastExpiresAt`, ms), or null. */
    val lastExpiresAtMillis: Double? = null,
    val updatedAtMillis: Double = 0.0,
    /** The newest reaction (iOS lastReactionBy / lastReactionEnc / lastReactionAt / lastReactionToEnc /
     *  lastReactionToAuthor): the emoji and the words it was on, sealed by the reactor. */
    val lastReactionBy: String = "",
    val lastReactionEnc: String? = null,
    val lastReactionAtMillis: Double = 0.0,
    val lastReactionToEnc: String? = null,
    val lastReactionToAuthor: String = "",
    val startedBy: String = "",
    val accepted: Boolean = true,
    val reqOff: Boolean = false,
    /** Restricted chat (iOS): each person's own switch, and the older shared fields. */
    val restrictedBy: Map<String, Boolean> = emptyMap(),
    val restrictLegacy: Boolean = false,
    val restrictRequestBy: String? = null,
    val restrictRequestAtMs: Long = 0,
    val deletedUsers: List<String> = emptyList(),
    /** `rk`: keys a member released for old sealed group items (nonce b64 to key b64). */
    val releasedKeys: Map<String, String> = emptyMap(),
    /** Channel: posts so far (server), and the numbers of posts deleted lately. */
    val postCount: Int = 0,
    val deletedPostSeqs: List<Int> = emptyList(),
    /** Mine as a follower (ChannelFollows, users/{me}/channels/{cid}); null when I am not one. */
    val followerRead: Int? = null,
    val followerMuted: Boolean = false,
    /** A group's shared wallpaper (iOS groupWallpaper, `wallpaper`): "none", "g:..", "u:<link>"; null when never set. */
    val groupWallpaper: String? = null,
) {
    val isGroup: Boolean get() = convType == "group"

    fun otherUid(me: String): String = users.firstOrNull { it != me } ?: ""
    fun others(me: String): List<String> = users.filter { it != me }
    fun isDeletedAccount(uid: String): Boolean = uid.isNotEmpty() && deletedUsers.contains(uid)

    fun name(me: String): String {
        val other = otherUid(me)
        if (!isGroup && isDeletedAccount(other)) return DELETED_ACCOUNT_NAME
        // 1:1 only: a name I saved for them on this phone overrides their profile name.
        if (!isGroup) ContactNames.name(other)?.let { return it }
        return names[other] ?: "User"
    }

    fun photoUrl(me: String): String? {
        val other = otherUid(me)
        if (!isGroup && isDeletedAccount(other)) return null
        // TODO: iOS also compares with the photo in the person's own profile (ProfilePhotoIndex)
        // and keeps whichever has the newer `?v=` stamp.
        return photos[other]?.takeIf { it.isNotEmpty() }
    }

    fun displayName(me: String): String =
        if (isGroup) title.ifEmpty { "Group" } else name(me)

    fun displayPhoto(me: String): String? =
        if (isGroup) avatarUrl?.takeIf { it.isNotEmpty() } else photoUrl(me)

    // Channel followers (users/{me}/channels) are not ported yet, so nobody is a follower here.
    fun isFollower(me: String): Boolean = isChannel && followerRead != null && !users.contains(me)

    fun isPinned(me: String): Boolean = pinnedBy[me] ?: false
    fun isArchived(me: String): Boolean = archivedBy[me] ?: false
    fun pinRank(me: String): Double = pinOrder[me] ?: displayUpdatedAt(me)

    /**
     * "Delete for me" until a newer message arrives. Never for a group I am still in (owner
     * 2026-11-02): removing a group from the list means leaving it, so a group I am a member of
     * always shows.
     */
    fun isCleared(me: String): Boolean {
        if (isGroup && users.contains(me)) return false
        return updatedAtMillis <= (clearedAt[me] ?: 0.0)
    }

    fun lastIsMine(me: String): Boolean = lastSender.isNotEmpty() && lastSender == me

    /** Never negative: a negative count is the old manual "mark as unread" flag. */
    fun unread(me: String): Int =
        if (isFollower(me)) com.fariin.app.channel.ChannelRules.unread(postCount, followerRead ?: 0, deletedPostSeqs)
        else maxOf(0, unreadCount[me] ?: 0)
    fun manuallyUnread(me: String): Boolean = (markedUnread[me] ?: false) || (unreadCount[me] ?: 0) < 0
    fun hasUnreadMark(me: String): Boolean = unread(me) > 0 || manuallyUnread(me)
    fun hasUnreadReply(me: String): Boolean = isGroup && unread(me) > 0 && (replyUnread[me] ?: 0) > 0
    fun isMuted(me: String, nowMillis: Double): Boolean = if (isFollower(me)) followerMuted else (mutedBy[me] ?: 0.0) > nowMillis

    fun lastDeliveredToOther(me: String): Boolean {
        if (isGroup || updatedAtMillis <= 0 || lastHeld) return false
        return (delivered[otherUid(me)] ?: 0.0) >= updatedAtMillis
    }

    /** A group call I may start here, as GroupCallWire.mayStart (the server checks again). */
    fun mayStartCall(me: String): Boolean =
        isGroup && !isChannel && users.contains(me) && (!onlyAdminsCall || admins.contains(me))

    fun lastReadByOther(me: String): Boolean {
        if (isGroup) return others(me).all { (unreadCount[it] ?: 0) <= 0 }
        if (lastHeld) return false
        return (unreadCount[otherUid(me)] ?: 0) <= 0
    }

    /** The old shared copy, or my private BlockList (users/{me}/blocked) once it has loaded. */
    /** A reaction newer than the last message: the list previews it (iOS freshReaction). Never in a
     *  channel, not from someone I blocked, not older than my delete-for-me. */
    fun freshReaction(me: String): Boolean =
        !isChannel && lastReactionBy.isNotEmpty() && lastReactionEnc != null &&
            lastReactionAtMillis > updatedAtMillis && lastReactionAtMillis > (clearedAt[me] ?: 0.0) &&
            !(isBlockedByMe(me) && lastReactionBy != me)

    fun isBlockedByMe(me: String): Boolean = !isGroup && ((blockedBy[me] ?: false) ||
        (BlockList.snapshot.owner == me && BlockList.contains(otherUid(me))))

    /** Freezes the list time while the newest message is one a block holds back. */
    private fun hiddenLastSince(me: String): Double? {
        if (isGroup || updatedAtMillis <= 0) return null
        if (isBlockedByMe(me)) {
            val since = blockedAt[me] ?: 0.0
            return if (since > 0 && updatedAtMillis > since) since else null
        }
        if (lastSender != otherUid(me)) return null
        val at = blockedAt[me] ?: 0.0
        val cleared = blockClearedAt[me] ?: 0.0
        if (at > 0 && cleared >= at && updatedAtMillis in at..cleared) return at
        return null
    }

    fun displayUpdatedAt(me: String): Double = hiddenLastSince(me) ?: updatedAtMillis
    fun listSortDate(me: String): Double = displayUpdatedAt(me)
    fun leaksBlocked(me: String): Boolean = hiddenLastSince(me) != null

    /** The newest line is a member's own words, sent in the clear in a group that switched. */
    val lastIsPlainText: Boolean
        get() {
            val t = lastPlainTag
            if (t.isNullOrEmpty() || lastMessageCipher.isEmpty()) return false
            return t == plainTag(lastMessageCipher)
        }

    companion object {
        const val DELETED_ACCOUNT_NAME = "Deleted Account"

        /** FNV-1a 64-bit of the UTF-8 bytes, hex. Same as iOS `Conversation.plainTag`. */
        fun plainTag(s: String): String {
            var h = 0xcbf29ce484222325uL
            for (b in s.encodeToByteArray()) {
                h = h xor (b.toUByte().toULong())
                h *= 0x100000001b3uL
            }
            return h.toString(16)
        }

        /** Same reading rules as iOS `Conversation(id:data:)`. */
        fun fromFirestore(id: String, data: Map<String, Any?>): Conversation {
            val type = data["type"] as? String ?: ""
            val startedBy = data["startedBy"] as? String
            return Conversation(
                id = id,
                users = stringList(data["users"]),
                names = stringMap(data["names"]),
                photos = stringMap(data["photos"]),
                lastMessageCipher = data["lastMessage"] as? String ?: "",
                lastImageUrl = data["lastImageUrl"] as? String,
                lastImageEnc = com.fariin.app.crypto.EncMeta.fromMap(data["lastImageEnc"] as? Map<*, *>),
                lastSender = data["lastSender"] as? String ?: "",
                unreadCount = intMap(data["unreadCount"]),
                replyUnread = intMap(data["replyUnread"]),
                mutedBy = doubleMap(data["mutedBy"]),
                pinnedBy = boolMap(data["pinnedBy"]),
                archivedBy = boolMap(data["archivedBy"]),
                clearedAt = doubleMap(data["clearedAt"]),
                blockedBy = boolMap(data["blockedBy"]),
                blockedAt = doubleMap(data["blockedAt"]),
                blockClearedAt = doubleMap(data["blockClearedAt"]),
                pinOrder = doubleMap(data["pinOrder"]),
                markedUnread = boolMap(data["markedUnread"]),
                delivered = doubleMap(data["delivered"]),
                lastHeld = data["lastHeld"] as? Boolean ?: false,
                admins = stringList(data["admins"]),
                onlyAdminsCall = data["onlyAdminsCall"] as? Boolean ?: false,
                convType = type,
                title = data["title"] as? String ?: "",
                avatarUrl = data["avatarUrl"] as? String,
                groupWallpaper = if (type == "group") data["wallpaper"] as? String else null,
                isChannel = data["channel"] as? Boolean ?: false,
                verified = (data["verified"] as? Boolean ?: false) && (data["label"] as? String).isNullOrEmpty(),
                e2eeOff = type == "group" && (data["e2ee"] as? Boolean) == false,
                lastPlainTag = data["lastPlainTag"] as? String,
                disappearSeconds = (data["disappearSeconds"] as? Number)?.toInt() ?: 0,
                lastExpiresAtMillis = millis(data["lastExpiresAt"]).takeIf { it > 0 },
                updatedAtMillis = millis(data["updatedAt"]),
                lastReactionBy = data["lastReactionBy"] as? String ?: "",
                lastReactionEnc = (data["lastReactionEnc"] as? String)?.takeIf { it.isNotEmpty() },
                lastReactionAtMillis = millis(data["lastReactionAt"]),
                lastReactionToEnc = (data["lastReactionToEnc"] as? String)?.takeIf { it.isNotEmpty() },
                lastReactionToAuthor = data["lastReactionToAuthor"] as? String ?: "",
                startedBy = startedBy ?: "",
                // Absent means "from before message requests existed": accepted.
                accepted = data["accepted"] as? Boolean ?: (data["startedBy"] == null),
                reqOff = data["reqOff"] as? Boolean ?: false,
                restrictedBy = (data["restrictedBy"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                    (k as? String)?.takeIf { v == true }?.let { it to true } }?.toMap() ?: emptyMap(),
                restrictLegacy = listOf("noScreenshots", "noForwarding", "noSaving").any { data[it] == true },
                restrictRequestBy = (data["restrictRequest"] as? Map<*, *>)?.get("by") as? String,
                restrictRequestAtMs = ((data["restrictRequest"] as? Map<*, *>)?.get("at") as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L,
                deletedUsers = stringList(data["deletedUsers"]),
                releasedKeys = stringMap(data["rk"]),
                postCount = maxOf(0, (data["postCount"] as? Number)?.toInt() ?: 0),
                deletedPostSeqs = (data["deletedPostSeqs"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
            )
        }

        internal fun millis(v: Any?): Double = when (v) {
            is Timestamp -> v.seconds * 1000.0 + v.nanoseconds / 1_000_000.0
            is Number -> v.toDouble()
            else -> 0.0
        }

        private fun stringList(v: Any?): List<String> =
            (v as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        private fun stringMap(v: Any?): Map<String, String> =
            (v as? Map<*, *>)?.entries?.mapNotNull { (k, x) ->
                if (k is String && x is String) k to x else null
            }?.toMap() ?: emptyMap()

        private fun intMap(v: Any?): Map<String, Int> =
            (v as? Map<*, *>)?.entries?.mapNotNull { (k, x) ->
                if (k is String && x is Number) k to x.toInt() else null
            }?.toMap() ?: emptyMap()

        private fun doubleMap(v: Any?): Map<String, Double> =
            (v as? Map<*, *>)?.entries?.mapNotNull { (k, x) ->
                if (k is String) {
                    val d = millis(x)
                    if (x is Number || x is Timestamp) k to d else null
                } else null
            }?.toMap() ?: emptyMap()

        private fun boolMap(v: Any?): Map<String, Boolean> =
            (v as? Map<*, *>)?.entries?.mapNotNull { (k, x) ->
                if (k is String && x is Boolean) k to x else null
            }?.toMap() ?: emptyMap()
    }
}
