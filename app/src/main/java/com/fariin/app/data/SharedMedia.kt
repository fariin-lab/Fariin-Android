package com.fariin.app.data

import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.Wire
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.tasks.await

/**
 * A chat's shared content, after Kulan-iOS MediaGalleryView (the All Media page) and
 * ChatService.sharedMedia / galleryContent (the loader). The page has four tabs here, Media,
 * Files, Voice and Links, in the iOS order; iOS also has GIFs, which Android does not draw yet.
 *
 * The lists are pure (unit tested); [SharedMediaSource] is the one Firestore read.
 */
object SharedMedia {
    enum class Tab(val label: String) { MEDIA("Media"), FILES("Files"), VOICE("Voice"), LINKS("Links") }

    /** The Media tab's Show filter (iOS MediaFilter, in the "..." menu). */
    enum class Filter(val label: String) { ALL("All Media"), PHOTOS("Photos"), VIDEOS("Videos") }

    /**
     * One tile of the grid. An album is ONE message holding several items, and the grid lists them
     * item by item (iOS expandedGalleryItems), as "<messageId>-<index>". [pictureUrl] is the photo,
     * or the video's poster; [videoUrl] is set only for a video.
     */
    data class Item(
        val id: String,
        val messageId: String,
        val createdAtMillis: Double,
        val pictureUrl: String,
        val pictureEnc: EncMeta?,
        val videoUrl: String? = null,
        val videoEnc: EncMeta? = null,
        val duration: Double? = null,
        val thumb: String? = null,
        val blurhash: String? = null,
    ) {
        val isVideo: Boolean get() = videoUrl != null
    }

    /** A link found in a message: the address to open and the host shown as the row's title. */
    data class Link(val url: String, val host: String)

    data class Lists(
        val media: List<Item> = emptyList(),
        val files: List<Message> = emptyList(),
        val voice: List<Message> = emptyList(),
        val links: List<Pair<Message, Link>> = emptyList(),
    ) {
        val photoCount: Int get() = media.count { !it.isVideo }
        val videoCount: Int get() = media.count { it.isVideo }
        /** Everything the tabs hold (iOS MediaGalleryView.itemCount). */
        val total: Int get() = media.size + files.size + voice.size + links.size
    }

    /** The profile strip shows at most this many tiles (iOS ProfileMediaCard). */
    const val STRIP = 12

    /** iOS galleryContent: a window over the newest messages, filtered here. */
    const val WINDOW = 400L

    /**
     * Sorts [messages] (any order) into the four tabs, newest first. Left out everywhere: deleted,
     * hidden for me ([hidden]), past their disappearing time, and view-once (iOS closed that hole
     * on 2026-08-11). A file still uploading has no URL yet and is left out until it has one.
     */
    fun lists(messages: List<Message>, nowMs: Double, hidden: (String) -> Boolean = { false }): Lists {
        val shown = messages
            .filter { !it.deleted && !it.viewOnce && !hidden(it.id) && !Disappearing.isExpired(it, nowMs) }
            .sortedByDescending { it.createdAtMillis }
        return Lists(
            media = shown.flatMap(::items),
            files = shown.filter { it.type == "file" && it.fileUrl != null },
            voice = shown.filter { it.type == "audio" && it.audioUrl != null },
            links = shown.mapNotNull { m -> if (isPlainText(m)) firstLink(m.text)?.let { m to it } else null },
        )
    }

    /** The Media tab after the Show filter. */
    fun filtered(media: List<Item>, filter: Filter): List<Item> = when (filter) {
        Filter.ALL -> media
        Filter.PHOTOS -> media.filter { !it.isVideo }
        Filter.VIDEOS -> media.filter { it.isVideo }
    }

    /** A photo, a video, or an album's items, in the album's own order. */
    fun items(m: Message): List<Item> = when (m.type) {
        "album" -> m.album.mapIndexed { i, a ->
            Item("${m.id}-$i", m.id, m.createdAtMillis, a.imageUrl, a.enc, a.videoUrl, a.videoEnc, a.duration)
        }
        "video" -> {
            val poster = m.thumbUrl
            val clip = m.videoUrl
            if (poster != null && clip != null) listOf(Item(m.id, m.id, m.createdAtMillis, poster, m.thumbEnc, clip, m.enc,
                m.duration, m.thumb, m.blurhash)) else emptyList()
        }
        "image", null -> m.imageUrl?.let { listOf(Item(m.id, m.id, m.createdAtMillis, it, m.enc, thumb = m.thumb, blurhash = m.blurhash)) }
            ?: emptyList()
        else -> emptyList()
    }

