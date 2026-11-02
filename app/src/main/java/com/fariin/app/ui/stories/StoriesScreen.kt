package com.fariin.app.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.data.StoryMedia
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.Conversation
import com.fariin.app.data.MyProfile
import com.fariin.app.data.MyProfileRepository
import com.fariin.app.data.StoriesRepository
import com.fariin.app.data.StoriesState
import com.fariin.app.data.StoryGroup
import com.fariin.app.data.StoryList
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay

/**
 * The Stories tab in the iOS list style (StoriesTabView.friendsList): "My Story >", my row
 * (Add Story when I have none), then "Recent Stories" (unseen) and "Viewed Stories", which
 * folds. Each row is the newest story's picture with the author's face in the corner, a green
 * to blue ring while unseen, grey once seen. Read only.
 *
 * A row opens the viewer (StoryViewer.kt) on that ring and walks on through the ones below it;
 * the camera button and Add Story open the story camera (StoryCamera.kt), whose gallery button picks
 * a photo or video; either way the composer (StoryComposer.kt) follows.
 *
 * Long press on a row: Send Message and Hide Stories (Unhide Story on the dimmed Hidden Stories
 * list); on my row Add Story and Posted Stories, which the "My Story >" heading also opens.
 *
 * TODO: the more menu, the per-story ring segments.
 */
