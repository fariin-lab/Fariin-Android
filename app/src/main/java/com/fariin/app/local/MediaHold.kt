package com.fariin.app.local

import android.content.Context
import com.fariin.app.data.Message
import com.fariin.app.media.AutoDownload
import com.fariin.app.media.ChatMedia

/**
 * Media first (iOS safety review 2026-11-12, item 6, DeliveryCursor.mediaMissing): the server deletes
 * a delivered message and, later, its file on the cursor, while the phone's row only holds the link.
 * So no mark passes a message whose file is not yet in this phone's media store: InboxSync, the
 * history pull and the open chat stop the mark one millisecond below the OLDEST such message and ask
 * for the file under the person's auto-download settings. The mark moves on once it has landed.
 *
 * Not waited for: a deleted message, an opened view-once, a GIF (a public link, not our file), a
 * call. An UNOPENED view-once is waited for and never fetched ahead of the tap.
 * Known limit (as iOS): the store is a cache with a size cap, so a file here today can go later.
 */
object MediaHold {
    private fun store(ctx: Context) = ChatMedia(ctx, "", null)   // the cache is keyed by url only

    private data class Want(val url: String, val enc: com.fariin.app.crypto.EncMeta?, val kind: AutoDownload.Kind)

    private fun wants(m: Message): List<Want> {
        if (m.deleted || m.isCall || m.type == "gif") return emptyList()
        if (m.album.isNotEmpty()) return m.album.map { a ->
            if (!a.videoUrl.isNullOrEmpty()) Want(a.videoUrl, a.videoEnc, AutoDownload.Kind.VIDEOS)
            else Want(a.imageUrl, a.enc, AutoDownload.Kind.PHOTOS)
        }.filter { it.url.isNotEmpty() }
        return listOfNotNull(
            m.videoUrl?.takeIf { it.isNotEmpty() }?.let { Want(it, m.enc, AutoDownload.Kind.VIDEOS) }
                ?: m.audioUrl?.takeIf { it.isNotEmpty() }?.let { Want(it, m.enc, AutoDownload.Kind.AUDIO) }
                ?: m.fileUrl?.takeIf { it.isNotEmpty() }?.let { Want(it, m.enc, AutoDownload.Kind.DOCUMENTS) }
                ?: m.imageUrl?.takeIf { it.isNotEmpty() }?.let { Want(it, m.enc, AutoDownload.Kind.PHOTOS) },
        )
    }

    /** True while this message's file is not on the phone yet, so no mark may pass it. */
    fun missing(ctx: Context, m: Message): Boolean {
        if (m.viewOnce && m.consumed) return false
        val w = wants(m)
        if (w.isEmpty()) return false
        if (m.viewOnce) return true   // unopened: waits for the tap
        if (m.localPath != null && java.io.File(m.localPath).exists()) return false
        val media = store(ctx)
        return w.any { !ChatMedia.isLocal(it.url) && !media.has(it.url) }
    }

    /** Asks for the missing files under the auto-download rules; never a view-once ahead of the tap. */
    suspend fun fetch(ctx: Context, cid: String, crypto: com.fariin.app.crypto.FariinCrypto, m: Message) {
        if (m.viewOnce || m.deleted) return
        val media = ChatMedia(ctx, cid, crypto)
        for (w in wants(m)) {
            if (ChatMedia.isLocal(w.url) || media.cached(w.url) != null) continue
            if (!AutoDownload.allowedNow(ctx, w.kind)) continue
            runCatching { media.file(w.url, w.enc, video = w.kind == AutoDownload.Kind.VIDEOS) }
        }
    }
}
