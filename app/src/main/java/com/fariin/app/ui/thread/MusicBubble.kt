package com.fariin.app.ui.thread

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.Message
import com.fariin.app.data.MusicRules
import com.fariin.app.media.AutoDownload
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.MediaRules
import com.fariin.app.media.MusicTags
import com.fariin.app.media.VoicePlayer
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.theme.Fariin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A song or a playlist in the chat (iOS MusicBubbleView, 2026-11-21). Not the voice note's look: a
 * cover (or a music note), the title over the artist, and a thin seek line with the times. A playlist
 * is its cover square, its name, "Playlist · N songs", and opens into its tracks; a tap on a track
 * plays it and the ones after it. Playback is [VoicePlayer]'s, the one audio player, so a song and a
 * voice note never sound together and the lock screen and the bar work for songs too.
 */
@Composable
internal fun MusicBody(m: Message, refs: List<MusicRules.Ref>, cid: String, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val media = chatMedia()
    val ink = if (isMe) Color.White else c.label
    val sub = if (isMe) Color.White.copy(alpha = 0.75f) else c.secondary
    val tint = if (isMe) Color.White else c.blue
    val st by VoicePlayer.state.collectAsState()
    val tagsVersion by MusicTags.version.collectAsState()
    val playlist = refs.size >= 2
    val busy = m.uploading || m.sendState == Message.SendState.SENDING
    val coverFile = if (m.thumbUrl != null) rememberPlainFile(m.thumbUrl, m.thumbEnc) else null
    val coverBmp = rememberBitmap(coverFile, 160)
    var open by rememberSaveable(m.id) { mutableStateOf(false) }

    fun localOf(r: MusicRules.Ref): File? = when {
        r.url.startsWith("/") -> File(r.url).takeIf { it.exists() }
        r.url.isEmpty() -> null
        else -> media.cached(r.url)
    }
    val fetch: suspend (MusicRules.Ref) -> File? = { r ->
        if (r.url.isEmpty()) null else if (r.url.startsWith("/")) File(r.url).takeIf { it.exists() } else media.file(r.url, r.enc)
    }
    // The tags of every song already on the phone; a single song comes down by itself under the
    // documents auto-download rule, as a document does (iOS DocumentPrefetch).
    LaunchedEffect(refs) {
        withContext(Dispatchers.IO) {
            for (r in refs) {
                val f = localOf(r) ?: if (!playlist && r.url.isNotEmpty() && !ChatMedia.isLocal(r.url) &&
                    r.fileSize <= AutoDownload.MAX_AUTO_BYTES && AutoDownload.allowedNow(ctx, AutoDownload.Kind.DOCUMENTS)) media.file(r.url, r.enc) else null
                f?.let { MusicTags.ensure(r.id, it) }
            }
        }
    }

    fun play(i: Int) {
        if (busy) return
        val r = refs.getOrNull(i) ?: return
        if (r.url.isEmpty()) return
        val cover = coverFile?.let { f -> runCatching { f.readBytes() }.getOrNull() }
        VoicePlayer.toggleMusic(ctx, cid, refs, i, cover, fetch)
    }
    val currentIndex = refs.indexOfFirst { it.id == st.id }
    val current = currentIndex >= 0
    val playingHere = current && st.playing
    val loadingHere = refs.any { it.id == st.loading }
    val failedHere = refs.getOrNull(maxOf(0, currentIndex))?.id?.let { it in st.failed } == true
    val first = refs[0]
    val firstTags = remember(first.id, tagsVersion) { MusicTags.of(first.id) }
    val artBmp = rememberArt(if (playlist) null else firstTags?.art)
    val shown = coverBmp ?: artBmp

    Column(Modifier.width(width)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(if (playlist) RoundedCornerShape(10.dp) else CircleShape)
                    .background(tint.copy(alpha = if (isMe) 0.22f else 0.15f))
                    .clickable(enabled = !busy) { play(if (current) currentIndex else 0) }
                    .semantics { contentDescription = if (playingHere) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                if (shown != null) {
                    Image(shown, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                }
                val glyphTint = if (shown != null) Color.White else tint
                when {
                    busy || (loadingHere && !(playlist && open)) -> com.fariin.app.ui.components.FariinSpinner(size = 22.dp, color = glyphTint)
                    failedHere && !(playlist && open) -> MusicGlyph(R.drawable.ic_sf_exclamation_triangle, glyphTint)
                    playingHere -> MusicGlyph(R.drawable.ic_sf_pause_fill, glyphTint)
                    current || shown != null -> MusicGlyph(R.drawable.ic_sf_play_fill, glyphTint)
                    playlist -> MusicGlyph(R.drawable.ic_sf_list_bullet, glyphTint)
                    else -> MusicGlyph(R.drawable.ic_sf_music_note, glyphTint)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val title: String
                val line: String
                if (playlist) {
                    title = MusicRules.playlistName(m.listName, m.text).ifEmpty { "Playlist" }
                    line = "Playlist · ${refs.size} songs"
                } else {
                    title = first.title ?: firstTags?.title ?: first.plainTitle
                    line = first.artist ?: firstTags?.artist ?: if (firstTags != null) "Unknown artist" else MediaRules.bubbleSizeLabel(first.fileSize)
                }
                Text(title, fontSize = 16.sp, color = ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line + if (busy) " · Uploading" else "", fontSize = 13.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playlist) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { open = !open }
                    .semantics { contentDescription = if (open) "Hide songs" else "Show songs" }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(if (open) R.drawable.ic_sf_chevron_up else R.drawable.ic_sf_chevron_down), null,
                        tint = sub, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (!playlist) {
            val total = if (current && st.durationMs > 0) st.durationMs else firstTags?.durationMs ?: 0
            val pos = if (current) st.positionMs else st.paused[first.id] ?: 0
            SeekLine(first.id, if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f, current, tint, sub)
            val times = when {
                total <= 0 -> ""
                current || pos > 0 -> MusicRules.clock(pos) + " / " + MusicRules.clock(total)
                else -> MusicRules.clock(total)
            }
            if (times.isNotEmpty()) Text(times, fontSize = 12.sp, color = sub)
        }
        if (playlist && open) {
            refs.forEachIndexed { i, r ->
                val tags = remember(r.id, tagsVersion) { MusicTags.of(r.id) }
                val isCur = st.id == r.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy && r.url.isNotEmpty()) { play(i) }
                    .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = if (isMe) 0.22f else 0.15f)),
                        contentAlignment = Alignment.Center) {
                        when {
                            st.loading == r.id -> com.fariin.app.ui.components.FariinSpinner(size = 18.dp, color = tint)
                            r.id in st.failed -> MusicGlyph(R.drawable.ic_sf_exclamation_triangle, tint, 16.dp)
                            isCur && st.playing -> MusicGlyph(R.drawable.ic_sf_pause_fill, tint, 16.dp)
                            isCur -> MusicGlyph(R.drawable.ic_sf_play_fill, tint, 16.dp)
                            else -> MusicGlyph(R.drawable.ic_sf_music_note, tint, 16.dp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title ?: tags?.title ?: r.plainTitle, fontSize = 15.sp, color = ink,
                            fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val s = if (isCur && st.durationMs > 0) MusicRules.clock(st.positionMs) + " / " + MusicRules.clock(st.durationMs)
                            else r.artist ?: tags?.artist ?: if (tags != null) "Unknown artist" else MediaRules.bubbleSizeLabel(r.fileSize)
                        Text(s, fontSize = 12.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (isCur) SeekLine(r.id, if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f, true, tint, sub)
            }
        }
        MusicRules.caption(playlist, m.listName, m.text)?.let { words ->
            Spacer(Modifier.height(4.dp))
            Text(words, fontSize = 16.sp, color = ink)
        }
        Box(Modifier.align(Alignment.End)) { meta() }
    }
}

@Composable
private fun MusicGlyph(res: Int, tint: Color, size: Dp = 20.dp) {
    Icon(ImageVector.cachedVector(res), null, tint = tint, modifier = Modifier.size(size))
}

/** The thin seek line: a tap or a sideways drag moves the open song there (iOS seek line). */
@Composable
private fun SeekLine(id: String, progress: Float, live: Boolean, tint: Color, track: Color) {
    Box(Modifier.fillMaxWidth().height(18.dp)
        .pointerInput(id, live) {
            if (!live) return@pointerInput
            detectTapGestures { o -> if (size.width > 0) VoicePlayer.seek(id, o.x / size.width) }
        }
        .pointerInput(id, live) {
            if (!live) return@pointerInput
            detectHorizontalDragGestures { ch, _ -> ch.consume(); if (size.width > 0) VoicePlayer.seek(id, ch.position.x / size.width) }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(track.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(2.dp)).background(tint))
    }
}

/** A song's own picture (JPEG bytes from its tags), decoded once. */
@Composable
internal fun rememberArt(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
}