@Composable
fun StoriesTab(me: String, conversations: List<Conversation>, demo: Pair<MyProfile, StoriesState>?, addRequest: Int = 0) {
    val state: StoriesState
    val profile: MyProfile?
    if (demo != null) {
        profile = demo.first
        state = demo.second
    } else {
        val profRepo = remember { MyProfileRepository() }
        profile = remember(me) { profRepo.listen(me) }.collectFlow(null)
        val convs by rememberUpdatedState(conversations)
        val prof by rememberUpdatedState(profile)
        val repo = remember { StoriesRepository() }
        state = remember(me) {
            repo.listen(me, { convs }, { prof?.name?.ifEmpty { null } ?: "You" }, { prof?.photoUrl })
        }.collectFlow(StoriesState())
    }
    StoriesScreen(me, conversations, profile, state, live = demo == null, addRequest)
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectFlow(initial: T): T {
    var v by remember(this) { mutableStateOf(initial) }
    LaunchedEffect(this) { collect { v = it } }
    return v
}

@Composable
private fun StoriesScreen(me: String, conversations: List<Conversation>, profile: MyProfile?, state: StoriesState, live: Boolean, addRequest: Int) {
    val c = Fariin.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The viewer: which rings it walks through and where it starts.
    var viewing by remember { mutableStateOf<Pair<List<StoryGroup>, Int>?>(null) }
    var composing by remember { mutableStateOf<StoryMedia.Prepared?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    val upload by StoryUploads.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prepareStoryMedia(context, uri)
                .onSuccess { composing = it; pickError = null }
                .onFailure { pickError = it.message ?: "Couldn't open this file." }
        }
    }
    // The story camera (StoryCamera.kt); its gallery button opens the picker, as iOS onLibrary.
    var camera by rememberSaveable { mutableStateOf(false) }
    val openLibrary: () -> Unit = {
        if (live) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val addStory: () -> Unit = { if (live) camera = true }
    // The chat list's camera button: open the camera once per press (not again on recomposition).
    // The count lives outside the tab: the tab's own saved state is dropped when another tab is
    // open, and coming back to Stories must not open the camera again. A smaller count = a new shell.
    LaunchedEffect(addRequest) {
        if (addRequest < AddRequests.handled) AddRequests.handled = addRequest
        if (addRequest > AddRequests.handled) { AddRequests.handled = addRequest; addStory() }
    }
    LaunchedEffect(pickError) { if (pickError != null) { delay(3000); pickError = null } }
    // "Xm ago" moves once a minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    var viewedCollapsed by rememberSaveable { mutableStateOf(false) }
    var hiddenCollapsed by rememberSaveable { mutableStateOf(false) }
    var posted by rememberSaveable { mutableStateOf(false) }
    var hideTarget by remember { mutableStateOf<StoryGroup?>(null) }
    val openChat = com.fariin.app.ui.thread.LocalOpenChat.current
    // Hide Stories (iOS StoryPrefs "hiddenStories") and my story lists (Settings > Stories).
    LaunchedEffect(Unit) { com.fariin.app.data.StoryMutes.load(context) }
    LaunchedEffect(me, live) { if (live) com.fariin.app.data.StoryPrivacyStore.start(me) }
    val muted by com.fariin.app.data.StoryMutes.set.collectAsState()
    val myName = profile?.name?.ifEmpty { null } ?: "You"
    val (shown, hidden) = com.fariin.app.data.StoryMutes.split(state.others, muted)
    val recent = shown.filter { it.hasUnseen }
    val viewed = shown.filter { !it.hasUnseen }
    val ordered = recent + viewed
    val openRing: (StoryGroup) -> Unit = { g -> viewing = ordered to ordered.indexOfFirst { it.key == g.key }.coerceAtLeast(0) }
    // A hidden person's story walks the Hidden Stories list, not the page above it (iOS).
    val openHidden: (StoryGroup) -> Unit = { g -> viewing = hidden to hidden.indexOfFirst { it.key == g.key }.coerceAtLeast(0) }
    val sendMessage: (StoryGroup) -> Unit = { g ->
        val uid = g.stories.firstOrNull()?.authorUid
        val conv = if (g.key.startsWith(StoryGroup.GROUP_PREFIX)) conversations.firstOrNull { it.id == g.key.removePrefix(StoryGroup.GROUP_PREFIX) }
            else conversations.firstOrNull { !it.isGroup && it.otherUid(me) == uid }
        if (conv != null) openChat(conv)
    }
    fun friendMenu(g: StoryGroup) = listOf(
        RowAction("Send Message", R.drawable.ic_menu_chat) { sendMessage(g) },
        RowAction("Hide Stories", R.drawable.ic_eye_off, destructive = true) { hideTarget = g },
    )
    fun hiddenMenu(g: StoryGroup) = listOf(
        RowAction("Send Message", R.drawable.ic_menu_chat) { sendMessage(g) },
        RowAction("Unhide Story", R.drawable.ic_stories) {
            g.stories.firstOrNull()?.authorUid?.let { com.fariin.app.data.StoryMutes.setHidden(context, it, false) }
        },
    )
    val myMenu = listOf(
        RowAction("Add Story", R.drawable.ic_stories) { addStory() },
        RowAction("Posted Stories", R.drawable.ic_stories_stack) { posted = true },
    )
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A second tap on Stories: to the top.
    com.fariin.app.ui.shell.OnTabReselect { listState.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        // Material 3 top app bar: title at the start, actions (camera, overflow) at the end.
        com.fariin.app.ui.components.FariinTopBar("Stories", actions = {
            androidx.compose.material3.IconButton(onClick = addStory) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "Add Story", tint = c.label)
            }
            androidx.compose.material3.IconButton(onClick = { /* TODO: more menu */ }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = c.label)
            }
        })
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            item {
                Row(
                    Modifier.clickable { posted = true }.padding(start = 20.dp, end = 20.dp, top = 8.dp)
                        .semantics { contentDescription = "Posted Stories" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("My Story", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = c.blue)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.blue, modifier = Modifier.size(18.dp))
                }
            }
            item {
                val mine = state.mine
                val newest = mine?.stories?.lastOrNull()
                val uploadLine = when (val u = upload) {
                    StoryUploads.State.Uploading -> "Uploading…"
                    is StoryUploads.State.Failed -> u.message
                    null -> null
                }
                if (mine != null && newest != null) {
                    StoryRow(
                        thumbUrl = newest.listThumb, title = "My Story", subtitle = uploadLine ?: StoryList.ago(newest.createdAtMs, now),
                        authorPhoto = profile?.photoUrl, seen = !mine.hasUnseen, camera = true,
                        backThumbUrl = mine.stories.getOrNull(mine.stories.size - 2)?.listThumb,
                        onClick = { viewing = listOf(mine) to 0 }, onCamera = addStory, menu = myMenu,
                    )
                } else {
                    StoryRow(
                        thumbUrl = "", title = "Add Story", subtitle = uploadLine ?: "Disappears after 24 hours",
                        authorPhoto = profile?.photoUrl, seen = true, camera = true, addBadge = true,
                        onClick = addStory, onCamera = addStory, menu = myMenu,
                    )
                }
                pickError?.let { Text(it, color = c.red, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            if (recent.isNotEmpty()) {
                item { Heading("Recent Stories") }
                items(recent, key = { "r" + it.key }) { g -> FriendRow(g, now, friendMenu(g)) { openRing(g) } }
            }
            if (viewed.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { viewedCollapsed = !viewedCollapsed }
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Viewed Stories", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = c.blue)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (viewedCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (viewedCollapsed) "Show viewed stories" else "Hide viewed stories",
                            tint = c.secondary, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (!viewedCollapsed) items(viewed, key = { "v" + it.key }) { g -> FriendRow(g, now, friendMenu(g)) { openRing(g) } }
            }
            // iOS Hidden Stories: only while there is one, dimmed, folding like Viewed Stories.
            if (hidden.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { hiddenCollapsed = !hiddenCollapsed }
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Hidden Stories", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = c.blue)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (hiddenCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (hiddenCollapsed) "Show hidden stories" else "Hide hidden stories",
                            tint = c.secondary, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (!hiddenCollapsed) items(hidden, key = { "h" + it.key }) { g ->
                    Box(Modifier.alpha(0.45f)) { FriendRow(g, now, hiddenMenu(g)) { openHidden(g) } }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    viewing?.let { (groups, start) ->
        // Live copies of the rings, so a story posted or deleted meanwhile is not stale when opened.
        if (live) StoryViewerDialog(me, groups, start, conversations, onClose = { viewing = null })
        else viewing = null
    }
    if (camera) {
        // A capture goes through the same prepare and composer as a picked item.
        StoryCameraDialog(
            active = composing == null, error = pickError,
            onCaptured = { uri ->
                scope.launch {
                    prepareStoryMedia(context, uri)
                        .onSuccess { composing = it; pickError = null }
                        .onFailure { pickError = it.message ?: "Couldn't open this file." }
                }
            },
            onLibrary = openLibrary, onClose = { camera = false },
        )
    }
    if (posted) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { posted = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            PostedStoriesPage(me, state.mine?.stories ?: emptyList(), profile?.photoUrl,
                onOpen = { s ->
                    // Start the viewer on the tapped story: a watermark just before it.
                    state.mine?.let { g -> viewing = listOf(g.copy(lastViewedAtMs = s.createdAtMs - 1)) to 0 }
                },
                onBack = { posted = false }, conversations = conversations, myHandle = profile?.handle)
        }
    }
    hideTarget?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { hideTarget = null },
            title = { Text("Hide Stories?") },
            text = { Text("New story updates from ${g.name.ifEmpty { "this person" }} won't appear at the top of the stories list anymore.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    g.stories.firstOrNull()?.authorUid?.let { com.fariin.app.data.StoryMutes.setHidden(context, it, true) }
                    hideTarget = null
                }) { Text("Hide Stories", color = c.red) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { hideTarget = null }) { Text("Cancel") } },
        )
    }
    composing?.let { m -> StoryComposerDialog(me, m, conversations, onClose = { composing = null }, onPosted = { composing = null; camera = false }) }
}

