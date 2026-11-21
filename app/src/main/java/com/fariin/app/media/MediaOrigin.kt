package com.fariin.app.media

import com.fariin.app.data.Message
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ONE FILE, ONE COPY ON THIS PHONE, HOWEVER MANY MESSAGES CARRY IT (iOS MediaOrigin, 2026-11-15).
 *
 * The media cache is keyed by download url, so a file forwarded into another chat was downloaded
 * again even when this phone already held it. Every media message names its ORIGIN: a key made from
 * the storage path of the file's first upload (`origin`, written by a forward; a message without one
 * uses the key of its own path, [Message.mediaOrigin]). This index remembers, per signed-in account,
 * which cached url holds each origin; [ChatMedia] asks it before downloading, and a hit is copied into
 * a separate ALIAS file for the asking url. Nothing is fetched from a different address because of it.
 *
 * Security (same rules as iOS):
 *  - `origin` is a one-way key, never the path; in a 1:1 or an end-to-end group it is sealed like the
 *    text (the server knows every path and could match a clear key to its chat).
 *  - Bound to the file's bytes: the field is "<key>.<size>". A slot is served from another url's file
 *    only for a FORWARDED message that names a size, when the cached file has exactly that many bytes
 *    and the same pictured size (and length, for a clip). No size = always download.
 *  - A slot whose key is CLAIMED (its `origin` field) may read an alias but never names a file for
 *    others: only a message's own path key registers its file.
 *  - An alias is for showing only. Forward input reads the message's own file ([ChatMedia.file] own).
 *  - One index file per uid, read off the main thread; until it is read every lookup misses.
 */
object MediaOrigin {
    const val KEY_LENGTH = 32
    private const val MAX_ALIASES = 4000
    private const val SLOT_GENERATION = 2500

    // ---------------------------------------------------------------- the key and the field

