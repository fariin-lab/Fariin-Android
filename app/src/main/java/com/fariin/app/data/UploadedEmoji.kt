package com.fariin.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Custom emoji uploaded in the admin panel (iOS UploadedEmoji, owner 2026-11-06).
 * `config/features.customEmoji` = `[{ id, url, name, at }]`; the reaction key is "cu_" + id, a
 * 10-character id of a-z and 0-9, used exactly like the built-in "ce_1".."ce_181".
 * Each file downloads once into cache/custom_emoji/<id>.tgs, checked to be https, gzip and at most
 * 64 KB (the server's own cap). An id no longer on the list is not fetched any more.
 * Fed by the [AppFeatures] listener; no polling.
 */
object UploadedEmoji {
    data class Item(val id: String, val url: String)

    const val MAX_BYTES = 64 * 1024
    private const val FILE = "uploaded_emoji"
    private const val KEY = "list.v1"

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** The panel's list, in its order (newest last). */
    val items: StateFlow<List<Item>> = _items
    private val _landed = MutableStateFlow(0)
    /** Goes up each time a file lands, so a glyph that was waiting draws it. */
    val landed: StateFlow<Int> = _landed
    /** True once the panel's list is known (saved from an earlier launch, or from the listener):
     *  before that nothing counts as retired, so a fresh install does not hide every uploaded one. */
    private val _known = MutableStateFlow(false)
    val known: StateFlow<Boolean> = _known

    private var prefs: android.content.SharedPreferences? = null
    private var dir: File? = null
    private val inFlight = HashSet<String>()
    /** Ids whose file was refused this launch (too big, not gzip): not asked for again until the next open. */
    private val refused = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isKey(s: String?): Boolean =
        s != null && s.length == 13 && s.startsWith("cu_") && s.drop(3).all { it in 'a'..'z' || it in '0'..'9' }

    /**
     * A "cu_" key whose id the panel no longer lists (iOS isRetired): not drawn, not counted, not
     * offered. A key still on the list, a "ce_" key or a plain emoji is never retired.
     */
    fun isRetired(key: String?): Boolean = _known.value && retired(key, _items.value)

    fun retired(key: String?, list: List<Item>): Boolean =
        isKey(key) && list.none { it.id == key!!.drop(3) }

    /** The panel's list, keeping only well-formed https entries, in its order, no repeats. */
    fun parse(raw: Any?): List<Item> {
        val list = raw as? List<*> ?: return emptyList()
        val seen = HashSet<String>()
        return list.mapNotNull { x ->
            val d = x as? Map<*, *> ?: return@mapNotNull null
            val id = d["id"] as? String ?: return@mapNotNull null
            val url = d["url"] as? String ?: return@mapNotNull null
            if (!isKey("cu_$id") || !url.startsWith("https://") || !seen.add(id)) null else Item(id, url)
        }
    }

    /** True for a gzip file of the allowed size. */
    fun looksValid(bytes: ByteArray): Boolean =
        bytes.size in 21..MAX_BYTES && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    fun init(c: Context) {
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        dir = File(c.applicationContext.cacheDir, "custom_emoji")
        _known.value = p.contains(KEY)
        _items.value = runCatching {
            val a = JSONArray(p.getString(KEY, "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { mapOf("id" to it.optString("id"), "url" to it.optString("url")) }
        }.getOrNull()?.let { parse(it) } ?: emptyList()
    }

    /** From the config/features listener. */
    fun update(raw: Any?) {
        val list = parse(raw)
        val first = !_known.value
        _known.value = true
        if (list == _items.value) { if (first) { save(list); prune(list) }; return }
        _items.value = list
        save(list)
        prune(list)
    }

    private fun save(list: List<Item>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("url", it.url)) }
        prefs?.edit()?.putString(KEY, a.toString())?.apply()
    }

    /** Cached files of ids taken off the list are deleted (iOS removes them the same way). */
    private fun prune(list: List<Item>) {
        val d = dir ?: return
        val keep = list.map { it.id }.toHashSet()
        scope.launch {
            d.listFiles()?.forEach { f ->
                val id = f.name.substringBefore('.')
                if (id !in keep && synchronized(inFlight) { id !in inFlight }) f.delete()
            }
        }
    }

    /** The Lottie JSON for a "cu_" key, or null while it is not here yet (a download is started). */
    fun json(key: String): String? {
        if (!isKey(key)) return null
        val id = key.drop(3)
        val f = dir?.let { File(it, "$id.tgs") } ?: return null
        if (f.exists()) {
            val text = runCatching { GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } }.getOrNull()
            if (text != null) return text
            f.delete()
        }
        fetch(id, f)
        return null
    }

    private fun fetch(id: String, f: File) {
        val link = synchronized(inFlight) {
            val u = _items.value.firstOrNull { it.id == id }?.url ?: return
            if (id in refused || !inFlight.add(id)) return
            u
        }
        scope.launch {
            try {
                val c = URL(link).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                val bytes = try {
                    if (c.responseCode != 200 || c.contentLengthLong > MAX_BYTES) null
                    else c.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) return@use null
                        }
                        out.toByteArray()
                    }
                } finally { c.disconnect() }
                if (bytes == null || !looksValid(bytes)) { synchronized(inFlight) { refused.add(id) }; return@launch }
                // Must really unpack to JSON before it is kept.
                val ok = runCatching { GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }.trimStart().startsWith("{") }
                    .getOrDefault(false)
                if (!ok) { synchronized(inFlight) { refused.add(id) }; return@launch }
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "$id.part")
                tmp.writeBytes(bytes)
                if (tmp.renameTo(f)) _landed.value++ else tmp.delete()
            } catch (_: Exception) {
            } finally {
                synchronized(inFlight) { inFlight.remove(id) }
            }
        }
    }
}
