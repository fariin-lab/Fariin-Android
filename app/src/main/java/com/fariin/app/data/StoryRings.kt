package com.fariin.app.data

/**
 * The story ring on a chat-list photo (iOS MainShell storyRing / storySeen): a 1:1 chat rings with
 * the other person's stories, a group with its own ring ("g:" + id), my group stories still
 * uploading included. A person hidden in Stories gets no ring here either.
 */
object StoryRings {
    fun forChat(c: Conversation, me: String, s: StoriesState, hidden: Set<String>): StoryGroup? {
        if (c.isChannel) return null
        if (!c.isGroup) {
            val other = c.otherUid(me)
            if (other.isEmpty() || other in hidden) return null
            return s.others.firstOrNull { it.key == other && it.stories.isNotEmpty() }
        }
        val key = StoryGroup.GROUP_PREFIX + c.id
        s.others.firstOrNull { it.key == key && it.stories.isNotEmpty() }?.let { g ->
            return if (g.stories.first().authorUid in hidden) null else g
        }
        val mine = s.mine?.stories?.filter { it.groupId == c.id }.orEmpty()
        if (mine.isEmpty()) return null
        return StoryGroup(key, c.displayName(me), c.displayPhoto(me), mine)
    }

    /** One flag per story, oldest first: true = seen (grey arc), from the group's watermark. */
    fun seenFlags(g: StoryGroup): List<Boolean> =
        g.stories.map { it.createdAtMs <= (g.lastViewedAtMs ?: Long.MIN_VALUE) }

    /** iOS StoryRingGeometry: at most twenty arcs, the newest ones. */
    const val MAX_SEGMENTS = 20
    fun condensed(seen: List<Boolean>): List<Boolean> = if (seen.size > MAX_SEGMENTS) seen.takeLast(MAX_SEGMENTS) else seen

    /** The gap between arcs as a fraction of a turn: 2 line widths of circumference, capped at 70% of an arc. */
    fun gap(count: Int, diameter: Float, lineWidth: Float): Float =
        if (count <= 1 || diameter <= 0f) 0f else minOf((lineWidth * 2) / (Math.PI.toFloat() * diameter), (1f / count) * 0.7f)
}
