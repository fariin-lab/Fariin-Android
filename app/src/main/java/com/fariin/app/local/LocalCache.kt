package com.fariin.app.local

import android.content.Context
import com.fariin.app.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's copy of the chat list and of the newest messages of each chat, so a cold start
 * draws both at once, offline too, and the live listeners correct them a moment later. After
 * Kulan-iOS ConversationsDiskCache (200 chats, previews still sealed) and ThreadMessageCache (the
 * newest 60 opened messages per chat on disk).
 *
 * Where: noBackupFilesDir/local-v1/<uid>/, app-private and never in a backup or a phone-to-phone
 * transfer (iOS isExcludedFromBackup). Every file is sealed with the Keystore key ([AtRest]), on
 * top of Android's own file encryption. Writes go to a background thread, a short pause after the
 * last change, and only when something changed. Sign-out deletes the folder ([wipe]).
 */
object LocalCache {
    const val CHAT_CAP = 200
    const val THREAD_CAP = 60
    private const val PAUSE_MS = 400L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingWrites = ConcurrentHashMap<String, Job>()
    private val lastWritten = ConcurrentHashMap<String, Int>()
    @Volatile private var root: File? = null
    private val lock = Any()
    @Volatile private var generation = 0
    /** Bumped by a sign-out wipe: work begun before it must not write after it. */
    val currentGeneration: Int get() = generation
    /** Memory copies of what was read, so reopening a chat in the same run skips the disk. */
    // The last 24 chats read (was 12; the background warm of the top 20 pushed the newest out, 2026-11-09).
    private val threadMemo: MutableMap<String, List<Message>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Message>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Message>>?) = size > 24
        })
    @Volatile private var chatsMemo: Pair<String, List<Pair<String, Map<String, Any?>>>>? = null

    fun init(c: Context) {
        if (root == null) root = File(c.applicationContext.noBackupFilesDir, "local-v1")
    }

    private fun dir(uid: String): File? = root?.let { File(it, safe(uid)) }
    private fun safe(s: String) = s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }.joinToString("")

    private fun readSealed(f: File): String? = try {
        if (!f.exists()) null else AtRest.open(f.readBytes())?.toString(Charsets.UTF_8)
    } catch (_: Exception) { null }

    private fun writeSealed(f: File, text: String) {
        val sealed = AtRest.seal(text.toByteArray(Charsets.UTF_8)) ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Write later (after [PAUSE_MS]) unless the same content was written last. */
    private fun schedule(key: String, f: File, hash: Int, text: () -> String) {
        if (lastWritten[key] == hash) return
        pendingWrites.remove(key)?.cancel()
        val g = generation
        pendingWrites[key] = io.launch {
            delay(PAUSE_MS)
            // Under the lock, and only for the session that asked: a write already running when
            // the account signs out can never land after the wipe.
            synchronized(lock) {
                if (g == generation) try { writeSealed(f, text()); lastWritten[key] = hash } catch (_: Exception) {}
            }
            pendingWrites.remove(key)
        }
    }

    // ------------------------------------------------------------------ chat list

    /** The saved chat list if it is already in memory (never touches the disk). */
    fun peekChats(uid: String): List<Pair<String, Map<String, Any?>>>? = chatsMemo?.takeIf { it.first == uid }?.second

    fun loadChats(uid: String): List<Pair<String, Map<String, Any?>>> {
        chatsMemo?.let { (u, l) -> if (u == uid) return l }
        val f = dir(uid)?.let { File(it, "chats.bin") } ?: return emptyList()
        val list = readSealed(f)?.let { runCatching { LocalJson.decodeChats(it) }.getOrNull() } ?: emptyList()
        chatsMemo = uid to list
        return list
    }

    fun saveChats(uid: String, docs: List<Pair<String, Map<String, Any?>>>) {
        val kept = docs.take(CHAT_CAP)
        chatsMemo = uid to kept
        val f = dir(uid)?.let { File(it, "chats.bin") } ?: return
        schedule("chats:$uid", f, kept.hashCode()) { LocalJson.encodeChats(kept) }
    }

    // ------------------------------------------------------------------ followed channels

    /** Followed channels for the first frame (iOS d15f501ef): my follow entries and each channel's
     *  document, saved like the chat list, so a channel row never pops in after the list. */
    @Volatile private var followsMemo: Triple<String, List<Pair<String, Map<String, Any?>>>, List<Pair<String, Map<String, Any?>>>>? = null

    fun loadFollows(uid: String): Pair<List<Pair<String, Map<String, Any?>>>, List<Pair<String, Map<String, Any?>>>> {
        followsMemo?.let { (u, e, d) -> if (u == uid) return e to d }
        val base = dir(uid) ?: return emptyList<Pair<String, Map<String, Any?>>>() to emptyList()
        fun read(name: String) = readSealed(File(base, name))?.let { runCatching { LocalJson.decodeChats(it) }.getOrNull() } ?: emptyList()
        val e = read("follows.bin"); val d = read("follow_docs.bin")
        followsMemo = Triple(uid, e, d)
        return e to d
    }

    fun peekFollows(uid: String) = followsMemo?.takeIf { it.first == uid }?.let { it.second to it.third }

    fun saveFollows(uid: String, entries: List<Pair<String, Map<String, Any?>>>, docs: List<Pair<String, Map<String, Any?>>>) {
        followsMemo = Triple(uid, entries, docs)
        val base = dir(uid) ?: return
        schedule("follows:$uid", File(base, "follows.bin"), entries.hashCode()) { LocalJson.encodeChats(entries) }
        schedule("followdocs:$uid", File(base, "follow_docs.bin"), docs.hashCode()) { LocalJson.encodeChats(docs) }
    }

    // ------------------------------------------------------------------ messages

    fun loadThread(uid: String, cid: String): List<Message> {
        threadMemo["$uid/$cid"]?.let { return it }
        val f = dir(uid)?.let { File(File(it, "threads"), safe(cid) + ".bin") } ?: return emptyList()
        val list = readSealed(f)?.let { runCatching { LocalJson.decodeMessages(it) }.getOrNull() } ?: emptyList()
        threadMemo["$uid/$cid"] = list
        return list
    }

    /** [messages] oldest first, as the thread holds them. */
    fun saveThread(uid: String, cid: String, messages: List<Message>) {
        val kept = LocalJson.windowToKeep(messages, THREAD_CAP)
        if (kept.isEmpty()) return   // an empty first answer never wipes what was kept
        threadMemo["$uid/$cid"] = kept
        val f = dir(uid)?.let { File(File(it, "threads"), safe(cid) + ".bin") } ?: return
        schedule("t:$uid/$cid", f, kept.hashCode()) { LocalJson.encodeMessages(kept) }
    }

    fun forgetThread(uid: String, cid: String) {
        threadMemo.remove("$uid/$cid")
        dir(uid)?.let { File(File(it, "threads"), safe(cid) + ".bin").delete() }
    }

    // ------------------------------------------------------------------ quote cards

    /** The last quote-card answers (QuoteStore), as a JSON text; null when none were kept. */
    fun loadQuotes(uid: String): String? = dir(uid)?.let { readSealed(File(it, "quotes.bin")) }

    fun saveQuotes(uid: String, json: String) {
        val f = dir(uid)?.let { File(it, "quotes.bin") } ?: return
        schedule("q:$uid", f, json.hashCode()) { json }
    }

    /**
     * Plain Log Out: the chat list stays for the same account's return (SessionEnd keepFor), but
     * the opened messages are decrypted text and go, as iOS removes ThreadMessageCache.
     */
    fun wipeThreads() {
        synchronized(lock) {
            generation++
            for (j in pendingWrites.values) j.cancel()
            pendingWrites.clear(); lastWritten.clear(); threadMemo.clear(); chatsMemo = null
            root?.listFiles()?.forEach { File(it, "threads").deleteRecursively() }
        }
    }

    /** Sign-out: these are opened messages, so they never outlive the session (iOS removeAll). */
    fun wipe() {
        synchronized(lock) {
            generation++
            for (j in pendingWrites.values) j.cancel()
            pendingWrites.clear(); lastWritten.clear(); threadMemo.clear(); chatsMemo = null; followsMemo = null
            root?.deleteRecursively()
        }
    }
}
