package com.fariin.app.data

/**
 * Opens the sealed last line of a chat for the list preview. The real one will come from the
 * crypto package (a port of Kulan-iOS/Kulan/Services/Crypto.swift); until it is wired, the
 * placeholder returns null and the row says "Message".
 */
fun interface MessageDecryptor {
    /** Plain text of [conv]'s last message as [me] may read it, or null when it cannot be opened. */
    fun previewText(conv: Conversation, me: String): String?

    /** Any other sealed line of [conv] (a reaction's emoji, the words it was on), written by [author]. */
    fun open(conv: Conversation, sealed: String, author: String): String? = null

    /** [previewText] without opening a box: [ChatList.PEEK_MISS] when it would have to. The
     *  default is the real call, for openers that do no crypto work. */
    fun peekPreview(conv: Conversation, me: String): String? = previewText(conv, me)

    /** [open] without opening a box: [ChatList.PEEK_MISS] when it would have to. */
    fun peekOpen(conv: Conversation, sealed: String, author: String): String? = open(conv, sealed, author)
}

object NoDecryptor : MessageDecryptor {
    override fun previewText(conv: Conversation, me: String): String? = null
}

/** What the chat list shows and in what order. Mirrors `ChatsView.visible` in MainShell.swift. */
object ChatList {

    /** A pending request from somebody else lives on the Message Requests page, not in the list. */
    fun isIncomingRequest(c: Conversation, me: String): Boolean {
        if (c.isGroup || c.reqOff) return false
        if (c.startedBy.isEmpty() || c.accepted) return false
        // TODO: iOS also asks MsgPolicy (the person's "message requests on" switch).
        return c.lastSender.isNotEmpty() && c.lastSender != me
    }

    /** [keep]: chats with a draft (or a live call) that stay listed with nothing said yet (iOS). */
    fun visible(all: List<Conversation>, me: String, keep: Set<String> = emptySet()): List<Conversation> =
        all.asSequence()
            .filter { !it.isCleared(me) && !it.isArchived(me) }
            .filter { !isIncomingRequest(it, me) }
            // A 1:1 chat merely opened, with nothing ever said, stays out until something real
            // happens: a message either way, an unread, a pin, a draft or a call. Groups always list.
            .filter { it.isGroup || it.lastMessageCipher.isNotEmpty() || it.hasUnreadMark(me) || it.isPinned(me) || it.id in keep }
            .sortedWith { a, b ->
                val ap = a.isPinned(me)
                val bp = b.isPinned(me)
                when {
                    ap != bp -> if (ap) -1 else 1
                    ap && bp && a.pinRank(me) != b.pinRank(me) -> b.pinRank(me).compareTo(a.pinRank(me))
                    else -> b.listSortDate(me).compareTo(a.listSortDate(me))
                }
            }
            .toList()

    /** The small icon iOS draws before some preview lines (MainShell previewRow): the line's
     *  words after any "Name: " sender prefix decide it. */
    enum class PreviewIcon {
        PIN, CONTACT, LOCATION, POLL, MIC, VIEW_ONCE, DELETED, VIDEO, PHOTO, ALBUM,
        FILE, FILE_ZIP, FILE_DOC, FILE_AUDIO, GIF,
        CALL, CALL_OUT, CALL_IN, VIDEO_CALL, VIDEO_CALL_OUT, VIDEO_CALL_IN,
        NEWER, HELLO, BLOCKED,
    }
    fun previewIcon(line: String): PreviewIcon? = when (line.substringAfter(": ", line)) {
        "Pinned a message" -> PreviewIcon.PIN
        "Contact" -> PreviewIcon.CONTACT
        "Location" -> PreviewIcon.LOCATION
        else -> null
    }

