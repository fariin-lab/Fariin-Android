package com.fariin.app.data

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One sticker (iOS Sticker): a built-in one is its pack + file stem; [animated] for .tgs and WebP. */
data class StickerRef(val packId: String, val stickerId: String, val emoji: String, val animated: Boolean) {
    val key: String get() = "$packId/$stickerId"
}

data class StickerPackInfo(val id: String, val title: String, val stickers: List<StickerRef>)

/**
 * The built-in packs (iOS StickerStore): the same art as the iPhone, shipped in
 * assets/stickers/<pack>/<sticker>.webp|.tgs with the iOS packs.json, so a message carries only the
 * pack and the file and every phone draws its own copy. Recently Used is this phone's, per account.
 */
object StickerCatalog {
    private const val DIR = "stickers"
    private const val PREFS = "fariin_stickers"
    @Volatile private var packs: List<StickerPackInfo>? = null
    @Volatile private var files: Set<String> = emptySet()

    /** One `stickerPacks/{id}` record (iOS StickerStore.RemotePack): for a pack the app ships, an
     *  override (title, order, on/off, hidden stickers); for kind "server", the whole pack with a
     *  download link per sticker. Managed from the admin panel without an app update. */
    data class RemotePack(val id: String, val kind: String, val title: String, val order: Int,
                          val enabled: Boolean, val deleted: Boolean, val hidden: Boolean?, val stickers: List<Item>) {
        data class Item(val id: String, val emoji: String?, val ext: String?, val url: String?, val animated: Boolean?, val hidden: Boolean?)
        val shown: Boolean get() = enabled && hidden != true && !deleted
    }

    /** The panel's packs, as iOS rebuildPacks: the shipped ones with their overrides, then the
     *  server's, by the panel's order (ties keep the shipped order). Pure. */
    fun arrange(shipped: List<StickerPackInfo>, remote: List<RemotePack>): List<StickerPackInfo> {
        val overrides = remote.filter { it.kind == "bundled" }.associateBy { it.id }
        val entries = ArrayList<Pair<Int, StickerPackInfo>>()
        shipped.forEachIndexed { i, p ->
            val o = overrides[p.id] ?: run { entries += i to p; return@forEachIndexed }
            if (!o.shown) return@forEachIndexed
            val hidden = o.stickers.filter { it.hidden == true }.map { it.id }.toSet()
            val kept = p.stickers.filter { it.stickerId !in hidden }
            if (kept.isNotEmpty()) entries += o.order to StickerPackInfo(p.id, o.title.ifEmpty { p.title }, kept)
        }
        for (r in remote) if (r.kind == "server" && r.shown) {
            val items = r.stickers.filter { it.hidden != true && !it.url.isNullOrEmpty() }
                .map { StickerRef(r.id, it.id, it.emoji ?: "", if (it.ext == "tgs") true else it.animated ?: false) }
            if (items.isNotEmpty()) entries += r.order to StickerPackInfo(r.id, r.title.ifEmpty { r.id }, items)
        }
        return entries.withIndex().sortedWith(compareBy({ it.value.first }, { it.index })).map { it.value.second }
    }

    /** Every server sticker's download link, hidden and removed packs too: sent messages need them. */
    @Volatile private var links: Map<String, String> = emptyMap()
    fun link(key: String): String? = links[key]
    private val remoteFlow = kotlinx.coroutines.flow.MutableStateFlow<List<RemotePack>>(emptyList())
    @Volatile private var watching = false

