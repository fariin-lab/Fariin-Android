package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/** One story, the fields the list needs (iOS Story in StoriesService.swift). */
data class Story(
    val id: String,
    val authorUid: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val mediaUrl: String,
    val thumbUrl: String = "",
    val isVideo: Boolean = false,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupPhoto: String? = null,
) {
    /** The ring a story belongs to: its author, or "g:" + the group for a group's story. */
    val ringKey: String get() = groupId?.takeIf { it.isNotEmpty() }?.let { StoryGroup.GROUP_PREFIX + it } ?: authorUid
    val listThumb: String get() = thumbUrl.ifEmpty { mediaUrl }
}

/** One person's (or group's) stories, oldest first, as iOS StoryGroup. */
data class StoryGroup(
    val key: String,
    val name: String,
    val photoUrl: String?,
    val stories: List<Story>,
    val lastViewedAtMs: Long? = null,
) {
    val hasUnseen: Boolean get() = stories.any { it.createdAtMs > (lastViewedAtMs ?: Long.MIN_VALUE) }

    companion object {
        const val GROUP_PREFIX = "g:"
    }
}

/** The hashed audience entry in `recipientUids`: sha256("fariin-audience-v1:" + uid), lower hex. */
object StoryAudienceToken {
    fun token(uid: String): String =
        MessageDigest.getInstance("SHA-256").digest("fariin-audience-v1:$uid".toByteArray())
            .joinToString("") { "%02x".format(it) }
}

data class StoriesState(
    val loaded: Boolean = false,
    val mine: StoryGroup? = null,
    val others: List<StoryGroup> = emptyList(),
)