    /**
     * The row's preview line in parts (iOS ChatRow.previewContent below the live states): the bold
     * "Name: " [prefix], the grey [text], an optional [icon] before it. [missed] reddens the icon
     * (a call that came in unanswered). A reaction on something keeps its [reactionLead] ("You
     * reacted ❤️") apart from the words so the row can put the pictures or a type icon between.
     * [plain] is the whole line as one string (the widget, the tests).
     */
    @androidx.compose.runtime.Immutable
    data class RowPreview(
        val text: String,
        val prefix: String = "",
        val icon: PreviewIcon? = null,
        val missed: Boolean = false,
        /** The last message is a picture: the thumbnail (or the album's stack) sits before the words. */
        val photo: Boolean = false,
        val isCall: Boolean = false,
        val reactionLead: String? = null,
        /** The reaction line draws [Conversation.lastReactionToImages] between lead and words. */
        val reactionPictures: Boolean = false,
        /** Someone reacted to MY message: the grey heart beside the badge (iOS freshReactionEmoji). */
        val heart: Boolean = false,
        val plain: String = prefix + text,
    )

    /** iOS lastEventHiddenByBlock: a group member I blocked is hidden everywhere in the row. */
    fun hiddenByBlock(c: Conversation, author: String, atMillis: Double): Boolean =
        c.isGroup && BlockList.hides(author, atMillis)

    /** iOS ThreadRepository.isWallpaperNotice. */
    fun isWallpaperNotice(t: String): Boolean =
        t.endsWith(" changed the group wallpaper") || t.endsWith(" removed the group wallpaper")

    private val featureMarker = Regex("^fariin-[a-z0-9]+:")
    private val knownMarkers = listOf(CardRules.CONTACT, CardRules.LOCATION, PinRules.MARKER, PollRules.MARKER)

    /**
     * iOS previewBadge: a stored plain marker as an icon and clean words, never the emoji. [mine]:
     * I placed the call / deleted the message this marker describes. Null for anything else.
     */
    fun badge(s: String, mine: Boolean = false): Pair<PreviewIcon, String>? {
        if (s.startsWith("🎤 Voice message")) return PreviewIcon.MIC to ("Voice message" + s.removePrefix("🎤 Voice message"))
        if (s == "🎤 One-time voice message") return PreviewIcon.VIEW_ONCE to "One-time voice message"
        if (s == "View-once photo") return PreviewIcon.VIEW_ONCE to "View-once photo"
        if (s == "This message was deleted") return PreviewIcon.DELETED to (if (mine) "You deleted this message" else "This message was deleted")
        if (s.startsWith("🎥 Video")) return PreviewIcon.VIDEO to ("Video" + s.removePrefix("🎥 Video"))
        if (s.startsWith("🎥 ")) return PreviewIcon.VIDEO to s.removePrefix("🎥 ")
        if (s.startsWith("📷 ")) return PreviewIcon.PHOTO to s.removePrefix("📷 ")
        if (s.startsWith("🎬 ")) return PreviewIcon.ALBUM to s.removePrefix("🎬 ")
        if (s.startsWith("🎤 ")) return PreviewIcon.MIC to s.removePrefix("🎤 ")
        return when (s) {
            "📄 File" -> PreviewIcon.FILE to "File"
            "GIF" -> PreviewIcon.GIF to "GIF"
            "📞 Missed call", "📞 Declined call" ->
                if (mine) PreviewIcon.CALL_OUT to "Voice call" else PreviewIcon.CALL_IN to "Missed voice call"
            "📞 Call" -> PreviewIcon.CALL to "Voice call"
            "📹 Missed video call", "📹 Declined video call" ->
                if (mine) PreviewIcon.VIDEO_CALL_OUT to "Video call" else PreviewIcon.VIDEO_CALL_IN to "Missed video call"
            "📹 Video call" -> PreviewIcon.VIDEO_CALL to "Video call"
            else -> null
        }
    }

    /** iOS lastIsCall: 📞 / 📹 are call records (🎥 is a video message). */
    fun lastIsCall(c: Conversation): Boolean =
        c.lastMessageCipher.startsWith("📞 ") || c.lastMessageCipher.startsWith("📹 ")

    /** iOS lastSenderPrefix: "Omar: " before a member's real message in a group, never a notice. */
    fun senderPrefix(c: Conversation, me: String, senderName: (String) -> String?): String {
        if (!c.isGroup || c.isChannel || c.lastSender.isEmpty() || c.lastSender == me) return ""
        if (hiddenByBlock(c, c.lastSender, c.updatedAtMillis)) return ""
        val raw = c.lastMessageCipher
        if (!(raw.startsWith("enc") || badge(raw) != null || c.lastIsPlainText)) return ""
        val n = senderName(c.lastSender) ?: c.names[c.lastSender] ?: "Someone"
        return "${n.split(" ").first()}: "
    }

