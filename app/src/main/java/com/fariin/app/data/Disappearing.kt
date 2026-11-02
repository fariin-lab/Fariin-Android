package com.fariin.app.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Disappearing messages, as Kulan-iOS does them (ChatService.setDisappear / disappearLabel,
 * DisappearingMessagesView, ThreadRepository burn, ConversationsRepository burnedIfDue).
 *
 * The timer is `conversations/{cid}.disappearSeconds` (0 = off), shared by every member. A message
 * carries NO field from the sender: the server's `onNewMessage` stamps `expiresAt` on it (and
 * `lastExpiresAt` on the chat) from the chat's timer, and the rules refuse `expiresAt` from any
 * phone. The phone only hides what is due, at the second it falls due, then asks the server to
 * delete (`burnExpiredMessages`); the five-minute server sweep is the safety net.
 *
 * A timer change is never silent: a system line with the value attached (`disappearSeconds`), so
 * each phone words it for its own reader ("You set..." / "<name> set...").
 */
object Disappearing {
    /** iOS UserDefaults key, same name. */
    const val DEFAULT_KEY = "defaultDisappearSeconds"
    private const val PREFS = "fariin_chats"

    /** DisappearingMessagesView presets, in its order. */
    val presets: List<Pair<String, Int>> = listOf(
        "Off" to 0, "4 weeks" to 2_419_200, "1 week" to 604_800, "1 day" to 86_400,
        "8 hours" to 28_800, "1 hour" to 3_600, "5 minutes" to 300, "30 seconds" to 30,
    )

    /** NewGroupView's short list. */
    val newGroupChoices: List<Pair<String, Int>> = listOf(
        "Off" to 0, "1 Day" to 86_400, "1 Week" to 604_800, "1 Month" to 2_592_000,
    )

    /** CustomTimePicker units: name, seconds, allowed numbers. */
    data class Unit(val name: String, val seconds: Int, val range: IntRange)
    val units = listOf(
        Unit("Seconds", 1, 30..59), Unit("Minutes", 60, 1..59), Unit("Hours", 3_600, 1..23),
        Unit("Days", 86_400, 1..30), Unit("Weeks", 604_800, 1..12),
    )
    const val FLOOR_SECONDS = 30

    /** The custom picker's start: the largest whole unit that fits, never below the floor. */
    fun customSeed(seconds: Int): Pair<Unit, Int> {
        val s = maxOf(FLOOR_SECONDS, seconds)
        val u = units.reversed().firstOrNull { s % it.seconds == 0 && s / it.seconds >= 1 } ?: units.first()
        return u to (s / u.seconds).coerceIn(u.range)
    }

    fun isPreset(seconds: Int) = presets.any { it.second == seconds }

    /** ChatService.disappearLabel: "8 hours", "1 week", "Off". */
    fun label(seconds: Int): String {
        if (seconds <= 0) return "Off"
        val units = listOf(604_800 to "week", 86_400 to "day", 3_600 to "hour", 60 to "minute", 1 to "second")
        for ((size, name) in units) if (seconds % size == 0) { val n = seconds / size; return "$n $name${if (n == 1) "" else "s"}" }
        for ((size, name) in units) if (seconds >= size) { val n = seconds / size; return "$n $name${if (n == 1) "" else "s"}" }
        return "$seconds seconds"
    }

    /** ChatService.disappearShortLabel: 30s, 5m, 8h, 1d, 4w. */
    fun shortLabel(seconds: Int): String {
        if (seconds <= 0) return "Off"
        val units = listOf(604_800 to "w", 86_400 to "d", 3_600 to "h", 60 to "m", 1 to "s")
        for ((size, s) in units) if (seconds % size == 0) return "${seconds / size}$s"
        for ((size, s) in units) if (seconds >= size) return "${seconds / size}$s"
        return "${seconds}s"
    }

