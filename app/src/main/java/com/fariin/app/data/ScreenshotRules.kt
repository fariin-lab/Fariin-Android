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
        /** The words the person can hide: the message, or a photo's caption ("" when it has none). */
        val text: String,
        /** A media row's label ("Photo", "Voice message"), drawn when its picture is not on this phone. */
        val label: String? = null,
        /** Where its real picture is, for a photo, video, album or sticker. */
        val pic: Pic? = null,
        /** A link card's title and site, under the words. */
        val linkTitle: String? = null,
        val linkHost: String? = null,
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
        /** The row id of the message it quotes (null = no reply), so hiding that row also hides the quote. */
        val replyId: String? = null,
    )

    /**
     * A media row's picture: [url] is looked up only in the chat's already opened files (never
     * fetched); a sticker is also found by its pack and id. [aspect] is width over height.
     */
    data class Pic(val url: String?, val stickerPack: String? = null, val stickerId: String? = null,
                   val video: Boolean = false, val aspect: Float = 1f)

    private fun aspect(w: Double?, h: Double?): Float =
        if (w == null || h == null || w <= 0 || h <= 0) 1f else (w / h).toFloat().coerceIn(0.5f, 2f)

    /** The picture a media row draws: a photo itself, a video's poster, an album's first tile, a sticker. */
    fun picOf(m: Message): Pic? = when {
        m.poll != null -> null
        m.type == "sticker" -> if (m.stickerId.isNullOrEmpty() && m.imageUrl.isNullOrEmpty()) null
            else Pic(m.imageUrl, m.stickerPack, m.stickerId)
        m.type == "album" -> m.album.firstOrNull()?.let { Pic(it.imageUrl, video = it.videoUrl != null, aspect = aspect(it.width, it.height)) }
        m.type == "video" || m.videoUrl != null -> m.thumbUrl?.let { Pic(it, video = true, aspect = aspect(m.width, m.height)) }
        m.type == "image" || m.type == "gif" || m.imageUrl != null -> m.imageUrl?.let { Pic(it, aspect = aspect(m.width, m.height)) }
        else -> null
    }

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
            text = if (kind == Kind.MEDIA) caption.orEmpty() else m.text,
            label = if (kind == Kind.MEDIA) mediaLabel(m) else null,
            pic = if (kind == Kind.MEDIA) picOf(m) else null,
            linkTitle = m.linkPreview?.title?.takeIf { kind == Kind.TEXT && it.isNotBlank() },
            linkHost = m.linkPreview?.takeIf { kind == Kind.TEXT && it.title.isNotBlank() }?.host,
            isMe = isMe, authorId = m.authorId,
            senderName = if (isGroup && !isMe && kind != Kind.SYSTEM) name(m.authorId) else null,
            timeMs = minOf(m.createdAtMillis, System.currentTimeMillis().toDouble()),
            tick = ThreadLogic.tick(m, me, receipts),
            day = day(m.createdAtMillis),
            replyName = m.replyTo?.let { name(it.authorId) },
            replyText = m.replyTo?.let { it.text.ifEmpty { "Message" } },
            edited = m.edited, forwarded = m.forwarded,
            replyId = m.replyTo?.let { r -> messages.firstOrNull { it.id == r.id || it.clientId == r.id }?.rowId ?: r.id },
        )
    }

    /**
     * What is hidden in one message (iOS ShotCover): some spans of its words, or the whole message.
     * Spans are in the text's UTF-16 units, start inclusive, end exclusive, sorted, never touching.
     */
    data class Span(val start: Int, val end: Int)

    data class Cover(val spans: List<Span> = emptyList(), val whole: Boolean = false) {
        val isEmpty: Boolean get() = spans.isEmpty() && !whole

        /** Adds a span, folding it into any span it touches (iOS cover). */
        fun cover(s: Span): Cover {
            if (s.end <= s.start) return this
            var merged = s
            val kept = ArrayList<Span>()
            for (x in spans) {
                if (x.end < merged.start || merged.end < x.start) kept.add(x)
                else merged = Span(minOf(x.start, merged.start), maxOf(x.end, merged.end))
            }
            kept.add(merged)
            return copy(spans = kept.sortedBy { it.start })
        }

        /** Shows again the span that holds [index]; unchanged when nothing was hidden there. */
        fun reveal(index: Int): Cover = spans.indexOfFirst { index >= it.start && index < it.end }
            .let { i -> if (i < 0) this else copy(spans = spans.filterIndexed { j, _ -> j != i }) }

        fun hides(index: Int): Boolean = whole || spans.any { index >= it.start && index < it.end }
    }

    /** The word around [index] (letters, digits and inner apostrophes), or null on a space or mark. */
    fun wordAt(text: String, index: Int): Span? {
        fun inWord(i: Int) = i in text.indices && (text[i].isLetterOrDigit() || text[i].isSurrogate() ||
            (text[i] == '\'' && i > 0 && i + 1 < text.length && text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()))
        // A tap just past the last letter of a word still means that word.
        val at = when { inWord(index) -> index; inWord(index - 1) -> index - 1; else -> return null }
        var a = at
        var b = at + 1
        while (inWord(a - 1)) a--
        while (inWord(b)) b++
        return Span(a, b)
    }

    /**
     * The text as the picture draws it: every hidden letter becomes a dot, one per UTF-16 unit, so
     * places in the text stay the same and a tap still finds its word. Spaces stay, so lines wrap as
     * before. The hidden words themselves are never handed to the text drawing.
     */
    fun drawnText(text: String, cover: Cover): String {
        if (cover.whole) return coverText(text)
        if (cover.spans.isEmpty()) return text
        val out = StringBuilder(text)
        for (s in cover.spans) for (i in s.start.coerceAtLeast(0) until s.end.coerceAtMost(text.length)) {
            if (!text[i].isWhitespace()) out.setCharAt(i, '•')
        }
        return out.toString()
    }

    /**
     * A reply's quote as the picture draws it: dots when anything in the quoted row is hidden, so
     * the hidden words never show through the quote of a later message.
     */
    fun quoteText(s: Shot, edits: Edits): String? {
        val t = s.replyText ?: return null
        val quoted = s.replyId ?: return t
        return if (edits.cover(quoted).isEmpty) t else coverText(t)
    }

    /** The editor's changes, kept apart from the copy. [removed] newest last, so Undo brings back the last one. */
    data class Edits(val removed: List<String> = emptyList(), val covers: Map<String, Cover> = emptyMap()) {
        fun remove(id: String) = if (id in removed) this else copy(removed = removed + id)
        fun undo() = if (removed.isEmpty()) this else copy(removed = removed.dropLast(1))
        fun rows(shots: List<Shot>): List<Shot> = removed.toSet().let { gone -> shots.filter { it.id !in gone } }
        fun cover(id: String): Cover = covers[id] ?: Cover()
        private fun put(id: String, c: Cover) = copy(covers = if (c.isEmpty) covers - id else covers + (id to c))
        /** The whole message hidden or shown again (its word spans go with it, as iOS). */
        fun toggleHidden(id: String) = put(id, if (cover(id).whole) Cover() else Cover(whole = true))
        fun isHidden(id: String) = cover(id).whole
        /** Hides the word of [text] at [index]; a tap on a word already hidden shows it again. */
        fun tapWord(id: String, text: String, index: Int): Edits {
            val c = cover(id)
            if (c.spans.any { index >= it.start && index < it.end }) return put(id, c.reveal(index))
            val w = wordAt(text, index) ?: return this
            return put(id, c.cover(w))
        }
        fun reveal(id: String, index: Int) = put(id, cover(id).reveal(index))
    }

    /** Where a row starts a new day (its pill) and where a group sender's name shows. */
    data class Place(val showDay: Boolean, val showName: Boolean)

    /** [pageStarts]: rows that open a later picture; each picture starts with its day pill and the name (iOS pageView). */
    fun placements(rows: List<Shot>, pageStarts: Set<Int> = emptySet()): List<Place> = rows.mapIndexed { i, s ->
        val prev = rows.getOrNull(i - 1)
        val newDay = prev == null || prev.day != s.day || i in pageStarts
        val runStart = newDay || prev!!.kind == Kind.SYSTEM || prev.authorId != s.authorId
        Place(showDay = newDay, showName = s.senderName != null && runStart)
    }

    /** Room above the first row of a picture, and the footer block under the last (iOS ScreenshotExporter: 12, 10 + 22 + 12). */
    const val PAGE_TOP_DP = 12
    const val FOOTER_DP = 44

    /**
     * iOS ScreenshotExporter.plan: the rows split into pictures no taller than the screen, always
     * between two messages. [heights] are the rows without their day pill, [ownDay] whether a row
     * shows its pill anyway, [dayPx] the pill's height (every picture starts with one), [budget] the
     * screen's height less the room above and the footer. A row taller than a whole picture gets one
     * of its own. Returns row indices, one list per picture.
     */
    fun plan(heights: List<Int>, ownDay: List<Boolean>, dayPx: Int, budget: Int): List<List<Int>> {
        if (heights.isEmpty()) return emptyList()
        val pages = mutableListOf(mutableListOf<Int>())
        var used = 0
        for (i in heights.indices) {
            var h = heights[i] + if (ownDay.getOrElse(i) { false } || pages.last().isEmpty()) dayPx else 0
            if (used + h > budget && pages.last().isNotEmpty()) {
                pages.add(mutableListOf())
                h = heights[i] + dayPx
                used = 0
            }
            pages.last().add(i)
            used += h
        }
        return pages
    }

    /** Hidden words are never drawn: dots stand in their place, so nothing can be read back from the picture. */
    fun coverText(text: String): String {
        val n = text.codePointCount(0, text.length).coerceIn(3, 24)
        return "•".repeat(n)
    }

    /** The header line under the title (iOS countLine): "2 pictures · 9 messages". */
    fun countLine(messages: Int, pictures: Int): String =
        (if (pictures == 1) "1 picture" else "$pictures pictures") + " · " + (if (messages == 1) "1 message" else "$messages messages")
}