    /**
     * "Reacted 😂 to "Photo"" when the newest event is a reaction (iOS MainShell reactionPreview):
     * mine "You reacted 😂", a group member's "Omar reacted 😂", on my message without its words
     * "Reacted 😂 to your message". Null when there is no fresh reaction or it cannot be opened.
     */
    fun reactionPreview(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): String? {
        if (!c.freshReaction(me)) return null
        // A blocked group member's reaction is hidden the same as their words.
        if (hiddenByBlock(c, c.lastReactionBy, c.lastReactionAtMillis)) return null
        val raw = decryptor.open(c, c.lastReactionEnc ?: return null, c.lastReactionBy)?.takeIf { it.isNotEmpty() && it != "…" && it != "🔒" } ?: return null
        // An animated or uploaded reaction ("ce_5", "cu_<id>") is never shown as its key: the line
        // reads "Reacted" alone (2026-11-06).
        val keyed = Regex("^(ce|cu)_[A-Za-z0-9]+$").matches(raw)
        val emoji = if (keyed) "" else raw
        val words = c.lastReactionToEnc?.let { decryptor.open(c, it, c.lastReactionBy) }
        val on = if (words.isNullOrEmpty() || words == "…" || words == "🔒") "" else " to \"$words\""
        val emo = if (emoji.isEmpty()) "" else " $emoji"
        if (c.lastReactionBy == me) return "You reacted$emo$on"
        if (c.isGroup) {
            val n = senderName(c.lastReactionBy) ?: c.names[c.lastReactionBy] ?: "Someone"
            return "${n.split(" ").first()} reacted$emo$on"
        }
        if (on.isNotEmpty()) return "Reacted$emo$on"
        return if (c.lastReactionToAuthor == me) "Reacted$emo to your message" else "Reacted$emo"
    }

    private fun isEmojiStart(cp: Int): Boolean = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF

    /** iOS reactionWithPictures / reactionWithIcon: the reaction line split at ' to "'. */
    private fun reactionParts(c: Conversation, line: String, heart: Boolean): RowPreview {
        val at = line.indexOf(" to \"")
        if (at < 0) return RowPreview(line, heart = heart)
        val lead = line.substring(0, at)
        val quoted = line.substring(at + 5).removeSuffix("\"")
        if (c.lastReactionToImages.isNotEmpty()) {
            // The quote opens with its own emoji; the picture replaces it ("📷 Photos" to "Photos").
            var words = quoted
            val sp = words.indexOf(' ')
            if (words.isNotEmpty() && isEmojiStart(words.codePointAt(0)) && sp > 0) words = words.substring(sp + 1)
            return RowPreview(words, reactionLead = lead, reactionPictures = true, heart = heart, plain = line)
        }
        if (quoted == "GIF") return RowPreview("GIF", icon = PreviewIcon.GIF, reactionLead = lead, heart = heart, plain = line)
        val marks = listOf("🎤 " to PreviewIcon.MIC, "🎥 " to PreviewIcon.VIDEO, "📷 " to PreviewIcon.PHOTO,
            "📹 " to PreviewIcon.VIDEO, "📞 " to PreviewIcon.CALL, "🎬 " to PreviewIcon.ALBUM)
        for ((mark, icon) in marks) if (quoted.startsWith(mark))
            return RowPreview(quoted.removePrefix(mark), icon = icon, reactionLead = lead, heart = heart, plain = line)
        if (quoted.startsWith("📄 ")) {
            val name = quoted.removePrefix("📄 ")
            val icon = when (name.substringAfterLast('.', "").lowercase()) {
                "zip", "rar", "7z", "gz", "tar" -> PreviewIcon.FILE_ZIP
                "pdf", "doc", "docx", "pages", "txt", "rtf" -> PreviewIcon.FILE_DOC
                "mp3", "m4a", "wav", "aac" -> PreviewIcon.FILE_AUDIO
                else -> PreviewIcon.FILE
            }
            return RowPreview(name, icon = icon, reactionLead = lead, heart = heart, plain = line)
        }
        return RowPreview(line, heart = heart)
    }

