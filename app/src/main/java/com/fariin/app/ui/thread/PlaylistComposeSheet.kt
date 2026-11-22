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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked song in the playlist sheet: where it is, its name, and its tags once read. */
internal data class PickedSong(val uri: Uri, val name: String, val title: String? = null, val artist: String? = null,
                               val art: ByteArray? = null,
                               /** Typed in Rename (iOS Song.title / artist); null = the song's own. */
                               val newTitle: String? = null, val newArtist: String? = null) {
    /** The song's own title (its tags, else the file name), as iOS tagTitle. */
    val ownTitle: String get() = title ?: MusicRules.plainTitle(name)
    val shownTitle: String get() = newTitle ?: ownTitle
    val shownArtist: String? get() = newArtist ?: artist
    /** The renamed title and artist to send: null when nothing different was typed (iOS sentTitle / sentArtist). */
    val sent: Pair<String?, String?> get() = MusicRules.sentField(newTitle, ownTitle) to MusicRules.sentField(newArtist, artist)
}

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
 * The playlist send sheet (iOS PlaylistComposeSheet, 2026-11-21), in our look: the cover, the
 * playlist's name, a caption, and the songs. The cover is the first song's own picture until a
 * photo is chosen or it is removed (iOS SongSheetCover). Tap a song to rename its title and artist,
 * hold and drag to move it, swipe it left to take it out. Send gives the songs in their order, each
 * with its renamed words, and the cover: null = the first song's own, empty = none, else the photo.
 */
@Composable
internal fun PlaylistComposeSheet(uris: List<Uri>, onDismiss: () -> Unit,
                                  onSend: (List<Uri>, String, String, List<Pair<String?, String?>>, ByteArray?) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val songs = remember { mutableStateListOf<PickedSong>().also { l ->
        uris.take(MusicRules.MAX_TRACKS).forEach { u -> l += PickedSong(u, pickedName(ctx, u) ?: "Audio") } } }
    var name by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    // iOS SongSheetCover: null = .own, empty = .none (removed), else .picked (a 600px JPEG).
    var coverPick by remember { mutableStateOf<ByteArray?>(null) }
    var coverMenu by remember { mutableStateOf(false) }
    var loadingCover by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Uri?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val photoPick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) {
            loadingCover = true
            scope.launch {
                // Shrunk to the cover's size as a JPEG, which also drops the camera's metadata (iOS coverJPEG).
                val jpeg = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
                        ?.let { MusicTags.artJpeg(it, MusicRules.COVER_PIXELS) }
                }
                loadingCover = false
                if (jpeg != null && jpeg.isNotEmpty()) coverPick = jpeg
            }
        }
    }
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
                        onSend(songs.map { it.uri }, name, caption, songs.map { it.sent }, coverPick)
                    }.padding(6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pick = coverPick
                val shown = when {
                    pick == null -> songs.firstOrNull()?.art
                    pick.isEmpty() -> null
                    else -> pick
                }
                val art = rememberArt(shown)
                // Tap the cover: Choose Photo, or Remove (iOS coverMenu).
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.15f))
                    .clickable(enabled = !loadingCover) { coverMenu = true }
                    .semantics { contentDescription = if (art == null) "Add Cover" else "Change Cover" },
                    contentAlignment = Alignment.Center) {
                    if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(ImageVector.cachedVector(R.drawable.ic_sf_music_note), null, tint = c.accent, modifier = Modifier.size(34.dp))
                    Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Icon(ImageVector.cachedVector(R.drawable.ic_sf_pencil), null, tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(13.dp))
                    }
                    if (coverMenu) com.fariin.app.ui.components.GlassDropdown(true, onDismissRequest = { coverMenu = false },
                        shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border,
                        containerColor = c.card) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Choose Photo", color = c.label) },
                            leadingIcon = { Icon(ImageVector.cachedVector(R.drawable.ic_sf_photo_on_rectangle), null, tint = c.label, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                coverMenu = false
                                photoPick.launch(androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        if (art != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Remove", color = c.red) },
                            leadingIcon = { Icon(ImageVector.cachedVector(R.drawable.ic_sf_trash), null, tint = c.red, modifier = Modifier.size(20.dp)) },
                            onClick = { coverMenu = false; coverPick = ByteArray(0) })
                    }
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
                            .clickable(onClickLabel = "Rename") { renaming = s.uri }
                            .semantics { contentDescription = "Song ${i + 1}. Tap to rename, hold and drag to move, swipe left to remove" },
                            verticalAlignment = Alignment.CenterVertically) {
                            val art = rememberArt(s.art)
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center) {
                                if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Icon(ImageVector.cachedVector(R.drawable.ic_sf_music_note), null, tint = c.accent, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.shownTitle, fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.shownArtist ?: "Unknown artist", fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // Rename Song (iOS): the title and artist; cleared or unchanged is the song's own again.
    val renameAt = renaming?.let { u -> songs.indexOfFirst { it.uri == u } } ?: -1
    if (renameAt >= 0) {
        val u = songs[renameAt].uri
        var t by remember(u) { mutableStateOf(songs[renameAt].shownTitle) }
        var a by remember(u) { mutableStateOf(songs[renameAt].shownArtist.orEmpty()) }
        com.fariin.app.ui.components.GlassAlert(
            onDismissRequest = { renaming = null },
            title = { Text("Rename Song") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetField(t, "Title") { t = it.take(MusicRules.SONG_FIELD_LIMIT) }
                    SheetField(a, "Artist") { a = it.take(MusicRules.SONG_FIELD_LIMIT) }
                }
            },
            dismissButton = { Text("Cancel", fontSize = 17.sp, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { renaming = null }.padding(8.dp)) },
            confirmButton = { Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    renaming = null
                    val j = songs.indexOfFirst { it.uri == u }
                    if (j >= 0) songs[j] = songs[j].copy(newTitle = MusicRules.sentField(t, songs[j].ownTitle),
                        newArtist = MusicRules.sentField(a, songs[j].artist))
                }.padding(8.dp)) },
        )
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