    /** NewGroupView's row value: its own label, else the long label capitalised ("8 Hours"). */
    fun newGroupLabel(seconds: Int): String =
        newGroupChoices.firstOrNull { it.second == seconds }?.first
            ?: label(seconds).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** The stored sentence (chat list, older builds): the writer's name baked in. */
    fun storedLine(name: String, seconds: Int): String =
        if (seconds > 0) "$name set disappearing message time to ${label(seconds)}."
        else "$name turned off disappearing messages."

    /** The line as each reader sees it (MessageRowModelBuilder.systemNotice). */
    fun noticeLine(who: String, seconds: Int): String =
        if (seconds > 0) "$who set disappearing message time to ${label(seconds)}."
        else "$who turned off disappearing messages."

    /** DisappearingMessagesView header. */
    fun pageHeader(isChannel: Boolean): String =
        if (isChannel) "New posts in this channel disappear for every follower once the chosen time has passed since they were posted."
        else "New messages in this chat disappear once the chosen time has passed since they were sent, whether or not they have been read."

    const val NEW_GROUP_FOOTER = "New messages in this group disappear for everyone once this time has passed."
    const val SETTINGS_FOOTER = "Automatically delete messages for everyone after a period of time in all new chats you start."
    const val NOT_CHANGED = "The disappearing message timer was not changed. Check your connection and try again."
    const val CONFIRM_TITLE = "Turn on disappearing messages?"
    fun confirmText(seconds: Int): String =
        "New messages in this group will be deleted for all members after ${label(seconds)}. Everyone will see that you turned this on."

    /** A message the timer has taken (its server stamp has passed). Hiding only; the server deletes. */
    fun isExpired(m: Message, nowMs: Double): Boolean = m.expiresAtMillis?.let { nowMs >= it } ?: false

    /**
     * The live line under Delete in the long-press menu (iOS CMActionRow.remaining): whole days
     * once a day or more is left (rounded), then h:mm:ss, then m:ss. Never below zero.
     */
    fun countdown(leftMs: Double): String {
        val total = (maxOf(0.0, leftMs) / 1000).toLong()
        if (total >= 86_400) {
            val days = Math.round(total / 86_400.0)
            return "Disappears in $days ${if (days == 1L) "day" else "days"}"
        }
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "Disappears in %d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.ROOT, "Disappears in %d:%02d", m, s)
    }

    /** The soonest stamp still ahead, or null. */
    fun nextExpiry(messages: Collection<Message>, nowMs: Double): Double? =
        messages.mapNotNull { it.expiresAtMillis }.filter { it > nowMs }.minOrNull()

    /**
     * MessageRowModelBuilder.provisionalExpiry: a message the server has not stamped yet, written
     * after the notice that turned the current timer on, draws the timer mark from the first frame.
     * Drawing only; hiding waits for the real stamp. [sinceMs] null = that notice is not loaded.
     */
    fun shownExpiry(m: Message, timerSeconds: Int, sinceMs: Double?, nowMs: Double): Double? {
        if (m.deleted || m.isSystem) return null
        m.expiresAtMillis?.let { return it }
        if (timerSeconds <= 0) return null
        val since = sinceMs ?: (nowMs - 120_000)
        if (m.createdAtMillis < since) return null
        return m.createdAtMillis + timerSeconds * 1000.0
    }

    /** When the newest timer notice that turned the timer ON was written (ThreadView timerSince). */
    fun timerSince(messages: List<Message>): Double? {
        val last = messages.lastOrNull { it.disappearSeconds != null } ?: return null
        return if ((last.disappearSeconds ?: 0) > 0) last.createdAtMillis else null
    }

    /** ConversationsRepository.burnedIfDue: the list's copy of a summary whose timer ran out, blanked. */
    fun burnedIfDue(c: Conversation, nowMs: Double): Conversation {
        val due = c.lastExpiresAtMillis ?: return c
        if (due > nowMs) return c
        return c.copy(lastMessageCipher = "", lastImageUrl = null, lastImageEnc = null)
    }