    /** The panel's packs, live: listens to `stickerPacks` once per process (iOS watchCatalog). */
    fun live(context: Context): kotlinx.coroutines.flow.Flow<List<StickerPackInfo>> {
        val shipped = packs(context)
        if (!watching) {
            watching = true
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("stickerPacks").addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener   // refused or offline: keep the last catalog
                val list = snap.documents.map { d ->
                    val x = d.data.orEmpty()
                    val items = (x["stickers"] as? List<*>).orEmpty().mapNotNull { st ->
                        val m = st as? Map<*, *> ?: return@mapNotNull null
                        val id = (m["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        RemotePack.Item(id, m["emoji"] as? String, m["ext"] as? String, m["url"] as? String, m["animated"] as? Boolean, m["hidden"] as? Boolean)
                    }
                    RemotePack(d.id, x["kind"] as? String ?: "", x["title"] as? String ?: "", (x["order"] as? Number)?.toInt() ?: 0,
                        x["enabled"] as? Boolean ?: true, x["deleted"] as? Boolean ?: false, x["hidden"] as? Boolean, items)
                }
                links = list.filter { it.kind == "server" }.flatMap { r -> r.stickers.mapNotNull { it.url?.takeIf { u -> u.isNotEmpty() }?.let { u -> "${r.id}/${it.id}" to u } } }.toMap()
                remoteFlow.value = list
            }
        }
        return kotlinx.coroutines.flow.combine(kotlinx.coroutines.flow.flowOf(shipped), remoteFlow) { s, r -> arrange(s, r) }
    }

    fun packs(context: Context): List<StickerPackInfo> {
        packs?.let { return it }
        val am = context.assets
        val have = HashSet<String>()
        val list = try {
            val json = org.json.JSONObject(am.open("$DIR/packs.json").bufferedReader().use { it.readText() })
            val arr = json.getJSONArray("packs")
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.getJSONObject(i)
                val id = p.getString("id")
                val inPack = am.list("$DIR/$id")?.toSet() ?: emptySet()
                inPack.forEach { have += "$id/$it" }
                val st = p.getJSONArray("stickers")
                // A pack with no art in this build is left out (iOS loadManifest); a .tgs always moves.
                val items = (0 until st.length()).mapNotNull { j ->
                    val s = st.getJSONObject(j)
                    val sid = s.getString("id")
                    val tgs = "$sid.tgs" in inPack
                    if (!tgs && "$sid.webp" !in inPack) null
                    else StickerRef(id, sid, s.optString("emoji"), tgs || s.optBoolean("animated", true))
                }
                if (items.isEmpty()) null else StickerPackInfo(id, p.getString("title"), items)
            }
        } catch (_: Exception) { emptyList() }
        files = have
        packs = list
        return list
    }

    /** The art in this build: "stickers/<pack>/<id>.tgs" or ".webp", or null (then the public copy). */
    fun assetPath(context: Context, packId: String, stickerId: String): String? {
        packs(context)
        return when {
            "$packId/$stickerId.tgs" in files -> "$DIR/$packId/$stickerId.tgs"
            "$packId/$stickerId.webp" in files -> "$DIR/$packId/$stickerId.webp"
            else -> null
        }
    }

    /** A .tgs's shipped still for the panel tile (iOS TGSSticker: `<id>_still.png`). */
    fun stillPath(context: Context, packId: String, stickerId: String): String? {
        packs(context)
        return "$DIR/$packId/${stickerId}_still.png".takeIf { "$packId/${stickerId}_still.png" in files }
    }

    fun find(context: Context, key: String): StickerRef? =
        packs(context).firstNotNullOfOrNull { p -> p.stickers.firstOrNull { it.key == key } }

    private fun recentsKey(uid: String) = "stickers.recents.v1.$uid"

    fun recents(context: Context, uid: String): List<StickerRef> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(recentsKey(uid), "") ?: ""
        return raw.split('\n').filter { it.isNotEmpty() }.mapNotNull { find(context, it) }
    }

    fun noteUsed(context: Context, uid: String, s: StickerRef) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = (p.getString(recentsKey(uid), "") ?: "").split('\n').filter { it.isNotEmpty() }
        p.edit().putString(recentsKey(uid), StickerGifRules.noteUsed(old, s.key).joinToString("\n")).apply()
    }

    /** The panel's last tab for every chat and across launches (iOS stickerPanel.lastTab); Stickers first. */
    fun lastTabGifs(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("panel.gifs", false)

    fun setLastTabGifs(context: Context, gifs: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("panel.gifs", gifs).apply()
    }
}

/**
 * iOS GiphyService: GIPHY's REST API straight from the phone. The key comes once from the
 * `giphyKey` Cloud Function (kept out of the source) and is kept on the phone across launches.
 */
object Giphy {
    private const val PREFS = "fariin_stickers"
    private const val KEY = "giphy.key.v1"
    @Volatile private var memo: String? = null

    private suspend fun key(context: Context): String? {
        memo?.let { return it }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY, null)?.takeIf { it.isNotEmpty() }?.let { memo = it; return it }
        return try {
            val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("giphyKey").call().await()
            val k = ((r.getData() as? Map<*, *>)?.get("key") as? String)?.takeIf { it.isNotEmpty() }
            if (k != null) { memo = k; p.edit().putString(KEY, k).apply() }
            k
        } catch (_: Exception) { null }
    }

    /** Trending with no words, else a search (iOS search): 30 GIFs, rated up to PG-13. */
    suspend fun search(context: Context, q: String): List<StickerGifRules.Gif> = withContext(Dispatchers.IO) {
        val k = key(context) ?: return@withContext emptyList()
        val t = q.trim()
        val path = if (t.isEmpty()) "trending" else "search"
        val qs = buildString {
            append("api_key=").append(URLEncoder.encode(k, "UTF-8")).append("&limit=30&rating=pg-13")
            if (t.isNotEmpty()) append("&q=").append(URLEncoder.encode(t, "UTF-8"))
        }
        try {
            val c = URL("https://api.giphy.com/v1/gifs/$path?$qs").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try { StickerGifRules.parseGiphy(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        } catch (_: Exception) { emptyList() }
    }
}
