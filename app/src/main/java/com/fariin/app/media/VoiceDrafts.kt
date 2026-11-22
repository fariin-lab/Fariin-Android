package com.fariin.app.media

import android.content.Context
import java.io.File

/**
 * A voice note recorded but not sent, kept per chat (iOS keeps the paused note when the chat is
 * left; owner 2026-11-09: Android lost it). The audio lives in the app's own files, its length and
 * the meter levels (for the waveform) in a small preference, until it is sent or binned.
 */
object VoiceDrafts {
    class Draft(val file: File, val duration: Double, val levels: List<Float>)

    private const val PREFS = "fariin_voice_drafts"

    /** Each chat's kept note length in seconds, for the list's red "Draft:" with the mic (iOS draftIndex). */
    private val _index = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Double>>(emptyMap())
    val index: kotlinx.coroutines.flow.StateFlow<Map<String, Double>> = _index
    @Volatile private var loaded = false

    /** Reads the kept notes' lengths once, so the list knows them before any chat is opened. */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val m = HashMap<String, Double>()
        for ((k, v) in all) (v as? String)?.substringBefore("|")?.toDoubleOrNull()?.let { if (it > 0) m[k] = it }
        _index.value = _index.value + m
    }
    private fun dir(ctx: Context) = File(ctx.filesDir, "voice-drafts").apply { mkdirs() }
    private fun target(ctx: Context, key: String) = File(dir(ctx), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".m4a")

    fun save(ctx: Context, key: String, file: File, duration: Double, levels: List<Float>) {
        if (key.isEmpty() || !file.exists()) return
        val to = target(ctx, key)
        if (file.absolutePath != to.absolutePath) {
            runCatching { file.copyTo(to, overwrite = true); file.delete() }.onFailure { return }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, duration.toString() + "|" + levels.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }).apply()
        _index.value = _index.value + (key to duration)
    }

    /** The chat's kept note, or null. The record stays until [forget] (sent or binned). */
    fun load(ctx: Context, key: String): Draft? {
        if (key.isEmpty()) return null
        val f = target(ctx, key)
        val meta = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        if (meta == null || !f.exists() || f.length() == 0L) { forget(ctx, key); return null }
        val (d, lv) = meta.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val levels = lv.split(",").mapNotNull { it.toFloatOrNull() }
        return Draft(f, d.toDoubleOrNull() ?: return null, levels)
    }

    /** Sign-out (iOS AudioRecorder.wipeAllDrafts): every kept note and its record go. */
    fun wipe(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { File(ctx.filesDir, "voice-drafts").deleteRecursively() }
        _index.value = emptyMap()
    }

    /** Only the record; the file itself goes with the send or the bin. */
    fun forget(ctx: Context, key: String) {
        if (key.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
        _index.value = _index.value - key
    }
}