    /** base64url(SHA-256("fariin-origin|" + path)), first 32 characters, no padding. */
    fun key(path: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("fariin-origin|$path").toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d).take(KEY_LENGTH)
    }

    /** An origin key and nothing else (32 base64url characters); junk is no origin. */
    fun valid(s: String?): String? {
        if (s == null || s.length != KEY_LENGTH) return null
        return s.takeIf { it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' } }
    }

    data class Claim(val key: String, val size: Long?)

    /** The clear field: "<key>.<size>", or the key alone when the size is not known. */
    fun payload(key: String, size: Long?): String = if (size != null && size > 0) "$key.$size" else key

    /** The clear field read back; anything malformed (or a failed open's sentinel) is no origin. */
    fun parse(s: String?): Claim? {
        if (s.isNullOrEmpty() || s.length > KEY_LENGTH + 16) return null
        val parts = s.split('.')
        if (parts.size > 2) return null
        val key = valid(parts[0]) ?: return null
        if (parts.size == 1) return Claim(key, null)
        val digits = parts[1]
        if (digits.isEmpty() || digits.length > 15 || !digits.all { it in '0'..'9' }) return null
        val n = digits.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Claim(key, n)
    }

    /** The stored field: clear (older builds, plain groups, channels) or sealed like the text. */
    fun open(raw: Any?, open: (String) -> String): Claim? {
        val s = raw as? String ?: return null
        if (s.isEmpty() || s.length > 512 * 1024) return null
        parse(s)?.let { return it }
        if (!s.startsWith("enc1:") && !s.startsWith("encg1:")) return null
        return parse(runCatching { open(s) }.getOrNull())
    }

    /**
     * The object path of a media link: a Firebase download url (…/o/<encoded path>?…), or an R2 link
     * (`r2:<path>`, groups and channels since config r2Media; 2026-11-21 contract addition, the same
     * path form `chat/<cid>/…`). Anything else is null.
     */
    fun storagePath(url: String?): String? {
        if (url != null && R2Media.isR2(url)) return R2Media.keyOf(url).takeIf { it.startsWith("chat/") && !it.contains("..") }
        if (url == null || !url.startsWith("https://firebasestorage.googleapis.com/")) return null
        val parts = url.substringBefore('?').split("/o/")
        if (parts.size != 2 || parts[1].isEmpty() || parts[1].contains('/')) return null
        return runCatching { java.net.URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8") }.getOrNull()
    }

    /** [key] of [url]'s path, remembered per url (asked for every media message each time it is read). */
    fun keyOfUrl(url: String?): String? {
        if (url == null) return null
        keyMemo.get(url)?.let { return it.ifEmpty { null } }
        val k = storagePath(url)?.let(::key)
        keyMemo.put(url, k ?: "")
        return k
    }
    private val keyMemo = StringMemo(4096)

    private fun videoSig(w: Double?, h: Double?, d: Double?) =
        "${Math.round(w ?: 0.0)}x${Math.round(h ?: 0.0)}d${Math.round(d ?: 0.0)}"
    private fun imageSig(w: Double?, h: Double?) = "${Math.round(w ?: 0.0)}x${Math.round(h ?: 0.0)}"

    // ---------------------------------------------------------------- state

    private class Slot(val origin: String, val sig: String, val forwarded: Boolean, val size: Long?, val claimed: Boolean)
    /** Where this phone holds one origin: its own cached url [u], pictured [sig], byte count [b]. */
    private class Alias(var u: String, var sig: String, var b: Long, var t: Long)

    private class Slots {
        var cur = HashMap<String, Slot>(); var old = HashMap<String, Slot>()
        operator fun get(k: String): Slot? = cur[k] ?: old[k]
        fun set(k: String, s: Slot) { cur[k] = s; if (cur.size > SLOT_GENERATION) { old = cur; cur = HashMap() } }
        fun remove(k: String) { cur.remove(k); old.remove(k) }
    }

    private val lock = Any()
    private var slots = Slots()
    private var aliases = HashMap<String, Alias>()
    private var cleared = HashSet<String>()
    private var loadedUid: String? = null
    private var wantedUid: String? = null
    private var saveQueued = false
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "MediaOrigin").apply { isDaemon = true } }

    private fun dir(): File? = runCatching {
        File(com.google.firebase.FirebaseApp.getInstance().applicationContext.filesDir, "media-origin").also { it.mkdirs() }
    }.getOrNull()

    private fun indexFile(uid: String): File? = dir()?.let { File(it, uid.filter { c -> c.isLetterOrDigit() } + ".json") }

    private fun currentUid(): String? = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }
        .getOrNull()?.takeIf { it.isNotEmpty() }

    /** Caller holds [lock]. A different account (or none loaded): drop memory, read its index on [io]. */
    private fun switchLocked(uid: String?) {
        if (uid == wantedUid) return
        wantedUid = uid; loadedUid = null
        slots = Slots(); aliases = HashMap(); cleared = HashSet()
        if (uid == null) return
        io.execute {
            val a = HashMap<String, Alias>(); val c = HashSet<String>()
            runCatching {
                val f = indexFile(uid)
                if (f != null && f.exists()) {
                    val o = org.json.JSONObject(f.readText())
                    o.optJSONObject("a")?.let { m ->
                        for (k in m.keys()) {
                            val e = m.optJSONObject(k) ?: continue
                            val b = e.optLong("b", 0L)
                            if (valid(k) == null || b <= 0) continue
                            a[k] = Alias(e.optString("u"), e.optString("s"), b, e.optLong("t"))
                        }
                    }
                    o.optJSONArray("c")?.let { arr -> for (i in 0 until arr.length()) c += arr.optString(i) }
                }
            }
            synchronized(lock) {
                if (wantedUid == uid) { aliases = a; cleared = c; loadedUid = uid }
            }
        }
    }

    /** Start reading the signed-in account's index (launch, sign-in). Cheap to call again. */
    fun warm() { val uid = currentUid(); synchronized(lock) { switchLocked(uid) } }

    /** Caller holds [lock]. */
    private fun scheduleSaveLocked() {
        val uid = loadedUid ?: return
        if (saveQueued) return
        saveQueued = true
        io.schedule(Runnable {
            val snap: String
            synchronized(lock) {
                saveQueued = false
                if (loadedUid != uid) return@Runnable
                if (aliases.size > MAX_ALIASES) {
                    val keep = aliases.entries.sortedByDescending { it.value.t }.take(MAX_ALIASES * 3 / 4)
                    aliases = HashMap(keep.associate { it.key to it.value })
                }
                if (cleared.size > MAX_ALIASES) cleared = HashSet(cleared.take(MAX_ALIASES / 2))
                val a = org.json.JSONObject()
                for ((k, v) in aliases) a.put(k, org.json.JSONObject().put("u", v.u).put("s", v.sig).put("b", v.b).put("t", v.t))
                snap = org.json.JSONObject().put("a", a).put("c", org.json.JSONArray(cleared.toList())).toString()
            }
            runCatching {
                val f = indexFile(uid) ?: return@runCatching
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(snap)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }, 2, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- slots (filled as messages are read)

    /** What [m]'s media urls carry. Called when a message is read from the server or the local copy. */
    fun note(m: Message) {
        if (m.deleted) return
        val fw = m.forwarded
        val list = ArrayList<Pair<String, Slot>>(2)
        val claimedTop = valid(m.origin) != null
        val topSize = if (claimedTop) m.originSize else null
        val origin = m.mediaOrigin
        if (origin != null) {
            when {
                m.type == "video" && !m.videoUrl.isNullOrEmpty() ->
                    list += m.videoUrl to Slot(origin, videoSig(m.width, m.height, m.duration), fw, topSize, claimedTop)
                m.type == "image" && !m.imageUrl.isNullOrEmpty() ->
                    list += m.imageUrl to Slot(origin, imageSig(m.width, m.height), fw, topSize, claimedTop)
            }
        }
        for (it in m.album) {
            val o = it.mediaOrigin ?: continue
            val claimed = valid(it.origin) != null
            val size = if (claimed) it.originSize else null
            if (it.videoUrl != null) list += it.videoUrl to Slot(o, videoSig(it.width, it.height, it.duration), fw, size, claimed)
            else list += it.imageUrl to Slot(o, imageSig(it.width, it.height), fw, size, claimed)
        }
        if (list.isEmpty()) return
        val uid = currentUid()
        synchronized(lock) {
            switchLocked(uid)
            if (wantedUid == null) return
            for ((u, s) in list) if (u !in cleared) slots.set(u, s)
        }
    }

    /** [url]'s OWN cached file holds [bytes] plain bytes: it names its origin for other messages. */
    fun cached(url: String, bytes: Long) {
        if (bytes <= 0) return
        synchronized(lock) {
            if (loadedUid == null) return
            val s = slots[url] ?: return
            if (s.claimed) return
            // A forward whose file is not the size it claims says something untrue: it names nothing.
            if (s.forwarded && s.size != null && s.size != bytes) return
            val a = aliases[s.origin]
            if (a != null && a.u == url && a.sig == s.sig && a.b == bytes) return
            aliases[s.origin] = Alias(url, s.sig, bytes, System.currentTimeMillis())
            scheduleSaveLocked()
        }
    }

    /** Another url whose own cached file is the same file as [url]'s, with its byte count; or null. */
    fun source(url: String): Pair<String, Long>? = synchronized(lock) {
        if (loadedUid == null) return null
        val s = slots[url] ?: return null
        val size = s.size ?: return null
        if (!s.forwarded) return null
        val a = aliases[s.origin] ?: return null
        if (a.u == url || a.sig != s.sig || a.b != size) return null
        a.u to size
    }

    /**
     * The urls of messages read this session that carry the same file as [url]'s forward under their
     * OWN path key (not a claim), with the same pictured size: the places to measure when the index
     * has no entry yet (a clip watched through the stream cache never passed a download).
     */
    fun ownersOf(url: String): List<String> = synchronized(lock) {
        if (loadedUid == null) return emptyList()
        val s = slots[url] ?: return emptyList()
        if (!s.forwarded || s.size == null) return emptyList()
        (slots.old.entries + slots.cur.entries)
            .filter { (k, v) -> k != url && !v.claimed && v.origin == s.origin && v.sig == s.sig }
            .map { it.key }.distinct().take(4)
    }

    /** [url]'s media was removed: forget its slot so another message's file does not fill it again. */
    fun forget(url: String) {
        synchronized(lock) {
            slots.remove(url)
            if (loadedUid == null) return
            cleared += url
            aliases.entries.removeAll { it.value.u == url }
            scheduleSaveLocked()
        }
    }

    /** Sign-out: the index names files of this account's chats; it goes with them. */
    fun removeAll() {
        val uid: String?
        synchronized(lock) {
            uid = loadedUid ?: wantedUid
            slots = Slots(); aliases = HashMap(); cleared = HashSet(); loadedUid = null; wantedUid = null
        }
        if (uid != null) io.execute { runCatching { indexFile(uid)?.delete() } }
    }

    // ---------------------------------------------------------------- forwards (the writer side)

    /**
     * The clear `origin` a forward of [m] carries: its first home's key and the byte count of the
     * file this phone holds for [m] (its own copy, never an alias), else what [m] was forwarded with,
     * else a file's `fileSize`. Null for an album (its items carry their own) or no media path.
     */
    fun payloadFor(m: Message, src: ChatMedia): String? {
        if (m.album.isNotEmpty()) return null
        val key = m.mediaOrigin ?: return null
        var size: Long? = when (m.type) {
            "video" -> m.videoUrl?.let { src.ownBytes(it) }
            "image" -> m.imageUrl?.let { src.ownBytes(it) }
            "file" -> m.fileSize
            else -> null
        }
        if ((size ?: 0L) <= 0L) size = m.originSize
        return payload(key, size)
    }

    /** [payloadFor] for each album item of [m], in order. */
    fun albumPayloads(m: Message, src: ChatMedia): List<String?> = m.album.map { it ->
        val key = it.mediaOrigin ?: return@map null
        var size = src.ownBytes(it.videoUrl ?: it.imageUrl)
        if ((size ?: 0L) <= 0L) size = it.originSize
        payload(key, size)
    }
}

/** A small thread-safe least-recently-used string map (plain Java, so it also runs in JVM tests). */
internal class StringMemo(private val max: Int) {
    private val map = object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > max
    }
    @Synchronized fun get(k: String): String? = map[k]
    @Synchronized fun put(k: String, v: String) { map[k] = v }
}