    private fun isPlainText(m: Message): Boolean =
        (m.type == null || m.type == "text") && m.imageUrl == null && m.text.isNotEmpty() &&
            m.text != Message.NOT_ENCRYPTED && m.text != Wire.NOT_READY && m.text != Wire.LOCKED

    // A web address: with a scheme, starting www., or a bare domain (iOS uses the system data
    // detector, which finds all three). Not part of an e-mail address.
    private val LINK = Regex(
        "(?<![@\\w.-])((?:https?://)[^\\s<>\"]+|(?:www\\.)[^\\s<>\"]+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?::\\d{2,5})?(?:/[^\\s<>\"]*)?)(?![@\\w])",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '\'', '"')

    /** The first web address in [text], or null. A bare domain opens over https. */
    fun firstLink(text: String): Link? {
        if (text.isEmpty()) return null
        for (match in LINK.findAll(text)) {
            var raw = match.value.trimEnd(*TRAILING)   // the sentence's full stop is not the address's
            if (raw.isEmpty()) continue
            val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringBefore(':').lowercase()
            // A bare "word.word" needs a real-looking ending: digits are a version or a price, not a domain.
            if (!host.contains('.') || host.substringAfterLast('.').any { it.isDigit() }) continue
            raw = withScheme
            return Link(raw, host.removePrefix("www."))
        }
        return null
    }

    /** The count line under the title, for the tab on screen (iOS subtitle). */
    fun subtitle(tab: Tab, l: Lists, media: List<Item> = l.media): String {
        fun n(c: Int, one: String) = "$c $one${if (c == 1) "" else "s"}"
        return when (tab) {
            Tab.MEDIA -> "${n(media.count { !it.isVideo }, "photo")}, ${n(media.count { it.isVideo }, "video")}"
            Tab.FILES -> n(l.files.size, "file")
            Tab.VOICE -> n(l.voice.size, "voice message")
            Tab.LINKS -> n(l.links.size, "link")
        }
    }

    /** What an empty tab says (iOS emptyState). */
    fun emptyText(tab: Tab): String = when (tab) {
        Tab.MEDIA -> "No media"
        Tab.FILES -> "No files"
        Tab.VOICE -> "No voice messages"
        Tab.LINKS -> "No links"
    }

    /**
     * The grid's date sections, newest first, as iOS groups them: Today, Yesterday, This Month,
     * then the month's name this year, then month and year.
     */
    fun sections(media: List<Item>, nowMs: Double, zone: ZoneId = ZoneId.systemDefault(),
                 locale: Locale = Locale.getDefault()): List<Pair<String, List<Item>>> {
        val today = Instant.ofEpochMilli(nowMs.toLong()).atZone(zone).toLocalDate()
        fun bucket(ms: Double): String {
            val d = Instant.ofEpochMilli(ms.toLong()).atZone(zone).toLocalDate()
            return when {
                d == today -> "Today"
                d == today.minusDays(1) -> "Yesterday"
                d.year == today.year && d.month == today.month -> "This Month"
                d.year == today.year -> d.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                else -> d.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + d.year
            }
        }
        val out = ArrayList<Pair<String, MutableList<Item>>>()
        for (item in media) {
            val title = bucket(item.createdAtMillis)
            if (out.lastOrNull()?.first == title) out.last().second.add(item) else out.add(title to mutableListOf(item))
        }
        return out
    }
}

/**
 * The read behind the page (iOS ChatService.galleryContent): the newest [SharedMedia.WINDOW]
 * messages by `createdAt`, filtered on the phone. A query on the message type would need a
 * composite index and fail without one; this one needs only the automatic single-field index.
 * Text is opened with the chat's keys exactly as the chat opens it.
 */
class SharedMediaSource(
    private val cid: String,
    private val me: String,
    private val crypto: FariinCrypto,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    /** What is already on the phone: the last answer, else the chat's saved copy. */
    fun local(): List<Message> = cache[cid] ?: com.fariin.app.local.LocalCache.loadThread(me, cid)

    /** The server's answer, or null when the load failed (which is not the same as no media). */
    suspend fun fetch(): List<Message>? {
        if (cid.isEmpty()) return null
        return try {
            val snap = db.collection("conversations").document(cid).collection("messages")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(SharedMedia.WINDOW).get().await()
            snap.documents.map { d ->
                Message.fromFirestore(d.id, d.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
            }.also { cache[cid] = it }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Warm copy per chat, so the profile card and the page open at once on re-entry. */
        private val cache = ConcurrentHashMap<String, List<Message>>()

        /** Sign-out: opened messages must not outlive the account in memory (iOS clearSharedMediaCache). */
        fun clear() = cache.clear()
    }
}
