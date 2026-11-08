package com.fariin.app.data

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A story being posted when the app died is still being posted (iOS StoryOutbox). Before the first
 * byte moves, what the post IS goes to disk: its media in the app's own files, the caption, the
 * audience and the story id it will use. Landed or refused, the ticket goes. One still here at the
 * next start of the same account (and under a day old) is posted again through the same door: its
 * half-written document is removed first, and one that did land in full is only forgotten.
 * Each ticket names its owner, so another account signing in on this phone never posts it.
 */
object StoryOutbox {
    private const val MAX_AGE_MS = 24L * 3600 * 1000
    /** Posts running in this process: never picked up by [resume] while their own upload goes on. */
    private val active = java.util.Collections.synchronizedSet(HashSet<String>())

    class Ticket(
        val id: String, val owner: String, val dir: File, val caption: String, val choice: StoryAudienceChoice,
        val picked: Set<String>, val hours: Int, val capture: Boolean, val listIds: List<String>, val firstTried: Long,
    )

    private fun root(c: Context) = File(c.filesDir, "story-outbox").also { it.mkdirs() }

    /** Written before the upload starts; returns the story id the post must use. */
    fun write(c: Context, owner: String, media: StoryMedia.Prepared, caption: String, choice: StoryAudienceChoice,
              picked: Set<String>, hours: Int, capture: Boolean, lists: List<StoryListDoc>,
              group: GroupStoryPost? = null): String? = runCatching {
        val id = StoryRules.newStoryId()
        val dir = File(root(c), id).also { it.mkdirs() }
        File(dir, "photo.jpg").writeBytes(media.jpeg)
        media.video?.let { v -> c.contentResolver.openInputStream(v.file)?.use { i -> File(dir, "clip.mp4").outputStream().use { i.copyTo(it) } } }
        val o = JSONObject().put("owner", owner).put("caption", caption).put("choice", choice.name)
            .put("picked", JSONArray(picked.toList())).put("hours", hours).put("capture", capture)
            .put("lists", JSONArray(lists.map { it.id })).put("first", System.currentTimeMillis())
            .put("blur", media.blurThumb).put("w", media.width).put("h", media.height)
            .put("stickers", JSONArray(media.stickers.map { JSONObject(StoryTaps.asMap(it)) }))
        media.repostOf?.let { o.put("repost", JSONObject(it.asMap())) }
        // A group story keeps its group through a retry (iOS: the tag rides the upload queue).
        group?.let { g ->
            o.put("group", JSONObject().put("id", g.target.id).put("name", g.target.name).put("photo", g.target.photoUrl ?: "")
                .put("count", g.target.memberCount).put("channel", g.target.isChannel).put("replies", g.allowsReplies))
        }
        media.video?.info?.let { o.put("video", JSONObject().put("d", it.durationSec).put("w", it.width).put("h", it.height).put("b", it.bytes)) }
        File(dir, "ticket.json").writeText(o.toString())
        active += id
        id
    }.getOrNull()

    fun remove(c: Context, id: String) { File(root(c), id).deleteRecursively(); active -= id }

    /** A failed post of this run stays on disk for the next start, and stops counting as running. */
    fun release(id: String) { active -= id }

    /**
     * A failed post of [me]'s still on disk and not running (iOS hasWaiting): the failure alert
     * offers Try Again only then.
     */
    fun hasWaiting(c: Context, me: String): Boolean = me.isNotEmpty() && root(c).listFiles().orEmpty().any { dir ->
        dir.isDirectory && dir.name !in active && runCatching {
            val o = JSONObject(File(dir, "ticket.json").readText())
            o.optString("owner") == me && System.currentTimeMillis() - o.optLong("first") <= MAX_AGE_MS
        }.getOrDefault(false)
    }

    /** One pass at a time: the start, Try Again and a reconnect can all ask at once. */
    private val pass = kotlinx.coroutines.sync.Mutex()

