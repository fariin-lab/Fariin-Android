package com.fariin.app.ui.glow

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.data.AppFeatures
import com.fariin.app.data.Conversation
import com.fariin.app.data.Glow
import com.fariin.app.data.GlowLoad
import com.fariin.app.data.GlowPerson
import com.fariin.app.data.GlowRules
import com.fariin.app.data.MyProfile
import com.fariin.app.data.PostedStory
import com.fariin.app.data.StoriesState
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FloatingBack
import com.fariin.app.ui.components.FloatingTextAction
import com.fariin.app.ui.stories.PostedStoriesPage
import com.fariin.app.ui.stories.StoryViewerDialog

/** The app's one stories feed and the chat list, for pages outside the Stories tab (MainShell). */
data class MyStoriesFeed(val state: StoriesState = StoriesState(), val conversations: List<Conversation> = emptyList())
val LocalMyStories = compositionLocalOf { MyStoriesFeed() }

/* My Profile is always dark (iOS forces .dark on the page); the cards are white at 10%. */
private val PageColor = Color.Black
private val CardColor = Color.White.copy(alpha = 0.10f)
private val Label = Color.White
private val Secondary = Color(0x99EBEBF5)
private val CardCorner = RoundedCornerShape(22.dp)

/**
 * MY PROFILE (iOS GlowProfileView on my own uid): the big photo that folds into a circle when the
 * page scrolls, my name, @username and bio, the Glowers · Glowing card (with Glow on), and the
 * Posted stories card. Other people get the ordinary contact page, never this one.
 */
