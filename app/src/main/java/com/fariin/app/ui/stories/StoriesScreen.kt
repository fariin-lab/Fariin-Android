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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * TODO: opening a story (the viewer, marking seen), posting one (camera button, Add Story),
 * Posted Stories, the more menu, Hidden Stories, the per-story ring segments, upload progress.
 */
@Composable
fun StoriesTab(me: String, conversations: List<Conversation>, demo: Pair<MyProfile, StoriesState>?) {
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
    StoriesScreen(profile, state)
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectFlow(initial: T): T {
    var v by remember(this) { mutableStateOf(initial) }
    LaunchedEffect(this) { collect { v = it } }
    return v
}

@Composable
private fun StoriesScreen(profile: MyProfile?, state: StoriesState) {
    val c = Fariin.colors
    // "Xm ago" moves once a minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    var viewedCollapsed by rememberSaveable { mutableStateOf(false) }
    val myName = profile?.name?.ifEmpty { null } ?: "You"
    val recent = state.others.filter { it.hasUnseen }
    val viewed = state.others.filter { !it.hasUnseen }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(40.dp).clip(CircleShape)
                    .background(c.fieldFill).clickable { /* TODO: more menu */ },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = "More", tint = c.label)
            }
            Text("Stories", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.Center))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.clickable { /* TODO: Posted Stories */ }.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("My Story", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label.copy(alpha = 0.7f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.secondary,
                        modifier = Modifier.size(20.dp))
                }
            }
            item {
                val mine = state.mine
                val newest = mine?.stories?.lastOrNull()
                if (mine != null && newest != null) {
                    StoryRow(
                        thumbUrl = newest.listThumb, title = "My Story", subtitle = StoryList.ago(newest.createdAtMs, now),
                        authorPhoto = profile?.photoUrl, seen = !mine.hasUnseen, camera = true,
                        backThumbUrl = mine.stories.getOrNull(mine.stories.size - 2)?.listThumb,
                    )
                } else {
                    StoryRow(
                        thumbUrl = "", title = "Add Story", subtitle = "Disappears after 24 hours",
                        authorPhoto = profile?.photoUrl, seen = true, camera = true, addBadge = true,
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item { Heading("Recent Stories") }
                items(recent, key = { "r" + it.key }) { g -> FriendRow(g, now) }
            }
            if (viewed.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { viewedCollapsed = !viewedCollapsed }
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Viewed Stories", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label.copy(alpha = 0.7f))
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (viewedCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (viewedCollapsed) "Show viewed stories" else "Hide viewed stories",
                            tint = c.secondary, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (!viewedCollapsed) items(viewed, key = { "v" + it.key }) { g -> FriendRow(g, now) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Heading(title: String) {
    Text(
        title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FriendRow(g: StoryGroup, now: Long) {
    val newest = g.stories.lastOrNull()
    StoryRow(
        thumbUrl = newest?.listThumb ?: "", title = g.name.ifEmpty { "Fariin user" },
        subtitle = newest?.let { StoryList.ago(it.createdAtMs, now) } ?: "",
        authorPhoto = g.photoUrl, seen = !g.hasUnseen,
        squircle = false,
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
) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clickable { /* TODO: open the story viewer */ }
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
                Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill).clickable { /* TODO: post a story */ },
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
