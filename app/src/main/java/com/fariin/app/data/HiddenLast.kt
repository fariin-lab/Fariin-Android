package com.fariin.app.data

import android.content.Context
import org.json.JSONObject

/**
 * The chat list line after I delete a chat's newest message for me only (owner 2026-11-13: a live
 * location he deleted still showed as "Live location"). The summary on the server is everyone's, so
 * this phone keeps its own line: the message before it, or nothing. It holds only until a newer
 * message moves the chat on (the chat's time passes the deleted one's).
 */
object HiddenLast {
    private const val PREFS = "fariin_hidden"
    private const val KEY = "hiddenLast.v1"
    /** cid -> (the deleted message's time, the line to show instead). */
    @Volatile private var lines: Map<String, Pair<Double, String>> = emptyMap()
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        lines = runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { it.getDouble("at") to it.getString("line") } }
        }.getOrDefault(emptyMap())
    }

    fun set(c: Context, cid: String, at: Double, line: String) {
        load(c)
        lines = lines + (cid to (at to line))
        save(c)
    }

    /** The line to show for [c], or null when the chat has moved on (or nothing was deleted). */
    fun line(c: Conversation): String? {
        val (at, line) = lines[c.id] ?: return null
        // The server stamps the summary a moment after the message's own time: a few seconds' slack.
        return if (c.updatedAtMillis <= at + 5_000) line else null
    }

    private fun save(c: Context) {
        val o = JSONObject()
        lines.forEach { (k, v) -> o.put(k, JSONObject().put("at", v.first).put("line", v.second)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
