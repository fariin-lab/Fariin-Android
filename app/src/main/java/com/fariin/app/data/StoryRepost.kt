package com.fariin.app.data

/**
 * The credit a reposted story carries (iOS StoryRepost, the `repostOf` map): who posted the
 * original, its id (the back-link), whether it was drawn as a card, and for a group story the
 * group. Same keys both ways so either app reads the other's.
 */
data class StoryRepost(
    val authorUid: String,
    val authorName: String,
    val storyId: String,
    val modified: Boolean = false,
    val card: Boolean = false,
    val groupId: String? = null,
    val groupPhoto: String? = null,
) {
    fun asMap(): Map<String, Any> = buildMap {
        put("authorUid", authorUid); put("authorName", authorName); put("storyId", storyId)
        put("modified", modified); put("card", card)
        if (!groupId.isNullOrEmpty()) { put("groupId", groupId); put("groupPhoto", groupPhoto ?: "") }
    }

    companion object {
        /** iOS StoryRepost.from: the author is required, the story id is not. */
        fun from(raw: Any?): StoryRepost? {
            val m = raw as? Map<*, *> ?: return null
            val uid = (m["authorUid"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            return StoryRepost(uid, m["authorName"] as? String ?: "", m["storyId"] as? String ?: "",
                m["modified"] as? Boolean ?: false, m["card"] as? Boolean ?: false,
                (m["groupId"] as? String)?.takeIf { it.isNotEmpty() }, (m["groupPhoto"] as? String)?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * iOS StoryShareRights.allows, for repost: a person's story only when it went to Everyone, never a
 * one-time or screenshot-protected one, never a repost of a repost, never from someone I blocked.
 * Group stories: iOS allows them only from a PUBLIC group, and this app's chat list does not know
 * which groups are public yet, so they are not offered here.
 */
object StoryRepostRules {
    fun allows(s: Story, repostOf: StoryRepost?, me: String, blockedByMe: Boolean): Boolean =
        repostOf == null && s.groupId.isNullOrEmpty() && s.isPublic && !s.oneTime && !s.captureProtected &&
            !s.isVideo && (s.authorUid == me || !blockedByMe)
}