    /**
     * Posts left over from an earlier run of [me], or failed in this one; others' and stale ones are
     * thrown away. Also run by Try Again and when the connection comes back (iOS retry).
     */
    suspend fun resume(c: Context, me: String, convs: List<Conversation>) = pass.withLock { resumeOnce(c, me, convs) }

    private suspend fun resumeOnce(c: Context, me: String, convs: List<Conversation>) {
        val dirs = root(c).listFiles().orEmpty().filter { it.isDirectory && it.name !in active }
        for (dir in dirs) {
            val o = runCatching { JSONObject(File(dir, "ticket.json").readText()) }.getOrNull()
            // Unreadable, another account's, or too old to still be news: never posted under anyone.
            if (o == null || o.optString("owner") != me || System.currentTimeMillis() - o.optLong("first") > MAX_AGE_MS) {
                dir.deleteRecursively(); continue
            }
            val id = dir.name
            val doc = FirebaseFirestore.getInstance().collection("stories").document(id)
            // Unreadable (offline): wait for a start that can tell whether it landed, never guess.
            val existing = runCatching { doc.get(com.google.firebase.firestore.Source.SERVER).await() }.getOrNull() ?: continue
            if (existing.getString("mediaUrl")?.isNotEmpty() == true) { dir.deleteRecursively(); continue }   // it did land
            if (existing.exists()) runCatching { doc.delete().await() }
            val media = prepared(dir, o) ?: run { dir.deleteRecursively(); null } ?: continue
            val choice = runCatching { StoryAudienceChoice.valueOf(o.getString("choice")) }.getOrNull() ?: run { dir.deleteRecursively(); null } ?: continue
            val picked = o.optJSONArray("picked")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val listIds = o.optJSONArray("lists")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val lists = StoryPrivacyStore.custom.value.filter { it.id in listIds }
            val group = o.optJSONObject("group")?.takeIf { it.optString("id").isNotEmpty() }?.let { g ->
                GroupStoryPost(GroupStoryTarget(g.optString("id"), g.optString("name"), g.optString("photo").ifEmpty { null },
                    g.optInt("count"), g.optBoolean("channel")), g.optBoolean("replies", true))
            }
            active += id   // running: not "waiting" to the alert while it goes
            try {
                StoryService(me).post(media, o.optString("caption"), choice, picked, o.optInt("hours", 24), convs, o.optBoolean("capture"),
                    StoryPrivacyStore.myFriends.value, StoryPrivacyStore.hidden.value, lists, fixedId = id, group = group)
                dir.deleteRecursively()
            } catch (e: StoryService.PostRefused) {
                dir.deleteRecursively()
            } catch (e: Exception) {
                android.util.Log.w("StoryOutbox", "resume failed, kept for later", e)
            } finally {
                active -= id
            }
        }
    }

    private fun prepared(dir: File, o: JSONObject): StoryMedia.Prepared? {
        val jpeg = runCatching { File(dir, "photo.jpg").readBytes() }.getOrNull() ?: return null
        val clip = File(dir, "clip.mp4")
        val video = o.optJSONObject("video")?.let { v ->
            if (!clip.exists()) return null
            StoryMedia.Video(Uri.fromFile(clip), StoryRules.VideoInfo(v.optDouble("d"), v.optInt("w"), v.optInt("h"), v.optLong("b")))
        }
        val stickers = o.optJSONArray("stickers")?.let { a -> StoryTaps.list((0 until a.length()).map { toMap(a.getJSONObject(it)) }) }.orEmpty()
        val repost = o.optJSONObject("repost")?.let { StoryRepost.from(toMap(it)) }
        return StoryMedia.Prepared(jpeg, o.optString("blur"), video, o.optInt("w"), o.optInt("h"), stickers, repost)
    }

    private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
        when (val v = o.get(k)) {
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { i -> v.get(i).let { if (it is JSONObject) toMap(it) else it } }
            JSONObject.NULL -> null
            else -> v
        }
    }
}
