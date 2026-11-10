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
        p.all.keys.filter { it == KEY || it == DONE || it.startsWith("$KEY.chat.") }.forEach { e.remove(it) }
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
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(chatKey(cid), if (on) "always" else "never").apply()
    }

    /** [id] is "cid/messageId". The id is claimed before the copy and given back if it fails. */
    fun save(c: Context, id: String, file: File, isVideo: Boolean) {
        val app = c.applicationContext
        if (!savesMedia(app, id.substringBefore('/')) || GallerySaver.needsPermission(app) || !claim(app, id)) return
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
