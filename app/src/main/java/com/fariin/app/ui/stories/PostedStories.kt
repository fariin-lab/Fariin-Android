package com.fariin.app.ui.stories

import com.fariin.app.ui.components.GlassDropdown
import com.fariin.app.ui.components.GlassAlert
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.PostedStoriesRules
import com.fariin.app.data.Story
import com.fariin.app.data.StoryService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fariin.app.ui.components.floating
import kotlinx.coroutines.async

/**
 * My Posted Stories (iOS PostedStoriesView for my own uid): my live stories in folders by who they
 * went to (Everyone, My Chats, Custom, View once), three to a row, with the filter in the title.
 * A tap opens the viewer on that story; a long press offers Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostedStoriesPage(
    me: String,
    stories: List<Story>,
    myPhoto: String?,
    onOpen: (Story) -> Unit,
    onBack: () -> Unit,
    conversations: List<com.fariin.app.data.Conversation> = emptyList(),
    myHandle: String? = null,
) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val service = remember(me) { StoryService(me) }
    var filter by rememberSaveable { mutableStateOf(PostedStoriesRules.Filter.ALL) }
    var filterMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Story?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editViewers by remember { mutableStateOf<Story?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(me) { com.fariin.app.data.StoryPrivacyStore.start(me) }
    val custom by com.fariin.app.data.StoryPrivacyStore.custom.collectAsState()
    val links by com.fariin.app.data.StoryPrivacyStore.links.collectAsState()
    BackHandler(enabled = editViewers == null) { onBack() }
    val all = remember(stories) { stories.sortedByDescending { it.createdAtMs } }
    val rows = all.filter { filter.matches(it) }
    val folders = PostedStoriesRules.folders(rows, mergeCustom = filter != PostedStoriesRules.Filter.CUSTOM) {
        com.fariin.app.data.StoryPrivacyRules.storyName(links[it.id], custom)
    }

    // iOS edit mode: select stories to delete them together.
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // iOS loadViewCounts: every tile's count asked at once; no badge while a count is unknown.
    val counts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val ids = all.map { it.id }
    LaunchedEffect(ids) {
        kotlinx.coroutines.coroutineScope {
            ids.filter { it !in counts }.forEach { id ->
                launch { service.viewSummary(id) { "" to null }?.let { counts[id] = it.count } }
            }
        }
    }
    LaunchedEffect(ids) { selected = selected.filter { it in ids }.toSet() }
    fun deleteSelected() {
        deleting = true
        scope.launch {
            val chosen = selected.toList()
            val failed = kotlinx.coroutines.coroutineScope {
                chosen.map { id -> async { id to service.deleteStory(id) } }.map { it.await() }
            }.filter { !it.second }.map { it.first }.toSet()
            selected = failed
            if (failed.isNotEmpty()) error = "Couldn't delete. Check your connection and try again."
            deleting = false
            if (selected.isEmpty()) editing = false
        }
    }
    val folderView = filter == PostedStoriesRules.Filter.ALL || filter == PostedStoriesRules.Filter.CUSTOM

    Box(Modifier.fillMaxSize()) {
    com.fariin.app.ui.components.GlassColumnPage(false, Modifier.background(c.bg), page = c.bg, barEstimate = 56.dp, bar = {
        // iOS: Back in a glass circle, the filter centred as the title with its chevron, Edit (Done)
        // in a glass capsule at the end.
        Box(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp)) {
            com.fariin.app.ui.components.FloatingBack(onBack, Modifier.align(Alignment.CenterStart), circle = true)
            Box(Modifier.align(Alignment.Center)) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { filterMenu = true }).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(filter.title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_down), contentDescription = "Filter", tint = c.label, modifier = Modifier.size(16.dp))
                }
                GlassDropdown(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    PostedStoriesRules.Filter.entries.forEach { f ->
                        DropdownMenuItem(text = { Text(f.title, fontWeight = if (f == filter) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { filter = f; filterMenu = false })
                    }
                }
            }
            if (all.isNotEmpty()) com.fariin.app.ui.components.FloatingTextAction(if (editing) "Done" else "Edit", modifier = Modifier.align(Alignment.CenterEnd)) {
                editing = !editing
                if (!editing) selected = emptySet()
            }
        }
    }) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        error?.let { Text(it, color = c.red, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        when {
            all.isEmpty() -> Empty(R.drawable.ic_stories_stack, "No live stories", "Stories disappear after 24 hours.")
            rows.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Nothing in ${filter.title}", color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(filter.explain, color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
                TextButton(onClick = { filter = PostedStoriesRules.Filter.ALL }) { Text("Clear Filter", color = c.accent) }
            }
            // iOS StoryTileGrid: three columns, 6 apart, 12 from the edges; tiles 0.655 wide to tall,
            // corner 24.
            else -> LazyVerticalGrid(
                GridCells.Fixed(PostedGrid.COLUMNS), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = PostedGrid.MARGIN, end = PostedGrid.MARGIN, top = if (folderView) 0.dp else 8.dp,
                    bottom = 24.dp + (if (editing) 64.dp else 0.dp) + com.fariin.app.ui.shell.bottomBarInset()),
                horizontalArrangement = Arrangement.spacedBy(PostedGrid.GAP), verticalArrangement = Arrangement.spacedBy(PostedGrid.GAP),
            ) {
                val tile: @Composable (Story) -> Unit = { s ->
                    PostedTile(s, counts[s.id], editing = editing, chosen = s.id in selected,
                        onTap = {
                            if (editing) selected = if (s.id in selected) selected - s.id else selected + s.id
                            else onOpen(s)
                        },
                        onHold = { if (!editing) menuFor = s.id }) {
                        // iOS hold menu: Edit Viewers, Share (only a story that went to Everyone), Delete.
                        GlassDropdown(expanded = menuFor == s.id, onDismissRequest = { menuFor = null }) {
                            if (PostedStoriesRules.canEditViewers(s)) DropdownMenuItem(text = { Text("Edit Viewers") },
                                leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_gs_members), contentDescription = null) },
                                onClick = { menuFor = null; editViewers = s })
                            if (PostedStoriesRules.canShare(s)) DropdownMenuItem(text = { Text("Share") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_link_share), contentDescription = null, modifier = Modifier.size(24.dp)) },
                                onClick = {
                                    menuFor = null
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(android.content.Intent.EXTRA_TEXT, PostedStoriesRules.storyLink(s.id, myHandle))
                                    ctx.startActivity(android.content.Intent.createChooser(send, "Share"))
                                })
                            DropdownMenuItem(text = { Text("Delete", color = c.red) },
                                leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), contentDescription = null, tint = c.red) },
                                onClick = { menuFor = null; confirmDelete = s })
                        }
                    }
                }
                if (folderView) folders.forEachIndexed { i, folder ->
                    // iOS: the folder's name alone, 17 semibold, 18 from the edge, 4 above the first
                    // (18 above the others) and 10 above its tiles; the grid's 6 is part of those.
                    item(key = "h:" + folder.key, span = { GridItemSpan(maxLineSpan) }) {
                        Text(folder.name, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp, top = if (i == 0) 4.dp else 12.dp, bottom = 4.dp))
                    }
                    items(folder.stories, key = { it.id }) { s -> tile(s) }
                } else items(rows, key = { it.id }) { s -> tile(s) }
            }
        }
    }
    if (editing) {
        // iOS edit bar: "Select Stories" / "N Selected" in a glass capsule, the red trash at the end.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Box(Modifier.align(Alignment.Center).height(44.dp).floating(CircleShape).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(if (selected.isEmpty()) "Select Stories" else "${selected.size} Selected", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected.isEmpty()) c.secondary else c.label, maxLines = 1)
            }
            Box(Modifier.align(Alignment.CenterEnd).size(44.dp).floating()
                .clickable(enabled = selected.isNotEmpty() && !deleting) { confirmDeleteSelected = true }
                .semantics { contentDescription = "Delete" },
                contentAlignment = Alignment.Center) {
                if (deleting) com.fariin.app.ui.components.FariinSpinner(size = 20.dp, color = c.label)
                else Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), contentDescription = null, tint = c.red.copy(alpha = if (selected.isEmpty()) 0.4f else 1f),
                    modifier = Modifier.size(22.dp))
            }
        }
    }
    editViewers?.let { s ->
        // iOS ShareStorySheet(editing:): the same page, Update instead of Post Story.
        ShareStoryAudiencePage(me, conversations, myPhoto = myPhoto, editing = s, busy = updating,
            onBack = { editViewers = null }) { choice, _, lists, _ ->
            updating = true
            scope.launch {
                try {
                    service.updateAudience(s, choice, lists, storyConvs(conversations),
                        com.fariin.app.data.StoryPrivacyStore.myFriends.value, com.fariin.app.data.StoryPrivacyStore.hidden.value)
                    editViewers = null
                } catch (e: StoryService.PostRefused) {
                    updateError = e.message
                } catch (e: Exception) {
                    android.util.Log.w("StoryEdit", "update failed", e)
                    updateError = "Who can see this story hasn't changed. Check your connection and try again."
                }
                updating = false
            }
        }
    }
    }
    updateError?.let { msg ->
        GlassAlert(
            onDismissRequest = { updateError = null },
            title = { Text("Couldn't update") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { updateError = null }) { Text("OK") } },
        )
    }
    if (confirmDeleteSelected) {
        val n = selected.size
        GlassAlert(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete $n ${if (n == 1) "story" else "stories"}?") },
            text = { Text("This cannot be undone. People who already saw them keep what they saw.") },
            confirmButton = { TextButton(onClick = { confirmDeleteSelected = false; deleteSelected() }) { Text("Delete", color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { s ->
        GlassAlert(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this story?") },
            text = { Text("It will also be deleted for everyone who received it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { if (!service.deleteStory(s.id)) error = "Couldn't delete. Check your connection and try again." }
                }) { Text("Delete", color = c.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Empty(icon: Int, title: String, line: String) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = c.label, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(line, color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

/** iOS StoryTileGrid / PostedTile: three columns, 6 apart, 12 from the edges, 0.655 cards at corner 24. */
private object PostedGrid {
    const val COLUMNS = 3
    val GAP = 6.dp
    val MARGIN = 12.dp
    const val ASPECT = 0.655f
    val CORNER = 24.dp
}

