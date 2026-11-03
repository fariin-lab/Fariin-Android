package com.fariin.app.push

/**
 * What a push says and what the notification shows: the logic of
 * Kulan-iOS/NotificationService/NotificationService.swift (`enrich`), with no Android types so the
 * unit tests run it on plain maps.
 *
 * Data keys the server sends (functions/pushPayload.js, index.js onNewMessage): `cid`, `aid` (the
 * sender), `mid`, `sn` (sender name; ABSENT when the recipient's Message Preview is off), `g` ("1"
 * for a group), `kind` (message type), `ct` (the CIPHERTEXT, enc1: or encg1: trimmed to my wrap),
 * `tx` (a plain group's words), `sp` (the chat's photo of the sender), `ackUid` + `ack` (1:1
 * delivered tick), `expiresAt` (ms, disappearing message). Title and body travel in the FCM
 * `notification` block today; a data-only push (see PROGRESS.md "Server changes needed") carries
 * them as `t` and `b`.
 */
data class PushData(
    val cid: String?,
    val aid: String?,
    val mid: String?,
    val senderName: String?,
    val isGroup: Boolean,
    val kind: String,
    val cipher: String?,
    val plainText: String?,
    val senderPhoto: String?,
    val ackUid: String?,
    val ack: String?,
    val expiresAtMs: Long?,
    val type: String?,
    val title: String,
    val body: String,
) {
    companion object {
        fun parse(data: Map<String, String>, notifTitle: String? = null, notifBody: String? = null): PushData {
            fun s(k: String) = data[k]?.takeIf { it.isNotEmpty() }
            return PushData(
                cid = s("cid"),
                aid = s("aid"),
                mid = s("mid"),
                senderName = s("sn"),
                isGroup = data["g"] == "1",
                kind = s("kind") ?: "text",
                cipher = s("ct"),
                plainText = s("tx"),
                senderPhoto = s("sp")?.takeIf { it.startsWith("https://") },
                ackUid = s("ackUid"),
                ack = s("ack"),
                expiresAtMs = s("expiresAt")?.toLongOrNull(),
                type = s("type"),
                title = notifTitle?.takeIf { it.isNotEmpty() } ?: s("t") ?: "Fariin",
                body = notifBody?.takeIf { it.isNotEmpty() } ?: s("b") ?: "New message",
            )
        }
    }
}

/** What the app saved about one chat for notifications (iOS NotifShared.Chat: g, n, p, m). */
data class ChatInfo(
    val isGroup: Boolean,
    /** 1:1: the name this phone shows for the other person. Group: the group's name. */
    val name: String?,
    /** 1:1: the other person's photo. Group: the group photo. */
    val photo: String?,
    /** Group only: member uid to photo. */
    val members: Map<String, String> = emptyMap(),
)

enum class NotifChannel(val id: String, val title: String) {
    MESSAGES("messages", "Messages"),
    CALLS("calls", "Calls"),
}

/** One notification, ready to draw. */
data class NotifSpec(
    /** The chat it belongs to (also the notification tag and the conversation shortcut id). */
    val key: String,
    /** True when it routes to a real chat on tap (a story has no chat). */
    val opensChat: Boolean,
    val channel: NotifChannel,
    /** The title line: the group's name, or the sender's. */
    val title: String,
    /** The message line, as a conversation shows it under the sender's name (no "Name: "). */
    val line: String,
    /** The flat body iOS shows ("Name: text" in a group). */
    val body: String,
    /** No person, no style: Message Preview off or not a chat push. Drawn as a plain notification. */
    val plain: Boolean,
    val senderUid: String,
    val senderName: String,
    val isGroup: Boolean,
    val groupName: String?,
    val senderPhoto: String?,
    val groupPhoto: String?,
    val mid: String?,
    val expiresAtMs: Long?,
)

sealed class NotifDecision {
    data class Skip(val why: String) : NotifDecision()
    data class Show(val spec: NotifSpec) : NotifDecision()
}

object NotifBuilder {
    /** The server's call fallback (index.js ringFallback) says only this in its body. */
    private val CALL_BODIES = setOf("Incoming call", "Incoming video call")

    /** Captions keep the kind's sign in front (NotificationService.prefix). */
    fun prefix(kind: String): String = when (kind) {
        "image", "album" -> "📷 "
        "video" -> "📹 "
        "file" -> "📎 "
        else -> ""
    }

    /** The crypto's "could not open" answers: never shown, the server's hint stays instead. */
    private val SENTINELS = setOf("…", "🔒", "[old message]")
    fun usable(opened: String?): String? = opened?.takeIf { it.isNotBlank() && it !in SENTINELS }

