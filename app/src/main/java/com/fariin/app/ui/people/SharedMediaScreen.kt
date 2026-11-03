package com.fariin.app.ui.people

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.fariin.app.ui.components.floating
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.HiddenMessages
import com.fariin.app.data.Message
import com.fariin.app.data.SharedMedia
import com.fariin.app.data.SharedMediaSource
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.MediaRules
import com.fariin.app.ui.thread.FileBody
import com.fariin.app.ui.thread.LocalChatMedia
import com.fariin.app.ui.thread.MediaViewer
import com.fariin.app.ui.thread.Viewing
import com.fariin.app.ui.thread.VoiceBody
import com.fariin.app.ui.thread.rememberBitmap
import com.fariin.app.ui.thread.rememberPlaceholder
import com.fariin.app.ui.thread.rememberPlainFile
import com.fariin.app.ui.theme.Fariin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All Media (Kulan-iOS MediaGalleryView and ProfileMediaCard): the profile's card with a strip of
 * the newest photos and videos and See All, and the page it opens, with Media / Files / Voice /
 * Links tabs. Files open as the file bubble opens them, voice notes play with the chat's player,
 * links open in the browser, photos and videos in the chat's viewer. Every file comes through the
 * chat's ChatMedia, so it is opened with the chat's keys.
 */
internal class SharedMediaState(val lists: SharedMedia.Lists, val loaded: Boolean)

/** Local first (the last answer, or the chat's saved copy), then the server (iOS loadMedia). */
@Composable
internal fun rememberSharedMedia(cid: String, me: String): SharedMediaState {
    val ctx = LocalContext.current
    // Delete for Me in the chat takes the item off these pages too (iOS isGone).
    val hidden by HiddenMessages.ids.collectAsState()
    val raw by produceState<Pair<List<Message>, Boolean>>(emptyList<Message>() to false, cid, me) {
        val source = SharedMediaSource(cid, me, CryptoHub.get(ctx.applicationContext))
        val local = withContext(Dispatchers.IO) { runCatching { source.local() }.getOrDefault(emptyList()) }
        if (local.isNotEmpty()) value = local to false
        // A failed load keeps what the phone knows; with nothing at all, one more try (iOS 2026-10-20).
        var fresh = source.fetch()
        if (fresh == null && local.isEmpty()) { delay(3_000); fresh = source.fetch() }
        value = (fresh ?: value.first) to true
    }
    return remember(raw, hidden) {
        SharedMediaState(SharedMedia.lists(raw.first, System.currentTimeMillis().toDouble()) { it in hidden }, raw.second)
    }
}

/** The chat's file source for these pages, so pictures and notes are opened with its keys. */
@Composable
private fun ProvideChatMedia(cid: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val media = LocalChatMedia.current ?: remember(cid) { ChatMedia(ctx.applicationContext, cid, CryptoHub.get(ctx.applicationContext)) }
    CompositionLocalProvider(LocalChatMedia provides media, content = content)
}

/** iOS MediaGalleryView: a photo pages through the photos of [all] in the grid's order; a video opens alone. */
private fun viewingOf(item: SharedMedia.Item, all: List<SharedMedia.Item>): Viewing =
    com.fariin.app.data.ViewerRules.sharedPages(all, item).let { (pages, start) -> Viewing.Pages(pages, start) }

/**
 * The profile's All Media card (iOS ProfileMediaCard): the heading and See All on one line, one row
 * of up to twelve thumbnails under it. A thumbnail opens the viewer, anywhere else opens the page.
 * Drawn only when the chat has photos or videos (iOS: no media, no card).
 */
@Composable
internal fun SharedMediaCard(cid: String, state: SharedMediaState, onSeeAll: () -> Unit) {
    val strip = state.lists.media.take(SharedMedia.STRIP)
    if (strip.isEmpty()) return
    val c = Fariin.colors
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    ProvideChatMedia(cid) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).clickable(onClick = onSeeAll)
                .padding(vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("All Media", style = MaterialTheme.typography.titleMedium, color = c.label, modifier = Modifier.weight(1f))
                    Text("See All", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.blue)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.secondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(strip, key = { it.id }) { item ->
                        MediaTile(item, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))) { viewing = viewingOf(item, state.lists.media) }
                    }
                }
            }
        }
        viewing?.let { MediaViewer(it) { viewing = null } }
    }
}

