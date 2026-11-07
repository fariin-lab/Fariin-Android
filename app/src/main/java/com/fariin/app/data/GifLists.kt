package com.fariin.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The GIF panel's two local lists (iOS GifRecents / GifFavorites): public GIPHY links and their
 * sizes, kept on this phone per account ("<base>.<uid>"), so a second account signed in here starts
 * with its own lists. Recent is newest first, at most 40; Favorites oldest first (a shelf, not
 * another recents row), at most 60.
 */
object GifLists {
    private const val PREFS = "fariin_stickers"

    /** Bumped on every change, so a panel already open redraws after the chat menu edits a list. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    internal class Store(private val base: String) {
        private fun key(uid: String) = if (uid.isEmpty()) base else "$base.$uid"

        fun read(ctx: Context, uid: String): List<StickerGifRules.Gif> {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(uid), null) ?: return emptyList()
            return fromJson(raw)
        }

        fun write(ctx: Context, uid: String, list: List<StickerGifRules.Gif>) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(uid), toJson(list)).apply()
            _changes.value++
        }
    }

    /** iOS store shape: [{u, w, h}]. Pure, for the tests. */
    fun toJson(list: List<StickerGifRules.Gif>): String = JSONArray().apply {
        for (g in list) put(JSONObject().put("u", g.url).put("w", g.width).put("h", g.height))
    }.toString()

    fun fromJson(json: String): List<StickerGifRules.Gif> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("u").ifEmpty { return@mapNotNull null }
            StickerGifRules.Gif(u, u, o.optDouble("w", 1.0), o.optDouble("h", 1.0))
        }
    }.getOrDefault(emptyList())

    /** Newest first, no twice, at most [cap]. Pure. */
    fun noted(list: List<StickerGifRules.Gif>, g: StickerGifRules.Gif, cap: Int): List<StickerGifRules.Gif> =
        (listOf(g) + list.filter { it.url != g.url }).take(cap)

    /** Appended at the end (oldest first), no twice, at most [cap]. Pure. */
    fun added(list: List<StickerGifRules.Gif>, g: StickerGifRules.Gif, cap: Int): List<StickerGifRules.Gif> =
        if (list.any { it.url == g.url }) list else (list + g).take(cap)
}

/** The GIFs this account sends, newest first (iOS GifRecents). */
object GifRecents {
    const val CAP = 40
    private val store = GifLists.Store("gifRecents.v1")

    fun all(ctx: Context, uid: String): List<StickerGifRules.Gif> = store.read(ctx, uid)

    fun note(ctx: Context, uid: String, g: StickerGifRules.Gif) =
        store.write(ctx, uid, GifLists.noted(all(ctx, uid), g, CAP))

    /** "Remove from Recent": a GIF sent once by mistake does not sit in the row for good. */
    fun forget(ctx: Context, uid: String, url: String) =
        store.write(ctx, uid, all(ctx, uid).filter { it.url != url })
}

/** GIFs kept on purpose, oldest first (iOS GifFavorites). */
object GifFavorites {
    const val CAP = 60
    private val store = GifLists.Store("gifFavorites.v1")

    fun all(ctx: Context, uid: String): List<StickerGifRules.Gif> = store.read(ctx, uid)

    fun contains(ctx: Context, uid: String, url: String): Boolean = all(ctx, uid).any { it.url == url }

    fun add(ctx: Context, uid: String, g: StickerGifRules.Gif) =
        store.write(ctx, uid, GifLists.added(all(ctx, uid), g, CAP))

    fun remove(ctx: Context, uid: String, url: String) =
        store.write(ctx, uid, all(ctx, uid).filter { it.url != url })

    /** The chat menu's row for this message (iOS menuTitle), or null when it is not a GIF. */
    fun menuTitle(ctx: Context, uid: String, m: Message): String? {
        val url = m.imageUrl
        if (!m.isGif || m.deleted || url == null) return null
        return if (contains(ctx, uid, url)) "Remove from Favorites" else "Add to Favorites"
    }

    /** Adds or removes, and returns the confirmation to show (iOS toggle). */
    fun toggle(ctx: Context, uid: String, m: Message): String {
        val url = m.imageUrl
        if (!m.isGif || url == null) return "Couldn't save this GIF. Try again."
        if (contains(ctx, uid, url)) { remove(ctx, uid, url); return "Removed from Favorites" }
        add(ctx, uid, StickerGifRules.Gif(url, url, m.width ?: 200.0, m.height ?: 200.0))
        return "Added to Favorites"
    }
}
