package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * The story writes and reads of iOS StoriesService, the same documents and Storage paths:
 * `stories/{id}` (+ `views/{me}`, `consumed/{me}`, `meta/views`), `users/{me}/storyContexts/{ring}`,
 * `users/{me}/hiddenStoryViews/{id}`, `users/{me}/publicStories/{id}`, `users/{me}/limits/{stories,
 * storiesDaily}`, Storage `stories/{id}/photo.jpg | video.mp4 | thumb.jpg`.
 */
class StoryService(
    private val me: String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {
    // ---- seen and receipts -------------------------------------------------------------------

    /** iOS StoryReceiptPrefs: `users/{me}/private/storyPrefs.receipts`, on unless switched off. */
    suspend fun receiptsOn(): Boolean = try {
        db.collection("users").document(me).collection("private").document("storyPrefs")
            .get().await().getBoolean("receipts") ?: true
    } catch (_: Exception) { true }

    /**
     * iOS markViewed: consume a one-time story, move my seen watermark for the ring when this
     * story is newer, then the receipt (`views/{me}`) or, with receipts off, the private record.
     */
    suspend fun markViewed(s: Story, watermark: Long?, receipts: Boolean) {
        if (me.isEmpty() || s.authorUid == me) return
        if (s.oneTime) consumeOneTime(s)
        if (StoryRules.advancesWatermark(watermark, s.createdAtMs)) {
            runCatching {
                db.collection("users").document(me).collection("storyContexts").document(s.ringKey)
                    .set(mapOf("lastViewedAt" to Timestamp(Date(s.createdAtMs))), SetOptions.merge()).await()
            }
        }
        runCatching {
            if (receipts) {
                db.collection("stories").document(s.id).collection("views").document(me)
                    .set(mapOf("viewedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
            } else {
                db.collection("users").document(me).collection("hiddenStoryViews").document(s.id)
                    .set(mapOf(
                        "storyId" to s.id, "authorUid" to s.authorUid, "at" to FieldValue.serverTimestamp(),
                        "expiresAt" to Timestamp(Date(s.expiresAtMs)),
                    )).await()
            }
        }
    }

    private suspend fun consumeOneTime(s: Story) {
        runCatching {
            db.collection("stories").document(s.id).collection("consumed").document(me)
                .set(mapOf("at" to FieldValue.serverTimestamp())).await()
        }
    }

    /** The heart (iOS setStoryReaction / clearStoryReaction): set with merge, never update. */
    suspend fun setLiked(s: Story, liked: Boolean): Boolean {
        if (me.isEmpty() || s.authorUid == me) return false
        val data: Map<String, Any> = if (liked) mapOf("viewedAt" to FieldValue.serverTimestamp(), "reaction" to "❤️")
            else mapOf("viewedAt" to FieldValue.serverTimestamp(), "reaction" to FieldValue.delete())
        return runCatching {
            db.collection("stories").document(s.id).collection("views").document(me).set(data, SetOptions.merge()).await()
        }.isSuccess
    }

    /** Did I already like it (my own receipt carries the heart). */
    suspend fun myReaction(storyId: String): String? = runCatching {
        db.collection("stories").document(storyId).collection("views").document(me).get().await().getString("reaction")
    }.getOrNull()

    // ---- the owner's side ------------------------------------------------------------------

    /** iOS fetchViewers: newest first, capped, from the server; null when the read failed. */
    suspend fun fetchViewers(storyId: String, who: (String) -> Pair<String, String?>): List<StoryViewer>? = try {
        val snap = db.collection("stories").document(storyId).collection("views")
            .orderBy("viewedAt", Query.Direction.DESCENDING).limit(StoryRules.VIEWER_LIST_CAP)
            .get(Source.SERVER).await()
        snap.documents.map { d ->
            val (name, photo) = who(d.id)
            StoryViewer(d.id, name, photo, d.getTimestamp("viewedAt")?.toDate()?.time ?: System.currentTimeMillis(),
                d.getString("reaction"))
        }.sortedByDescending { it.viewedAtMs }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // A newer read replaced this one (the views listener fires as it attaches): not a failure,
        // or the sheet flashed "Couldn't load viewers" on every open (owner 2026-11-03).
        throw e
    } catch (e: Exception) { android.util.Log.w("StoryService", "viewers read failed for $storyId", e); null }

    /**
     * iOS viewSummary: the server's counter (`meta/views`: count, reactionCount, recent, repostCount)
     * and, when it is missing or says 0, the receipts counted here.
     */
    suspend fun viewSummary(storyId: String, who: (String) -> Pair<String, String?>): StoryViewSummary? {
        val meta = runCatching {
            db.collection("stories").document(storyId).collection("meta").document("views").get().await()
        }.getOrNull()
        val fromMeta = meta?.data?.let { summaryOf(it, who) }
        if (fromMeta != null && fromMeta.count > 0) return fromMeta
        val receipts = fetchViewers(storyId, who) ?: return fromMeta
        return if (receipts.isEmpty()) fromMeta ?: StoryViewSummary.counted(receipts)
            else StoryViewSummary.counted(receipts).copy(repostCount = fromMeta?.repostCount ?: 0)
    }

    /** Live counter while my story is on screen (iOS observeViewCount); any change re-reads. */
    fun watchViews(storyId: String, onChange: () -> Unit): ListenerRegistration =
        db.collection("stories").document(storyId).collection("views").addSnapshotListener { s, _ -> if (s != null) onChange() }

    private fun summaryOf(data: Map<String, Any?>, who: (String) -> Pair<String, String?>): StoryViewSummary? {
        val count = (data["count"] as? Number)?.toInt() ?: if (data["repostCount"] is Number) 0 else return null
        val recent = (data["recent"] as? List<*>)?.filterIsInstance<String>()?.take(3)?.map { u ->
            val (n, p) = who(u); StoryViewer(u, n, p, 0L)
        } ?: emptyList()
        return StoryViewSummary(count, (data["reactionCount"] as? Number)?.toInt() ?: 0, recent,
            (data["repostCount"] as? Number)?.toInt() ?: 0)
    }

    /**
     * iOS deleteStory: the public mirror first, the files (best effort; the Storage rule refuses
     * deletes, which the server sweeps), then the document.
     */
    suspend fun deleteStory(id: String): Boolean {
        val data = runCatching { db.collection("stories").document(id).get().await().data }.getOrNull()
        val author = data?.get("authorUid") as? String ?: me
        if (author.isNotEmpty() && author != me) {
            return runCatching { db.collection("stories").document(id).delete().await() }.isSuccess
        }
        try {
            db.collection("users").document(author).collection("publicStories").document(id).delete().await()
        } catch (_: Exception) { return false }
        val paths = (data?.get("mediaPath") as? String)?.let { listOf(it) }
            ?: listOf(StoryRules.photoPath(id), StoryRules.videoPath(id))
        for (p in paths) runCatching { storage.reference.child(p).delete().await() }
        if (data == null || data["type"] == "video") runCatching { storage.reference.child(StoryRules.thumbPath(id)).delete().await() }
        return runCatching { db.collection("stories").document(id).delete().await() }.isSuccess
    }

    // ---- posting -----------------------------------------------------------------------------

    class PostRefused(message: String) : Exception(message)

    private val EMPTY_AUDIENCE = "Everyone in this list has been excluded or is no longer a chat. Pick another audience or add people to this one."

    /**
     * iOS postStory / postVideoSegment: the budget counters, the document with an empty
     * `mediaUrl`, the upload, then `mediaUrl` (and `thumbUrl`), and the public mirror for
     * Everyone. A failure removes what was written.
     */
    suspend fun post(
        media: StoryMedia.Prepared,
        caption: String,
        choice: StoryAudienceChoice,
        picked: Set<String>,
        hours: Int,
        convs: List<Conversation>,
        captureSwitch: Boolean = false,
        myFriends: StoryListDoc = StoryPrivacyRules.defaultMyFriends,
        hiddenFrom: Set<String> = emptySet(),
        lists: List<StoryListDoc> = emptyList(),
        /** The outbox's id, so a resumed post writes the story it promised (StoryOutbox). */
        fixedId: String? = null,
        /** A group story (iOS StoryAudienceTag.group): the group is the audience and [choice] is unused. */
        group: GroupStoryPost? = null,
    ): String {
        if (me.isEmpty()) throw PostRefused("Not signed in.")
        // A custom post names the people of its ticked lists (iOS postToLists: the union).
        val custom = group == null && choice == StoryAudienceChoice.CUSTOM
        val everyone = group == null && choice == StoryAudienceChoice.EVERYONE
        val people = if (custom) lists.flatMap { it.members }.toSet() else picked
        if (custom && lists.isEmpty()) throw PostRefused(EMPTY_AUDIENCE)
        val recipients: Set<String>
        val mode: String
        val replies: Boolean
        val capture: Boolean
        if (group != null) {
            // iOS resolveAudience for a group tag: the members as the server has them, mode "group".
            // A channel's story keeps replies on underneath (iOS postToGroup: its followers get no
            // reply field anyway, and the rules refuse a heart on a story with replies off).
            recipients = GroupStoryRights.audience(me, group.target.id)
            mode = "group"
            replies = group.allowsReplies || group.target.isChannel
            capture = captureSwitch
        } else {
            val glow = glowAudience(choice, convs)
            val (reached, how) = StoryPrivacyRules.resolve(StoryRules.contacts(me, convs), choice, people, myFriends, hiddenFrom, glow.first, glow.second)
            recipients = reached
            mode = if (everyone) "everyone" else how
            replies = if (custom) StoryPrivacyRules.listsAllowReplies(lists)
                else StoryPrivacyRules.allowsReplies(choice, myFriends, StoryPrivacyStore.glowers.value)
            capture = StoryPrivacyRules.captureProtected(choice, captureSwitch)
            // iOS "No one will see this": a list that resolves to nobody while I do have chats.
            if ((!choice.needsPeople || custom) && recipients.isEmpty() && StoryRules.contacts(me, convs).isNotEmpty())
                throw PostRefused(EMPTY_AUDIENCE)
            if (choice.needsPeople && recipients.isEmpty()) throw PostRefused("Pick at least one person.")
        }
        countAgainstBudget()
        val id = fixedId ?: StoryRules.newStoryId()
        val doc = db.collection("stories").document(id)
        val expires = Timestamp(Date(System.currentTimeMillis() + hours * 3_600_000L))
        val isVideo = media.video != null
        val payload = StoryRules.payload(
            me, isVideo, id, caption.trim(), if (group != null) StoryAudienceChoice.MY_CHATS else choice, mode, recipients, expires,
            FieldValue.serverTimestamp(), media.blurThumb, media.video?.info, allowsReplies = replies, captureProtected = capture,
            stickers = media.stickers,
        ).let { p -> media.repostOf?.let { p + ("repostOf" to it.asMap()) } ?: p }
            // A group story's fields laid over the ordinary ones (iOS payload.merge(groupStoryFields)).
            .let { p -> if (group != null) p + GroupStoryRights.fields(group.target) else p }
        val mainRef = storage.reference.child(if (isVideo) StoryRules.videoPath(id) else StoryRules.photoPath(id))
        val thumbRef = storage.reference.child(StoryRules.thumbPath(id))
        try {
            doc.set(payload).await()
            val urls = coroutineScope {
                val main = async {
                    if (isVideo) mainRef.putFile(media.video!!.file, StorageMetadata.Builder().setContentType("video/mp4").build()).await()
                    else mainRef.putBytes(media.jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
                    mainRef.downloadUrl.await().toString()
                }
                val thumb = async {
                    if (!isVideo) return@async ""
                    thumbRef.putBytes(media.jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
                    thumbRef.downloadUrl.await().toString()
                }
                main.await() to thumb.await()
            }
            doc.update(if (isVideo) mapOf("mediaUrl" to urls.first, "thumbUrl" to urls.second) else mapOf("mediaUrl" to urls.first)).await()
            if (everyone) {
                runCatching {
                    db.collection("users").document(me).collection("publicStories").document(id).set(mapOf(
                        "authorUid" to me, "createdAt" to FieldValue.serverTimestamp(), "expiresAt" to expires,
                        "mediaUrl" to urls.first, "thumbUrl" to urls.second, "blurThumb" to media.blurThumb,
                        "stickers" to media.stickers.map { StoryTaps.asMap(it) }, "type" to if (isVideo) "video" else "image",
                        *(media.repostOf?.let { arrayOf("repostOf" to it.asMap()) } ?: emptyArray()),
                        "caption" to caption.trim(), "duration" to (media.video?.info?.durationSec ?: 0.0),
                        "allowsReplies" to replies, "captureProtected" to capture,
                    )).await()
                }
            }
            // @names in the caption: a card in each mentioned person's chat with me (iOS StoryMentions).
            StoryMentions.send(id, caption.trim(), if (isVideo) urls.second.ifEmpty { urls.first } else urls.first, recipients, me)
            if (custom) StoryPrivacyStore.linkStory(id, StoryPrivacyRules.KIND_CUSTOM, lists.map { it.id }, lists.joinToString(", ") { it.name })
            return id
        } catch (e: Exception) {
            runCatching { doc.delete().await() }
            throw e
        }
    }

    /**
     * A Glowers story's people and who is blocked either way (iOS glowersRecipients + resolveAudience).
     * An empty list is refused rather than read as "everybody": no Glows yet, or not loaded yet.
     */
    private fun glowAudience(choice: StoryAudienceChoice, convs: List<Conversation>): Pair<Set<String>, Set<String>> {
        if (choice != StoryAudienceChoice.GLOWERS) return emptySet<String>() to emptySet()
        val g = Glow.state.value
        val people = StoryPrivacyRules.glowersRecipients(StoryPrivacyStore.glowers.value, g.relationship)
        if (people.isEmpty()) throw PostRefused(if (g.known) StoryPrivacyRules.GLOW_EMPTY else StoryPrivacyRules.GLOW_UNKNOWN)
        val blocked = convs.asSequence().filter { !it.isGroup }
            .filter { c -> val them = c.otherUid(me); them.isNotEmpty() && (c.isBlockedByMe(me) || c.isBlockedByMe(them)) }
            .map { it.otherUid(me) }.toSet() + BlockList.snapshot.entries.keys
        return people to blocked
    }

    /**
     * Edit Viewers (iOS updateStoryAudience): the same story, id, time, media and caption; only who
     * can see it changes. Everyone, My Chats or custom lists, resolved the way a post is. Narrowing
     * takes the public copy down first; widening to Everyone writes it.
     */
    suspend fun updateAudience(
        story: Story,
        choice: StoryAudienceChoice,
        lists: List<StoryListDoc>,
        convs: List<Conversation>,
        myFriends: StoryListDoc,
        hiddenFrom: Set<String>,
    ) {
        if (me.isEmpty() || story.authorUid != me) throw PostRefused("This isn't your story.")
        if (story.groupId != null) throw PostRefused("A group story's viewers are its group.")
        if (story.oneTime) throw PostRefused("A one-time story's viewers can't be changed.")
        val custom = choice == StoryAudienceChoice.CUSTOM
        if (custom && lists.isEmpty()) throw PostRefused(EMPTY_AUDIENCE)
        val contacts = StoryRules.contacts(me, convs)
        val people = if (custom) lists.flatMap { it.members }.toSet() else emptySet()
        val glow = glowAudience(choice, convs)
        val (recipients, how) = StoryPrivacyRules.resolve(contacts, choice, people, myFriends, hiddenFrom, glow.first, glow.second)
        val everyone = choice == StoryAudienceChoice.EVERYONE
        if (recipients.isEmpty() && (contacts.isNotEmpty() || choice == StoryAudienceChoice.GLOWERS)) throw PostRefused(EMPTY_AUDIENCE)
        val replies = if (custom) StoryPrivacyRules.listsAllowReplies(lists)
            else StoryPrivacyRules.allowsReplies(choice, myFriends, StoryPrivacyStore.glowers.value)
        val mirror = db.collection("users").document(me).collection("publicStories").document(story.id)
        if (!everyone) mirror.delete().await()
        db.collection("stories").document(story.id).update(mapOf(
            "audience" to mapOf("mode" to if (everyone) "everyone" else how, "listId" to "my-story"),
            "audienceLabel" to choice.tagLabel,
            "public" to everyone,
            "allowsReplies" to replies,
            "recipientUids" to recipients.map { StoryAudienceToken.token(it) },
        )).await()
        StoryPrivacyStore.linkStory(story.id, choice.tagLabel, if (custom) lists.map { it.id } else emptyList(),
            if (custom) lists.joinToString(", ") { it.name } else "")
        if (everyone) runCatching {
            mirror.set(mapOf(
                "authorUid" to me, "createdAt" to Timestamp(Date(story.createdAtMs)), "expiresAt" to Timestamp(Date(story.expiresAtMs)),
                "mediaUrl" to story.mediaUrl, "thumbUrl" to story.thumbUrl, "blurThumb" to story.blurThumb,
                "stickers" to story.taps.map { StoryTaps.asMap(it) }, "type" to if (story.isVideo) "video" else "image",
                "caption" to story.caption, "duration" to story.durationSec,
                "allowsReplies" to replies, "captureProtected" to story.captureProtected,
            )).await()
        }
    }

    private suspend fun countAgainstBudget() {
        bump("stories", 3_600_000L)
        bump("storiesDaily", 86_400_000L)
    }

    /** iOS bumpBudget: +1 inside the window, else a fresh window of one. Best effort. */
    private suspend fun bump(name: String, windowMs: Long) {
        val ref = db.collection("users").document(me).collection("limits").document(name)
        runCatching {
            val opened = ref.get().await().getTimestamp("windowStart")?.toDate()?.time
            if (opened != null && System.currentTimeMillis() - opened <= windowMs) ref.update("count", FieldValue.increment(1)).await()
            else ref.set(mapOf("windowStart" to FieldValue.serverTimestamp(), "count" to 1)).await()
        }
    }

    companion object {
        /** iOS StoriesService.who: my 1:1 chat's name and photo, else a group's member entry, else "Someone". */
        fun who(uid: String, convs: List<Conversation>, me: String): Pair<String, String?> {
            convs.firstOrNull { !it.isGroup && it.otherUid(me) == uid }?.let { return it.name(me) to it.photoUrl(me) }
            convs.firstOrNull { it.isGroup && it.names[uid] != null }?.let { g ->
                return (g.names[uid] ?: "Someone") to g.photos[uid]?.ifEmpty { null }
            }
            return "Someone" to null
        }
    }
}