    /**
     * The preview below the live states (call, typing, drafts), in iOS's order: a channel I follow,
     * blocked, the reaction, the photo, a marker's icon, "Say hello", then the opened words.
     */
    fun rowPreview(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): RowPreview {
        // A channel I only follow or visit: its last real post (server postPreview), never a notice I
        // cannot see inside. Its owner and admins (its members) keep the full last line.
        if (c.isChannel && me !in c.users) return RowPreview(channelPostLine(c))
        // A blocked person's newest line never leaks into the list.
        if (c.leaksBlocked(me)) return RowPreview("Blocked", icon = PreviewIcon.BLOCKED)
        reactionPreview(c, me, decryptor, senderName)?.let { line ->
            val heart = c.lastReactionBy != me && c.lastReactionToAuthor == me
            return reactionParts(c, line, heart)
        }
        val raw = c.lastMessageCipher
        val prefix = senderPrefix(c, me, senderName)
        val hidden = hiddenByBlock(c, c.lastSender, c.updatedAtMillis)
        // iOS isPhotoPreview: the little picture, and the words without their emoji.
        if (!hidden && (raw.startsWith("📷") || raw.startsWith("🎥") || raw.startsWith("🎬")) && !c.lastImageUrl.isNullOrEmpty()) {
            val label = listOf("🎥 ", "📷 ", "🎬 ").firstOrNull { raw.startsWith(it) }?.let { raw.removePrefix(it) } ?: "Photo"
            return RowPreview(label, prefix = prefix, photo = true)
        }
        if (!hidden) {
            val call = lastIsCall(c)
            val mine = (call || raw == "This message was deleted") && c.lastIsMine(me)
            badge(raw, mine)?.let { (icon, words) ->
                return RowPreview(words, prefix = prefix, icon = icon, missed = words.startsWith("Missed"), isCall = call)
            }
        }
        if (raw.isEmpty()) {
            if (!c.lastImageUrl.isNullOrEmpty() && !hidden) return RowPreview("Photo", prefix = prefix, photo = true)
            // A blank summary on a chat that has messages is not a new chat: empty, not "Say hello".
            return if (c.lastSender.isNotEmpty()) RowPreview("") else RowPreview("Say hello", icon = PreviewIcon.HELLO)
        }
        // iOS decodedLast. Suspended or disabled by Fariin: the reason, never its last message.
        if (c.fariinLocked()) return RowPreview(if (c.isChannel) "This channel is unavailable" else "This group is unavailable")
        if (hidden) return RowPreview("")
        val text = (if (c.isGroup && c.e2eeOff && c.lastIsPlainText) raw else decryptor.previewText(c, me)) ?: "Message"
        // A channel never shows an admin's wallpaper line (owner 2026-11-07).
        if (c.isChannel && isWallpaperNotice(text)) return RowPreview("")
        return when {
            text.startsWith(CardRules.CONTACT) -> RowPreview("Contact", prefix = prefix, icon = PreviewIcon.CONTACT)
            text.startsWith(CardRules.LOCATION) -> RowPreview("Location", prefix = prefix, icon = PreviewIcon.LOCATION)
            text.startsWith(PinRules.MARKER) -> RowPreview("Pinned a message", prefix = prefix, icon = PreviewIcon.PIN)
            PollRules.isPoll(text) -> RowPreview(PollRules.PREVIEW, prefix = prefix, icon = PreviewIcon.POLL)
            // A newer build's feature this one does not know: never the raw marker.
            featureMarker.find(text)?.value?.let { it !in knownMarkers } == true ->
                RowPreview("Message from a newer version", icon = PreviewIcon.NEWER)
            featureMarker.containsMatchIn(text) -> RowPreview("Message", prefix = prefix)
            else -> RowPreview(text, prefix = prefix)
        }
    }

