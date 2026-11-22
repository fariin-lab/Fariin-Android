package com.fariin.app.data

/*
 * Customize screenshot (iOS CustomScreenshot). After a screenshot of a 1:1 or group chat, a bar
 * offers to redo the picture: take messages out, hide their words, pick a background, then share.
 * Everything works on a COPY of the rows that were on screen and already opened on this phone.
 * Nothing is fetched and nothing is uploaded; the picture is made only when the person taps Share.
 */
object ScreenshotRules {
    /** How long the "Screenshot saved" bar stays (iOS: 5 s). */
    const val BAR_MS = 5_000L
    /** How long the Undo capsule stays after a message is taken out (iOS: 4 s). */
    const val UNDO_MS = 4_000L
    /** The file name start of a shared picture in cache/open (removed with the other temp copies). */
    const val FILE_PREFIX = "Fariin_shot_"

    /**
     * iOS offersCustomScreenshot: 1:1 chats and groups only. Never a channel, the Fariin chat, a
     * visitor of a public group, or a chat whose content is protected (Restricted chat, a group's
     * Protect Content: both fold into [restricted]). Never while the window blocks screenshots
     * (FLAG_SECURE: App Lock, Screen Security, a view-once open): the picture is blank there and
     * redoing it would undo the protection.
     */
    fun offers(isChannel: Boolean, isOfficial: Boolean, isGuest: Boolean, restricted: Boolean, windowSecure: Boolean): Boolean =
        !isChannel && !isOfficial && !isGuest && !restricted && !windowSecure

    enum class Kind { TEXT, SYSTEM, MEDIA }

    /** One message as the picture draws it: a plain copy, so later changes to the chat never reach it. */
    data class Shot(
        val id: String,
        val kind: Kind,
        /** The words, or the media label ("Photo", "Voice message") for [Kind.MEDIA]. */
        val text: String,
        val isMe: Boolean,
        val authorId: String,
        /** Someone else's name in a group; drawn only where a run starts ([showName]). */
        val senderName: String?,
        val timeMs: Double,
        val tick: Tick,
        val day: String,
        val replyName: String?,
        val replyText: String?,
        val edited: Boolean,
        val forwarded: Boolean,
    )

    /** Text the phone could not open: never put in a picture. */
    private val unreadable = setOf(com.fariin.app.crypto.Wire.LOCKED, com.fariin.app.crypto.Wire.NOT_READY,
        com.fariin.app.crypto.Wire.OLD_MESSAGE, Message.NOT_ENCRYPTED)

    /** iOS ScreenshotSnapshot.kind: null leaves the message out of the picture. */
    fun kindOf(m: Message): Kind? {
        // Content never meant to be kept stays out: view-once, deleted, calls, rows this phone cannot read.
        if (m.viewOnce || m.deleted || m.isCall || m.isRestrictRequest) return null
        if (m.text in unreadable) return null
        if (m.isSystem) return if (m.text.isEmpty()) null else Kind.SYSTEM
        if (m.poll != null) return Kind.MEDIA
        val media = m.type in setOf("image", "video", "audio", "file", "album", "gif", "sticker") ||
            m.imageUrl != null || m.videoUrl != null || m.audioUrl != null || m.fileUrl != null
        if (media) return Kind.MEDIA
        // A kind this picture does not draw (location, contact...): a neutral chip, never a guess.
        if (!m.type.isNullOrEmpty() && m.type != "text") return if (m.text.isEmpty()) Kind.MEDIA else Kind.TEXT
        return if (m.text.isEmpty()) null else Kind.TEXT
    }

    /** The label of a media row (iOS uses the same words). */
    fun mediaLabel(m: Message): String = when {
        m.poll != null -> "Poll"
        m.type == "sticker" -> m.stickerEmoji?.takeIf { it.isNotEmpty() } ?: "Sticker"
        m.type == "album" -> "Photos"
        m.type == "gif" -> "GIF"
        m.type == "image" || (m.imageUrl != null && m.videoUrl == null) -> "Photo"
        m.type == "video" || m.videoUrl != null -> "Video"
        m.type == "audio" || m.audioUrl != null -> "Voice message"
        m.type == "file" || m.fileUrl != null -> m.fileName ?: "File"
        else -> "Message"
    }

    /**
     * The copy of what was on screen, oldest first: [messages] in chat order, kept only when their
     * row id is in [onScreen].
     */
    fun snapshot(
        messages: List<Message>, onScreen: Set<String>, me: String, isGroup: Boolean, receipts: Receipts,
        name: (String) -> String, day: (Double) -> String,
    ): List<Shot> = messages.filter { it.rowId in onScreen }.mapNotNull { m ->
        val kind = kindOf(m) ?: return@mapNotNull null
        val isMe = m.authorId == me
        val caption = if (kind == Kind.MEDIA && m.poll == null && m.type != "sticker" && m.text.isNotEmpty()) m.text else null
        Shot(
            id = m.rowId, kind = kind,
            text = if (kind == Kind.MEDIA) (caption?.let { "${mediaLabel(m)}: $it" } ?: mediaLabel(m)) else m.text,
            isMe = isMe, authorId = m.authorId,
            senderName = if (isGroup && !isMe && kind != Kind.SYSTEM) name(m.authorId) else null,
            timeMs = minOf(m.createdAtMillis, System.currentTimeMillis().toDouble()),
            tick = ThreadLogic.tick(m, me, receipts),
            day = day(m.createdAtMillis),
            replyName = m.replyTo?.let { name(it.authorId) },
            replyText = m.replyTo?.let { it.text.ifEmpty { "Message" } },
            edited = m.edited, forwarded = m.forwarded,
        )
    }

    /** The editor's changes, kept apart from the copy. [removed] newest last, so Undo brings back the last one. */
    data class Edits(val removed: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
        fun remove(id: String) = if (id in removed) this else copy(removed = removed + id)
        fun undo() = if (removed.isEmpty()) this else copy(removed = removed.dropLast(1))
        fun toggleHidden(id: String) = copy(hidden = if (id in hidden) hidden - id else hidden + id)
        fun rows(shots: List<Shot>): List<Shot> = removed.toSet().let { gone -> shots.filter { it.id !in gone } }
        fun isHidden(id: String) = id in hidden
    }

    /** Where a row starts a new day (its pill) and where a group sender's name shows. */
    data class Place(val showDay: Boolean, val showName: Boolean)

    fun placements(rows: List<Shot>): List<Place> = rows.mapIndexed { i, s ->
        val prev = rows.getOrNull(i - 1)
        val newDay = prev == null || prev.day != s.day
        val runStart = newDay || prev!!.kind == Kind.SYSTEM || prev.authorId != s.authorId
        Place(showDay = newDay, showName = s.senderName != null && runStart)
    }

    /** Hidden words are never drawn: dots stand in their place, so nothing can be read back from the picture. */
    fun coverText(text: String): String {
        val n = text.codePointCount(0, text.length).coerceIn(3, 24)
        return "•".repeat(n)
    }

    /** The header line under the title (iOS countLine, one picture on Android). */
    fun countLine(messages: Int): String = if (messages == 1) "1 message" else "$messages messages"
}