@Composable
private fun Heading(title: String) {
    Text(
        title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Fariin.colors.blue,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FriendRow(g: StoryGroup, now: Long, menu: List<RowAction>, onClick: () -> Unit) {
    val newest = g.stories.lastOrNull()
    StoryRow(
        thumbUrl = newest?.listThumb ?: "", title = g.name.ifEmpty { "Fariin user" },
        subtitle = newest?.let { StoryList.ago(it.createdAtMs, now) } ?: "",
        authorPhoto = g.photoUrl, seen = !g.hasUnseen,
        squircle = false, onClick = onClick, menu = menu,
    )
}

/** One entry of a row's long-press menu (iOS CMAction). */
private class RowAction(val title: String, val icon: Int, val destructive: Boolean = false, val run: () -> Unit)

private val UnseenRing = Brush.linearGradient(listOf(Color(0xFF34C76F), Color(0xFF3DA1FD)))

/** iOS StoryListRow: 66 by 94 picture, 18 to the text, 20 side margin, 12 above and below. */
@Composable
private fun StoryRow(
    thumbUrl: String,
    title: String,
    subtitle: String,
    authorPhoto: String?,
    seen: Boolean,
    camera: Boolean = false,
    addBadge: Boolean = false,
    backThumbUrl: String? = null,
    squircle: Boolean = false,
    onClick: () -> Unit = {},
    onCamera: () -> Unit = {},
    menu: List<RowAction> = emptyList(),
) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(14.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = if (menu.isEmpty()) null else ({ menuOpen = true }))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 66.dp, height = 94.dp)) {
            if (!backThumbUrl.isNullOrEmpty()) {
                // The story before the newest, tilted behind it, cut off by a band of page colour.
                if (backThumbUrl.startsWith("demo:")) Box(
                    Modifier.offset(x = (-9).dp, y = 4.5.dp).size(width = 48.dp, height = 72.dp)
                        .rotate(-10.4f).clip(RoundedCornerShape(7.dp)).background(demoColor(backThumbUrl)),
                ) else AsyncImage(
                    backThumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (-9).dp, y = 4.5.dp).size(width = 48.dp, height = 72.dp)
                        .rotate(-10.4f).clip(RoundedCornerShape(7.dp)).background(c.placeholderFill),
                )
                Box(
                    Modifier.offset(x = (-2).dp, y = (-2).dp).size(width = 70.dp, height = 98.dp)
                        .clip(RoundedCornerShape(16.dp)).background(c.bg),
                )
            }
            Box(Modifier.size(width = 66.dp, height = 94.dp).clip(shape).background(c.placeholderFill), contentAlignment = Alignment.Center) {
                when {
                    // Debug demo data only: a plain colour instead of a picture.
                    thumbUrl.startsWith("demo:") -> Box(Modifier.fillMaxSize().background(demoColor(thumbUrl)))
                    thumbUrl.isNotEmpty() -> AsyncImage(thumbUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    addBadge && !authorPhoto.isNullOrEmpty() -> AsyncImage(authorPhoto, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    addBadge -> Icon(Icons.Filled.Person, null, tint = c.secondary, modifier = Modifier.size(30.dp))
                    else -> Icon(Icons.Filled.Add, null, tint = c.secondary, modifier = Modifier.size(26.dp))
                }
            }
            if (addBadge) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(28.dp).clip(CircleShape).background(c.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                // The face: 26 avatar, 1 gap, 1.2 ring (1.5 grey when seen).
                val ring = if (seen) 1.5.dp else 1.2.dp
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 7.dp, y = 5.dp).size(30.4.dp).clip(CircleShape)
                        .background(c.bg)
                        .then(
                            if (seen) Modifier.border(ring, if (c.dark) Color(0xFF505052) else Color(0xFFCACACA), CircleShape)
                            else Modifier.border(ring, UnseenRing, CircleShape),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(authorPhoto, 26.dp, squircle = squircle)
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 15.sp, color = c.secondary, maxLines = 1)
        }
        if (camera) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill).clickable { onCamera() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_story_camera), contentDescription = "Add Story", tint = c.label,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
        offset = androidx.compose.ui.unit.DpOffset(96.dp, 0.dp)) {
        menu.forEach { a ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(a.title, color = if (a.destructive) c.red else c.label) },
                leadingIcon = { Icon(painterResource(a.icon), contentDescription = null, tint = if (a.destructive) c.red else c.label, modifier = Modifier.size(22.dp)) },
                onClick = { menuOpen = false; a.run() },
            )
        }
    }
    }
}


private fun demoColor(url: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(url.removePrefix("demo:"))) }.getOrDefault(Color.Gray)

/** The last New Story press (chat list button, launcher shortcut) the Stories tab acted on. */
private object AddRequests { var handled = 0 }
