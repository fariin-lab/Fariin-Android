package com.fariin.app.ui.thread

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.MusicRules
import com.fariin.app.media.MediaRules
import com.fariin.app.media.MusicTags
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.theme.Fariin
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked song in the playlist sheet: where it is, its name, and its tags once read. */
internal data class PickedSong(val uri: Uri, val name: String, val title: String? = null, val artist: String? = null,
                               val art: ByteArray? = null)

/** The picked file's display name (OpenableColumns), or null. */
internal fun pickedName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** Two songs or more and nothing else: the pick is a playlist (iOS: several songs picked together). */
internal fun isPlaylistPick(context: Context, uris: List<Uri>): Boolean =
    uris.size >= 2 && uris.all { MediaRules.isSong(pickedName(context, it)) }

/**
 * The playlist send sheet (iOS PlaylistComposeSheet, 2026-11-21), in our look: the cover (the first
 * song's own picture, as iOS's default cover), the playlist's name, a caption, and the songs. Hold
 * a song and drag to move it, swipe it left to take it out. Send gives the songs in their order.
 */
@Composable
internal fun PlaylistComposeSheet(uris: List<Uri>, onDismiss: () -> Unit, onSend: (List<Uri>, String, String) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val songs = remember { mutableStateListOf<PickedSong>().also { l ->
        uris.take(MusicRules.MAX_TRACKS).forEach { u -> l += PickedSong(u, pickedName(ctx, u) ?: "Audio") } } }
    var name by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    // Each song's tags, read from the picked file in the background.
    LaunchedEffect(Unit) {
        for (u in uris.take(MusicRules.MAX_TRACKS)) {
            val t = withContext(Dispatchers.IO) { readTags(ctx, u) } ?: continue
            val i = songs.indexOfFirst { it.uri == u }
            if (i >= 0) songs[i] = songs[i].copy(title = t.title, artist = t.artist, art = t.art)
        }
    }
    val rowH = 56.dp
    val rowPx = with(density) { rowH.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }

    com.fariin.app.ui.components.FariinSheetDialog(onDismiss, handle = true) { _ ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", fontSize = 17.sp, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }.padding(6.dp))
                Spacer(Modifier.weight(1f))
                Text(if (songs.size == 1) "Song" else "Playlist", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Spacer(Modifier.weight(1f))
                Text("Send", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (songs.isEmpty()) c.secondary else c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = songs.isNotEmpty()) {
                        onSend(songs.map { it.uri }, name, caption)
                    }.padding(6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val art = rememberArt(songs.firstOrNull()?.art)
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center) {
                    if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(ImageVector.cachedVector(R.drawable.ic_sf_music_note), null, tint = c.accent, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (songs.size >= 2) SheetField(name, "Playlist name") { name = it.take(80) }
                    SheetField(caption, "Add a caption…") { caption = it.take(1000) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("${songs.size} ${if (songs.size == 1) "SONG" else "SONGS"}", fontSize = 13.sp, color = c.secondary)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(rowH * songs.size)) {
                songs.forEachIndexed { i, s ->
                    androidx.compose.runtime.key(s.uri) {
                        var swipeX by remember { mutableFloatStateOf(0f) }
                        val y = i * rowPx + if (dragging == i) dragY else 0f
                        Row(Modifier.fillMaxWidth().height(rowH)
                            .offset { IntOffset(swipeX.roundToInt(), y.roundToInt()) }
                            .background(c.bg)
                            .pointerInput(s.uri) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (swipeX < -size.width * 0.35f) songs.remove(s) else swipeX = 0f
                                    },
                                    onDragCancel = { swipeX = 0f },
                                ) { ch, dx -> ch.consume(); swipeX = (swipeX + dx).coerceAtMost(0f) }
                            }
                            .pointerInput(s.uri) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = songs.indexOf(s); dragY = 0f },
                                    onDragEnd = { dragging = -1; dragY = 0f },
                                    onDragCancel = { dragging = -1; dragY = 0f },
                                ) { ch, d ->
                                    ch.consume()
                                    dragY += d.y
                                    val at = dragging
                                    // Past half a row: the song changes places with its neighbour.
                                    if (dragY > rowPx / 2 && at < songs.size - 1) {
                                        songs.add(at + 1, songs.removeAt(at)); dragging = at + 1; dragY -= rowPx
                                    } else if (dragY < -rowPx / 2 && at > 0) {
                                        songs.add(at - 1, songs.removeAt(at)); dragging = at - 1; dragY += rowPx
                                    }
                                }
                            }
                            .semantics { contentDescription = "Song ${i + 1}. Hold and drag to move, swipe left to remove" },
                            verticalAlignment = Alignment.CenterVertically) {
                            val art = rememberArt(s.art)
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center) {
                                if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Icon(ImageVector.cachedVector(R.drawable.ic_sf_music_note), null, tint = c.accent, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title ?: MusicRules.plainTitle(s.name), fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist ?: "Unknown artist", fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { songs.remove(s) }
                                .semantics { contentDescription = "Remove" }, contentAlignment = Alignment.Center) {
                                Icon(ImageVector.cachedVector(R.drawable.ic_sf_minus_circle_fill), null, tint = c.secondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetField(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = c.secondary)
        BasicTextField(value, onChange, textStyle = TextStyle(fontSize = 16.sp, color = c.label), cursorBrush = SolidColor(c.accent),
            maxLines = 3, modifier = Modifier.fillMaxWidth())
    }
}

/** The picked song's own tags (title, artist, picture), or null when it cannot be read. */
private fun readTags(context: Context, uri: Uri): MusicTags.Tags? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        fun clean(k: Int) = r.extractMetadata(k)?.trim()?.ifEmpty { null }
        MusicTags.Tags(
            title = clean(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = clean(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            art = r.embeddedPicture?.let { MusicTags.artJpeg(it, 160) },
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}
