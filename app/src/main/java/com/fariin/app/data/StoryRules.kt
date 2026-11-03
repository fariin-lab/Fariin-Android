package com.fariin.app.data

import java.security.SecureRandom

/** One person in a story's viewers list (iOS StoryViewerInfo). */
data class StoryViewer(
    val uid: String,
    val name: String,
    val photoUrl: String?,
    val viewedAtMs: Long,
    val reaction: String? = null,
)

/** The owner bar's numbers (iOS StoryViewSummary). */
data class StoryViewSummary(
    val count: Int,
    val reactionCount: Int,
    val recent: List<StoryViewer>,
    val repostCount: Int = 0,
) {
    companion object {
        /** From the receipts themselves, when there is no counter document (iOS `counted(from:)`). */
        fun counted(viewers: List<StoryViewer>) = StoryViewSummary(
            count = viewers.size,
            reactionCount = viewers.count { !it.reaction.isNullOrEmpty() },
            recent = viewers.take(3),
        )
    }
}

/** Who a new story goes to: the rows of the iOS Share Story sheet that Android has. */
enum class StoryAudienceChoice(val title: String, val tagLabel: String, val footer: String) {
    EVERYONE("Everyone", "everyone",
        "Anyone on Fariin who opens your profile can watch this. People you have chatted with also get it in their stories."),
    MY_CHATS("My Chats", "friends", "The people you have chatted with can watch it."),
    /** Everyone I have a Glow with, either way (iOS StoryAudience.glowers); offered only with Glow on. */
    GLOWERS("Glowers", "glowers", "Only the people in this list can watch it."),
    CUSTOM("Custom", "custom", "Only the people in this list can watch it."),
    ONE_TIME("One-Time Story", "oneTime",
        "Each person can open this once. As soon as they do it is gone for them, and they cannot open it again."),
    ;

    val needsPeople: Boolean get() = this == CUSTOM || this == ONE_TIME
}

object StoryRules {
    /** iOS story lifetime menu: "Choose how long the story will be visible." */
    val HOURS = listOf(6, 12, 24, 48)
    const val DEFAULT_HOURS = 24

    /** iOS StoryPhoto: widths tried in turn until the JPEG is at most 1.5 MB, quality 0.85. */
    val PHOTO_WIDTHS = listOf(1080, 900, 720, 540)
    const val PHOTO_BUDGET = 1_500_000
    const val PHOTO_QUALITY = 85
    /** Storage rule: a story file is under 50 MB. */
    const val VIDEO_MAX_BYTES = 50L * 1024 * 1024
    const val VIEWER_LIST_CAP = 500L

