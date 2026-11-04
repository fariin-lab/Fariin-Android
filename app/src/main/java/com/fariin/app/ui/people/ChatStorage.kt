package com.fariin.app.ui.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.HiddenMessages
import com.fariin.app.data.Message
import com.fariin.app.data.SharedMediaSource
import com.fariin.app.media.ChatMedia
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRules
import com.fariin.app.ui.settings.YesNo
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.thread.rememberBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A chat's storage, item by item (iOS ChatStorageView): everything of this chat that sits on this
 * phone, biggest first, with its size on the tile; sort Largest / Newest / Oldest; select some or
 * all and delete them from this phone. As iOS, deleting here is Delete for Me: the message is
 * hidden on this phone (HiddenMessages) and its files go with it. Everyone else keeps theirs.
 */
internal object ChatStorageRules {
    enum class Kind { PHOTO, VIDEO, ALBUM, VOICE, FILE }
    enum class Sort(val label: String) { LARGEST("Largest"), NEWEST("Newest"), OLDEST("Oldest") }
    data class Item(val m: Message, val kind: Kind, val bytes: Long, val files: List<File>, val picture: File?)

    fun urls(m: Message): List<String> = buildList {
        m.imageUrl?.let(::add); m.thumbUrl?.let(::add); m.videoUrl?.let(::add); m.audioUrl?.let(::add); m.fileUrl?.let(::add)
        m.album.forEach { add(it.imageUrl); it.videoUrl?.let(::add) }
    }.filter { it.isNotEmpty() }.distinct()

    fun kind(m: Message): Kind? = when {
        m.album.isNotEmpty() -> Kind.ALBUM
        m.videoUrl != null -> Kind.VIDEO
        m.audioUrl != null -> Kind.VOICE
        m.fileUrl != null -> Kind.FILE
        m.imageUrl != null -> Kind.PHOTO
        else -> null
    }

    fun items(messages: List<Message>, media: ChatMedia, hidden: (String) -> Boolean): List<Item> {
        val seen = HashSet<String>()
        return messages.mapNotNull { m ->
            if (!seen.add(m.id) || m.deleted || m.viewOnce || hidden(m.id)) return@mapNotNull null
            val kind = kind(m) ?: return@mapNotNull null
            val files = urls(m).mapNotNull { media.cached(it) }
            val bytes = files.sumOf { it.length() }
            if (bytes <= 0) return@mapNotNull null
            val pic = listOfNotNull(m.imageUrl, m.thumbUrl, m.album.firstOrNull()?.imageUrl).firstNotNullOfOrNull { media.cached(it) }
            Item(m, kind, bytes, files, pic)
        }
    }

