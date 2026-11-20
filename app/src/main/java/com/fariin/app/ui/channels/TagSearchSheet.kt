package com.fariin.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.TagSearch
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * Tag Search's sheet (iOS TagPostsSheet, 2026-11-20): round X, "#tag · N posts", All Channels /
 * Following, the posts of public channels using the tag (newest first, 20 a page), placeholders
 * while loading, "No Results", Retry on an error. A tag inside a result opens a new search on top;
 * a tap on a post opens its channel. [startFollowing]: the tag was tapped in a private channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSearchSheet(tag: String, startFollowing: Boolean, onClose: () -> Unit, onOpenChannel: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (startFollowing) 1 else 0) }
    var rows by remember(tab) { mutableStateOf<List<TagSearch.Row>>(emptyList()) }
    var next by remember(tab) { mutableStateOf<String?>(null) }
    var count by remember(tab) { mutableIntStateOf(0) }
    var phase by remember(tab) { mutableStateOf("loading") }   // loading, on, failed, off, locked
    var loadingMore by remember(tab) { mutableStateOf(false) }
    var nested by remember { mutableStateOf<String?>(null) }
    fun load(first: Boolean) {
        if (!first && (next == null || loadingMore)) return
        if (!first) loadingMore = true
        val t = tab
        scope.launch {
            try {
                val p = TagSearch.fetch(tag, t == 1, if (first) null else next)
                if (t != tab) return@launch
                when (p.state) {
                    "off" -> { onClose(); return@launch }
                    "locked" -> phase = "locked"
                    else -> {
                        val seen = rows.map { it.post.mid }.toHashSet()
                        rows = (if (first) emptyList() else rows) + p.rows.filter { first || it.post.mid !in seen }
                        next = p.next
                        if (first) count = p.count
                        phase = "on"
                    }
                }
            } catch (_: Exception) { if (t == tab) { if (first) phase = "failed" } }
            finally { if (t == tab) loadingMore = false }
        }
    }
    LaunchedEffect(tab) { load(true) }
    nested?.let { t -> TagSearchSheet(t, startFollowing, onClose = { nested = null }, onOpenChannel = { cid -> nested = null; onOpenChannel(cid) }) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.fieldFill).clickable(onClickLabel = "Close", onClick = onClose),
                    contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close", tint = c.label, modifier = Modifier.size(14.dp))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tag, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1)
                    if (phase == "on") Text(if (count == 1) "1 post" else "$count posts", fontSize = 13.sp, color = c.secondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.fieldFill).padding(3.dp)) {
                listOf("All Channels", "Following").forEachIndexed { i, label ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(15.dp))
                        .background(if (tab == i) c.card else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { if (tab != i) tab = i }, contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium, color = c.label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (phase) {
                "loading" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp)).background(c.placeholderFill)) }
                }
                "failed" -> Middle("Couldn't load the posts.", "Retry") { phase = "loading"; load(true) }
                "locked" -> Middle("Tag Search isn't available right now.", null) {}
                else -> if (rows.isEmpty()) Middle("No Results", null) {} else LazyColumn(Modifier.fillMaxWidth()) {
                    items(rows, key = { it.channel.cid + "/" + it.post.mid }) { r ->
                        TagRow(r, onTag = { t -> if (t != tag) nested = t }) { onClose(); onOpenChannel(r.channel.cid) }
                    }
                    if (next != null) item(key = "more") {
                        LaunchedEffect(rows.size) { load(false) }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            com.fariin.app.ui.components.FariinSpinner(size = 22.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Middle(text: String, action: String?, onAction: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        if (action != null) Text(action, fontSize = 16.sp, color = c.blue,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(10.dp))
    }
}

/** One result: who posted it (photo, name, tick), then the post as a card: picture, words with their tags, time, views. */
@Composable
private fun TagRow(r: TagSearch.Row, onTag: (String) -> Unit, onOpen: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.channel.photoUrl, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(r.channel.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (r.channel.verified) {
                Spacer(Modifier.width(4.dp))
                Icon(ImageVector.cachedVector(R.drawable.ic_verified_tick), "Verified", tint = c.blue, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
            val pic = if (r.post.sealedMedia) null else r.post.imageUrl ?: r.post.thumbUrl
            if (pic != null) coil3.compose.AsyncImage(model = pic, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).height(180.dp).background(c.placeholderFill))
            else if (r.post.type in setOf("image", "video", "album")) Text(when (r.post.type) { "video" -> "Video"; "album" -> "Album"; else -> "Photo" },
                fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
            if (r.post.text.isNotEmpty()) {
                val text = r.post.text
                val marked = AnnotatedString.Builder(text).apply {
                    for (sp in TagSearch.find(text)) addLink(LinkAnnotation.Clickable(sp.target, TextLinkStyles(SpanStyle(color = c.blue))) { onTag(sp.target) }, sp.start, sp.end)
                }.toAnnotatedString()
                Text(marked, fontSize = 16.sp, lineHeight = 21.sp, color = c.label, maxLines = 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                val time = if (r.post.createdAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(r.post.createdAt).toString() else ""
                Text(listOfNotNull(if (r.post.views > 0) (if (r.post.views == 1) "1 view" else "${r.post.views} views") else null, if (r.post.edited) "edited" else null, time.ifEmpty { null })
                    .joinToString(" · "), fontSize = 12.sp, color = c.secondary)
            }
        }
    }
}
