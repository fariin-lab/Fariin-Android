package com.fariin.app.system

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.fariin.app.MainActivity
import com.fariin.app.data.Conversation
import com.fariin.app.push.FariinMessagingService
import com.fariin.app.push.NotificationPoster
import com.fariin.app.push.NotifStore
import com.fariin.app.push.PushRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * The recent chats as long-lived conversation shortcuts (Android's way of knowing "who you talk
 * to"): one per chat with the person (or the group) and its face. Android uses them in three
 * places at once:
 *  - the share sheet's Direct Share row (category [SHARE_CATEGORY], matched by res/xml/shortcuts.xml),
 *  - the launcher's long-press menu (lowest ranks first, after the static New Chat and New Story),
 *  - the Conversations section of notification settings (each chat can get its own sound, priority
 *    and bubble), which needs the notification's shortcut id to name one of these.
 *
 * The id of a shortcut is the chat id, the same as the notification tag, so a notification and
 * the chat's shortcut are the same conversation.
 */
object ChatShortcuts {
    const val SHARE_CATEGORY = "com.fariin.app.category.SHARE_TARGET"
    /** How many recent chats are published (the launcher shows the first few, the share sheet more). */
    const val MAX_PUBLISHED = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastSignature: String? = null

    /** A chat as the shortcuts need it: pure data, so the choice of chats is testable. */
    data class Entry(
        val cid: String,
        val title: String,
        val isGroup: Boolean,
        /** The 1:1 partner (the Person's key) or null for a group. */
        val personUid: String?,
        val photo: String?,
        val updatedAt: Double,
        val pinned: Boolean,
    )

    /**
     * Which chats get a shortcut, in rank order: pinned chats first, then the newest. Left out:
     * channels (one can not share into a channel I only follow), requests not answered yet,
     * archived and blocked chats, chats with no name (a half-loaded row).
     */
    fun choose(convs: List<Conversation>, me: String, max: Int = MAX_PUBLISHED): List<Entry> =
        convs.asSequence()
            .filter { !it.isChannel && !it.isBlockedByMe(me) && !it.isArchived(me) }
            .filter { it.users.isEmpty() || me in it.users }
            .filter { it.isGroup || it.startedBy.isEmpty() || it.accepted }
            .map { Entry(it.id, it.displayName(me), it.isGroup, if (it.isGroup) null else it.otherUid(me),
                it.displayPhoto(me), it.updatedAtMillis, it.isPinned(me)) }
            .filter { it.title.isNotBlank() }
            .sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.updatedAt })
            .take(max)
            .toList()

    /** Only republish when the chosen chats, their order, names or photos changed. */
    fun signature(list: List<Entry>): String = list.joinToString("|") { "${it.cid}:${it.title}:${it.photo ?: ""}" }

    /** The chat list changed (MainActivity): publish the recent chats in the background. */
    fun publish(c: Context, me: String, convs: List<Conversation>) {
        val app = c.applicationContext
        val list = choose(convs, me)
        val sig = "$me#" + signature(list)
        if (sig == lastSignature) return
        lastSignature = sig
        scope.launch {
            val faces = list.map { e -> async { e.photo?.let { runCatching { FariinMessagingService.loadAvatar(app, it) }.getOrNull() } } }.awaitAll()
            val shortcuts = list.mapIndexed { i, e -> build(app, e.cid, e.title, e.isGroup, e.personUid, faces[i], rank = i) }
            try {
                // Shortcuts of chats that left the list go from the launcher; long-lived ones stay
                // known to the system for their notification settings (removeDynamic, not remove).
                val keep = shortcuts.map { it.id }.toSet()
                val gone = ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id }.filter { it !in keep }
                if (gone.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(app, gone)
                for (s in shortcuts) ShortcutManagerCompat.pushDynamicShortcut(app, s)
            } catch (_: Exception) {}
        }
    }

    /** The intent a chat shortcut (launcher, share sheet's chat, conversation settings) opens. */
    fun openIntent(c: Context, cid: String): Intent = Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        .putExtra(PushRouter.EXTRA_OPEN_CID, cid)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * One chat's shortcut. Also used by the notification ([NotificationPoster]) so both carry
     * the same id, person and face.
     */
    fun build(c: Context, cid: String, title: String, isGroup: Boolean, personUid: String?, photo: ByteArray?,
              rank: Int = 0, person: Person? = null): ShortcutInfoCompat {
        val icon = IconCompat.createWithBitmap(NotificationPoster.circle(photo, group = isGroup))
        val who = person ?: if (!isGroup && personUid != null)
            Person.Builder().setName(title).setKey(personUid).setIcon(icon).build() else null
        return ShortcutInfoCompat.Builder(c, cid)
            .setShortLabel(title)
            .setLongLabel(title)
            .setIcon(icon)
            .setIntent(openIntent(c, cid))
            .setLongLived(true)
            .setRank(rank)
            .setCategories(setOf(SHARE_CATEGORY))
            .setIsConversation()
            .apply { if (who != null) setPerson(who) }
            .build()
    }

    /** The chat a shortcut id names, for the share sheet's Direct Share target. */
    fun titleOf(c: Context, cid: String): String? = NotifStore.chat(c, cid)?.name

    /** Sign-out: no chat of the old account stays on the launcher or in the share sheet. */
    fun clear(c: Context) {
        lastSignature = null
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(c)
            ShortcutManagerCompat.removeLongLivedShortcuts(c, ShortcutManagerCompat.getShortcuts(c,
                ShortcutManagerCompat.FLAG_MATCH_CACHED).map { it.id })
        } catch (_: Exception) {}
    }
}