    fun sorted(items: List<Item>, sort: Sort): List<Item> = when (sort) {
        Sort.LARGEST -> items.sortedWith(compareByDescending<Item> { it.bytes }.thenByDescending { it.m.createdAtMillis })
        Sort.NEWEST -> items.sortedByDescending { it.m.createdAtMillis }
        Sort.OLDEST -> items.sortedBy { it.m.createdAtMillis }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatStoragePage(cid: String, me: String, title: String, pop: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val media = remember(cid) { ChatMedia(ctx.applicationContext, cid, CryptoHub.get(ctx.applicationContext)) }
    val hidden by HiddenMessages.ids.collectAsState()
    var gen by remember { mutableStateOf(0) }
    val items by produceState<List<ChatStorageRules.Item>?>(null, cid, gen, hidden) {
        value = withContext(Dispatchers.IO) {
            val source = SharedMediaSource(cid, me, CryptoHub.get(ctx.applicationContext))
            val msgs = (source.fetch() ?: runCatching { source.local() }.getOrDefault(emptyList()))
            ChatStorageRules.items(msgs, media) { it in hidden }
        }
    }
    var sort by remember { mutableStateOf(ChatStorageRules.Sort.LARGEST) }
    var sortOpen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val list = items?.let { ChatStorageRules.sorted(it, sort) }
    val total = list?.sumOf { it.bytes } ?: 0L
    val pickedBytes = list?.filter { it.m.id in selected }?.sumOf { it.bytes } ?: 0L
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }

    androidx.activity.compose.BackHandler(selecting) { selecting = false; selected = emptySet() }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar(if (selecting) (if (selected.isEmpty()) "Select Items" else "${selected.size} Selected") else "Storage",
            onBack = if (selecting) null else pop,
            action = if (!list.isNullOrEmpty()) ({
                TextButton(onClick = { selecting = !selecting; if (!selecting) selected = emptySet() }) {
                    Text(if (selecting) "Done" else "Select", color = c.accent, fontSize = 17.sp)
                }
            }) else null)
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(span = { GridItemSpan(3) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (list != null) SettingsRules.bytes(total) else " ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                            Text(if (list != null) "${list.size} ${if (list.size == 1) "item" else "items"} from $title on this phone" else "Measuring…",
                                fontSize = 13.sp, color = c.secondary, maxLines = 1)
                        }
                        Box {
                            Row(Modifier.clip(CircleShape).background(c.card).clickable(enabled = !list.isNullOrEmpty()) { sortOpen = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.SwapVert, null, tint = c.label, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(sort.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label)
                            }
                            DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }, containerColor = c.card) {
                                ChatStorageRules.Sort.entries.forEach { s ->
                                    DropdownMenuItem(text = { Text(s.label, color = c.label) }, onClick = { sort = s; sortOpen = false },
                                        trailingIcon = if (s == sort) ({ Icon(Icons.Filled.Check, null, tint = c.accent) }) else null)
                                }
                            }
                        }
                    }
                }
                when {
                    list == null -> items(12) { Box(Modifier.aspectRatio(1f).background(c.label.copy(alpha = 0.07f))) }
                    list.isEmpty() -> item(span = { GridItemSpan(3) }) {
                        Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Storage, null, tint = c.secondary, modifier = Modifier.size(38.dp))
                            Spacer(Modifier.size(10.dp))
                            Text("Nothing from this chat is stored on this phone.", fontSize = 15.sp, color = c.secondary)
                        }
                    }
                    else -> items(list, key = { it.m.id }) { item ->
                        val on = item.m.id in selected
                        Box(Modifier.aspectRatio(1f).background(c.label.copy(alpha = 0.07f))
                            .combinedClickable(
                                onLongClick = { if (!selecting) { selecting = true; selected = selected + item.m.id } },
                                onClick = { if (!selecting) selecting = true; toggle(item.m.id) })) {
                            val bmp = rememberBitmap(item.picture, 360)
                            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize().background(c.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                if (item.kind == ChatStorageRules.Kind.VOICE) Icon(Icons.Filled.Mic, null, tint = c.accent, modifier = Modifier.size(34.dp))
                                else Text(item.m.fileName?.substringAfterLast('.', "")?.uppercase()?.take(4)?.ifEmpty { null } ?: "FILE",
                                    fontWeight = FontWeight.Bold, color = c.accent, fontSize = 16.sp)
                            }
                            Text(SettingsRules.bytes(item.bytes), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 3.dp))
                            if (item.kind == ChatStorageRules.Kind.VIDEO) Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
                            if (selecting && on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                            if (selecting) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                .background(if (on) c.blue else Color.Black.copy(alpha = 0.25f)).border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (on) Icon(Icons.Filled.Check, "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(3) }) { Spacer(Modifier.size(if (selecting) 110.dp else 24.dp)) }
            }
            if (selecting && list != null) {
                val all = list.isNotEmpty() && selected.size == list.size
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.bgSecondary).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = if (all) emptySet() else list.map { it.m.id }.toSet() }) {
                        Text(if (all) "Deselect All" else "Select All", color = c.accent, fontSize = 17.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = selected.isNotEmpty(), onClick = { confirm = true }) {
                        Text(if (selected.isEmpty()) "Delete" else "Delete ${SettingsRules.bytes(pickedBytes)}",
                            color = if (selected.isEmpty()) c.secondary else c.red, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (confirm) YesNo(if (selected.size == 1) "Delete 1 item from this phone?" else "Delete ${selected.size} items from this phone?",
        "They're removed from this chat on this phone only and can't be brought back here. Everyone else in the chat keeps theirs.",
        "Delete from This Phone", onYes = {
            confirm = false
            val gone = list.orEmpty().filter { it.m.id in selected }
            gone.forEach { item -> HiddenMessages.hide(ctx, item.m.id); item.files.forEach { runCatching { it.delete() } } }
            selected = emptySet(); selecting = false; gen++
        }, onNo = { confirm = false })
}
