package com.fariin.app.media

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Settings > Chats > Save to Photos (iOS AutoSaveToPhotos): received photos go into the phone's
 * gallery (Pictures/Fariin) as they come down, so nobody has to tap Save on each one.
 *
 * Only what others send: my own pictures are already on my phone. Never a view-once photo: the
 * sender asked for it to vanish, and a setting they cannot see must not keep it. Off by default.
 * Each message is saved once (a ledger of ids, capped), so opening a chat again copies nothing twice.
 */
object AutoSaveMedia {
    const val KEY = "chats.saveToPhotos"
    private const val PREFS = "fariin_chats"
    private const val DONE = "chats.saveToPhotos.done"
    private const val CAP = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isOn(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Sign-out (iOS SessionWipe): the Save to Photos choices, global and per chat, are this account's. */
    fun wipe(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        p.all.keys.filter { it == KEY || it == DONE || it == SINCE || it.startsWith("$KEY.chat.") }.forEach { e.remove(it) }
        e.apply()
    }

    // Per chat (iOS ChatChoice, Group Info's menu): "always" or "never" overrides the Settings
    // switch for that chat; nothing stored = follow Settings.
    private fun chatKey(cid: String) = "$KEY.chat.$cid"
    fun savesMedia(c: Context, cid: String): Boolean = !com.fariin.app.data.ChatRestrictions.isOn(cid) && savesMediaSetting(c, cid)

    private fun savesMediaSetting(c: Context, cid: String): Boolean =
        when (c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(chatKey(cid), null)) {
            "always" -> true
            "never" -> false
            else -> isOn(c)
        }
    fun setChat(c: Context, cid: String, on: Boolean) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val was = p.getString(chatKey(cid), null)
        val e = p.edit().putString(chatKey(cid), if (on) "always" else "never")
        // iOS setChoice: switching to Always starts saving now; Never has no start of its own.
        if (on) { if (was != "always") e.putLong(chatSinceKey(cid), System.currentTimeMillis()) }
        else e.remove(chatSinceKey(cid))
        e.apply()
    }

    // iOS AutoSaveToPhotos: only media sent after the switch went on, and in the last 7 days, is
    // saved. Turning it on (or opening an old chat) never pours the history into the gallery.
    private const val SINCE = "$KEY.since"
    private const val WINDOW_MS = 7 * 86_400_000L
    private const val LEGACY_GRACE_MS = 15 * 60_000L
    private fun chatSinceKey(cid: String) = "$KEY.chat.$cid.since"

    /** The Settings switch was turned on: saving starts now (iOS noteTurnedOn). */
    fun noteTurnedOn(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SINCE, System.currentTimeMillis()).apply()
    }

    /** From when this chat saves. A switch that was on before this build starts from the first look. */
    private fun sinceMs(c: Context, cid: String): Long {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (p.getString(chatKey(cid), null) == "always") chatSinceKey(cid) else SINCE
        var t = p.getLong(key, 0L)
        if (t <= 0L) { t = System.currentTimeMillis() - LEGACY_GRACE_MS; p.edit().putLong(key, t).apply() }
        return t
    }

    /**
     * [id] is "cid/messageId". The id is claimed before the copy and given back if it fails.
     * [sentAtMs]: when the message was sent; older than the start or the 7 day window is not saved.
     * [expiring]: the message has an expiry (`expiresAt`); it is never saved (K-26).
     */
    fun save(c: Context, id: String, file: File, isVideo: Boolean, sentAtMs: Double? = null, expiring: Boolean = false) {
        val app = c.applicationContext
        val cid = id.substringBefore('/')
        if (!savesMedia(app, cid) || GallerySaver.needsPermission(app)) return
        if (sentAtMs != null && sentAtMs < maxOf(sinceMs(app, cid), System.currentTimeMillis() - WINDOW_MS)) return
        if (!claim(app, id)) return
        // A disappearing message, or a chat with the timer on, never goes into the gallery: the
        // chat copy goes when the timer ends, a gallery copy would stay (K-26). The id stays
        // claimed, so turning the timer off later does not save the old media.
        if (expiring) return
        val timed = com.fariin.app.data.CryptoHub.listedConversation(cid)
        if (timed != null && timed.disappearSeconds > 0) return
        // Nothing from a message request nobody has accepted (iOS isUnansweredRequest). The id stays
        // claimed, so accepting later does not pour the old pictures in.
        val conv = com.fariin.app.data.CryptoHub.listedConversation(cid)
        if (conv != null && !conv.isGroup && conv.startedBy.isNotEmpty() && !conv.accepted) return
        scope.launch {
            if (!GallerySaver.save(app, file, isVideo)) release(app, id)
        }
    }

    @Synchronized
    private fun claim(c: Context, id: String): Boolean {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = p.getString(DONE, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        if (id in done) return false
        p.edit().putString(DONE, (done + id).takeLast(CAP).joinToString("\n")).apply()
        return true
    }

    @Synchronized
    private fun release(c: Context, id: String) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = p.getString(DONE, "").orEmpty().split('\n').filter { it.isNotEmpty() && it != id }
        p.edit().putString(DONE, done.joinToString("\n")).apply()
    }
}
