package com.fariin.app.data

import android.content.Context
import com.fariin.app.crypto.B64
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.Wire
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * F-15, the port of iOS GroupKeyRelease.swift. Groups are no longer end-to-end (owner 2026-10-23),
 * but old group content was sealed for the members of that day. A phone that holds wraps opens its
 * own wraps of old items (no file download) and sends the per-item keys to `releaseGroupKeys`. The
 * server checks each key and files it on the message under `rk`; every member's phone then opens
 * the item with it (ThreadRepository.parse notes `rk`).
 *
 * Only for a group that switched (`e2ee == false`) and does not hide its history. One group at a
 * time, small batches with a pause, a daily ceiling below the server's, and a cursor per group on
 * this phone so a stopped pass resumes. Keys are never logged. 1:1 chats are never touched.
 */
object GroupKeyRelease {
    private const val PAGE = 100L
    private const val PAGES_PER_PASS = 10
    private const val TEXT_PER_CALL = 200
    private const val FILES_PER_CALL = 10
    private const val PAUSE_MS = 1_500L
    /** Items ASKED per day on this phone; the server allows 5,000 per uid per UTC day. */
    private const val DAILY_CEILING = 4_000
    private const val PREFS = "groupKeyRelease.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val noted = ConcurrentHashMap.newKeySet<String>()
    private val lock = Mutex()
    @Volatile private var app: Context? = null

    fun attach(ctx: Context) { if (app == null) app = ctx.applicationContext }

    /** A switched group is in my chat list. Once per launch per group, a quiet pass a little later. */
    fun noteGroup(cid: String) {
        if (!Wire.isGroupCid(cid) || !noted.add(cid)) return
        scope.launch { delay(20_000); run(cid, opened = false) }
    }

    /** I opened this chat. 1:1 ids are ignored. */
    fun chatOpened(cid: String) {
        if (!Wire.isGroupCid(cid)) return
        scope.launch { delay(2_000); run(cid, opened = true) }
    }

    private suspend fun run(cid: String, opened: Boolean) {
        // One group at a time; the others wait their turn.
        lock.withLock { runCatching { pass(cid, opened) } }
    }

    // ------------------------------------------------------------------ progress, on this phone

    private class Progress(var top: Double = 0.0, var bottom: Double = 0.0, var done: Boolean = false,
                           var lastRun: Double = 0.0, var notBefore: Double = 0.0)

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(cid: String): Progress {
        val raw = prefs()?.getString("p.$cid", null) ?: return Progress()
        return runCatching {
            val j = JSONObject(raw)
            Progress(j.optDouble("top", 0.0), j.optDouble("bottom", 0.0), j.optBoolean("done", false),
                j.optDouble("lastRun", 0.0), j.optDouble("notBefore", 0.0))
        }.getOrDefault(Progress())
    }

    private fun save(cid: String, p: Progress) {
        val j = JSONObject().put("top", p.top).put("bottom", p.bottom).put("done", p.done)
            .put("lastRun", p.lastRun).put("notBefore", p.notBefore)
        prefs()?.edit()?.putString("p.$cid", j.toString())?.apply()
    }