/** The open story's slot for a Posted Stories tile (the viewer flies out of it and back in). */
fun postedTileKey(storyId: String) = "postedpage-$storyId"

/** iOS short count: 999, 1.2K, 12K, 1.2M. */
private fun shortCount(n: Int): String = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> (n / 1_000.0).let { k -> if (k < 10) String.format(java.util.Locale.US, "%.1fK", k) else "${k.toInt()}K" }
    else -> (n / 1_000_000.0).let { m -> if (m < 10) String.format(java.util.Locale.US, "%.1fM", m) else "${m.toInt()}M" }
}

/**
 * One Posted Stories card (iOS PostedStoryTile): the picture at 0.655, corner 24; a 52 dp black wash
 * (45 %) at the foot under the eye and the count in white 13 semibold, 8 in from the corner; a video
 * mark at the top end; in edit mode the round tick at the top start. Registers itself as the story's
 * flight slot and stays empty while its story is open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostedTile(s: Story, views: Int?, editing: Boolean, chosen: Boolean, onTap: () -> Unit, onHold: () -> Unit,
                       menu: @Composable () -> Unit) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(PostedGrid.CORNER)
    val key = postedTileKey(s.id)
    val view = androidx.compose.ui.platform.LocalView.current
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { PostedGrid.CORNER.toPx() }
    androidx.compose.runtime.DisposableEffect(key) {
        onDispose { StoryOrigins.rects.remove(key); StoryOrigins.radii.remove(key) }
    }
    Box(
        Modifier.aspectRatio(PostedGrid.ASPECT)
            .onGloballyPositioned { lc ->
                // In screen pixels: the viewer is its own window.
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val p = lc.positionInWindow()
                StoryOrigins.rects[key] = androidx.compose.ui.geometry.Rect(
                    p.x + loc[0], p.y + loc[1], p.x + loc[0] + lc.size.width, p.y + loc[1] + lc.size.height)
                StoryOrigins.radii[key] = radiusPx
            }
            .graphicsLayer { alpha = if (StoryOrigins.hiddenKey == key) 0f else 1f }
            .clip(shape).background(c.placeholderFill)
            .combinedClickable(onClick = onTap, onLongClick = onHold)
            .semantics { contentDescription = (if (s.isVideo) "Video story" else "Photo story") + (views?.let { ", ${shortCount(it)} " + (if (it == 1) "view" else "views") } ?: "") },
    ) {
        AsyncImage(s.listThumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (views != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(52.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
            Row(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_views), contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(shortCount(views), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (s.isVideo) Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(23.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_play_fill), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        if (editing) Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp)
            .shadow(2.dp, CircleShape).clip(CircleShape)
            .background(if (chosen) Color(0xFF0A84FF) else Color.Transparent)
            .border(if (chosen) 0.dp else 1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            if (chosen) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        menu()
    }
}
