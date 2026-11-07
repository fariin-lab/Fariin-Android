package com.fariin.app.ui.thread

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.media.AutoDownload
import com.fariin.app.media.FileOpener
import com.fariin.app.media.MediaRules
import com.fariin.app.media.UploadProgress
import com.fariin.app.media.ViewedOnce
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/** Who I am and what Cancel does, for the upload ring's X (iOS onCancelUpload). */
internal class UploadCancel(val me: String, val cancel: (Message) -> Unit,
    /** The X on one album tile: that item is dropped (iOS cancelItem). */
    val cancelItem: (Message, Int) -> Unit = { _, _ -> })
internal val LocalUploadCancel = androidx.compose.runtime.staticCompositionLocalOf<UploadCancel?> { null }

/**
 * The ring's tap for [m]: only on my OWN photo, video or album still going up (iOS
 * MediaBody.cancellable). A photo somebody else is still uploading gets the same ring, no X.
 */
@Composable
internal fun uploadCancelFor(m: Message): (() -> Unit)? {
    val u = LocalUploadCancel.current ?: return null
    if (m.authorId != u.me || m.clientId == null) return null
    if (m.sendState != Message.SendState.SENDING && !(m.uploading && m.sendState == null)) return null
    return { u.cancel(m) }
}

/** The X on tile [index] of my own album still going up, or null (somebody else's: no X). */
@Composable
internal fun itemCancelFor(m: Message, index: Int): (() -> Unit)? {
    val u = LocalUploadCancel.current ?: return null
    if (uploadCancelFor(m) == null) return null
    return { u.cancelItem(m, index) }
}

/**
 * A document (iOS file bubble): the type badge (or the PDF's first page), the name on up to two
 * lines, the size, and "Uploading" while it goes. Tap opens it in another app after the bytes are
 * opened into the cache; a document over the auto-download rule waits for a tap.
 */
@Composable
internal fun FileBody(m: Message, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = chatMedia()
    val ink = if (isMe) Color.White else c.label
    val sub = if (isMe) Color.White.copy(alpha = 0.75f) else c.secondary
    val name = m.fileName ?: "File"
    var opening by remember { mutableStateOf(false) }
    val auto = remember(m.id) {
        (m.fileSize ?: 0L) <= AutoDownload.MAX_AUTO_BYTES && AutoDownload.allowedNow(ctx, AutoDownload.Kind.DOCUMENTS)
    }
    val preview = if (m.thumbUrl != null) rememberPlainFile(m.thumbUrl, m.thumbEnc) else null
    val previewBmp = rememberBitmap(preview, 160)
    // Prefetch per the auto-download rule, so a tap opens at once.
    if (auto && m.fileUrl != null && m.localPath == null) rememberPlainFile(m.fileUrl, m.enc)
    val busy = m.sendState == Message.SendState.SENDING || m.uploading
    Column(Modifier.width(width)) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !opening && (m.localPath != null || m.fileUrl != null)) {
                opening = true
                scope.launch {
                    val f = m.localPath?.let { java.io.File(it) } ?: m.fileUrl?.let { media?.file(it, m.enc) }
                    // A PDF opens in Fariin's own reader (iOS PDFViewerSheet); other files in the phone's apps.
                    if (f != null && com.fariin.app.ui.files.PdfViewerActivity.handles(name)) {
                        opening = false
                        com.fariin.app.ui.files.PdfViewerActivity.open(ctx, f, name)
                        return@launch
                    }
                    val intent = f?.let { FileOpener.intentFor(ctx, it, name) }
                    opening = false
                    val msg = when {
                        intent == null -> "Couldn't open this file."
                        else -> try { ctx.startActivity(intent); null } catch (_: ActivityNotFoundException) { "No app on this phone opens this file." }
                    }
                    msg?.let { com.fariin.app.ui.components.Snack.show(it) }
                }
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (isMe) Color.White.copy(alpha = 0.22f) else c.blue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (previewBmp != null) Image(previewBmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(MediaRules.fileBadge(name), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isMe) Color.White else c.blue)
                if (busy || opening) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    val all by UploadProgress.map.collectAsState()
                    val f = m.clientId?.let { all[it] }
                    if (f != null && f > 0f && !opening) CircularProgressIndicator(progress = { f }, color = Color.White, strokeWidth = 2.dp,
                        trackColor = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(26.dp))
                    else CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 16.sp, color = ink, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    (m.fileSize?.let { MediaRules.fileSizeLabel(it) } ?: "") + if (busy) " · Uploading" else "",
                    fontSize = 13.sp, color = sub,
                )
            }
        }
        Box(Modifier.align(Alignment.End)) { meta() }
    }
}

/**
 * A view-once photo (iOS viewOncePill): "Photo" with the 1 mark until it is opened, then "Viewed"
 * with the crossed mark, and never openable again on this phone. A photo the server burned
 * (`consumed`, no file) is "Viewed" too. My own copy opens once for me as well (owner, 2026-10-03).
 */