    /**
     * @param me          the account signed in here ("" when nobody is)
     * @param chats       the app's saved chat details
     * @param open        opens `ct` for (cid, sender); null on any miss
     * @param activeChat  the chat open on screen right now, if any
     * @param pushOn      the Show Notifications switch
     */
    fun decide(
        p: PushData,
        me: String,
        chats: (String) -> ChatInfo?,
        open: (cipher: String, cid: String, sender: String) -> String?,
        activeChat: String? = null,
        pushOn: Boolean = true,
        /** Somebody I blocked (BlockList); empty when the app is not running, as iOS's background. */
        blocked: (String) -> Boolean = { false },
    ): NotifDecision {
        if (!pushOn) return NotifDecision.Skip("notifications switched off")
        // A signed-out phone shows nothing: its tokens left with the sign-out, and a late push would
        // name somebody else's contacts on a phone nobody is signed in to.
        if (me.isEmpty()) return NotifDecision.Skip("signed out")
        // My own message coming back (a token that also sits under my account), PushManager 2026-10-29.
        if (p.aid != null && p.aid == me) return NotifDecision.Skip("own message")
        // Nothing from somebody I blocked, even a push already on its way when I blocked them
        // (PushManager 2026-09-26). The sender is `aid`, or the other half of a 1:1 id.
        val from = p.aid ?: p.cid?.takeIf { '_' in it }?.split('_')?.firstOrNull { it != me }
        if (from != null && blocked(from)) return NotifDecision.Skip("blocked sender")
        // A 1:1 push names its recipient; one for another account is not for this phone.
        if (p.ackUid != null && p.ackUid != me) return NotifDecision.Skip("for another account")
        if (p.cid != null && p.cid == activeChat) return NotifDecision.Skip("chat is open")

        val channel = if (p.kind == "call" || p.body in CALL_BODIES) NotifChannel.CALLS else NotifChannel.MESSAGES
        val isStory = p.kind == "story"
        val key = p.cid ?: if (isStory && p.aid != null) "story-${p.aid}" else null
        val sender = p.aid
        val sn = p.senderName
        // No sender, no name (Message Preview off) or no chat: the banner exactly as the server sent it.
        if (key == null || sender == null || sn == null) {
            return NotifDecision.Show(NotifSpec(
                key = key ?: "fariin", opensChat = p.cid != null, channel = channel,
                title = p.title, line = p.body, body = p.body, plain = true,
                senderUid = sender ?: "", senderName = p.title, isGroup = p.isGroup, groupName = null,
                senderPhoto = null, groupPhoto = null, mid = p.mid, expiresAtMs = p.expiresAtMs,
            ))
        }

        val chat = chats(key)
        val direct = chats(Wire1to1.cid(me, sender)) ?: chats(Wire1to1.cid(sender, me))

        var text: String? = null
        if (p.cipher != null) text = usable(open(p.cipher, key, sender))
        if (text == null && p.isGroup) text = p.plainText
        val shownSender = direct?.name?.takeIf { it.isNotEmpty() } ?: sn

        val line: String
        val body: String
        if (!text.isNullOrEmpty()) {
            line = prefix(p.kind) + text
            body = if (p.isGroup) "$shownSender: $line" else line
        } else {
            // The server's hint. In a group it reads "Sender: hint"; the conversation already shows
            // the sender's name, so the line drops it.
            body = p.body
            line = if (p.isGroup) p.body.removePrefix("$sn: ").removePrefix("$shownSender: ") else p.body
        }
        val groupName = chat?.name?.takeIf { it.isNotEmpty() } ?: p.title
        val title = if (p.isGroup) groupName else shownSender

        val senderPhoto = if (p.isGroup) {
            chat?.members?.get(sender) ?: direct?.photo ?: p.senderPhoto
        } else {
            chat?.photo ?: (if (isStory) direct?.photo else null) ?: p.senderPhoto
        }
        return NotifDecision.Show(NotifSpec(
            key = key, opensChat = p.cid != null, channel = channel,
            title = title, line = line, body = body, plain = false,
            senderUid = sender, senderName = shownSender, isGroup = p.isGroup,
            groupName = if (p.isGroup) groupName else null,
            senderPhoto = senderPhoto, groupPhoto = if (p.isGroup) chat?.photo else null,
            mid = p.mid, expiresAtMs = p.expiresAtMs,
        ))
    }
}

/** The 1:1 chat id rule (`uidA_uidB`), kept here so this file stays free of the crypto package. */
internal object Wire1to1 {
    fun cid(a: String, b: String) = "${a}_$b"
}
