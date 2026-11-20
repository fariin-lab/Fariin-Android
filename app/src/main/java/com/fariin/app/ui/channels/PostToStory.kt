package com.fariin.app.ui.channels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.fariin.app.channel.ChannelInfo
import com.fariin.app.data.Message
import com.fariin.app.data.StoryMedia
import com.fariin.app.data.StoryTapTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Repost to Story" on a channel post (iOS PostToStory, owner 2026-11-15; flag on in iOS).
 * Only a PUBLIC channel's delivered photo, video, album or text post, never a poll, sticker, GIF,
 * voice note, file, deleted, view-once or card, and not in a restricted channel. The post becomes a
 * 1080x1920 picture: the post's own picture blurred and darkened behind (text only: the blue-purple
 * gradient), and the post as a dark card in the middle (channel photo, name, tick, its picture, up
 * to six lines of words). The card is a Link tap target to the post's own link
 * (https://fariin.com/c/<cid>/<mid>?n=<name>), so the viewer's "View post" opens it and the story
 * carries `post` (StoryPostLink). A video post shows its poster with a play mark (as iOS v2).
 */
object PostToStory {
    fun eligible(info: ChannelInfo, m: Message): Boolean =
        info.isPublic && !info.restricted && m.sendState == null && !m.deleted && !m.isSystem && !m.viewOnce &&
            m.poll == null && m.type !in setOf("sticker", "gif", "audio", "file") && !m.isCall &&
            !com.fariin.app.data.CardRules.isCard(m.text) && com.fariin.app.data.PinRules.notice(m.text) == null &&
            (pictureOf(m) != null || (m.type.isNullOrEmpty() || m.type == "text") && m.bodyText.isNotBlank())

    /** The post's own link, with the channel's name for "Shared from". */
    fun link(info: ChannelInfo, m: Message): String {
        val n = info.title.trim().replace('\n', ' ').take(64)
        return "https://fariin.com/c/${info.cid}/${m.id}" + if (n.isNotEmpty()) "?n=" + java.net.URLEncoder.encode(n, "UTF-8") else ""
    }

    /** url and seal of the picture the card shows: a photo, an album's first item, a video's poster. */
    private fun pictureOf(m: Message): Pair<String, com.fariin.app.crypto.EncMeta?>? = when {
        m.type == "video" -> m.thumbUrl?.takeIf { it.isNotEmpty() }?.let { it to m.thumbEnc }
        m.type == "album" -> m.album.firstOrNull()?.let { a -> a.imageUrl.takeIf { it.isNotEmpty() }?.let { it to a.enc } }
        m.type == "image" -> m.imageUrl?.takeIf { it.isNotEmpty() }?.let { it to m.enc }
        else -> null
    }

    private const val W = 1080
    private const val H = 1920
    /** iOS: a reposted video plays at most this long. */
    private const val CLIP_MS = 60_000L

    /** The story picture and its tap target, or null when the post's picture could not be read. */
    suspend fun build(ctx: Context, info: ChannelInfo, m: Message): StoryMedia.Prepared? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val pic: Bitmap? = pictureOf(m)?.let { (url, enc) ->
            val media = com.fariin.app.media.ChatMedia(app, info.cid, com.fariin.app.data.CryptoHub.get(app))
            val f = runCatching { media.cached(url) ?: media.file(url, enc) }.getOrNull() ?: return@withContext null
            BitmapFactory.decodeFile(f.path) ?: return@withContext null
        }
        val avatar: Bitmap? = info.avatarUrl?.let { u ->
            runCatching { java.net.URL(u).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // The ground: the picture blurred (drawn tiny, then stretched) and darkened, or the gradient.
        if (pic != null) {
            val small = Bitmap.createScaledBitmap(pic, 27, (27f * pic.height / pic.width).toInt().coerceAtLeast(1), true)
            val s = maxOf(W.toFloat() / small.width, H.toFloat() / small.height)
            val dw = small.width * s; val dh = small.height * s
            c.drawBitmap(small, null, RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2), paint)
            c.drawColor(0x73000000)
        } else {
            paint.shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), 0xFF2B5876.toInt(), 0xFF4E4376.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
            paint.shader = null
        }
        // The card: 84% wide, rounded, rgb(24,24,28) at 94%.
        val cw = W * 0.84f
        val pad = 36f
        val head = 112f
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 42f }
        // A media post's own caption only, never the "Photo"/"Video" stand-in word.
        val words = (if (pic != null) m.text else m.bodyText).trim()
        val layout = if (words.isEmpty()) null else StaticLayout.Builder.obtain(words, 0, words.length, textPaint, (cw - pad * 2).toInt())
            .setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f, 1f).build()
        val picH = pic?.let { minOf((cw * it.height / it.width), H * 0.5f) } ?: 0f
        val ch = head + picH + (layout?.let { it.height + pad * 2 } ?: pad)
        val left = (W - cw) / 2
        val top = (H - ch) / 2
        val card = RectF(left, top, left + cw, top + ch)
        paint.color = 0xF018181C.toInt()
        c.drawRoundRect(card, 72f, 72f, paint)
        c.save()
        val cardPath = android.graphics.Path().apply { addRoundRect(card, 72f, 72f, android.graphics.Path.Direction.CW) }
        c.clipPath(cardPath)
        // Header: the channel's photo (rounded square), its name, the tick.
        val ph = RectF(left + pad, top + 26f, left + pad + 60f, top + 86f)
        if (avatar != null) {
            c.save()
            c.clipPath(android.graphics.Path().apply { addRoundRect(ph, 16f, 16f, android.graphics.Path.Direction.CW) })
            c.drawBitmap(avatar, null, ph, paint)
            c.restore()
        } else { paint.color = 0xFF3A3A40.toInt(); c.drawRoundRect(ph, 16f, 16f, paint) }
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 40f; isFakeBoldText = true }
        val name = TextUtils.ellipsize(info.title, namePaint, cw - pad * 2 - 140f, TextUtils.TruncateAt.END).toString()
        c.drawText(name, ph.right + 22f, top + 70f, namePaint)
        if (info.verified) {
            val tx = ph.right + 22f + namePaint.measureText(name) + 16f
            paint.color = 0xFF0A84FF.toInt(); c.drawCircle(tx + 16f, top + 56f, 16f, paint)
            paint.color = 0xFFFFFFFF.toInt(); paint.strokeWidth = 5f; paint.style = Paint.Style.STROKE
            c.drawLine(tx + 8f, top + 56f, tx + 14f, top + 63f, paint); c.drawLine(tx + 14f, top + 63f, tx + 25f, top + 49f, paint)
            paint.style = Paint.Style.FILL
        }
        // The picture, full card width; a video's poster carries a play mark.
        if (pic != null) {
            val pr = RectF(left, top + head, left + cw, top + head + picH)
            val s = maxOf(pr.width() / pic.width, pr.height() / pic.height)
            val dw = pic.width * s; val dh = pic.height * s
            c.save(); c.clipRect(pr)
            c.drawBitmap(pic, null, RectF(pr.centerX() - dw / 2, pr.centerY() - dh / 2, pr.centerX() + dw / 2, pr.centerY() + dh / 2), paint)
            c.restore()
            if (m.type == "video") {
                paint.color = 0x99000000.toInt(); c.drawCircle(pr.centerX(), pr.centerY(), 64f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                val tri = android.graphics.Path().apply { moveTo(pr.centerX() - 20f, pr.centerY() - 32f); lineTo(pr.centerX() + 34f, pr.centerY()); lineTo(pr.centerX() - 20f, pr.centerY() + 32f); close() }
                c.drawPath(tri, paint)
            }
        }
        layout?.let { c.save(); c.translate(left + pad, top + head + picH + pad); it.draw(c); c.restore() }
        c.restore()
        // A video post plays inside the card (iOS v2): the clip (up to 60 s, its sound kept) laid in
        // the picture's box under this same drawing with that box cut open. The drawing above, with
        // the poster and play mark, is the story's still picture.
        val clip: StoryMedia.Video? = if (m.type == "video" && pic != null && !m.videoUrl.isNullOrEmpty()) runCatching {
            val media = com.fariin.app.media.ChatMedia(app, info.cid, com.fariin.app.data.CryptoHub.get(app))
            val src = media.cached(m.videoUrl!!) ?: media.file(m.videoUrl!!, m.enc, video = true) ?: return@runCatching null
            val box = RectF(left, top + head, left + cw, top + head + picH)
            val over = out.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(over).apply {
                save(); clipPath(cardPath)
                drawRect(box, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) })
                restore()
            }
            val srcUri = android.net.Uri.fromFile(src)
            val ms = runCatching {
                android.media.MediaMetadataRetriever().run {
                    try { setDataSource(app, srcUri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
                }
            }.getOrNull() ?: ((m.duration ?: 0.0) * 1000).toLong()
            val end = minOf(ms, CLIP_MS)
            val dir = java.io.File(app.cacheDir, "story-repost").apply { mkdirs() }
            val file = java.io.File(dir, "post-${System.currentTimeMillis()}.mp4")
            val ok = com.fariin.app.media.StoryVideoCut.edit(app, srcUri, file,
                com.fariin.app.media.StoryVideoCut.Edit(if (end > 0) 0L..end else null, mute = false, crop = null,
                    canvasW = W, canvasH = H, overlay = over, place = box))
            over.recycle()
            if (!ok) null else StoryMedia.Video(android.net.Uri.fromFile(file),
                com.fariin.app.data.StoryRules.VideoInfo(end / 1000.0, W, H, file.length()))
        }.getOrNull() else null
        val jpeg = java.io.ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val blur = runCatching {
            val t = Bitmap.createScaledBitmap(out, 24, 42, true)
            android.util.Base64.encodeToString(java.io.ByteArrayOutputStream().also { t.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrDefault("")
        // The tap target over the card: centre and size as fractions of the picture (StoryTaps).
        val tap = StoryTapTarget(x = card.centerX() / W.toDouble(), y = card.centerY() / H.toDouble(),
            w = card.width() / W.toDouble(), h = card.height() / H.toDouble(), rotation = 0.0, url = link(info, m), aspect = W / H.toDouble())
        StoryMedia.Prepared(jpeg, blur, clip, W, H, listOf(tap))
    }
}
