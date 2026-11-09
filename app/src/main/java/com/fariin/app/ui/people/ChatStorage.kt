package com.fariin.app.ui.people

import com.fariin.app.ui.components.GlassDropdown
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import com.fariin.app.ui.settings.BarPage
import androidx.compose.foundation.layout.height

import androidx.compose.animation.togetherWith
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

    /**
     * The last list measured per chat, in memory only, so the page opens on its real tiles at once
     * on re-entry and only refreshes the sizes behind them. Wiped with the shared-media cache at
     * sign-out (it holds opened messages).
     */
    val lastItems = java.util.concurrent.ConcurrentHashMap<String, List<Item>>()

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
    // One screen from the first frame (iOS measures the chat's messages already on the phone, no
    // network): the last list of this chat at once, then the phone's own copy of the chat, then the
    // server's newest window only to add what the saved copy lacks. Each pass keeps the previous
    // list on screen until the next is ready, so the grid never empties or goes grey.
    val measured by produceState<List<ChatStorageRules.Item>?>(ChatStorageRules.lastItems[cid], cid, gen, hidden) {
        val source = SharedMediaSource(cid, me, CryptoHub.get(ctx.applicationContext))
        val local = withContext(Dispatchers.IO) {
            runCatching { ChatStorageRules.items(source.local(), media) { it in hidden } }.getOrNull()
        }
        if (local != null) { value = local; ChatStorageRules.lastItems[cid] = local }
        val fresh = withContext(Dispatchers.IO) {
            source.fetch()?.let { msgs -> runCatching { ChatStorageRules.items(msgs, media) { it in hidden } }.getOrNull() }
        }
        when {
            fresh != null -> {
                // Both reads together: the saved copy can hold older items than the server's window.
                val merged = (fresh + local.orEmpty()).distinctBy { it.m.id }
                if (merged.map { it.m.id to it.bytes } != value?.map { it.m.id to it.bytes }) value = merged
                ChatStorageRules.lastItems[cid] = merged
            }
            local == null -> value = value ?: emptyList()
        }
    }
    // A cached list may still hold what was just deleted here; never show a hidden one.
    val items = measured?.filter { it.m.id !in hidden }
    // "Measuring…" only when it takes long enough to be seen (a quick pass shows no flash).
    var slow by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(cid) { kotlinx.coroutines.delay(150); slow = true }
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
    BarPage(if (selecting) (if (selected.isEmpty()) "Select Items" else "${selected.size} Selected") else "Storage",
            onBack = if (selecting) null else pop,
            // The slot is always there, so the bar keeps its shape; Select wakes when there is a tile.
            action = {
                com.fariin.app.ui.components.FloatingTextAction(if (selecting) "Done" else "Select", enabled = !list.isNullOrEmpty()) {
                    selecting = !selecting; if (!selecting) selected = emptySet()
                }
            }, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(span = { GridItemSpan(3) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // Same two lines in every state; the total fades in over the blank of the same height.
                            androidx.compose.animation.AnimatedContent(if (list != null) SettingsRules.bytes(total) else " ",
                                transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
                                label = "storageTotal") { t ->
                                Text(t, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 1)
                            }
                            Text(when {
                                list != null -> "${list.size} ${if (list.size == 1) "item" else "items"} from $title on this phone"
                                slow -> "Measuring…"
                                else -> " "
                            }, fontSize = 13.sp, color = c.secondary, maxLines = 1)
                        }
                        Box {
                            Row(Modifier.clip(CircleShape).background(c.card).clickable(enabled = !list.isNullOrEmpty()) { sortOpen = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_up_arrow_down), null, tint = c.label, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(sort.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label)
                            }
                            GlassDropdown(sortOpen, onDismissRequest = { sortOpen = false }, containerColor = c.card) {
                                ChatStorageRules.Sort.entries.forEach { s ->
                                    DropdownMenuItem(text = { Text(s.label, color = c.label) }, onClick = { sort = s; sortOpen = false },
                                        trailingIcon = if (s == sort) ({ Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.accent) }) else null)
                                }
                            }
                        }
                    }
                }
                when {
                    // Not known yet (first open, a slow phone): no placeholder grid, the header waits alone.
                    list == null -> Unit
                    list.isEmpty() -> item(span = { GridItemSpan(3) }) {
                        Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_set_storage), null, tint = c.secondary, modifier = Modifier.size(38.dp))
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
                                if (item.kind == ChatStorageRules.Kind.VOICE) Icon(ImageVector.vectorResource(R.drawable.ic_mic), null, tint = c.accent, modifier = Modifier.size(34.dp))
                                else Text(item.m.fileName?.substringAfterLast('.', "")?.uppercase()?.take(4)?.ifEmpty { null } ?: "FILE",
                                    fontWeight = FontWeight.Bold, color = c.accent, fontSize = 16.sp)
                            }
                            Text(SettingsRules.bytes(item.bytes), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 3.dp))
                            if (item.kind == ChatStorageRules.Kind.VIDEO) Icon(ImageVector.vectorResource(R.drawable.ic_sf_play_fill), null, tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
                            if (selecting && on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                            if (selecting) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                .background(if (on) c.blue else Color.Black.copy(alpha = 0.25f)).border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (on) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
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