    /** iOS StoriesService.newStoryId: 10 characters of [0-9A-Za-z]. */
    fun newStoryId(rng: java.util.Random = SecureRandom()): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        return String(CharArray(10) { chars[rng.nextInt(chars.length)] })
    }

    fun photoPath(id: String) = "stories/$id/photo.jpg"
    fun videoPath(id: String) = "stories/$id/video.mp4"
    fun thumbPath(id: String) = "stories/$id/thumb.jpg"

    /** "12s", "5m", "3h", "1d", "2w": the viewer header (iOS StoryViewer.timeAgo). */
    fun timeAgo(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val s = maxOf(0L, (nowMs - createdAtMs) / 1000)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            else -> "${s / (7 * 86_400)}w"
        }
    }

    /** iOS storyAudienceTitle: the line under my name on my own story. */
    fun audienceTitle(s: Story): String = when {
        s.oneTime -> "View once"
        s.audienceLabel == "everyone" -> "Everyone"
        s.audienceLabel == "glowers" -> "Glowers"
        s.audienceLabel == "group" -> s.groupName?.ifEmpty { null } ?: "Group"
        s.audienceLabel == "custom" -> "Custom"
        else -> "My Chats"
    }

    /** "N View" / "N Views" (iOS StoryOwnerStats). */
    fun viewsLabel(count: Int, oneTimeFull: Boolean): String =
        if (oneTimeFull) "Once viewer is full" else "$count View${if (count == 1) "" else "s"}"

    /**
     * The people I may post to (iOS resolveAudience `allContacts`): my 1:1 chats, not a request I
     * have not answered, nobody blocked either way.
     */
    fun contacts(me: String, convs: List<Conversation>): Set<String> =
        convs.asSequence()
            .filter { !it.isGroup && !ChatList.isIncomingRequest(it, me) }
            .filter { c -> val them = c.otherUid(me); them.isNotEmpty() && !c.isBlockedByMe(me) && !c.isBlockedByMe(them) }
            .map { it.otherUid(me) }
            .toSet()

    /** The audience and its `audience.mode`, as iOS resolveAudience (no hidden-from list on Android yet). */
    fun resolveAudience(me: String, convs: List<Conversation>, choice: StoryAudienceChoice, picked: Set<String>): Pair<Set<String>, String> {
        val all = contacts(me, convs)
        return when {
            choice.needsPeople -> picked.intersect(all) to "only"
            choice == StoryAudienceChoice.EVERYONE -> all to "everyone"
            else -> all to "all"
        }
    }

    /**
     * The story document a post creates (iOS postStory / postVideoSegment payload), `mediaUrl` (and
     * `thumbUrl`) empty until the upload is done. [now] stands in for the server time stamp.
     */
    fun payload(
        me: String,
        isVideo: Boolean,
        storyId: String,
        caption: String,
        choice: StoryAudienceChoice,
        mode: String,
        recipients: Set<String>,
        expiresAt: Any,
        now: Any,
        blurThumb: String,
        video: VideoInfo? = null,
        allowsReplies: Boolean = true,
        captureProtected: Boolean = false,
        stickers: List<StoryTapTarget> = emptyList(),
    ): Map<String, Any> {
        val everyone = choice == StoryAudienceChoice.EVERYONE
        val p = linkedMapOf<String, Any>(
            "authorUid" to me,
            "createdAt" to now,
            "expiresAt" to expiresAt,
            "type" to if (isVideo) "video" else "image",
            "mediaPath" to if (isVideo) videoPath(storyId) else photoPath(storyId),
            "mediaUrl" to "",
            "blurThumb" to blurThumb,
            "caption" to caption,
            "stickers" to stickers.map { StoryTaps.asMap(it) },
            "audience" to mapOf("mode" to mode, "listId" to "my-story"),
            "audienceLabel" to choice.tagLabel,
            "oneTime" to (choice == StoryAudienceChoice.ONE_TIME),
            "captureProtected" to captureProtected,
            "public" to everyone,
            "allowsReplies" to allowsReplies,
            "replyCount" to 0,
            "recipientUids" to recipients.map { StoryAudienceToken.token(it) },
        )
        if (isVideo && video != null) {
            p["thumbUrl"] = ""
            p["duration"] = video.durationSec
            p["width"] = video.width
            p["height"] = video.height
            p["bytes"] = video.bytes
            p["preloadPrefix"] = minOf(video.bytes, 1536L * 1024)
        }
        return p
    }

    data class VideoInfo(val durationSec: Double, val width: Int, val height: Int, val bytes: Long)

    /** Where the viewer opens a ring: the first story I have not seen, else the first (iOS). */
    fun startIndex(g: StoryGroup): Int {
        val seen = g.lastViewedAtMs ?: return 0
        val i = g.stories.indexOfFirst { it.createdAtMs > seen }
        return if (i < 0) 0 else i
    }

    /** A tap's meaning: the left third goes back, the rest goes on (StoryUI tapStory). */
    fun tapGoesBack(x: Float, width: Float): Boolean = x < width / 3f

    /** Moves through the rings: next item, else the next ring's first, else null (close). */
    fun next(cur: Pair<Int, Int>, sizes: List<Int>): Pair<Int, Int>? {
        val (g, i) = cur
        if (g !in sizes.indices) return null
        if (i + 1 < sizes[g]) return g to i + 1
        var ng = g + 1
        while (ng < sizes.size && sizes[ng] == 0) ng++
        return if (ng < sizes.size) ng to 0 else null
    }

    /** Back: the item before, else the previous ring's last, else stay on the first item. */
    fun previous(cur: Pair<Int, Int>, sizes: List<Int>): Pair<Int, Int> {
        val (g, i) = cur
        if (i > 0) return g to i - 1
        var pg = g - 1
        while (pg >= 0 && sizes[pg] == 0) pg--
        return if (pg >= 0) pg to sizes[pg] - 1 else g to 0
    }

    /** Only when the story is newer than what the server already holds (iOS advanceServerWatermark). */
    fun advancesWatermark(current: Long?, storyCreatedAtMs: Long): Boolean =
        current == null || storyCreatedAtMs > current

    /** A one-time story's audience has all been through (iOS oneTimeFull). */
    fun oneTimeFull(s: Story): Boolean = s.oneTime && s.recipientsLeft == 0
}
