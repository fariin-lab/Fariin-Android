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
    @Volatile private var loaded = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    fun init(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val f = File(File(c.applicationContext.noBackupFilesDir, "local-v1"), "drafts.bin")
            file = f
            _map.value = try {
                if (f.exists()) AtRest.open(f.readBytes())?.toString(Charsets.UTF_8)?.let(::decode) ?: emptyMap() else emptyMap()
            } catch (_: Exception) { emptyMap() }
            loaded = true
        }
    }

    fun text(cid: String): String = _map.value[cid] ?: ""
    /** Chats holding a draft, for the Log Out warning (iOS unsentSummary). */
    fun count(): Int = _map.value.values.count { it.isNotBlank() }

    fun set(cid: String, text: String) {
        val t = text.trim()
        val old = _map.value[cid] ?: ""
        if (old == t) return
        _map.value = if (t.isEmpty()) _map.value - cid else _map.value + (cid to t)
        if (old.isEmpty() != t.isEmpty()) flush() else later()
    }

    @Synchronized private fun later() {
        pending?.cancel()
        pending = io.launch { delay(PAUSE_MS); flush() }
    }

    /** Write now (app to the background, a draft appearing or going). */
    fun flush() {
        val f = file ?: return
        io.launch {
            synchronized(this@Drafts) {
                // The map as it is NOW, so an older write landing late can never bring back a
                // draft that was sent or wiped since.
                val snapshot = _map.value
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
        synchronized(this) {
            pending?.cancel(); pending = null
            _map.value = emptyMap()
            file?.delete()
        }
    }

    fun encode(m: Map<String, String>): String = JSONObject(m).toString()
    fun decode(s: String): Map<String, String> {
        val o = JSONObject(s)
        return o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotEmpty() }
    }
}
