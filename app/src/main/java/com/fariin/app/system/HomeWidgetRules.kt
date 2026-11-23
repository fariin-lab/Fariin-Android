package com.fariin.app.system

import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the home screen widget ([FariinWidget]) shows, with no Android types so the unit tests run
 * it. After Kulan-iOS WidgetShare (the Recent widget): the newest chats, leaving out cleared,
 * archived and blocked chats and requests not answered yet. Names and faces only, as on iOS: no
 * message words and no unread counts, whatever Message Preview says. With App Lock on nothing
 * personal is kept, only the lock itself.
 */
object HomeWidgetRules {
    /** Rows on the large widget; the small one shows the first [SMALL_ROWS]. */
    const val MAX_ROWS = 4
    const val SMALL_ROWS = 2
    /** A push within this long after a live publish is already in the list the app wrote. */
    const val LIVE_GRACE_MS = 10_000L

    data class Row(
        val cid: String,
        val name: String,
        val photo: String?,
        val group: Boolean = false,
        val channel: Boolean = false,
    )

    data class Snapshot(
        val signedIn: Boolean = false,
        val locked: Boolean = false,
        val rows: List<Row> = emptyList(),
        /** When the app's live chat list wrote this (ms); 0 = written from a push. */
        val liveAt: Long = 0,
    )

    /** The chats for the widget, newest first (iOS sorts the Recent widget by time only, no pins). */
    fun choose(convs: List<Conversation>, me: String, max: Int = MAX_ROWS): List<Conversation> =
        convs.asSequence()
            .filter { !it.isCleared(me) && !it.isArchived(me) && !it.isBlockedByMe(me) }
            .filter { !ChatList.isIncomingRequest(it, me) }
            .filter { it.displayName(me).isNotBlank() }
            .sortedByDescending { it.displayUpdatedAt(me) }
            .take(max)
            .toList()

    /** The snapshot to keep: with App Lock on, only the lock. */
    fun snapshot(convs: List<Conversation>, me: String, locked: Boolean, now: Long): Snapshot {
        if (locked) return Snapshot(signedIn = true, locked = true, liveAt = now)
        val rows = choose(convs, me).map { c ->
            Row(cid = c.id, name = c.displayName(me), photo = c.displayPhoto(me), group = c.isGroup, channel = c.isChannel)
        }
        return Snapshot(signedIn = true, locked = false, rows = rows, liveAt = now)
    }

    /** What a snapshot looks like on screen now: App Lock turned on since it was written still hides it. */
    fun masked(s: Snapshot, locked: Boolean): Snapshot = when {
        !s.signedIn -> Snapshot()
        locked || s.locked -> Snapshot(signedIn = true, locked = true, liveAt = s.liveAt)
        else -> s
    }

    /**
     * A chat push arrived while the app's list was not live: that chat goes to the top (newest
     * first, as the list will have it). Null back when nothing should change: locked, the chat is
     * not on the widget or already first, or the live list wrote it just now.
     */
    fun bump(s: Snapshot, cid: String, now: Long): Snapshot? {
        if (!s.signedIn || s.locked || now - s.liveAt < LIVE_GRACE_MS) return null
        val i = s.rows.indexOfFirst { it.cid == cid }
        if (i <= 0) return null
        return s.copy(rows = listOf(s.rows[i]) + s.rows.filterIndexed { j, _ -> j != i }, liveAt = 0)
    }

    /** A chat deleted, left or blocked (K-31): off the widget until the list says otherwise. Null = not on it. */
    fun drop(s: Snapshot, cid: String): Snapshot? =
        if (s.rows.none { it.cid == cid }) null else s.copy(rows = s.rows.filter { it.cid != cid })

    /** A short widget (the small size) draws smaller faces and a tighter header. */
    fun compact(heightDp: Float): Boolean = heightDp < 160f

    /**
     * How many chats fit in [heightDp]: the header and padding take 60 dp (50 compact), a row 48 dp
     * (38 compact). At least [SMALL_ROWS], at most [MAX_ROWS].
     */
    fun rowsFor(heightDp: Float): Int {
        val small = compact(heightDp)
        val fixed = if (small) 50f else 60f
        val row = if (small) 38f else 48f
        return ((heightDp - fixed) / row).toInt().coerceIn(SMALL_ROWS, MAX_ROWS)
    }

    /** Only redraw when what is shown changed. */
    fun signature(s: Snapshot): String = s.copy(liveAt = 0).toString()

    // ------------------------------------------------------------------ stored form

    fun encode(s: Snapshot): String = JSONObject().apply {
        put("v", 2); put("in", s.signedIn); put("lk", s.locked); put("at", s.liveAt)
        put("rows", JSONArray().apply {
            for (r in s.rows) put(JSONObject().apply {
                put("id", r.cid); put("name", r.name); r.photo?.let { put("ph", it) }; put("g", r.group); put("ch", r.channel)
            })
        })
    }.toString()

    /** Older files (v1) also kept words and counts: those keys are not read. */
    fun decode(text: String): Snapshot = try {
        val o = JSONObject(text)
        val a = o.optJSONArray("rows") ?: JSONArray()
        Snapshot(
            signedIn = o.optBoolean("in"), locked = o.optBoolean("lk"), liveAt = o.optLong("at"),
            rows = (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                Row(r.getString("id"), r.optString("name"), r.optString("ph").ifEmpty { null }, r.optBoolean("g"), r.optBoolean("ch"))
            },
        )
    } catch (_: Exception) { Snapshot() }
}
