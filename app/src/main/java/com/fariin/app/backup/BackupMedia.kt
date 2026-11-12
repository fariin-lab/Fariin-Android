package com.fariin.app.backup

import android.content.Context
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.data.Message
import com.fariin.app.local.LocalDb
import com.fariin.app.local.LocalJson
import com.fariin.app.media.ChatMedia

/**
 * Photos, voice notes and videos in the chat backup (same format as iOS 7687219ef). manifest.bin
 * gains "mediaKey" (b64, one per account) and "media": [{n, id, cid, slot, i, url}].
 *   identity = "<id>|<slot>|<i or -1>|<url or empty>"   (UTF-8)
 *   n        = "md-" + hex(generichash(identity, key = mediaKey, 16))
 *   itemKey  = generichash("key|" + identity, key = mediaKey, 32)
 *   md- file = nonce(24) || secretbox(bytes, itemKey)
 * Only bytes already on this phone, never view-once or deleted, nothing over 100 MB; videos only
 * with Include Videos. md- files belong to no set: one already in the Drive is listed, not uploaded.
 */
object BackupMedia {
    private const val MAX = 100L * 1024 * 1024

    class Item(val entry: Map<String, Any?>, val name: String, val itemKey: ByteArray, val file: java.io.File)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun identity(id: String, slot: String, i: Int?, url: String?) = "$id|$slot|${i ?: -1}|${url ?: ""}"

    fun name(c: FariinCrypto, key: ByteArray, identity: String): String =
        "md-" + hex(c.genericHash(16, identity.toByteArray(Charsets.UTF_8), key)!!)

    fun itemKey(c: FariinCrypto, key: ByteArray, identity: String): ByteArray =
        c.genericHash(32, ("key|$identity").toByteArray(Charsets.UTF_8), key)!!

    /** Every item this phone can put in the backup now. */
    fun collect(ctx: Context, c: FariinCrypto, uid: String, key: ByteArray, includeVideos: Boolean): List<Item> {
        val media = ChatMedia(ctx, "", null)   // the cache is keyed by url only
        val out = ArrayList<Item>()
        fun add(m: Message, cid: String, slot: String, i: Int?, entryUrl: String?, cacheUrl: String?) {
            val f = cacheUrl?.let { media.cached(it) } ?: return
            if (f.length() > MAX) return
            val id = identity(m.id, slot, i, entryUrl)
            out.add(Item(mapOf("cid" to cid, "i" to i, "id" to m.id, "n" to name(c, key, id), "slot" to slot, "url" to entryUrl),
                name(c, key, id), itemKey(c, key, id), f))
        }
        LocalDb.eachRawMessage(uid) { r ->
            val cid = r["cid"] as? String ?: return@eachRawMessage
            val body = r["body"] as? String ?: return@eachRawMessage
            if (body == "{}" || body.isEmpty()) return@eachRawMessage
            val m = runCatching { LocalJson.decodeMessage(org.json.JSONObject(body)) }.getOrNull() ?: return@eachRawMessage
            if (m.deleted || m.viewOnce) return@eachRawMessage
            if (m.album.isNotEmpty()) {
                m.album.forEachIndexed { i, a ->
                    add(m, cid, "image", i, a.imageUrl, a.imageUrl)
                    if (includeVideos && a.videoUrl != null) add(m, cid, "video", i, null, a.videoUrl)
                }
                return@eachRawMessage
            }
            m.imageUrl?.let { add(m, cid, "image", null, it, it) }
            m.thumbUrl?.let { add(m, cid, "image", null, it, it) }
            m.audioUrl?.let { add(m, cid, "audio", null, null, it) }
            if (includeVideos) m.videoUrl?.let { add(m, cid, "video", null, null, it) }
        }
        return out
    }

    /** Null when the item cannot be read or does not fit in memory on this phone (it is skipped). */
    fun seal(c: FariinCrypto, item: Item): ByteArray? = try {
        val (n, box) = c.sealWith(item.itemKey, item.file.readBytes()) ?: return null
        n + box
    } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }

    /**
     * Restore: each entry's file opened and put where this app looks for it (Android keeps every
     * opened file by its url: an image by the entry's url, a voice note or video by the message's
     * own url, found in the restored history).
     */
    suspend fun place(ctx: Context, c: FariinCrypto, uid: String, key: ByteArray, entries: List<Map<String, Any?>>,
                      fetch: suspend (String) -> ByteArray?) {
        val media = ChatMedia(ctx, "", null)   // the cache is keyed by url only
        for (e in entries) runCatching {
            val n = e["n"] as? String ?: return@runCatching
            val id = e["id"] as? String ?: return@runCatching
            val cid = e["cid"] as? String ?: return@runCatching
            val slot = e["slot"] as? String ?: return@runCatching
            val i = (e["i"] as? Number)?.toInt()
            val url = e["url"] as? String
            val target = url ?: run {
                val m = LocalDb.message(uid, cid, id) ?: return@runCatching
                when (slot) {
                    "audio" -> m.audioUrl
                    "video" -> if (i != null) m.album.getOrNull(i)?.videoUrl else m.videoUrl
                    else -> null
                }
            } ?: return@runCatching
            if (media.cached(target) != null) return@runCatching
            val file = fetch(n) ?: return@runCatching
            if (file.size < 40) return@runCatching
            val ik = itemKey(c, key, identity(id, slot, i, url))
            val plain = c.openWith(ik, file.copyOfRange(0, 24), file.copyOfRange(24, file.size)) ?: return@runCatching
            media.store(target, plain)
        }
    }
}