@Composable
internal fun ViewOnceBody(m: Message, cid: String, isMe: Boolean, onView: (Viewing) -> Unit, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val epoch by ViewedOnce.epoch.collectAsState()
    val spent = remember(m.id, epoch, m.consumed) { m.consumed || ViewedOnce.contains(ctx, m.id) }
    val url = m.imageUrl
    val canOpen = !spent && url != null && m.sendState == null
    val ink = if (isMe) Color.White else c.label
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = canOpen) {
                url?.let { onView(Viewing.Photo(it, m.enc, once = cid to m.id, msg = m)) }
            }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (spent) Color.Transparent else ink.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (spent) Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_block), null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                else Text("1", color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (spent) "Viewed" else "Photo", fontSize = 17.sp, color = if (spent) ink.copy(alpha = 0.7f) else ink)
            if (m.sendState == Message.SendState.SENDING) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.align(Alignment.End)) { meta() }
    }
}

/**
 * An album of up to ten (iOS album grid): tiles keep their real shape (AlbumMosaicLayout); a
 * video tile has the play disc and its length. Tap opens that tile.
 *
 * My own album going up (iOS AlbumTileView + UploadRingView): every tile wears its own ring keyed
 * "clientId#index" (a tile waiting its turn spins, the one going up fills), and its X drops that
 * item: it leaves the bubble at once and the album goes without it. The tiles left keep their
 * original index, so every other ring still matches its upload.
 */
