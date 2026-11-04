package com.fariin.app.ui.stories

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
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

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.label) }
            Box {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { filterMenu = true }).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(filter.title, color = c.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Filter", tint = c.label)
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    PostedStoriesRules.Filter.entries.forEach { f ->
                        DropdownMenuItem(text = { Text(f.title, fontWeight = if (f == filter) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { filter = f; filterMenu = false })
                    }
                }
            }
        }
        error?.let { Text(it, color = c.red, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        when {
            all.isEmpty() -> Empty(R.drawable.ic_stories_stack, "No live stories", "Stories disappear after 24 hours.")
            rows.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Nothing in ${filter.title}", color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(filter.explain, color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
                TextButton(onClick = { filter = PostedStoriesRules.Filter.ALL }) { Text("Clear Filter", color = c.accent) }
            }
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + com.fariin.app.ui.shell.bottomBarInset()),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                folders.forEach { folder ->
                    item(key = "h:" + folder.key, span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (folder.kind == "everyone") Avatar(myPhoto, 28.dp)
                            else Box(Modifier.size(28.dp).clip(CircleShape).background(if (folder.kind == "custom") Color.Gray else Color(0xFFFF9500)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Group, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(folder.name, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(folder.stories, key = { it.id }) { s ->
                        Box(
                            Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(c.placeholderFill)
                                .combinedClickable(onClick = { onOpen(s) }, onLongClick = { menuFor = s.id }),
                        ) {
                            AsyncImage(s.listThumb, contentDescription = "Story", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            // iOS hold menu: Edit Viewers, Share (only a story that went to Everyone), Delete.
                            DropdownMenu(expanded = menuFor == s.id, onDismissRequest = { menuFor = null }) {
                                if (PostedStoriesRules.canEditViewers(s)) DropdownMenuItem(text = { Text("Edit Viewers") },
                                    leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null) },
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
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = c.red) },
                                    onClick = { menuFor = null; confirmDelete = s })
                            }
                        }
                    }
                }
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
        AlertDialog(
            onDismissRequest = { updateError = null },
            title = { Text("Couldn't update") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { updateError = null }) { Text("OK") } },
        )
    }
    confirmDelete?.let { s ->
        AlertDialog(
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
