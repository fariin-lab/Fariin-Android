package com.fariin.app.system

import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the home screen widget ([FariinWidget]) shows, with no Android types so the unit tests run
 * it. After Kulan-iOS WidgetShare (the Recent widget): the newest chats, leaving out cleared,
 * archived and blocked chats and requests not answered yet. With App Lock on nothing personal is
 * kept: no names, no faces, no words, only the count of unread chats. With Message Preview off
 * (Settings > Notifications, the same switch the notifications follow) the names stay and the
 * words go.
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
        /** The short line under the name, or null when the words are hidden (Message Preview off). */
        val preview: String?,
        val unread: Int,
        /** Marked unread by hand: a dot, no number. */
        val markedUnread: Boolean = false,
        val muted: Boolean = false,
        val group: Boolean = false,
        val channel: Boolean = false,
    )

    data class Snapshot(
        val signedIn: Boolean = false,
        val locked: Boolean = false,
        /** Chats with something unread (the Chats tab's number). */
        val unreadChats: Int = 0,
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

    /** Chats with an unread mark, blocked ones not counted (as the Chats tab badge). */
    fun unreadChats(convs: List<Conversation>, me: String): Int =
        convs.count { !it.isBlockedByMe(me) && !it.isArchived(me) && !ChatList.isIncomingRequest(it, me) && it.hasUnreadMark(me) }

    /**
     * The snapshot to keep. [previewOf] opens a chat's last line (called only for the chosen rows
     * and only when the words may show).
     */
    fun snapshot(
        convs: List<Conversation>, me: String, locked: Boolean, showPreview: Boolean, now: Long,
        nowMillis: Double = now.toDouble(), previewOf: (Conversation) -> String,
    ): Snapshot {
        val count = unreadChats(convs, me)
        if (locked) return Snapshot(signedIn = true, locked = true, unreadChats = count, liveAt = now)
        val rows = choose(convs, me).map { c ->
            Row(
                cid = c.id, name = c.displayName(me), photo = c.displayPhoto(me),
                preview = if (showPreview) previewOf(c).replace('\n', ' ').take(120) else null,
                unread = c.unread(me), markedUnread = c.unread(me) == 0 && c.manuallyUnread(me),
                muted = c.isMuted(me, nowMillis), group = c.isGroup, channel = c.isChannel,
            )
        }
        return Snapshot(signedIn = true, locked = false, unreadChats = count, rows = rows, liveAt = now)
    }

    /**
     * What a snapshot looks like on screen now: the lock or a Message Preview switch turned since
     * it was written still hides what it must. Never shows more than was kept.
     */
    fun masked(s: Snapshot, locked: Boolean, showPreview: Boolean): Snapshot = when {
        !s.signedIn -> Snapshot()
        locked || s.locked -> Snapshot(signedIn = true, locked = true, unreadChats = s.unreadChats, liveAt = s.liveAt)
        !showPreview -> s.copy(rows = s.rows.map { it.copy(preview = null) })
        else -> s
    }

    /**
     * A chat push arrived while the app's list was not live: that chat gets one more unread and
     * goes to the top, its line replaced by [line] (null = keep the words hidden). Null back when
     * nothing should change: the chat is not on the widget, or the live list wrote it just now
     * (and so already counts this message).
     */
    fun bump(s: Snapshot, cid: String, line: String?, showPreview: Boolean, now: Long): Snapshot? {
        if (!s.signedIn || now - s.liveAt < LIVE_GRACE_MS) return null
        if (s.locked) return s.copy(liveAt = 0)   // the count of chats is not known to grow: a chat may already be unread
        val i = s.rows.indexOfFirst { it.cid == cid }
        if (i < 0) return null
        val old = s.rows[i]
        val row = old.copy(unread = old.unread + 1, markedUnread = false,
            preview = if (showPreview) line?.replace('\n', ' ')?.take(120) ?: old.preview else null)
        val wasUnread = old.unread > 0 || old.markedUnread
        return s.copy(rows = listOf(row) + s.rows.filterIndexed { j, _ -> j != i },
            unreadChats = s.unreadChats + if (wasUnread) 0 else 1, liveAt = 0)
    }

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

    /** The badge text: the number, 99+ above that. */
    fun countLabel(n: Int): String = if (n > 99) "99+" else n.toString()

    /** Only redraw when what is shown changed. */
    fun signature(s: Snapshot): String = s.copy(liveAt = 0).toString()

    // ------------------------------------------------------------------ stored form

    fun encode(s: Snapshot): String = JSONObject().apply {
        put("v", 1); put("in", s.signedIn); put("lk", s.locked); put("n", s.unreadChats); put("at", s.liveAt)
        put("rows", JSONArray().apply {
            for (r in s.rows) put(JSONObject().apply {
                put("id", r.cid); put("name", r.name); r.photo?.let { put("ph", it) }; r.preview?.let { put("pv", it) }
                put("u", r.unread); put("mu", r.markedUnread); put("m", r.muted); put("g", r.group); put("ch", r.channel)
            })
        })
    }.toString()

    fun decode(text: String): Snapshot = try {
        val o = JSONObject(text)
        val a = o.optJSONArray("rows") ?: JSONArray()
        Snapshot(
            signedIn = o.optBoolean("in"), locked = o.optBoolean("lk"), unreadChats = o.optInt("n"), liveAt = o.optLong("at"),
            rows = (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                Row(r.getString("id"), r.optString("name"), r.optString("ph").ifEmpty { null }, if (r.has("pv")) r.getString("pv") else null,
                    r.optInt("u"), r.optBoolean("mu"), r.optBoolean("m"), r.optBoolean("g"), r.optBoolean("ch"))
            },
        )
    } catch (_: Exception) { Snapshot() }
}
