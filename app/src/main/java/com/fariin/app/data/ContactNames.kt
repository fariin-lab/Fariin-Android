package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** One person's local card: the name I gave them and a private note (iOS ContactCard). */
data class ContactCard(val first: String = "", val last: String = "", val note: String = "") {
    val displayName: String?
        get() = listOf(first, last).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    val isEmpty: Boolean get() = displayName == null && note.isBlank()
}

/**
 * Set nickname (iOS ContactNames): names and notes I keep for people, on this phone only and never
 * sent. In a 1:1 the name replaces their profile name everywhere the chat's name shows. Held in a
 * Compose state map, so every screen that drew a name redraws when one is saved.
 */
object ContactNames {
    private const val PREFS = "fariin_contacts"
    private const val KEY = "contactCards.v2"
    const val NAME_LIMIT = 40
    const val NOTE_LIMIT = 100

    private val cards = mutableStateMapOf<String, ContactCard>()
    @Volatile private var app: Context? = null
    /** The saved cards, parsed once: by the start-up thread ([preload]) or the first reader. */
    private val disk = com.fariin.app.local.LoadOnce {
        val c = app ?: return@LoadOnce
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return@LoadOnce
        val read = HashMap<String, ContactCard>()
        try {
            val o = JSONObject(raw)
            for (uid in o.keys()) {
                val d = o.getJSONObject(uid)
                read[uid] = ContactCard(d.optString("f"), d.optString("l"), d.optString("n"))
            }
        } catch (_: Exception) {}
        // One write into the state map, so a screen redraws once when the names arrive.
        if (read.isNotEmpty()) cards.putAll(read)
    }

    /** Cheap, at app start: only remembers the context. The file is read by [preload] or first use. */
    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
    }

    /** Parses the saved cards now (a start-up thread). */
    fun preload() = disk.ensure()

    fun name(uid: String): String? { disk.ensure(); return cards[uid]?.displayName }
    fun note(uid: String): String? { disk.ensure(); return cards[uid]?.note?.trim()?.takeIf { it.isNotEmpty() } }
    fun card(uid: String): ContactCard { disk.ensure(); return cards[uid] ?: ContactCard() }

    fun setCard(uid: String, card: ContactCard) {
        disk.ensure()
        val c = card.copy(first = card.first.take(NAME_LIMIT), last = card.last.take(NAME_LIMIT), note = card.note.take(NOTE_LIMIT))
        if (c.isEmpty) cards.remove(uid) else cards[uid] = c
        persist()
    }

    fun remove(uid: String) { disk.ensure(); cards.remove(uid); persist() }

    /** Sign-out: they were this account's names. */
    fun clear() { disk.ensure(); cards.clear(); persist() }

    private fun persist() {
        val c = app ?: return
        val o = JSONObject()
        for ((uid, card) in cards) o.put(uid, JSONObject().put("f", card.first).put("l", card.last).put("n", card.note))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