object StoryList {
    fun parse(id: String, data: Map<String, Any?>): Story? {
        val author = data["authorUid"] as? String ?: return null
        val url = (data["mediaUrl"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val exp = (data["expiresAt"] as? Timestamp)?.toDate()?.time ?: return null
        return Story(
            id = id, authorUid = author,
            createdAtMs = (data["createdAt"] as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis(),
            expiresAtMs = exp, mediaUrl = url,
            thumbUrl = data["thumbUrl"] as? String ?: "",
            isVideo = data["type"] == "video",
            groupId = (data["groupId"] as? String)?.takeIf { it.isNotEmpty() },
            groupName = data["groupName"] as? String,
            groupPhoto = data["groupPhoto"] as? String,
        )
    }

    /**
     * iOS StoriesRepository.rebuild: live stories grouped by ring, blocked authors and
     * unanswered message requests left out, unseen first then newest. Names come from my chats
     * first, then from fetched profiles.
     */
    fun build(
        me: String,
        stories: List<Story>,
        convs: List<Conversation>,
        profiles: Map<String, Pair<String, String?>>,
        watermarks: Map<String, Long>,
        myName: String,
        myPhoto: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): StoriesState {
        val blocked = convs.filter { !it.isGroup && it.isBlockedByMe(me) }.map { it.otherUid(me) }.toSet()
        val byRing = stories.distinctBy { it.id }.filter { it.expiresAtMs > nowMs }
            .filter { s -> !blocked.contains(s.authorUid) }
            .groupBy { it.ringKey }
        var mine: StoryGroup? = null
        val others = mutableListOf<StoryGroup>()
        for ((key, list) in byRing) {
            val sorted = list.sortedBy { it.createdAtMs }
            if (key.startsWith(StoryGroup.GROUP_PREFIX)) {
                val gid = key.removePrefix(StoryGroup.GROUP_PREFIX)
                val conv = convs.firstOrNull { it.id == gid }
                others += StoryGroup(
                    key, conv?.displayName(me) ?: sorted.last().groupName ?: "",
                    conv?.displayPhoto(me) ?: sorted.last().groupPhoto, sorted, watermarks[key],
                )
                continue
            }
            if (key == me) {
                mine = StoryGroup(key, myName, myPhoto, sorted, watermarks[key])
                continue
            }
            val conv = convs.firstOrNull { !it.isGroup && it.otherUid(me) == key }
            val (name, photo) = conv?.let { it.name(me) to it.photoUrl(me) } ?: profiles[key] ?: ("" to null)
            others += StoryGroup(key, name, photo, sorted, watermarks[key])
        }
        others.sortWith(
            compareByDescending<StoryGroup> { it.hasUnseen }
                .thenByDescending { it.stories.lastOrNull()?.createdAtMs ?: 0L },
        )
        return StoriesState(loaded = true, mine = mine, others = others)
    }

    /** "Just now", "5m ago", "3h ago", "1d ago", as iOS StoryListRow.ago. */
    fun ago(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val s = maxOf(0L, (nowMs - createdAtMs) / 1000)
        return when {
            s < 60 -> "Just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }
}

/**
 * The same four listeners as iOS StoriesRepository.start: stories whose `recipientUids` holds
 * my audience token, the legacy ones holding my bare uid, my own (`authorUid == me`), and my
 * `users/{me}/storyContexts` seen watermarks. Read only: opening a story (which marks it seen)
 * is not built yet.
 *
 * TODO (iOS has these): channel stories, the official channel's story, one-time stories already
 * used on this device, the local per-story seen flags, hidden authors, the 24-hour expiry timer.
 */
class StoriesRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    fun listen(me: String, convs: () -> List<Conversation>, myName: () -> String, myPhoto: () -> String?): Flow<StoriesState> =
        callbackFlow {
            var byToken = emptyList<Story>()
            var byLegacy = emptyList<Story>()
            var mine = emptyList<Story>()
            val watermarks = mutableMapOf<String, Long>()
            val profiles = mutableMapOf<String, Pair<String, String?>>()
            val asked = mutableSetOf<String>()
            var heard = 0

            fun rebuild() {
                if (heard != 7) return
                val all = byToken + byLegacy + mine
                val known = convs().filter { !it.isGroup }.map { it.otherUid(me) }.toSet()
                val unknown = all.map { it.authorUid }.toSet() - known - me - asked
                for (u in unknown) {
                    if (u.isEmpty() || u.contains('/')) continue
                    asked += u
                    launch {
                        val snap = runCatching { db.collection("users").document(u).get().await() }.getOrNull()
                        if (snap != null && snap.exists()) {
                            profiles[u] = (snap.getString("name") ?: "") to snap.getString("photoUrl")
                            trySend(StoryList.build(me, byToken + byLegacy + mine, convs(), profiles, watermarks, myName(), myPhoto()))
                        }
                    }
                }
                trySend(StoryList.build(me, all, convs(), profiles, watermarks, myName(), myPhoto()))
            }

            fun parse(docs: List<DocumentSnapshot>) = docs.mapNotNull { d -> d.data?.let { StoryList.parse(d.id, it) } }

            val stories = db.collection("stories")
            val regs = mutableListOf<ListenerRegistration>()
            // Each story query counts as heard on its first answer or its first error, so one
            // refused query (the legacy one, say) cannot keep the list blank.
            regs += stories.whereArrayContains("recipientUids", StoryAudienceToken.token(me))
                .addSnapshotListener { s, _ -> if (s != null) byToken = parse(s.documents); heard = heard or 1; rebuild() }
            regs += stories.whereArrayContains("recipientUids", me)
                .addSnapshotListener { s, _ -> if (s != null) byLegacy = parse(s.documents); heard = heard or 2; rebuild() }
            regs += stories.whereEqualTo("authorUid", me)
                .addSnapshotListener { s, _ -> if (s != null) mine = parse(s.documents); heard = heard or 4; rebuild() }
            regs += db.collection("users").document(me).collection("storyContexts")
                .addSnapshotListener { s, _ ->
                    s?.documents?.forEach { d ->
                        val ts = (d.get("lastViewedAt") as? Timestamp)?.toDate()?.time ?: return@forEach
                        val cur = watermarks[d.id] ?: Long.MIN_VALUE
                        if (ts + 1 > cur) watermarks[d.id] = ts + 1
                    }
                    rebuild()
                }
            awaitClose { regs.forEach { it.remove() } }
        }
}
