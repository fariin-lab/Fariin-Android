package com.fariin.app.channel

import android.content.Context
import com.fariin.app.data.PublicGroups
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One public group in Explore's Groups tab (server `discoverGroups`, iOS GroupDirectory.Discovered). */
data class DiscoveredGroup(
    val cid: String, val handle: String, val title: String, val photoUrl: String?,
    val memberCount: Int, val verified: Boolean, val label: String,
)

/**
 * Explore opens on what it saw last (iOS ExploreCache, owner 2026-11-11: "this skeleton is taking
 * time"). Both tabs come from callables that can take seconds when cold, so the last answer of each
 * is a small JSON in the cache folder, per account, drawn on the first frame; the server's answer
 * replaces it in place. [warm] fetches channels a few seconds after the chat list shows (at most
 * every 10 min); groups only for an account that has opened the Groups tab before. One call at a
 * time per tab: an open during a warm waits for that same call. Wiped with the account (SessionEnd).
 */
object ExploreCache {
    private const val WARM_EVERY_MS = 10 * 60 * 1000L
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var memoryFor = ""
    @Volatile private var channels: List<DiscoveredChannel>? = null
    @Volatile private var groups: List<DiscoveredGroup>? = null
    private var channelsCall: CompletableDeferred<List<DiscoveredChannel>>? = null
    private var groupsCall: CompletableDeferred<List<DiscoveredGroup>>? = null
    @Volatile private var lastWarm = 0L
    private var dir: File? = null

    private fun file(kind: String, uid: String): File? =
        dir?.takeIf { uid.isNotEmpty() }?.let { File(it, "explore-$kind-$uid.json") }

    /** Memory belongs to [uid]; the first ask after launch (or an account switch) reads its files. */
    private fun sync(c: Context, uid: String) = synchronized(lock) {
        if (dir == null) dir = c.applicationContext.cacheDir
        if (uid == memoryFor) return@synchronized
        memoryFor = uid
        channels = null; groups = null; channelsCall = null; groupsCall = null; lastWarm = 0L
        channels = file("channels", uid)?.let { f -> runCatching { readChannels(f.readText()) }.getOrNull() }
        groups = file("groups", uid)?.let { f -> runCatching { readGroups(f.readText()) }.getOrNull() }
    }

    fun cachedChannels(c: Context, uid: String): List<DiscoveredChannel>? { sync(c, uid); return channels }
    fun cachedGroups(c: Context, uid: String): List<DiscoveredGroup>? { sync(c, uid); return groups }

    suspend fun fetchChannels(c: Context, uid: String): List<DiscoveredChannel> {
        sync(c, uid)
        val (call, mine) = synchronized(lock) {
            channelsCall?.let { it to false } ?: CompletableDeferred<List<DiscoveredChannel>>().also { channelsCall = it }.let { it to true }
        }
        if (mine) {
            try {
                val rows = ChannelService.discover()
                synchronized(lock) { if (memoryFor == uid) { channels = rows; channelsCall = null } }
                save("channels", uid, writeChannels(rows))
                call.complete(rows)
            } catch (e: Exception) {
                synchronized(lock) { if (channelsCall === call) channelsCall = null }
                call.completeExceptionally(e)
            }
        }
        return call.await()
    }

    suspend fun fetchGroups(c: Context, uid: String): List<DiscoveredGroup> {
        sync(c, uid)
        val (call, mine) = synchronized(lock) {
            groupsCall?.let { it to false } ?: CompletableDeferred<List<DiscoveredGroup>>().also { groupsCall = it }.let { it to true }
        }
        if (mine) {
            try {
                val rows = PublicGroups.discover()
                synchronized(lock) { if (memoryFor == uid) { groups = rows; groupsCall = null } }
                save("groups", uid, writeGroups(rows))
                call.complete(rows)
            } catch (e: Exception) {
                synchronized(lock) { if (groupsCall === call) groupsCall = null }
                call.completeExceptionally(e)
            }
        }
        return call.await()
    }

    /** A few seconds after the chat list shows: channels always, groups only once that tab was used. */
    fun warm(c: Context, uid: String) {
        if (uid.isEmpty()) return
        sync(c, uid)
        val now = System.currentTimeMillis()
        if (now - lastWarm < WARM_EVERY_MS) return
        lastWarm = now
        io.launch {
            kotlinx.coroutines.delay(4_000)
            runCatching { fetchChannels(c, uid) }
            if (groups != null) runCatching { fetchGroups(c, uid) }
        }
    }

    /** Every account's kept lists (sign-out). */
    fun wipe(c: Context) {
        synchronized(lock) { memoryFor = ""; channels = null; groups = null; channelsCall = null; groupsCall = null; lastWarm = 0L }
        c.applicationContext.cacheDir.listFiles { f -> f.name.startsWith("explore-") && f.name.endsWith(".json") }?.forEach { it.delete() }
    }

    private fun save(kind: String, uid: String, json: String) {
        val f = file(kind, uid) ?: return
        io.launch { runCatching { val tmp = File(f.path + ".tmp"); tmp.writeText(json); tmp.renameTo(f) } }
    }

    private fun writeChannels(rows: List<DiscoveredChannel>): String = JSONArray().apply {
        rows.forEach { r ->
            put(JSONObject().put("cid", r.cid).put("handle", r.handle).put("title", r.title).put("photoUrl", r.photoUrl ?: "")
                .put("description", r.description).put("followerCount", r.followerCount).put("admins", r.admins)
                .put("verified", r.verified).put("label", r.label).put("official", r.official))
        }
    }.toString()

    private fun readChannels(s: String): List<DiscoveredChannel> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> DiscoveredChannel.from(o.toMap()) } }
    }

    private fun writeGroups(rows: List<DiscoveredGroup>): String = JSONArray().apply {
        rows.forEach { r ->
            put(JSONObject().put("cid", r.cid).put("handle", r.handle).put("title", r.title).put("photoUrl", r.photoUrl ?: "")
                .put("memberCount", r.memberCount).put("verified", r.verified).put("label", r.label))
        }
    }.toString()

    private fun readGroups(s: String): List<DiscoveredGroup> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> PublicGroups.discovered(o.toMap()) } }
    }

    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { opt(it) }
}
