package com.fariin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Link previews, SENDER-SIDE (iOS LinkPreviewService + ChatService.sealLinkPreview /
 * attachLinkPreview). The sender's phone fetches the page once while composing; title,
 * description and picture travel INSIDE the encrypted message, so the reader never contacts the
 * site. The text is written at once and the card is added to that same message a moment later,
 * in one update of `linkPreview` = {url, title, desc, imageUrl, imageEnc}: words sealed like the
 * text, the picture sealed and uploaded like a photo to chat/<cid>/<msgId>-lp.enc.
 *
 * Off with Settings > Chats > Link Previews (`linkPreviewsEnabled`, default on): then nothing is
 * fetched at all, since building a card means this phone contacting the site.
 *
 * Our own group and channel links (invites and public @names) get their card from the server, not
 * the web page ([groupDraft]). Not yet here: our own profile and story links. They go without a
 * card rather than with the site's banner.
 */
object LinkPreviews {
    const val PREFS = "fariin_chats"
    const val PREF_KEY = "linkPreviewsEnabled"

    @Volatile private var app: Context? = null
    fun init(context: Context) { app = context.applicationContext }

    /** Settings > Chats > Link Previews. Default on, as iOS. */
    fun enabled(): Boolean =
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(PREF_KEY, true) ?: true

    /** A composer draft: everything the card needs, still plaintext, still on this phone. */
    class Draft(
        val url: String, val title: String, val desc: String, val jpeg: ByteArray?,
        /** The page is a video or a reel (og:type / og:video, or a known reel address). The story
         *  card draws its own play button; YouTube's cover has one baked in, so its draft stays false. */
        val isVideo: Boolean = false,
        /** The site's small icon (PNG), for the story card's host row. Only [storyDraft] fills it. */
        val icon: ByteArray? = null,
    ) {
        val host: String get() = StickerGifRules.host(url)
        /** The picture's own size, read once from its header; 0 x 0 without one. */
        val picSize: Pair<Int, Int> by lazy {
            val b = jpeg ?: return@lazy 0 to 0
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            maxOf(0, o.outWidth) to maxOf(0, o.outHeight)
        }
    }

    /** What a page's head says about itself. [video] = og:type video or an og:video tag. */
    data class Meta(val title: String, val desc: String, val image: String?, val video: Boolean = false)

    // ------------------------------------------------------------------ the X (one link per chat)

    private val suppressed = ConcurrentHashMap<String, String>()

    /** The X on the card: that link goes without a card until the text no longer holds it. */
    fun suppress(cid: String, url: String?) { if (url == null) suppressed.remove(cid) else suppressed[cid] = url }
    fun suppressed(cid: String): String? = suppressed[cid]
    fun takeSuppressed(cid: String): String? = suppressed.remove(cid)

    /**
     * The link a send should get a card for, or null: previews off, no https link, or the one
     * the reader closed (iOS lateCardURL conditions).
     */
    fun cardUrlFor(text: String, enabled: Boolean, suppressed: String?): String? {
        if (!enabled) return null
        val u = firstUrl(text) ?: return null
        return if (u == suppressed) null else u
    }

    /** iOS linkCardAllowed for a 1:1: never on the knock (my one first message to a stranger). */
    fun allowedIn(conv: Conversation?, me: String): Boolean =
        conv == null || conv.isGroup || !(conv.startedBy == me && !conv.accepted && conv.lastSender.isEmpty())

    // ------------------------------------------------------------------ pure parsing

    private val URL_RE = Regex("""(?i)\bhttps://[^\s<>"]+""")

    /** First https link in a text (iOS firstURL: the data detector's links, https only). */
    fun firstUrl(text: String): String? {
        // A link typed without a scheme ("fariin.com/vee") previews as https (iOS 9332362b).
        var u = URL_RE.find(text)?.value
            ?: return TextLinks.find(text).firstOrNull { it.kind == TextLinks.Kind.WEB }?.target
                ?.takeIf { it.startsWith("https://") && hostOf(it)?.contains('.') == true }
        // Sentence punctuation after a link is not part of it; a closing bracket only when unopened.
        while (u.isNotEmpty()) {
            val last = u.last()
            u = when {
                last in ".,;:!?'" -> u.dropLast(1)
                last == ')' && u.count { it == '(' } < u.count { it == ')' } -> u.dropLast(1)
                last == ']' && u.count { it == '[' } < u.count { it == ']' } -> u.dropLast(1)
                else -> break
            }
        }
        val host = hostOf(u) ?: return null
        return if (host.contains('.')) u else null
    }

    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()?.ifEmpty { null }