    /**
     * iOS MainShell call rows (owner 2026-09-26): one marker reaches both phones, so the direction
     * comes from who placed the call. An unanswered call I made is "Voice call" / "Video call";
     * only the person called sees "Missed". Null when [text] is not a call marker.
     */
    fun callWords(text: String, mine: Boolean): String? = when (text) {
        "📞 Missed call", "📞 Declined call" -> if (mine) "↗ Voice call" else "📞 Missed voice call"
        "📞 Call" -> "📞 Voice call"
        "📹 Missed video call", "📹 Declined video call" -> if (mine) "↗ Video call" else "📹 Missed video call"
        "📹 Video call" -> "📹 Video call"
        else -> null
    }

    /** A call record, not a message: no sent / read ticks beside it (iOS). */
    fun isCallMarker(raw: String): Boolean = callWords(raw, false) != null

    /**
     * iOS ChatRow.recordingLabel / typingLabel: the live line from the chat's own `typing` field
     * (the list already listens to it, so it costs no read). A 1:1 says "recording…" / "typing…",
     * a group names first names. Never from someone I blocked, never a stale flag.
     */
    fun recordingLabel(c: Conversation, me: String, nowMs: Double = System.currentTimeMillis().toDouble()): String? {
        if (c.isBlockedByMe(me)) return null
        val recs = c.others(me).filter { c.recording[it] == true && c.activityFresh(it, nowMs) && !hiddenByBlock(c, it, nowMs) }
        if (recs.isEmpty()) return null
        if (!c.isGroup) return "recording…"
        val n = c.names[recs[0]] ?: "Someone"
        return "${n.split(" ").first()} is recording…"
    }

    fun typingLabel(c: Conversation, me: String, nowMs: Double = System.currentTimeMillis().toDouble()): String? {
        if (c.isBlockedByMe(me)) return null
        val typers = c.others(me).filter { c.typing[it] == true && c.activityFresh(it, nowMs) && !hiddenByBlock(c, it, nowMs) }
        if (typers.isEmpty()) return null
        if (!c.isGroup) return "typing…"
        val names = typers.map { (c.names[it] ?: "Someone").split(" ").first() }
        return if (names.size == 1) "${names[0]} is typing…" else "${names.joinToString(", ")} are typing…"
    }

    /** The preview line under the name, as one string (the widget). */
    fun previewLine(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): String =
        rowPreview(c, me, decryptor, senderName).plain

    /** A follower's line for a channel: the last post's words, else a word for its kind; "No posts yet". */
    fun channelPostLine(c: Conversation): String {
        if (c.postPreviewText == null && c.postPreviewType == null) return "No posts yet"
        // A location or contact card's marker text is never shown: its word instead ("Location").
        c.postPreviewText?.trim()?.takeIf { it.isNotEmpty() }?.let { return CardRules.previewWord(it) ?: it }
        return when (c.postPreviewType) {
            "image" -> "Photo"
            "video" -> "Video"
            "album" -> "Album"
            "audio" -> "Voice message"
            "file" -> "File"
            "gif" -> "GIF"
            "sticker" -> "Sticker"
            "poll" -> PollRules.PREVIEW
            else -> "Post"
        }
    }

    /** What a peek returns when the line has not been opened yet. */
    const val PEEK_MISS = "\u0000peek-miss"

    /**
     * [previewLine] from lines already opened, with no box opened now: null when one would have
     * to be. The chat row shows this at once and opens the rest off the main thread.
     */
    fun cachedPreviewLine(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): String? =
        cachedRowPreview(c, me, decryptor, senderName)?.plain

    /** [rowPreview] from lines already opened, with no box opened now: null when one would have to be. */
    fun cachedRowPreview(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): RowPreview? {
        var missed = false
        val peek = object : MessageDecryptor {
            override fun previewText(conv: Conversation, me: String): String? =
                decryptor.peekPreview(conv, me).let { if (it == PEEK_MISS) { missed = true; null } else it }
            override fun open(conv: Conversation, sealed: String, author: String): String? =
                decryptor.peekOpen(conv, sealed, author).let { if (it == PEEK_MISS) { missed = true; null } else it }
        }
        val line = rowPreview(c, me, peek, senderName)
        return if (missed) null else line
    }
}
