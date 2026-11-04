package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject

/**
 * The search page's Recent (iOS SearchRecents): the chats and people opened from search, newest
 * first, at most [CAP]. Opening one again moves it to the front; a person and the chat made by
 * opening them are one entry (the newer replaces the older). Kept on this phone per account,
 * never sent anywhere; Clear empties it.
 */
object SearchRecents {
    const val CAP = 20
    private const val PREFS = "fariin_search"

    /** kind "chat": key is a conversation id; kind "person": key is a uid. */
    data class Entry(val kind: String, val key: String, val title: String, val handle: String = "", val photo: String = "") {
        val id get() = "$kind:$key"
    }

    val entries: SnapshotStateList<Entry> = mutableStateListOf()
    private var account = ""

    fun start(c: Context, me: String) {
        if (me == account) return
        account = me
        entries.clear()
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent.$me", null) ?: return
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                entries += Entry(o.getString("k"), o.getString("key"), o.optString("t"), o.optString("h"), o.optString("p"))
            }
        }
    }

    fun recordChat(c: Context, me: String, conv: Conversation) {
        val other = if (conv.isGroup || conv.isChannel) "" else conv.otherUid(me)
        remember(c, me, Entry("chat", conv.id, conv.displayName(me)), twin = if (other.isEmpty()) null else "person:$other")
    }

    fun recordPerson(c: Context, me: String, p: Person, chatId: String?) {
        remember(c, me, Entry("person", p.uid, p.shownName, p.handle, p.photoUrl ?: ""), twin = chatId?.let { "chat:$it" })
    }

    fun clear(c: Context, me: String) {
        entries.clear()
        save(c, me)
    }

    private fun remember(c: Context, me: String, e: Entry, twin: String?) {
        start(c, me)
        entries.removeAll { it.id == e.id || it.id == twin }
        entries.add(0, e)
        while (entries.size > CAP) entries.removeAt(entries.lastIndex)
        save(c, me)
    }

    private fun save(c: Context, me: String) {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("k", it.kind).put("key", it.key).put("t", it.title).put("h", it.handle).put("p", it.photo)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent.$me", a.toString()).apply()
    }

    /** Sign-out: this phone forgets what the account searched for, as iOS SessionWipe does. */
    fun wipe(c: Context) {
        entries.clear(); account = ""
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