    /** Our call links are never fetched: the path carries the call's key (iOS isCallLink). */
    fun isCallLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (host == "call.fariin.com") return true
        if (host != "fariin.com" && host != "www.fariin.com") return false
        val first = runCatching { URI(url).path }.getOrNull()?.split('/')?.firstOrNull { it.isNotEmpty() }
        return first == "call" || first == "voice" || first == "video"
    }

    /** Any of our own addresses: their cards come from the account on iOS, not from the site. */
    fun isOwnLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "fariin.com" || host.endsWith(".fariin.com")
    }

    /** No fetch to this phone's own network (iOS isPrivateHost, decision D-composer-6). */
    fun isPrivateHost(url: String): Boolean {
        var host = hostOf(url) ?: return true
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val v4 = if (host.startsWith("::ffff:")) host.substring(7) else host
        val parts = v4.split('.')
        if (parts.size == 4) {
            val o = parts.mapNotNull { it.toIntOrNull() }
            if (o.size == 4 && o.all { it in 0..255 }) {
                return when {
                    o[0] == 0 || o[0] == 10 || o[0] == 127 -> true
                    o[0] == 169 && o[1] == 254 -> true
                    o[0] == 192 && o[1] == 168 -> true
                    o[0] == 172 && o[1] in 16..31 -> true
                    o[0] == 100 && o[1] in 64..127 -> true   // carrier-grade NAT
                    else -> false
                }
            }
        }
        if (host.contains(':')) {
            if (host == "::1" || host == "::") return true
            if (host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")) return true
            if (host.startsWith("fc") || host.startsWith("fd")) return true
        }
        return false
    }

    /** The 11-character video id of a YouTube link (watch, youtu.be, shorts, embed, live, v). */
    fun youtubeId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val id = when {
            host == "youtu.be" -> parts.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                parts.firstOrNull() == "watch" -> (uri.rawQuery ?: "").split('&')
                    .firstOrNull { it.startsWith("v=") }?.substring(2)
                parts.size >= 2 && parts[0] in setOf("shorts", "embed", "live", "v") -> parts[1]
                else -> null
            }
            else -> null
        } ?: return null
        return if (id.length == 11 && id.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }) id else null
    }

    /**
     * og:title / og:description / og:image, with twitter:* and <title> / description as
     * fallbacks (iOS fetch). Null when the page has no title. The image address is decoded and
     * made absolute against the page, https only and never a private host.
     */
    fun parse(html: String, pageUrl: String): Meta? {
        val title = metaContent(html, "og:title") ?: metaContent(html, "twitter:title") ?: tagTitle(html)
        if (title.isNullOrEmpty()) return null
        val desc = metaContent(html, "og:description") ?: metaContent(html, "twitter:description")
            ?: metaContent(html, "description") ?: ""
        val image = (metaContent(html, "og:image") ?: metaContent(html, "twitter:image"))?.let(::decodeEntities)
            ?.let { raw -> runCatching { URI(pageUrl).resolve(raw.trim()).toString() }.getOrNull() }
            ?.takeIf { it.startsWith("https://") && !isPrivateHost(it) }
        val ogType = metaContent(html, "og:type")?.lowercase() ?: ""
        val video = ogType.startsWith("video") || metaContent(html, "og:video") != null ||
            metaContent(html, "og:video:url") != null || isVideoLink(pageUrl)
        return Meta(decodeEntities(title), decodeEntities(desc), image, video)
    }

    // ------------------------------------------------------------------ reels and short videos

    fun isTikTok(url: String): Boolean = hostOf(url)?.let { it == "tiktok.com" || it.endsWith(".tiktok.com") } == true

    fun isInstagram(url: String): Boolean = hostOf(url)?.let { it == "instagram.com" || it.endsWith(".instagram.com") } == true

    /**
     * An address that is a reel or a short video by its shape alone (iOS isVideoLink):
     * instagram.com/reel/…, /reels/…, /tv/…, a TikTok /video/ page or its vm. / vt. short link.
     */
    fun isVideoLink(url: String): Boolean {
        val parts = runCatching { URI(url).path }.getOrNull().orEmpty()
            .split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
        if (isInstagram(url)) return parts.firstOrNull() in setOf("reel", "reels", "tv")
        if (isTikTok(url)) {
            val host = hostOf(url).orEmpty()
            return "video" in parts || host.startsWith("vm.") || host.startsWith("vt.")
        }
        return false
    }

    /** The sign-in page's own title ("Instagram", "TikTok - Make Your Day") says nothing about the post. */
    fun isBrandTitle(url: String, title: String): Boolean {
        val t = title.lowercase()
        return (isInstagram(url) && t == "instagram") || (isTikTok(url) && (t == "tiktok" || t.startsWith("tiktok - ")))
    }

    /** <meta property="og:title" content="...">, either attribute order, property= or name=. */
    fun metaContent(html: String, property: String): String? {
        val p = Regex.escape(property)
        for (attr in listOf("property", "name")) {
            firstGroup(html, """<meta[^>]*$attr=["']$p["'][^>]*content=["']([^"']*)["']""")?.let { return it }
            firstGroup(html, """<meta[^>]*content=["']([^"']*)["'][^>]*$attr=["']$p["']""")?.let { return it }
        }
        return null
    }

    fun tagTitle(html: String): String? = firstGroup(html, "<title[^>]*>([^<]*)</title>")

    private fun firstGroup(text: String, pattern: String): String? =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)
            ?.groupValues?.getOrNull(1)?.trim()

    /** Numeric entities first (&#xb7; / &#183;), then the named ones (iOS decodeEntities). */
    fun decodeEntities(s: String): String {
        var out = s
        if (out.contains("&#")) {
            out = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(out) { m ->
                val body = m.groupValues[1]
                val v = if (body.startsWith("x", ignoreCase = true)) body.substring(1).toIntOrNull(16) else body.toIntOrNull()
                if (v != null && Character.isValidCodePoint(v)) String(Character.toChars(v)) else m.value
            }
        }
        for ((e, c) in listOf("&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")) {
            out = out.replace(e, c)
        }
        return out
    }

    /** Index just past `</head>` in what has arrived so far, or -1 (the read stops there).
     *  [from] = where the newest chunk began, so each chunk is searched once. */
    fun headEnd(bytes: ByteArray, length: Int, from: Int = 0): Int {
        val tag = "</head>".toByteArray()
        var i = maxOf(0, from - tag.size + 1)
        while (i + tag.size <= length) {
            var hit = true
            for (j in tag.indices) if ((bytes[i + j].toInt() or 0x20) != (tag[j].toInt() or 0x20)) { hit = false; break }
            if (hit) return i + tag.size
            i++
        }
        return -1
    }

    /**
     * The `linkPreview` map written on the message (iOS sealLinkPreview): url always, title and
     * desc only when not empty, the picture's address and seal when it went up. Null when the url
     * itself could not be sealed, so no card goes rather than an unreadable one.
     */
    suspend fun fields(url: String, title: String, desc: String, image: Pair<String, EncMeta>?,
                       seal: suspend (String) -> String?): Map<String, Any>? {
        if (url.isEmpty()) return null
        val cu = seal(url) ?: return null
        val d = HashMap<String, Any>()
        d["url"] = cu
        if (title.isNotEmpty()) seal(title)?.let { d["title"] = it }
        if (desc.isNotEmpty()) seal(desc)?.let { d["desc"] = it }
        if (image != null) { d["imageUrl"] = image.first; d["imageEnc"] = image.second.asMap() }
        return d
    }

    // ------------------------------------------------------------------ fetching (bounded)

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Draft>()
    private val missAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Draft?>>()

    /** A card already built this session (the composer's, so a send takes it at once). */
    fun cached(url: String): Draft? = cache[url]

    /** The card for [url], shared with any fetch already running. A miss is kept one minute. */
    suspend fun draft(url: String): Draft? {
        if (isCallLink(url)) return null
        if (isOwnLink(url) && GroupLinkCards.target(url) == null) return null
        cache[url]?.let { return it }
        missAt[url]?.let { if (System.currentTimeMillis() - it < 60_000) return null }
        // Lazy, so it is in the map before it can finish and take itself out.
        val fresh = io.async(start = CoroutineStart.LAZY) {
            val d = runCatching { build(url) }.getOrNull()
            if (d == null) missAt[url] = System.currentTimeMillis() else { cache[url] = d; missAt.remove(url) }
            inFlight.remove(url)
            d
        }
        val task = inFlight.putIfAbsent(url, fresh) ?: fresh
        if (task !== fresh) fresh.cancel()
        task.start()
        return task.await()
    }

    /** [draft], given up on after [millis] (Send tapped while the card was still loading). */
    suspend fun draftWithin(url: String, millis: Long): Draft? = withTimeoutOrNull(millis) { draft(url) }

    private suspend fun build(url: String): Draft? {
        GroupLinkCards.target(url)?.let { return groupDraft(url, it) }
        youtubeId(url)?.let { id -> youtube(url, id)?.let { return it } }
        // TikTok: its page often answers without tags; its public oEmbed then has the title and cover.
        if (isTikTok(url)) {
            val og = fetchPage(url)
            if (og?.jpeg != null) return og
            return tiktok(url) ?: og
        }
        return fetchPage(url)
    }

    // ------------------------------------------------------------------ the story link card (iOS d2cbac93)

    private val storyCache = ConcurrentHashMap<String, Draft>()
    private val iconCache = ConcurrentHashMap<String, ByteArray>()
    private val iconMissAt = ConcurrentHashMap<String, Long>()

    /**
     * The text story's card for [url]: [draft], plus what only the story card shows. A reel or video
     * page that would not answer (Instagram often refuses without sign-in) still gets a host-only card
     * with nothing made up; a sign-in page's brand title is dropped; the site's icon is fetched.
     * Kept for the session, like the chat cards.
     */
    suspend fun storyDraft(url: String): Draft? {
        storyCache[url]?.let { return it }
        val video = isVideoLink(url)
        val d = draft(url) ?: if (video) Draft(url, "", "", null, isVideo = true) else return null
        val brand = isBrandTitle(url, d.title)
        val icon = withContext(Dispatchers.IO) { siteIcon(url) }
        val out = Draft(url, if (brand) "" else d.title, if (brand) "" else d.desc, d.jpeg,
            isVideo = d.isVideo || video, icon = icon)
        storyCache[url] = out
        return out
    }

    /** [storyDraft], given up on after [millis]. */
    suspend fun storyDraftWithin(url: String, millis: Long): Draft? = withTimeoutOrNull(millis) { storyDraft(url) }

    /** TikTok's public oEmbed: title and cover, no sign-in. Null when it has nothing. */
    private fun tiktok(url: String): Draft? {
        val o = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8")
        val json = download(o, url, "application/json", 256 * 1024)?.toString(Charsets.UTF_8) ?: return null
        val j = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val title = j.optString("title", "").trim()
        val cover = j.optString("thumbnail_url", "").takeIf { it.startsWith("https://") && !isPrivateHost(it) }
            ?.let { fetchImage(it, url) }
        if (title.isEmpty() && cover == null) return null
        return Draft(url, title, j.optString("author_name", ""), cover, isVideo = true)
    }

    /**
     * The site's small icon (iOS siteIcon): its apple-touch-icon, else favicon.ico, at most 512 KB and
     * 16 px wide, as a 96 px PNG. Null when neither answers (the card draws a link glyph). Per host;
     * a miss is kept ten minutes.
     */
    private fun siteIcon(url: String): ByteArray? {
        if (!url.startsWith("https://") || isPrivateHost(url)) return null
        val host = hostOf(url) ?: return null
        iconCache[host]?.let { return it }
        iconMissAt[host]?.let { if (System.currentTimeMillis() - it < 600_000) return null }
        for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
            val bytes = download("https://$host$path", url, "image/*", 512 * 1024) ?: continue
            val raw = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: continue
            if (raw.width < 16) continue
            val side = 96
            val bmp = if (raw.width > side || raw.height > side) {
                val s = side.toFloat() / maxOf(raw.width, raw.height)
                Bitmap.createScaledBitmap(raw, (raw.width * s).toInt().coerceAtLeast(1), (raw.height * s).toInt().coerceAtLeast(1), true)
            } else raw
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray().also { iconCache[host] = it; iconMissAt.remove(host) }
        }
        iconMissAt[host] = System.currentTimeMillis()
        return null
    }

    /**
     * A group or channel link's card from the server (iOS groupDraft / publicGroupDraft): the name,
     * "N members" or "N followers", and the photo. A dead invite says so ("Unavailable" with no
     * description, which the bubble draws as "This link is no longer valid"); a name that is not a
     * group, or no answer, goes bare. Readers draw the group's current name and photo over it.
     */
    private suspend fun groupDraft(url: String, t: GroupLinkCards.Target): Draft? =
        when (val live = GroupLinkCards.load(t)) {
            is GroupLinkCards.Live.Found -> Draft(url, live.title, live.countLine, live.photoUrl?.let { fetchImage(it, url) })
            GroupLinkCards.Live.Gone -> if (t is GroupLinkCards.Target.Invite) Draft(url, "Unavailable", "", null) else null
            GroupLinkCards.Live.Unknown -> null
        }

    /** A real phone browser's User-Agent, as iOS: an honest bot name got text-only cards (CDNs refused the image). */
    private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    private const val TIMEOUT_MS = 8_000
    private const val PAGE_CAP = 300_000
    private const val IMAGE_CAP = 3 * 1024 * 1024
    private const val IMAGE_SIDE = 800

    private fun open(url: String, accept: String, referer: String? = null): HttpURLConnection? {
        if (!url.startsWith("https://") || isPrivateHost(url)) return null
        val c = URL(url).openConnection() as? HttpURLConnection ?: return null
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        referer?.let { c.setRequestProperty("Referer", it) }
        return c
    }

    /** Only as far as </head> (or 300 KB): every tag read here lives there. */
    private fun fetchPage(url: String): Draft? {
        val c = open(url, "text/html,application/xhtml+xml") ?: return null
        try {
            if (c.responseCode != 200 || !(c.contentType ?: "").contains("html")) return null
            if (isPrivateHost(c.url.toString())) return null
            val buf = ByteArray(PAGE_CAP)
            var n = 0
            try {
                c.inputStream.use { s ->
                    while (n < PAGE_CAP) {
                        val r = s.read(buf, n, minOf(8192, PAGE_CAP - n))
                        if (r <= 0) break
                        n += r
                        if (headEnd(buf, n, n - r) >= 0) break
                    }
                }
            } catch (_: Exception) { if (n == 0) return null }   // what came before a drop is still read
            val html = String(buf, 0, n, Charsets.UTF_8)
            val meta = parse(html, url) ?: return null
            val jpeg = meta.image?.let { fetchImage(it, url) }
            return Draft(url, meta.title, meta.desc, jpeg, isVideo = meta.video)
        } catch (_: Exception) {
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, pageUrl: String, accept: String, cap: Int): ByteArray? {
        val c = open(url, accept, pageUrl) ?: return null
        try {
            if (c.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { s ->
                val b = ByteArray(16384)
                while (true) {
                    val r = s.read(b)
                    if (r <= 0) break
                    out.write(b, 0, r)
                    if (out.size() > cap) return null
                }
            }
            return out.toByteArray()
        } catch (_: Exception) {
            return null
        } finally {
            c.disconnect()
        }
    }

    /** The page's picture, at most 3 MB, downscaled to 800 px on its long side, as JPEG 0.75. */
    private fun fetchImage(url: String, pageUrl: String, minWidth: Int = 0, play: Boolean = false): ByteArray? {
        val bytes = download(url, pageUrl, "image/*", IMAGE_CAP) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth <= minWidth) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_SIDE) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, IMAGE_SIDE.toFloat() / maxOf(raw.width, raw.height))
        var bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        if (play) bmp = withPlayButton(bmp)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray()
    }

    /**
     * A YouTube link gets the video's own card (iOS youtubeDraft): title and channel from the
     * public oEmbed endpoint, the cover from the thumbnail host (HD when it has one) with a play
     * button baked in. Null hands over to the ordinary page read.
     */
    private fun youtube(url: String, id: String): Draft? {
        val watch = "https://www.youtube.com/watch?v=$id"
        val o = "https://www.youtube.com/oembed?url=" + URLEncoder.encode(watch, "UTF-8") + "&format=json"
        val json = download(o, url, "application/json", 256 * 1024)?.toString(Charsets.UTF_8) ?: return null
        val j = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val title = j.optString("title", "")
        if (title.isEmpty()) return null
        val channel = j.optString("author_name", "")
        var cover: ByteArray? = null
        for (name in listOf("maxresdefault", "hqdefault")) {
            // The "no HD cover" answer is a tiny grey placeholder.
            cover = fetchImage("https://i.ytimg.com/vi/$id/$name.jpg", url, minWidth = 200, play = true)
            if (cover != null) break
        }
        return Draft(url, title, channel, cover)
    }

    /** A dark translucent disc with a white triangle, centred (iOS withPlayButton). */
    private fun withPlayButton(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val d = minOf(w, h) * 0.22f
        val cx = w / 2
        val cy = h / 2
        cv.drawCircle(cx, cy, d / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) })
        val t = d * 0.36f
        val path = Path().apply {
            moveTo(cx - t * 0.38f, cy - t / 2)
            lineTo(cx - t * 0.38f, cy + t / 2)
            lineTo(cx + t * 0.55f, cy)
            close()
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bmp
    }

    // ------------------------------------------------------------------ attach to the sent message

    /**
     * iOS attachLinkPreview: seal the card, upload its picture like a photo, and add it to the
     * message already written under [clientId]. The rules take this once, from the author,
     * within two minutes of the send. Not on a 1:1 request still waiting for its answer.
     * False = the message simply stays without a card.
     */
    suspend fun attach(convRef: DocumentReference, cid: String, me: String, crypto: FariinCrypto,
                       clientId: String, d: Draft, members: List<String>?): Boolean = withContext(Dispatchers.IO) {
        if (clientId.isEmpty() || clientId.contains('/')) return@withContext false
        if (members == null) {
            fun open(c: Conversation) = c.startedBy.isEmpty() || c.accepted || c.reqOff
            val local = CryptoHub.listedConversation(cid)
            if (local == null || !open(local)) {
                val data = runCatching { convRef.get().await().data }.getOrNull() ?: return@withContext false
                if (!open(Conversation.fromFirestore(cid, data))) return@withContext false
            }
        }
        val seal: suspend (String) -> String? = { s ->
            runCatching { if (members != null) crypto.sealForGroup(s, members, cid) else crypto.encryptForConversation(cid, s) }.getOrNull()
        }
        val image = d.jpeg?.let { jpeg ->
            runCatching {
                val (cipher, meta) = if (members != null) crypto.sealBytesForGroup(jpeg, members, cid) else crypto.encryptBytes(cid, jpeg)
                val ref = FirebaseStorage.getInstance().reference.child("chat/$cid/$clientId-lp.enc")
                ref.putBytes(cipher, StorageMetadata.Builder().setContentType("application/octet-stream").build()).await()
                ref.downloadUrl.await().toString() to meta
            }.getOrNull()   // the card ships without its picture
        }?.takeIf { it.first.startsWith("https://") }   // the rules take https only (the local test server is http)
        val lp = fields(d.url, d.title, d.desc, image, seal) ?: return@withContext false
        try {
            convRef.collection("messages").document(clientId).update("linkPreview", lp).await()
            true
        } catch (_: Exception) { false }
    }
}
