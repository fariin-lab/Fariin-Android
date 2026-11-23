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
import kotlinx.coroutines.isActive

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
    private const val LOCKED = "#applock"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** K-97: the publish still loading faces, cancelled by [clear]; [generation] counts clears. */
    @Volatile private var job: kotlinx.coroutines.Job? = null
    @Volatile private var generation = 0
    @Volatile private var lastSignature: String? = null
    /** The last list asked for, so App Lock turned off can publish it again at once. */
    @Volatile private var lastInput: Triple<String, List<Conversation>, List<Conversation>>? = null

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
     * deleted, archived and blocked chats, chats with no name (a half-loaded row).
     */
    fun choose(convs: List<Conversation>, me: String, max: Int = MAX_PUBLISHED): List<Entry> =
        convs.asSequence()
            .filter { !it.isChannel && !it.isBlockedByMe(me) && !it.isArchived(me) && !it.isCleared(me) }
            .filter { it.users.isEmpty() || me in it.users }
            .filter { it.isGroup || it.startedBy.isEmpty() || it.accepted }
            .map { Entry(it.id, it.displayName(me), it.isGroup, if (it.isGroup) null else it.otherUid(me),
                it.displayPhoto(me), it.updatedAtMillis, it.isPinned(me)) }
            .filter { it.title.isNotBlank() }
            .sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.updatedAt })
            .take(max)
            .toList()

    /**
     * Chats whose shortcut must go everywhere, notification settings too: deleted for me, blocked,
     * or a group I left. They stay gone until the chat comes back to my list.
     */
    fun dropped(convs: List<Conversation>, me: String): Set<String> =
        convs.filter { it.isCleared(me) || it.isBlockedByMe(me) || (it.isGroup && it.users.isNotEmpty() && me !in it.users) }
            .map { it.id }.toSet()

    /** Only republish when the chosen chats, their order, names or photos changed. */
    fun signature(list: List<Entry>): String = list.joinToString("|") { "${it.cid}:${it.title}:${it.photo ?: ""}" }

    /** The chat list changed (MainActivity): publish the recent chats in the background. [all] is
     *  every chat doc, deleted ones too (the shown list leaves them out), for [dropped]. */
    fun publish(c: Context, me: String, convs: List<Conversation>, all: List<Conversation> = convs) {
        val app = c.applicationContext
        if (com.fariin.app.Fb.auth.currentUser?.uid != me) return   // signing out: the old list must not come back
        lastInput = Triple(me, convs, all)
        // App Lock on: no chat names or faces on the launcher or in any share sheet. Cleared once per
        // run too, for shortcuts an older version left while App Lock was already on.
        if (!com.fariin.app.security.EntryRules.chatShortcuts(com.fariin.app.security.AppLock.enabled(app))) {
            if (lastSignature != LOCKED) { clear(app); lastSignature = LOCKED }
            return
        }
        val list = choose(convs, me)
        val drop = dropped(all, me)
        val sig = "$me#" + signature(list) + "#" + drop.sorted().joinToString(",")
        if (sig == lastSignature) return
        lastSignature = sig
        val gen = generation
        job?.cancel()
        job = scope.launch {
            val faces = list.map { e -> async { e.photo?.let { runCatching { FariinMessagingService.loadAvatar(app, it) }.getOrNull() } } }.awaitAll()
            val shortcuts = list.mapIndexed { i, e -> build(app, e.cid, e.title, e.isGroup, e.personUid, faces[i], rank = i) }
            // Signed out while the faces loaded: clear() already ran, publish nothing.
            if (!isActive || gen != generation || com.fariin.app.Fb.auth.currentUser?.uid != me) return@launch
            try {
                // A deleted, blocked or left chat leaves notification settings and the share sheet too.
                val known = ShortcutManagerCompat.getShortcuts(app,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED).map { it.id }.toSet()
                val remove = drop.filter { it in known }
                if (remove.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(app, remove)
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

    /**
     * App Lock switched: on takes every chat off the launcher and the share sheet, off publishes the
     * last list again. Only the visible (dynamic) shortcuts go: the long-lived cached ones hold each
     * chat's conversation settings (priority, bubbles), so they stay. clear() is for sign-out only.
     */
    fun appLockSwitched(c: Context, on: Boolean) {
        if (on) {
            lastSignature = null
            generation++
            job?.cancel(); job = null
            try { ShortcutManagerCompat.removeAllDynamicShortcuts(c) } catch (_: Exception) {}
            return
        }
        val (me, convs, all) = lastInput ?: return
        publish(c, me, convs, all)
    }

    /** Sign-out: no chat of the old account stays on the launcher or in the share sheet. */
    fun clear(c: Context) {
        lastSignature = null
        generation++
        job?.cancel(); job = null
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(c)
            ShortcutManagerCompat.removeLongLivedShortcuts(c, ShortcutManagerCompat.getShortcuts(c,
                ShortcutManagerCompat.FLAG_MATCH_CACHED).map { it.id })
        } catch (_: Exception) {}
    }
}
