package com.fariin.app.data

import com.fariin.app.crypto.EncMeta

/**
 * The full-screen viewer's rules, after iOS ImageViewerView and VideoPlayerScreen: which pages a
 * tap opens, which buttons each page shows, and the header words. No Compose here.
 */
object ViewerRules {

    /**
     * One page of the viewer. [key] is the message id, or "<messageId>-<index>" for an album tile
     * (iOS synthetic album ids). [once]: a view-once photo (cid + message id).
     */
    data class Page(
        val key: String,
        val messageId: String?,
        val isVideo: Boolean,
        val url: String,
        val enc: EncMeta?,
        val posterUrl: String? = null,
        val posterEnc: EncMeta? = null,
        val authorId: String? = null,
        val atMillis: Double? = null,
        val albumParent: String? = null,
        val once: Pair<String, String>? = null,
    )

    /**
     * What a tap in a chat opens (iOS ThreadView): a single photo or video opens alone (owner,
     * 2026-07-29: no paging into other messages' pictures); a view-once photo alone; an album photo
     * pages through that album's photos, starting on the one tapped; an album video opens alone.
     * Returns the pages and the index to start on.
     */
    fun chatPages(m: Message, albumIndex: Int? = null, once: Pair<String, String>? = null): Pair<List<Page>, Int> {
        val item = albumIndex?.let { m.album.getOrNull(it) }
        if (item != null) {
            if (item.isVideo) return listOf(albumPage(m, albumIndex, item)) to 0
            val photos = m.album.withIndex().filter { !it.value.isVideo }
            val pages = photos.map { (i, a) -> albumPage(m, i, a) }
            return pages to photos.indexOfFirst { it.index == albumIndex }.coerceAtLeast(0)
        }
        val video = m.mediaKind == Message.MediaKind.VIDEO
        val url = if (video) m.videoUrl else m.localPath ?: m.imageUrl
        url ?: return emptyList<Page>() to 0
        return listOf(Page(
            key = m.id, messageId = m.id, isVideo = video, url = url,
            enc = if (!video && m.localPath != null) null else m.enc,
            posterUrl = if (video) m.thumbUrl else null, posterEnc = if (video) m.thumbEnc else null,
            authorId = m.authorId, atMillis = m.createdAtMillis, once = once,
        )) to 0
    }

    private fun albumPage(m: Message, i: Int, a: Message.AlbumItem) = Page(
        key = "${m.id}-$i", messageId = m.id, isVideo = a.isVideo,
        url = a.videoUrl ?: a.imageUrl, enc = if (a.isVideo) a.videoEnc else a.enc,
        posterUrl = if (a.isVideo) a.imageUrl else null, posterEnc = if (a.isVideo) a.enc else null,
        authorId = m.authorId, atMillis = m.createdAtMillis, albumParent = m.id,
    )

    /**
     * All Media (iOS MediaGalleryView): a photo pages through every photo of the grid in the grid's
     * order; a video opens alone.
     */
    fun sharedPages(items: List<SharedMedia.Item>, tapped: SharedMedia.Item): Pair<List<Page>, Int> {
        if (tapped.isVideo) return listOf(sharedPage(tapped)) to 0
        val photos = items.filter { !it.isVideo }
        return photos.map(::sharedPage) to photos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
    }

    private fun sharedPage(it: SharedMedia.Item) = Page(
        key = it.id, messageId = it.messageId, isVideo = it.isVideo,
        url = it.videoUrl ?: it.pictureUrl, enc = if (it.isVideo) it.videoEnc else it.pictureEnc,
        posterUrl = if (it.isVideo) it.pictureUrl else null, posterEnc = if (it.isVideo) it.pictureEnc else null,
        authorId = it.authorId.ifEmpty { null }, atMillis = it.createdAtMillis,
        // An album tile's id is "<messageId>-<index>" (SharedMedia.itemsOf).
        albumParent = it.messageId.takeIf { m -> m != it.id },
    )

    /** The pages of the same album as [page], in order (iOS albumSiblings). Empty when not an album. */
    fun siblings(pages: List<Page>, page: Page): List<Page> =
        page.albumParent?.let { p -> pages.filter { it.albumParent == p } } ?: emptyList()

    /**
     * Which buttons a page shows (iOS header menu and bottomBar). A view-once photo has no Share and
     * no Save (iOS savingLocked). Delete needs the chat's delete (absent in All Media) and a whole
     * message: one album tile cannot be removed on its own here.
     */
    data class Buttons(
        val share: Boolean,
        /** The bottom bar's red trash (my own photo). */
        val barDelete: Boolean,
        /** The bottom bar's Save (theirs); with [saveAll] > 1 it asks Save All or Save This. */
        val barSave: Boolean,
        /** Save All (n) in the menus; 0 = a single Save. */
        val saveAll: Int,
        /** Save Image / Save Video in the menu. */
        val menuSave: Boolean,
        val menuDelete: Boolean,
        /** The pen between Share and Delete / Save (iOS scribble.variable): draw on this photo and send
         *  the copy to the chat. A photo only, never a view-once one, and only where the viewer can send. */
        val draw: Boolean = false,
    )

    fun buttons(page: Page, pages: List<Page>, me: String, chatCanDelete: Boolean, chatCanSend: Boolean = false): Buttons {
        val locked = page.once != null
        val mine = page.authorId != null && page.authorId == me
        val delete = chatCanDelete && page.messageId != null && page.albumParent == null
        val album = siblings(pages, page).size
        return Buttons(
            share = !locked,
            barDelete = mine && delete,
            // Mine but not deletable here (All Media, an album tile): the place keeps Save rather than standing empty.
            barSave = !locked && !(mine && delete),
            saveAll = if (!locked && album > 1) album else 0,
            menuSave = !locked,
            menuDelete = delete,
            draw = chatCanSend && !locked && !page.isVideo,
        )
    }

    /** The header's first line: "You" for my own, else the name [nameOf] gives (iOS senderName). */
    fun title(page: Page, me: String, nameOf: (String) -> String?): String {
        val a = page.authorId ?: return ""
        return if (a == me) "You" else nameOf(a) ?: ""
    }

    /** The scrubber's labels, "00:07" and "-00:07" (iOS VideoPlayerScreen). */
    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
}