    /** GroupSettingsView.canEdit: not closed, and the owner or an admin with Change info. */
    fun canChangeInGroup(conv: Map<String, Any?>, me: String, nowMs: Double = System.currentTimeMillis().toDouble()): Boolean {
        if (me.isEmpty()) return false
        // Closed: ended by its owner, or disabled / suspended by Fariin (the same test as Edit Group).
        val mod = conv["mod"] as? Map<*, *>
        val state = mod?.get("state") as? String ?: "ok"
        if (state == "ended" || state == "disabled") return false
        if (state == "suspended") {
            val until = (mod?.get("until") as? com.google.firebase.Timestamp)?.toDate()?.time?.toDouble() ?: 0.0
            if (until <= 0 || until > nowMs) return false
        }
        return PinRules.adminCan(conv, me, "changeInfo")
    }

    /** What a tap says to someone who may not change it (GroupSettingsView chatSection). */
    fun refusal(conv: Map<String, Any?>, me: String): String {
        val admins = (conv["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return if (me in admins) "You need the Change info right to change this." else "Only admins can change this."
    }

    // ------------------------------------------------------------------ the Settings default

    @Volatile private var appContext: Context? = null
    fun init(c: Context) { appContext = c.applicationContext }

    /** The Settings default for new chats I start (0 = off). */
    var defaultSeconds: Int
        get() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(DEFAULT_KEY, 0) ?: 0
        set(v) { appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putInt(DEFAULT_KEY, v)?.apply() }

    // ------------------------------------------------------------------ writes

    /**
     * ChatService.setDisappear: no change, no write; else the timer, then the system line with the
     * value attached. Answers whether the timer is now [seconds]; a refused write posts no line.
     */
    suspend fun set(cid: String, seconds: Int, me: String, myName: String,
                    db: FirebaseFirestore = FirebaseFirestore.getInstance()): Boolean {
        val ref = db.collection("conversations").document(cid)
        val current = try { (ref.get().await().get("disappearSeconds") as? Number)?.toInt() ?: 0 } catch (_: Exception) { 0 }
        if (current == seconds) return true
        try { ref.set(mapOf("disappearSeconds" to seconds), SetOptions.merge()).await() } catch (_: Exception) { return false }
        val text = storedLine(myName.ifEmpty { "Someone" }, seconds)
        try {
            val batch = db.batch()
            batch.set(ref.collection("messages").document(), mapOf(
                "text" to text, "authorId" to me, "type" to "system",
                "createdAt" to FieldValue.serverTimestamp(), "disappearSeconds" to seconds))
            batch.update(ref, mapOf("lastMessage" to text, "lastSender" to me, "updatedAt" to FieldValue.serverTimestamp()))
            batch.commit().await()
        } catch (_: Exception) {}
        return true
    }

    /** The chat list's burn tick: notifications of messages past their stamp go (iOS
     *  NotificationCleaner.pruneExpired). [due] = chats whose newest message is due, to the
     *  chat's updatedAt and that message's stamp. */
    fun pruneNotifications(due: Map<String, Pair<Double, Double>>) {
        val c = appContext ?: return
        try { com.fariin.app.push.NotificationPoster.pruneExpired(c, due) } catch (_: Exception) {}
    }

    private val burnAsked = ConcurrentHashMap<String, Long>()

    /** Ask the server to delete what is due (`burnExpiredMessages`); fire and forget. Empty [ids]:
     *  the server finds them (the chat list's ask). One ask per chat per 20 s. */
    fun askBurn(cid: String, ids: List<String>) {
        val now = System.currentTimeMillis()
        val key = cid + if (ids.isEmpty()) "" else ids.sorted().joinToString(",")
        burnAsked[key]?.let { if (now - it < 20_000) return }
        burnAsked[key] = now
        try {
            FirebaseFunctions.getInstance("me-central1").getHttpsCallable("burnExpiredMessages")
                .call(mapOf("cid" to cid, "ids" to ids.take(200)))
        } catch (_: Exception) {}
    }
}
