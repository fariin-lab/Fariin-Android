package com.fariin.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fariin.app.data.Message
import com.fariin.app.data.QuoteRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The post's picture for a quote (iOS quotePicture, owner 2026-11-06). Only an older sealed post
 * needs it (the server cannot read that one): its author's or an admin's phone sends a small JPEG of
 * the post as it already has it open, kept once per post. The Quotes sheet also draws it. The full picture from this chat's cache when it has been seen, else the post's small inline
 * thumbnail; a video gives its poster. At most 900 px on the long side and about 380 KB.
 * Nothing is downloaded for it, and all the work is off the main thread.
 */
object QuotePicture {
    suspend fun make(media: ChatMedia?, m: Message): ByteArray? = withContext(Dispatchers.IO) {
        val url = QuoteRules.pictureUrl(m.imageUrl, m.videoUrl, m.thumbUrl, m.album.firstOrNull()?.imageUrl)
            ?: return@withContext null
        val cached = runCatching { media?.cached(url)?.readBytes() }.getOrNull()
        var bmp = cached?.let { BitmapDecode.fromBytes(it, QuoteRules.PICTURE_SIDE) }
        if (bmp == null) bmp = m.thumb?.let { t ->
            runCatching { android.util.Base64.decode(t, android.util.Base64.DEFAULT) }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        val src = bmp ?: return@withContext null
        val (w, h) = QuoteRules.pictureSize(src.width, src.height)
        if (w <= 0 || h <= 0) return@withContext null
        val small = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        var out: ByteArray? = null
        for (q in QuoteRules.PICTURE_QUALITIES) {
            val buf = ByteArrayOutputStream()
            if (!small.compress(Bitmap.CompressFormat.JPEG, q, buf)) break
            out = buf.toByteArray()
            if (out.size <= QuoteRules.PICTURE_BYTES) break
        }
        if (small !== src) small.recycle()
        src.recycle()
        out?.takeIf { it.size <= QuoteRules.PICTURE_BYTES }
    }
}