@Composable
internal fun AlbumMosaic(m: Message, maxW: Dp, onView: (Viewing) -> Unit, capsule: @Composable () -> Unit,
    /** "cid/messageId" when Save to Photos may copy this received album's photos (each once). */
    autoSaveId: String? = null) {
    val saveCtx = androidx.compose.ui.platform.LocalContext.current
    // iOS albumPlan: album width min(bubble, 300); tiles keep their real shape (AlbumMosaicLayout).
    val side = minOf(maxW.value, 300f)
    val busy = m.sendState == Message.SendState.SENDING
    val clientId = m.clientId
    val dropped by com.fariin.app.media.MediaSend.cancelledItems.collectAsState()
    // An X'd tile leaves at once while the album is still mine and going up (never all of them:
    // the last X is the whole send).
    val visible = androidx.compose.runtime.remember(m.album, busy, dropped) {
        val all = m.album.indices.toList()
        if (!busy || clientId == null) all
        else all.filter { com.fariin.app.media.MediaSend.itemKey(clientId, it) !in dropped }.ifEmpty { all }
    }
    val shownIdx = visible.take(MediaRules.ALBUM_MAX_ITEMS)
    val items = shownIdx.map { m.album[it] }
    val plan = androidx.compose.runtime.remember(items, side) {
        com.fariin.app.media.AlbumMosaicLayout.plan(items.map { a ->
            val w = a.width; val h = a.height
            if (w != null && h != null && w > 0 && h > 0) (w / h).toFloat() else 1f
        }, side)
    }
    Box(Modifier.size(plan.width.dp, plan.height.dp)) {
        plan.tiles.forEach { tile ->
                        val at = shownIdx[tile.index]   // the item's own index in the album
                        val item = items[tile.index]
                        val w = tile.w
                        val h = tile.h
                        // A tile's picture follows auto-download (a video tile's poster always comes down).
                        val got = rememberGatedFile(item.imageUrl.ifEmpty { null }, item.enc, if (item.isVideo) null else AutoDownload.Kind.PHOTOS)
                        val bmp = rememberBitmap(got.file, 400)
                        // My own album: the picked picture from the first frame (iOS localAlbum).
                        val picked = rememberLocalPreview(item.localUri, item.isVideo, com.fariin.app.media.LocalPreview.TILE_SIDE)
                        if (!item.isVideo) androidx.compose.runtime.LaunchedEffect(got.file, autoSaveId) {
                            val f = got.file ?: return@LaunchedEffect
                            autoSaveId?.let { com.fariin.app.media.AutoSaveMedia.save(saveCtx, "$it/$at", f, isVideo = false) }
                        }
                        val pendingFetch = got.state != Fetch.DONE
                        Box(
                            Modifier.offset(tile.x.dp, tile.y.dp).size(w.dp, h.dp)
                                // The viewer zooms out of this tile and back into it (hero).
                                .heroSource(MediaOrigins.keyOf(m, at), 18.dp, MediaOrigins.rects, MediaOrigins.radii) { MediaOrigins.hiddenKey == MediaOrigins.keyOf(m, at) }
                                .background(Fariin.colors.secondary.copy(alpha = 0.18f))
                                .clickable(enabled = (bmp != null || pendingFetch) && !busy) {
                                    val v = item.videoUrl
                                    if (!item.isVideo && got.tap()) Unit
                                    else if (bmp == null) got.start()
                                    else {
                                        if (v != null) onView(Viewing.Video(v, item.videoEnc, msg = m, albumIndex = at))
                                        else onView(Viewing.Photo(item.imageUrl, item.enc, msg = m, albumIndex = at))
                                    }
                                },
                        ) {
                            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else if (picked != null) Image(picked, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else if (at == 0) rememberPlaceholder(m.thumb, m.blurhash)?.let {
                                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            // A photo tile's own download (iOS MediaDownloadOverlay per tile); a poster just loads.
                            if (pendingFetch && !busy && !item.isVideo) DownloadBadge(got.url, got.state, isVideo = false)
                            if (item.isVideo) {
                                Box(Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.PlayArrow, "Play video", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                item.duration?.let { d ->
                                    Text(MediaRules.durationLabel(d), fontSize = 11.sp, color = Color.White,
                                        modifier = Modifier.padding(5.dp).clip(RoundedCornerShape(50))
                                            .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                            }
                            if (busy) {
                                // Same 52 as a single photo; smaller only on a small tile (half its short side).
                                UploadRing(clientId?.let { com.fariin.app.media.MediaSend.itemKey(it, at) },
                                    Modifier.align(Alignment.Center), onCancel = itemCancelFor(m, at),
                                    side = MediaRules.tileRingSide(w, h).dp)
                            }
                        }
        }
        Box(Modifier.matchParentSize()) { capsule() }
    }
}

/**
 * The chat list's small picture (iOS lastImageUrl + lastImageEnc): the last photo, album cover
 * or video poster, opened with this chat's keys, 20 dp, rounded, before the preview words. Shown
 * only while the last message is that media (a newer text clears it on iOS too).
 */
@Composable
internal fun ChatListThumb(conv: com.fariin.app.data.Conversation, show: Boolean) {
    val url = conv.lastImageUrl
    val marker = conv.lastMessageCipher
    val isMedia = marker.startsWith("📷") || marker.startsWith("🎥") || marker.startsWith("🎬")
    if (!show || url.isNullOrEmpty() || conv.lastImageEnc == null || !isMedia) return
    val ctx = LocalContext.current
    val media = remember(conv.id) {
        com.fariin.app.media.ChatMedia(ctx.applicationContext, conv.id, com.fariin.app.data.CryptoHub.get(ctx.applicationContext))
    }
    val file by androidx.compose.runtime.produceState<java.io.File?>(null, url) { value = media.file(url, conv.lastImageEnc) }
    val bmp = rememberBitmap(file, 80)
    if (bmp != null) {
        Image(bmp, null, contentScale = ContentScale.Crop,
            modifier = Modifier.padding(top = 1.dp, end = 5.dp).size(20.dp).clip(RoundedCornerShape(4.dp)))
    }
}

/**
 * Whether a quote of [o] carries a picture (iOS replyThumb, composer banner and bubble quote): a
 * photo, an album's first tile, a video's poster, a GIF or a sticker. Never for a deleted or
 * view-once original: a quote must not draw a picture the bubble itself keeps hidden.
 */
internal fun hasReplyThumb(o: Message?): Boolean {
    if (o == null || o.deleted || o.viewOnce) return false
    if (o.isSticker || o.isGif) return true
    return when (o.mediaKind) {
        Message.MediaKind.PHOTO -> o.localPath != null || !o.imageUrl.isNullOrEmpty()
        Message.MediaKind.ALBUM -> o.album.isNotEmpty()
        Message.MediaKind.VIDEO -> o.localPath != null || !o.thumbUrl.isNullOrEmpty()
        else -> false
    }
}

/** The quote's small square picture for [o] ([hasReplyThumb]), opened by the bubble's own loaders. */
@Composable
internal fun ReplyThumb(o: Message, side: Dp, modifier: Modifier = Modifier) {
    val box = modifier.size(side).clip(RoundedCornerShape(6.dp))
    if (o.isSticker) {
        StickerArt(o.stickerPack!!, o.stickerId!!, o.imageUrl, o.enc, box, still = true)
        return
    }
    val fill = Fariin.colors.secondary.copy(alpha = 0.15f)
    if (o.isGif) {
        // A still frame: no animated decoder, so the first frame only.
        coil3.compose.AsyncImage(o.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = box.background(fill))
        return
    }
    val local = remember(o.localPath) { o.localPath?.let { java.io.File(it) }?.takeIf { it.exists() } }
    val (url, enc) = when (o.mediaKind) {
        Message.MediaKind.PHOTO -> o.imageUrl to o.enc
        Message.MediaKind.ALBUM -> o.album.firstOrNull()?.let { it.imageUrl.ifEmpty { null } to it.enc } ?: (null to null)
        Message.MediaKind.VIDEO -> o.thumbUrl to o.thumbEnc
        else -> null to null
    }
    val got = rememberGatedFile(if (local == null) url else null, enc, AutoDownload.Kind.PHOTOS)
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { side.roundToPx() }
    val bmp = rememberBitmap(local ?: got.file, px)
    Box(box.background(fill), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (o.mediaKind == Message.MediaKind.VIDEO) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(side * 0.5f))
        }
    }
}