/** A square thumbnail: the inline placeholder under the picture, a play disc on a video. */
@Composable
private fun MediaTile(item: SharedMedia.Item, modifier: Modifier, onOpen: () -> Unit) {
    val c = Fariin.colors
    val px = with(LocalDensity.current) { 120.dp.roundToPx() }
    val file = rememberPlainFile(item.pictureUrl, item.pictureEnc)
    val bmp = rememberBitmap(file, px)
    val placeholder = rememberPlaceholder(item.thumb, item.blurhash)
    Box(modifier.background(c.secondary.copy(alpha = 0.18f)).clickable(onClick = onOpen)) {
        (bmp ?: placeholder)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (item.isVideo) {
            Box(Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            item.duration?.let { d ->
                Text(MediaRules.durationLabel(d), fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

/**
 * The All Media page (iOS MediaGalleryView): the count line for the tab on screen under the title,
 * the four tabs (swipe or tap), and on Media the Show filter in the overflow menu.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SharedMediaScreen(cid: String, state: SharedMediaState, onBack: () -> Unit) {
    val c = Fariin.colors
    val tabs = SharedMedia.Tab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(SharedMedia.Filter.ALL) }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    BackHandler(onBack = onBack)
    val tab = tabs[pager.currentPage]
    val media = SharedMedia.filtered(state.lists.media, filter)
    ProvideChatMedia(cid) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Box(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
                com.fariin.app.ui.components.FariinTopBar(
                    "All Media", onBack = onBack,
                    // Never "0 photos, 0 videos" while still loading (iOS 2026-10-17).
                    subtitle = if (state.loaded) SharedMedia.subtitle(tab, state.lists, media) else " ",
                    actions = {
                        // No menu on an empty tab (iOS showsMoreMenu): Show is the only thing in it here.
                        if (tab == SharedMedia.Tab.MEDIA && state.lists.media.isNotEmpty()) Box {
                            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = c.label) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                                Text("Show", style = MaterialTheme.typography.labelMedium, color = c.secondary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                SharedMedia.Filter.entries.forEach { f ->
                                    DropdownMenuItem(text = { Text(f.label, color = c.label) }, onClick = { filter = f; menu = false },
                                        trailingIcon = { if (f == filter) Icon(Icons.Filled.Check, null, tint = c.blue) })
                                }
                            }
                        }
                    },
                )
            }
            // The tabs as one floating capsule with the chosen one in a pill (iOS segmented picker),
            // in place of Material's underlined tab row.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(40.dp)
                .floating(CircleShape).padding(3.dp)) {
                tabs.forEachIndexed { i, t ->
                    val on = pager.currentPage == i
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (on) c.fieldFill else Color.Transparent)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .semantics { role = androidx.compose.ui.semantics.Role.Tab; selected = on },
                        contentAlignment = Alignment.Center) {
                        Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (on) c.label else c.secondary,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (tabs[page]) {
                    SharedMedia.Tab.MEDIA -> MediaGrid(media, state.loaded) { viewing = viewingOf(it, media) }
                    SharedMedia.Tab.FILES -> Rows(state.loaded, state.lists.files.isEmpty(), SharedMedia.Tab.FILES, Icons.Outlined.Description) {
                        items(state.lists.files, key = { it.id }) { m -> FileRow(m) }
                    }
                    SharedMedia.Tab.VOICE -> Rows(state.loaded, state.lists.voice.isEmpty(), SharedMedia.Tab.VOICE, Icons.Outlined.Mic) {
                        items(state.lists.voice, key = { it.id }) { m -> VoiceRow(cid, m) }
                    }
                    SharedMedia.Tab.LINKS -> Rows(state.loaded, state.lists.links.isEmpty(), SharedMedia.Tab.LINKS, Icons.Outlined.Link) {
                        items(state.lists.links, key = { it.first.id }) { (m, link) -> LinkRow(m, link) }
                    }
                }
            }
        }
        viewing?.let { MediaViewer(it) { viewing = null } }
    }
}

/** Three square columns with a hairline gap, under date headings (iOS grid + sections). */
@Composable
private fun MediaGrid(media: List<SharedMedia.Item>, loaded: Boolean, onOpen: (SharedMedia.Item) -> Unit) {
    val c = Fariin.colors
    val sections = remember(media) { SharedMedia.sections(media, System.currentTimeMillis().toDouble()) }
    if (loaded && media.isEmpty()) { EmptyTab(SharedMedia.Tab.MEDIA, Icons.Outlined.PhotoLibrary); return }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // While the first answer is on its way: grey tiles, not a spinner (iOS gridSkeleton).
        if (!loaded && media.isEmpty()) items(12) { Box(Modifier.aspectRatio(1f).background(c.secondary.copy(alpha = 0.12f))) }
        sections.forEach { (title, list) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-$title") {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.secondary,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp))
            }
            items(list, key = { it.id }) { item -> MediaTile(item, Modifier.aspectRatio(1f)) { onOpen(item) } }
        }
    }
}

@Composable
private fun Rows(loaded: Boolean, empty: Boolean, tab: SharedMedia.Tab, icon: ImageVector,
                 content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    if (loaded && empty) { EmptyTab(tab, icon); return }
    LazyColumn(Modifier.fillMaxSize(), content = content)
}

@Composable
private fun EmptyTab(tab: SharedMedia.Tab, icon: ImageVector) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.secondary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text(SharedMedia.emptyText(tab), style = MaterialTheme.typography.titleMedium, color = c.secondary)
    }
}

private val DayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private fun day(ms: Double): String = DayFormat.format(Instant.ofEpochMilli(ms.toLong()).atZone(ZoneId.systemDefault()))

/** The chat's own file bubble body, so a tap opens the document exactly as it does in the chat. */
@Composable
private fun FileRow(m: Message) {
    val c = Fariin.colors
    val width = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FileBody(m, isMe = false, width = width) {
            Text(day(m.createdAtMillis), fontSize = 12.sp, color = c.secondary)
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 72.dp))
}

/** The chat's voice player, with the day it was sent (iOS voiceRow). */
@Composable
private fun VoiceRow(cid: String, m: Message) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { VoiceBody(m, cid, isMe = false, width = 240.dp) {} }
        Spacer(Modifier.width(8.dp))
        Text(day(m.createdAtMillis), fontSize = 12.sp, color = c.secondary)
    }
    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 72.dp))
}

/** The host as the title, the message under it, the address in blue; a tap opens the browser. */
@Composable
private fun LinkRow(m: Message, link: SharedMedia.Link) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
        catch (_: ActivityNotFoundException) { com.fariin.app.ui.components.Snack.show("No app on this phone opens this link.") }
    }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(link.host, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1)
        Text(m.text, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(link.url, style = MaterialTheme.typography.bodySmall, color = c.blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
}