    private fun utcDay(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun askedToday(): Int {
        val p = prefs() ?: return 0
        return if (p.getString("day", null) == utcDay()) p.getInt("n", 0) else 0
    }

    private fun addAsked(n: Int) {
        val total = askedToday() + n
        prefs()?.edit()?.putString("day", utcDay())?.putInt("n", total)?.apply()
    }

    // ------------------------------------------------------------------ the pass

    private class Stop(val waitS: Double?) : Exception()

    private class Item(val mid: String, val f: String, val n: String, val k: String) {
        val isFile: Boolean get() = f in FILE_FIELDS || f.startsWith("album.")
        val payload: Map<String, Any> get() = mapOf("mid" to mid, "f" to f, "n" to n, "k" to k)
    }

    private val FILE_FIELDS = setOf("enc", "thumbEnc", "linkPreview.imageEnc")

    private suspend fun pass(cid: String, opened: Boolean) {
        if (app == null) return
        val p = load(cid)
        val now = System.currentTimeMillis() / 1000.0
        if (now < p.notBefore) return
        // An opened chat re-checks after 10 minutes; the quiet pass after 30 minutes while there is
        // history left, and once a day after that.
        val gap = if (opened) 600.0 else if (p.done) 86_400.0 else 1_800.0
        if (now - p.lastRun < gap) return
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        p.lastRun = now
        save(cid, p)

        val db = com.fariin.app.Fb.db
        val g = runCatching { db.collection("conversations").document(cid).get(Source.SERVER).await().data }
            .getOrNull() ?: return
        val users = (g["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val banned = (g["bannedUids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val hidden = Conversation.millis(g["hideHistoryAt"]) > 0
        if (g["type"] as? String != "group" || g["e2ee"] != false || hidden || g["endedAt"] != null ||
            me !in users || me in banned) {
            p.notBefore = now + 3_600   // one cheap read an hour at most while it stays so
            save(cid, p)
            return
        }
        val crypto = CryptoHub.crypto
        try { crypto.ensureReady() } catch (_: Exception) { return }

        val msgs = db.collection("conversations").document(cid).collection("messages")
        try {
            // 1) Anything newer than what was looked at.
            if (p.top > 0) {
                val from = p.top
                var after: DocumentSnapshot? = null
                for (i in 0 until PAGES_PER_PASS) {
                    var q = msgs.whereGreaterThan("createdAt", Timestamp(Date(from.toLong())))
                        .orderBy("createdAt", Query.Direction.ASCENDING).limit(PAGE)
                    after?.let { q = q.startAfter(it) }
                    val page = q.get(Source.SERVER).await().documents
                    if (page.isEmpty()) break
                    release(cid, page)
                    for (d in page) ms(d)?.let { p.top = maxOf(p.top, it) }
                    save(cid, p)
                    after = page.last()
                    if (page.size < PAGE) break
                }
            }
            // 2) Back through history from where the last pass stopped (or from the newest).
            if (!p.done) {
                val below = p.bottom
                var after: DocumentSnapshot? = null
                for (i in 0 until PAGES_PER_PASS) {
                    var q: Query = msgs
                    if (below > 0) q = q.whereLessThan("createdAt", Timestamp(Date(below.toLong())))
                    q = q.orderBy("createdAt", Query.Direction.DESCENDING).limit(PAGE)
                    after?.let { q = q.startAfter(it) }
                    val page = q.get(Source.SERVER).await().documents
                    if (page.isEmpty()) { p.done = true; save(cid, p); break }
                    release(cid, page)
                    for (d in page) {
                        val t = ms(d) ?: continue
                        p.top = maxOf(p.top, t)
                        p.bottom = if (p.bottom > 0) minOf(p.bottom, t) else t
                    }
                    if (page.size < PAGE) p.done = true
                    save(cid, p)
                    if (p.done) break
                    after = page.last()
                }
            }
        } catch (s: Stop) {
            s.waitS?.let { p.notBefore = System.currentTimeMillis() / 1000.0 + it; save(cid, p) }
        } catch (_: Exception) {
            // Offline or a fault: the cursor stays where the last full page ended.
        }
    }

    private fun ms(d: DocumentSnapshot): Double? =
        (d.get("createdAt") as? Timestamp)?.toDate()?.time?.toDouble()

    /** The keys of one page this phone holds and the server does not have yet, sent in batches. */
    private suspend fun release(cid: String, docs: List<DocumentSnapshot>) {
        val authors = HashSet<String>()
        for (d in docs) {
            (d.get("authorId") as? String)?.let { authors.add(it) }
            (d.get("reactions") as? Map<*, *>)?.keys?.forEach { k -> (k as? String)?.let { authors.add(it) } }
            ((d.get("enc") as? Map<*, *>)?.get("a") as? String)?.let { authors.add(it) }
        }
        runCatching { CryptoHub.crypto.preloadKeys(authors) }
        var queue = docs.flatMap { collect(it) }
        if (queue.isEmpty()) return
        var retries = 0
        while (queue.isNotEmpty()) {
            if (askedToday() >= DAILY_CEILING) throw Stop(6 * 3600.0)
            // One call: up to 200 items, of which up to 10 files.
            val batch = ArrayList<Item>()
            val rest = ArrayList<Item>()
            var files = 0
            for (it in queue) {
                if (batch.size < TEXT_PER_CALL && (!it.isFile || files < FILES_PER_CALL)) {
                    batch.add(it); if (it.isFile) files++
                } else rest.add(it)
            }
            addAsked(batch.size)
            val again = call(cid, batch)
            // Skipped for a reason that can pass (budget, busy): once more, bounded.
            if (again.isNotEmpty() && retries < 20) { retries++; rest.addAll(again) }
            queue = rest
            if (queue.isNotEmpty()) delay(PAUSE_MS)
        }
        delay(PAUSE_MS)
    }

    /** Posts one batch. Returns the items to try again; throws to stop the pass. */
    private suspend fun call(cid: String, batch: List<Item>): List<Item> {
        val fn = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
            .getHttpsCallable("releaseGroupKeys").withTimeout(300, TimeUnit.SECONDS)
        try {
            val res = fn.call(mapOf("cid" to cid, "items" to batch.map { it.payload })).await().getData() as? Map<*, *>
            val results = (res?.get("results") as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            val retry = ArrayList<Item>()
            for (r in results) {
                if (r["status"] as? String != "skipped") continue
                val reason = r["reason"] as? String ?: ""
                if (reason != "budget" && reason != "busy") continue
                batch.firstOrNull { it.mid == r["mid"] as? String && it.n == r["n"] as? String }?.let { retry.add(it) }
            }
            return retry
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.FAILED_PRECONDITION, FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> throw Stop(6 * 3600.0)
                FirebaseFunctionsException.Code.INVALID_ARGUMENT -> return emptyList()   // move on
                else -> throw Stop(null)
            }
        } catch (e: Exception) {
            throw Stop(null)
        }
    }

    /** Every sealed group item in [d] whose key my own wrap opens and that has no released key yet. */
    private fun collect(d: DocumentSnapshot): List<Item> {
        val x = d.data ?: return emptyList()
        // Never released (the server refuses them too): deleted, view-once, held, disappearing.
        if (x["deleted"] == true || x["viewOnce"] == true || x["held"] == true || x["expiresAt"] != null) return emptyList()
        val mid = d.id
        val author = x["authorId"] as? String ?: ""
        val have = (x["rk"] as? Map<*, *>)?.keys?.mapNotNull { k ->
            (k as? String)?.let { B64.decodeLoose(it) }?.let { B64.encode(it) } }?.toSet() ?: emptySet()
        val crypto = CryptoHub.crypto
        val out = ArrayList<Item>()
        fun norm(n: String) = B64.decodeLoose(n)?.let { B64.encode(it) }

        fun text(f: String, raw: Any?, by: String) {
            val s = raw as? String ?: return
            if (!s.startsWith(Wire.ENCG1)) return
            val (n, k) = runCatching { crypto.ownGroupTextKey(s, by) }.getOrNull() ?: return
            val nn = norm(n) ?: return
            if (nn !in have) out.add(Item(mid, f, n, k))
        }
        fun file(f: String, map: Any?, url: Any?) {
            val u = url as? String
            if (u.isNullOrEmpty()) return
            val m = EncMeta.fromMap(map as? Map<*, *>) ?: return
            val (n, k) = runCatching { crypto.ownGroupFileKey(m) }.getOrNull() ?: return
            val nn = norm(n) ?: return
            if (nn !in have) out.add(Item(mid, f, n, k))
        }

        text("text", x["text"], author)
        text("blurhash", x["blurhash"], author)
        text("thumb", x["thumb"], author)
        (x["replyTo"] as? Map<*, *>)?.let { text("replyTo.text", it["text"], author) }
        val lp = x["linkPreview"] as? Map<*, *>
        if (lp != null) for (k in listOf("url", "title", "desc")) text("linkPreview.$k", lp[k], author)
        (x["reactions"] as? Map<*, *>)?.forEach { (uid, raw) -> (uid as? String)?.let { text("reactions.$it", raw, it) } }
        (x["edits"] as? List<*>)?.forEachIndexed { i, e -> text("edits.$i", (e as? Map<*, *>)?.get("t"), author) }
        val mainUrl = listOf(x["videoUrl"], x["audioUrl"], x["fileUrl"], x["imageUrl"])
            .mapNotNull { it as? String }.firstOrNull { it.isNotEmpty() }
        file("enc", x["enc"], mainUrl)
        file("thumbEnc", x["thumbEnc"], x["thumbUrl"])
        if (lp != null) file("linkPreview.imageEnc", lp["imageEnc"], lp["imageUrl"])
        (x["album"] as? List<*>)?.forEachIndexed { i, a ->
            val am = a as? Map<*, *> ?: return@forEachIndexed
            file("album.$i.enc", am["enc"], am["imageUrl"])
            file("album.$i.videoEnc", am["videoEnc"], am["videoUrl"])
        }
        return out
    }
}
