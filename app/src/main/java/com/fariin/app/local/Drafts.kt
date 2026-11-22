package com.fariin.app.local

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Unsent composer text per chat, after iOS `Drafts`: leave a chat with words in the box and they
 * are there when you come back, and the chat list says "Draft: ..." until you send or clear them.
 * Trimmed like iOS; a draft appearing or going is written at once, edits inside one after a short
 * pause (iOS 0.6 s), and the app going to the background writes whatever is owed ([flush]).
 *
 * Sealed at rest, as iOS seals them with a this-device-only key ([AtRest]); app-private, never
 * in a backup, and wiped at sign-out.
 */
object Drafts {
    private const val PAUSE_MS = 600L
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map.asStateFlow()
    @Volatile private var file: File? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    /** A draft's bold and links (iOS Drafts.setFormat, 2026-11-19): by chat, {"t": the trimmed
     *  words they were made for, "f": the wire fmt}. Sealed in the same file, under [FMT] keys. */
    @Volatile private var fmts: Map<String, String> = emptyMap()
    private const val FMT = "\u0001fmt:"
    /** drafts.bin read, unsealed (Keystore + AES-GCM) and decoded once, off the main thread when
     *  the start-up thread gets there first; every read and write below waits for it. */
    private val disk = LoadOnce {
        val f = file
        val m = try {
            if (f != null && f.exists()) AtRest.open(f.readBytes())?.toString(Charsets.UTF_8)?.let(::decode) ?: emptyMap() else emptyMap()
        } catch (_: Exception) { emptyMap() }
        // Anything set while the file was read cannot exist: set() waits for this read.
        fmts = m.filterKeys { it.startsWith(FMT) }.mapKeys { it.key.removePrefix(FMT) }
        _map.value = m.filterKeys { !it.startsWith(FMT) }
    }

    /** Cheap, at app start: only remembers where the file is. The read is [preload] or the first use. */
    fun init(c: Context) {
        if (file != null) return
        file = File(File(c.applicationContext.noBackupFilesDir, "local-v1"), "drafts.bin")
    }

    /** Reads the file now (a start-up thread). */
    fun preload() = disk.ensure()

    fun text(cid: String): String { disk.ensure(); return _map.value[cid] ?: "" }
    /** Chats holding a draft, for the Log Out warning (iOS unsentSummary). */
    fun count(): Int { disk.ensure(); return _map.value.values.count { it.isNotBlank() } }

    fun set(cid: String, text: String) {
        disk.ensure()
        val t = text.trim()
        val old = _map.value[cid] ?: ""
        if (old == t) return
        if (t.isEmpty()) fmts = fmts - cid
        _map.value = if (t.isEmpty()) _map.value - cid else _map.value + (cid to t)
        if (old.isEmpty() != t.isEmpty()) flush() else later()
    }

    /** The draft's bold and links ([fmt]: wire JSON for text.trim(), null = none), tied to those words. */
    fun setFormat(cid: String, text: String, fmt: String?) {
        disk.ensure()
        val t = text.trim()
        val v = if (t.isEmpty() || fmt == null) null else JSONObject().put("t", t).put("f", fmt).toString()
        if (fmts[cid] == v) return
        fmts = if (v == null) fmts - cid else fmts + (cid to v)
        later()
    }

    /** The draft's bold and links when they were made for [text] (trimmed), else null. */
    fun format(cid: String, text: String): String? {
        disk.ensure()
        val o = fmts[cid]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return if (o.optString("t") == text.trim()) o.optString("f").takeIf { it.isNotEmpty() } else null
    }

    @Synchronized private fun later() {
        pending?.cancel()
        pending = io.launch { delay(PAUSE_MS); flush() }
    }

    /** Write now (app to the background, a draft appearing or going). */
    fun flush() {
        val f = file ?: return
        io.launch {
            // Never write before the file was read: that would replace it with a partial map.
            disk.ensure()
            synchronized(this@Drafts) {
                // The map as it is NOW, so an older write landing late can never bring back a
                // draft that was sent or wiped since.
                val snapshot = _map.value + fmts.filterKeys { _map.value.containsKey(it) }.mapKeys { FMT + it.key }
                try {
                    if (snapshot.isEmpty()) f.delete() else {
                        val sealed = AtRest.seal(encode(snapshot).toByteArray(Charsets.UTF_8)) ?: return@launch
                        f.parentFile?.mkdirs(); f.writeBytes(sealed)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** Sign-out: drafts are unsent words, they go with the session. */
    fun clear() {
        disk.ensure()
        synchronized(this) {
            pending?.cancel(); pending = null
            _map.value = emptyMap()
            fmts = emptyMap()
            file?.delete()
        }
    }

    fun encode(m: Map<String, String>): String = JSONObject(m).toString()
    fun decode(s: String): Map<String, String> {
        val o = JSONObject(s)
        return o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotEmpty() }
    }
}
