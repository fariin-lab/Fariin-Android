package com.fariin.app.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * the camera button and Add Story pick a photo or video for the composer (StoryComposer.kt).
 *
 * TODO: Posted Stories, the more menu, Hidden Stories, the per-story ring segments, the camera.
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
    val addStory: () -> Unit = {
        if (live) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    // The chat list's camera button: open the picker once per press (not again on recomposition).
    var handledRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(addRequest) { if (addRequest > handledRequest) { handledRequest = addRequest; addStory() } }
    LaunchedEffect(pickError) { if (pickError != null) { delay(3000); pickError = null } }
    // "Xm ago" moves once a minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    var viewedCollapsed by rememberSaveable { mutableStateOf(false) }
    val myName = profile?.name?.ifEmpty { null } ?: "You"
    val recent = state.others.filter { it.hasUnseen }
    val viewed = state.others.filter { !it.hasUnseen }
    val ordered = recent + viewed
    val openRing: (StoryGroup) -> Unit = { g -> viewing = ordered to ordered.indexOfFirst { it.key == g.key }.coerceAtLeast(0) }

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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            item {
                Row(
                    Modifier.clickable { /* TODO: Posted Stories */ }.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("My Story", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = c.blue)
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
                        onClick = { viewing = listOf(mine) to 0 }, onCamera = addStory,
                    )
                } else {
                    StoryRow(
                        thumbUrl = "", title = "Add Story", subtitle = uploadLine ?: "Disappears after 24 hours",
                        authorPhoto = profile?.photoUrl, seen = true, camera = true, addBadge = true,
                        onClick = addStory, onCamera = addStory,
                    )
                }
                pickError?.let { Text(it, color = c.red, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            if (recent.isNotEmpty()) {
                item { Heading("Recent Stories") }
                items(recent, key = { "r" + it.key }) { g -> FriendRow(g, now) { openRing(g) } }
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
                if (!viewedCollapsed) items(viewed, key = { "v" + it.key }) { g -> FriendRow(g, now) { openRing(g) } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    viewing?.let { (groups, start) ->
        // Live copies of the rings, so a story posted or deleted meanwhile is not stale when opened.
        if (live) StoryViewerDialog(me, groups, start, conversations, onClose = { viewing = null })
        else viewing = null
    }
    composing?.let { m -> StoryComposerDialog(me, m, conversations, onClose = { composing = null }) }
}

@Composable
private fun Heading(title: String) {
    Text(
        title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Fariin.colors.blue,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FriendRow(g: StoryGroup, now: Long, onClick: () -> Unit) {
    val newest = g.stories.lastOrNull()
    StoryRow(
        thumbUrl = newest?.listThumb ?: "", title = g.name.ifEmpty { "Fariin user" },
        subtitle = newest?.let { StoryList.ago(it.createdAtMs, now) } ?: "",
        authorPhoto = g.photoUrl, seen = !g.hasUnseen,
        squircle = false, onClick = onClick,
    )
}

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
) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
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
}


private fun demoColor(url: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(url.removePrefix("demo:"))) }.getOrDefault(Color.Gray)