@Composable
fun GlowProfilePage(me: String, profile: MyProfile?, onEdit: () -> Unit, onBack: () -> Unit) {
    val feed = LocalMyStories.current
    var people by remember { mutableStateOf<String?>(null) }    // the people list's title while open
    var peopleGlowing by remember { mutableStateOf(false) }     // the list opened on its Glowing side
    var insights by remember { mutableStateOf(false) }
    var seeAll by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Long?>(null) }      // open my ring just before this story
    if (people != null) {
        val nav = com.fariin.app.ui.people.LocalPeopleNav.current
        GlowPeopleListPage(people!!, startOnGlowing = peopleGlowing, onOpenPerson = { nav.contact(it) },
            onBack = { people = null; peopleGlowing = false }); return
    }
    if (seeAll) {
        PostedStoriesPage(me, feed.state.mine?.stories ?: emptyList(), profile?.photoUrl,
            onOpen = { s -> viewing = s.createdAtMs - 1 }, onBack = { seeAll = false },
            conversations = feed.conversations, myHandle = profile?.handle)
        viewing?.let { w -> feed.state.mine?.let { g ->
            StoryViewerDialog(me, listOf(g.copy(lastViewedAtMs = w)), 0, feed.conversations, onClose = { viewing = null }) } }
        return
    }
    if (insights) {
        val handle = profile?.handle.orEmpty()
        GlowInsightsPage(me, handle, Glow.myPosted(feed.state.mine?.stories ?: emptyList()),
            onPeople = { glowing -> peopleGlowing = glowing; people = handle.ifEmpty { profile?.name?.ifEmpty { null } ?: "Glow" } },
            onPosted = { seeAll = true }, onBack = { insights = false })
        return
    }
    BackHandler(onBack = onBack)
    val glow by Glow.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val photo = profile?.photoUrl
    val hasPhoto = !photo.isNullOrEmpty()
    var circle by remember { mutableStateOf(false) }
    if (!hasPhoto) circle = false
    // Collapse at +1, expand at -32 only while dragging (the reference app's own rule, iOS heroThresholds).
    var pull by remember { mutableFloatStateOf(0f) }
    val expandAt = with(density) { 32.dp.toPx() }
    val connection = remember(hasPhoto) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (hasPhoto && circle && source == NestedScrollSource.UserInput && available.y > 0 && scroll.value == 0) {
                    pull += available.y
                    if (pull >= expandAt) { pull = 0f; circle = false; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                } else if (available.y <= 0) pull = 0f
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(hasPhoto) {
        snapshotFlow { scroll.value }.collect { v ->
            if (hasPhoto && !circle && v >= 1) { circle = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
    }
    // My own stories, every audience, newest first, with their view counts.
    val mineStories = feed.state.mine?.stories
    var posted by remember { mutableStateOf<GlowLoad<List<PostedStory>>>(GlowLoad.Loading) }
    LaunchedEffect(mineStories, feed.state.loaded) {
        if (!feed.state.loaded && mineStories == null) return@LaunchedEffect
        val rows = Glow.myPosted(mineStories.orEmpty())
        posted = GlowLoad.Loaded(rows)
        if (rows.isNotEmpty()) posted = GlowLoad.Loaded(Glow.withViewCounts(me, rows))
    }
    // The first three glow people's faces for the card.
    val faceIds = glow.relationship.sorted().take(3)
    var faces by remember { mutableStateOf<List<GlowPerson>>(emptyList()) }
    LaunchedEffect(faceIds) { faces = Glow.people(faceIds).valueOrNull.orEmpty() }

    Box(Modifier.fillMaxSize().background(PageColor)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            val pageH = maxHeight
            val barBottom = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
            val d = w * 0.465f
            val heroHeight = when { !hasPhoto -> barBottom + 110.dp + 16.dp; circle -> barBottom + d + 16.dp; else -> w }
            val spec = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 1f, stiffness = 320f)   // about 0.35 s
            val boxH by animateDpAsState(heroHeight, spec, label = "heroH")
            val imgW by animateDpAsState(if (circle) d else w, spec, label = "imgW")
            val imgH by animateDpAsState(if (circle) d else w, spec, label = "imgH")
            val corner by animateDpAsState(if (circle) d / 2 else 0.dp, spec, label = "corner")
            val top by animateDpAsState(if (circle) barBottom else 0.dp, spec, label = "top")
            Column(Modifier.fillMaxSize().nestedScroll(connection).verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().height(boxH)) {
                    if (hasPhoto) {
                        Box(Modifier.align(Alignment.TopCenter).offset(y = top).size(imgW, imgH).clip(RoundedCornerShape(corner))) {
                            AsyncImage(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (!circle) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(w * 0.42f)
                                .background(Brush.verticalGradient(listOf(PageColor.copy(alpha = 0f), PageColor))))
                        }
                    } else {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = barBottom)) { Avatar(null, 110.dp) }
                    }
                }
                // Identity: rides up into the photo's fade while the photo is big.
                Column(Modifier.fillMaxWidth().offset(y = if (hasPhoto && !circle) -(heroHeight * 0.10f) else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile?.name.orEmpty(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Label, textAlign = TextAlign.Center)
                    profile?.handle?.takeIf { it.isNotEmpty() }?.let { Text("@$it", fontSize = 17.sp, color = Secondary) }
                    profile?.about?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 15.sp, color = Secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp))
                    }
                    profile?.links?.let { com.fariin.app.ui.people.ProfileLinkChips(it, Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
                }
                val title = profile?.handle?.ifEmpty { null } ?: profile?.name?.ifEmpty { null } ?: "Glow"
                if (AppFeatures.glow) StatsCard(glow.glowers.size, glow.glowing.size, faces,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) { people = title }
                if (AppFeatures.insights) InsightsCard(glow.glowers.size, profile?.handle.orEmpty(), CardColor, Label, Secondary,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { insights = true }
                PostedCard(posted, onSeeAll = { seeAll = true }, onOpen = { s -> viewing = s.createdAtMs - 1 },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp))
                Spacer(Modifier.height(32.dp))
                // Room to scroll, so the photo can always fold (iOS minHeight: screen + 60).
                if (hasPhoto) Spacer(Modifier.height(pageH * 0.6f))
            }
        }
        // The bar is dark like the page (iOS barScheme .dark).
        com.fariin.app.ui.theme.FariinTheme(dark = true) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FloatingBack(onBack)
                Spacer(Modifier.weight(1f))
                FloatingTextAction("Edit", onClick = onEdit)
            }
        }
    }
    viewing?.let { w -> feed.state.mine?.let { g ->
        StoryViewerDialog(me, listOf(g.copy(lastViewedAtMs = w)), 0, feed.conversations, onClose = { viewing = null }) } }
}

