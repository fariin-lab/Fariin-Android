package com.fariin.app.data

import android.content.Context
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Favorites (iOS StickerStore.favorites + StickerBackup): stickers I made (a PNG, pack "mine") and
 * built-in stickers I saved, newest first. They belong to the ACCOUNT, not the phone: every change
 * is mirrored to Storage `userStickers/{uid}/` (one `<id>.png` per sticker I made and `index.json`,
 * the list, in the iOS format), and signing in restores what this phone lacks. Sign-out wipes the
 * phone's copy (the next person must not see them); the account keeps them.
 */
object StickerFavorites {
    const val MINE = "mine"
    private const val MAX = 200

    private val _list = MutableStateFlow<List<StickerRef>>(emptyList())
    val list: StateFlow<List<StickerRef>> = _list
    private var uid: String? = null
    private var root: File? = null

    /** iOS Sticker's JSON: {source, packId, stickerId, emoji, animated}. Pure, for the tests. */
    fun toJson(list: List<StickerRef>): String = JSONArray().apply {
        for (s in list) put(JSONObject()
            .put("source", if (s.packId == MINE) "mine" else "bundled")
            .put("packId", s.packId).put("stickerId", s.stickerId)
            .put("emoji", s.emoji).put("animated", s.animated))
    }.toString()

    fun fromJson(json: String): List<StickerRef> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val pack = o.optString("packId").ifEmpty { return@mapNotNull null }
            val id = o.optString("stickerId").ifEmpty { return@mapNotNull null }
            StickerRef(pack, id, o.optString("emoji"), o.optBoolean("animated"))
        }
    }.getOrDefault(emptyList())

    /** Newest first, no twice, at most [MAX]: the list after adding [s]. Pure. */
    fun withAdded(list: List<StickerRef>, s: StickerRef): List<StickerRef> =
        (listOf(s) + list.filter { it.key != s.key }).take(MAX)

    private fun dir(): File? = root?.let { File(it, "mine").apply { mkdirs() } }
    private fun indexFile(): File? = root?.let { File(it, "index.json") }

    /** The PNG of a sticker I made, on this phone (null when not here yet). */
    fun png(stickerId: String): File? = dir()?.let { File(it, "$stickerId.png") }?.takeIf { it.exists() }

    fun isFavorite(s: StickerRef) = _list.value.any { it.key == s.key }

    /** Load this account's list from the phone, then fill in from the account's backup. */
    suspend fun start(context: Context, me: String) = withContext(Dispatchers.IO) {
        if (uid == me) return@withContext
        uid = me
        root = File(context.filesDir, "stickers_fav/$me").apply { mkdirs() }
        _list.value = indexFile()?.takeIf { it.exists() }?.readText()?.let(::fromJson).orEmpty()
        restore(me)
    }

    /** Sign-out (iOS SessionWipe): the phone's copy goes; the account's backup stays. */
    fun wipe(context: Context) {
        runCatching { File(context.filesDir, "stickers_fav").deleteRecursively() }
        uid = null; root = null; _list.value = emptyList()
    }

    /** A sticker I made (iOS addMine): the PNG saved here and in the account, first in the list. */
    suspend fun addMine(png: ByteArray, emoji: String, keepingId: String? = null): StickerRef = withContext(Dispatchers.IO) {
        val id = keepingId ?: UUID.randomUUID().toString().uppercase()
        dir()?.let { File(it, "$id.png").writeBytes(png) }
        val s = StickerRef(MINE, id, emoji.ifEmpty { "🙂" }, false)
        save(withAdded(_list.value, s))
        uid?.let { u -> runCatching { folder(u).child("$id.png").putBytes(png, meta("image/png")).await() } }
        s
    }

    /** Save a built-in sticker to Favorites, or take any sticker out (iOS toggleFavorite). */
    suspend fun toggle(s: StickerRef): Boolean = withContext(Dispatchers.IO) {
        val on = !isFavorite(s)
        save(if (on) withAdded(_list.value, s) else _list.value.filter { it.key != s.key })
        if (!on && s.packId == MINE) {
            png(s.stickerId)?.delete()
            uid?.let { u -> runCatching { folder(u).child("${s.stickerId}.png").delete().await() } }
        }
        on
    }

    private fun save(next: List<StickerRef>) {
        _list.value = next
        val json = toJson(next)
        indexFile()?.writeText(json)
        // Best effort, written whole each time: a failed write is repaired by the next change.
        uid?.let { u -> folder(u).child("index.json").putBytes(json.toByteArray(), meta("application/json")) }
    }

    private suspend fun restore(me: String) {
        val remote = runCatching {
            String(folder(me).child("index.json").getBytes(2L * 1024 * 1024).await())
        }.getOrNull()?.let(::fromJson) ?: return
        for (s in remote) if (s.packId == MINE && png(s.stickerId) == null) {
            runCatching { folder(me).child("${s.stickerId}.png").getBytes(2L * 1024 * 1024).await() }
                .getOrNull()?.let { bytes -> dir()?.let { File(it, "${s.stickerId}.png").writeBytes(bytes) } }
        }
        // The account's order, then anything only this phone had (saved while offline).
        val merged = (remote + _list.value.filter { l -> remote.none { it.key == l.key } }).take(MAX)
        if (merged != _list.value) save(merged)
    }

    private fun folder(u: String) = FirebaseStorage.getInstance().reference.child("userStickers").child(u)
    private fun meta(type: String) = StorageMetadata.Builder().setContentType(type).build()
}