/** "N Glowers · N Glowing", the faces and "by …" under it; the door to my own lists. */
@Composable
private fun StatsCard(glowers: Int, glowing: Int, faces: List<GlowPerson>, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(CardCorner).background(CardColor).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (faces.isEmpty()) Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) { GlowMark(18.dp, filled = true, tint = Color.White) }
        else FaceCluster(faces)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(GlowRules.statsLine(glowers, glowing), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Label, maxLines = 2)
            Text(GlowRules.byLine(faces.map { it.name }), fontSize = 15.sp, color = Secondary, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
    }
}

/** Up to three faces: the first on top, the second right and the third left, lower and smaller. */
@Composable
private fun FaceCluster(faces: List<GlowPerson>) {
    val face = 40.dp; val side = 31.dp; val spread = 22.dp; val drop = 9.dp
    val shown = faces.take(3)
    val hasLeft = shown.size >= 3; val hasRight = shown.size >= 2
    val originX = if (hasLeft) spread + side / 2 else face / 2
    val maxX = if (hasRight) spread + side / 2 else face / 2
    val sideTop = face / 2 + drop - side / 2
    Box(Modifier.size(originX + maxX, face / 2 + drop + side / 2)) {
        if (hasLeft) Ringed(shown[2], side, Modifier.offset(originX - spread - side / 2, sideTop))
        if (hasRight) Ringed(shown[1], side, Modifier.offset(originX + spread - side / 2, sideTop))
        Ringed(shown[0], face, Modifier.offset(originX - face / 2, 0.dp))
    }
}

@Composable
private fun Ringed(p: GlowPerson, size: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(PageColor)) {
        Avatar(p.photoUrl, size)
        Box(Modifier.matchParentSize().border(2.dp, Color(0xFF1A1A1A), CircleShape))
    }
}


@Composable
private fun PostedCard(state: GlowLoad<List<PostedStory>>, onSeeAll: () -> Unit, onOpen: (PostedStory) -> Unit, modifier: Modifier) {
    val rows = state.valueOrNull.orEmpty()
    Column(modifier.fillMaxWidth().clip(CardCorner).background(CardColor).padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Posted stories", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Label, modifier = Modifier.weight(1f))
            if (rows.isNotEmpty()) Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("See All", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Label)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Label, modifier = Modifier.size(16.dp))
            }
        }
        when {
            state is GlowLoad.Loading -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Secondary) }
            rows.isEmpty() -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text("You have no live stories.", fontSize = 15.sp, color = Secondary) }
            else -> Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    val s = rows.getOrNull(i)
                    Box(Modifier.weight(1f)) { if (s != null) PostedTile(s) { onOpen(s) } }
                }
            }
        }
    }
}

/** A posted story tile (iOS PostedStoryTile): the picture, an eye and the count, a play mark on video. */
@Composable
fun PostedTile(s: PostedStory, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.655f).clip(RoundedCornerShape(24.dp)).background(Color.DarkGray).clickable(onClick = onClick)) {
        AsyncImage(s.thumbUrl, contentDescription = if (s.isVideo) "Video story" else "Photo story",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        s.views?.let { v ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(52.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(GlowRules.countShort(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        if (s.isVideo) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(6.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/** Puts the feed where pages outside the Stories tab can read it. */
@Composable
fun ProvideMyStories(state: StoriesState, conversations: List<Conversation>, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMyStories provides MyStoriesFeed(state, conversations), content = content)
