package com.fariin.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.crypto.Wire
import com.fariin.app.data.Conversation
import com.fariin.app.data.Glow
import com.fariin.app.data.GlowLoad
import com.fariin.app.data.GlowRules
import com.fariin.app.data.GlowStoryCard
import com.fariin.app.data.StoryGroup
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.FloatingTopBar
import com.fariin.app.ui.people.LocalPeopleNav
import com.fariin.app.ui.stories.StoryViewerDialog
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/* The Glowing grid (iOS GlowStoryCardView, the Stories tab's glowSection, GlowStoriesGridView):
 * two columns of tall story cards, one per glow person (their newest live story), the name and
 * face on the card. The picture opens the story; the face opens the person. */

private const val CardAspect = 0.655f
private val CardCorner = 34.dp
private val Gutter = 16.dp
private val Margin = 20.dp

/**
 * The Glowing cards, cache first: the last grid goes up at once and the fetch runs behind it,
 * replacing it only when something changed (his 2026-09-11 spec). Keyed on the live relationship.
 */
@Composable
fun rememberGlowCards(me: String, reload: Int = 0): GlowLoad<List<GlowStoryCard>> {
    val ctx = LocalContext.current
    val s by Glow.state.collectAsState()
    val uids = s.relationship.sorted()
    var load by remember(me) { mutableStateOf<GlowLoad<List<GlowStoryCard>>>(Glow.Cache.read(ctx, me)?.let { GlowLoad.Loaded(it) } ?: GlowLoad.Loading) }
    LaunchedEffect(me, uids, reload) {
        if (uids.isEmpty()) { load = GlowLoad.Loaded(emptyList()); Glow.Cache.write(ctx, me, emptyList()); return@LaunchedEffect }
        val cards = Glow.storyCards(uids)
        if (load.valueOrNull != cards) load = GlowLoad.Loaded(cards)
        Glow.Cache.write(ctx, me, cards)
    }
    return load
}

/** Is there a Glowing grid? From the first frame whenever there is a relationship and it may have cards. */
fun hasGlowGrid(relationship: Set<String>, load: GlowLoad<List<GlowStoryCard>>): Boolean =
    relationship.isNotEmpty() && when (load) {
        GlowLoad.Loading -> true
        is GlowLoad.Loaded -> load.value.isNotEmpty()
        GlowLoad.Failed -> false
    }

/** Opens a glow person's whole live set in the viewer (iOS GlowStoryOpen), pinned to them. */
class GlowOpener {
    var group by mutableStateOf<StoryGroup?>(null)
}

@Composable
fun GlowViewerHost(me: String, opener: GlowOpener, conversations: List<Conversation>) {
    opener.group?.let { g -> StoryViewerDialog(me, listOf(g), 0, conversations, onClose = { opener.group = null }) }
}

/** One card: the picture, a dark fade at the foot, the name bottom-left, the face ringed bottom-right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlowStoryCardView(card: GlowStoryCard, seen: Boolean, onOpen: () -> Unit, onFace: () -> Unit, onMessage: () -> Unit) {
    val c = Fariin.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(CardAspect).clip(RoundedCornerShape(CardCorner))
            .background(c.placeholderFill).combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .semantics { contentDescription = card.person.name }) {
            val face = GlowRules.cardAvatar(maxWidth.value).dp
            val inset = GlowRules.nameInset(CardCorner.value).dp
            AsyncImage(card.story.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.55f)))))
            Text(card.person.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = inset, bottom = inset, end = face + 10.dp + 4.dp))
            val ring = GlowRules.ringWidth(face.value, seen).dp
            Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(face).clip(CircleShape).background(c.bg).clickable(onClick = onFace)) {
                Avatar(card.person.photoUrl, face)
                Box(Modifier.matchParentSize().border(ring,
                    if (seen) androidx.compose.ui.graphics.SolidColor(if (c.dark) Color(0xFF505052) else Color(0xFFCACACA))
                    else Brush.linearGradient(listOf(Color(0xFF34C76F), Color(0xFF3DA1FD)),
                        start = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f)),
                    CircleShape))
            }
        }
        // The hold: the same two actions wherever a glower's story is drawn.
        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
            DropdownMenuItem(text = { Text("Send Message") }, onClick = { menu = false; onMessage() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_menu_chat), null, tint = c.label) })
            DropdownMenuItem(text = { Text("View Profile") }, onClick = { menu = false; onFace() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_account), null, tint = c.label) })
        }
    }
}

/** Two cards to a row, the Stories tab's grid spacing. */
@Composable
fun GlowCardRow(me: String, pair: List<GlowStoryCard>, seenOf: (String) -> Boolean, opener: GlowOpener) {
    val nav = LocalPeopleNav.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(start = Margin, end = Margin, bottom = Gutter), horizontalArrangement = Arrangement.spacedBy(Gutter)) {
        pair.forEach { card ->
            Box(Modifier.weight(1f)) {
                GlowStoryCardView(card, seenOf(card.person.id),
                    onOpen = { scope.launch { opener.group = Glow.storyGroup(card.person) } },
                    onFace = { nav.contact(card.person.id) },
                    onMessage = { nav.chat(Wire.directCid(me, card.person.id)) })
            }
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

/** The section heading, in the Stories tab's own heading look, opening all Glowing stories. */
@Composable
fun GlowingHeading(onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Glowing", style = MaterialTheme.typography.titleSmall, color = c.accent)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
    }
}

/** All Glowing stories (iOS GlowStoriesGridView): the page behind the "Glowing" heading. */
@Composable
fun GlowStoriesPage(me: String, conversations: List<Conversation>, seenOf: (String) -> Boolean, onBack: () -> Unit) {
    val c = Fariin.colors
    BackHandler(onBack = onBack)
    val s by Glow.state.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val load = rememberGlowCards(me, reload)
    val opener = remember { GlowOpener() }
    com.fariin.app.ui.components.FloatingBarPage("Glowing", onBack) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        when (load) {
            GlowLoad.Loading -> com.fariin.app.ui.components.PageLoading()
            GlowLoad.Failed -> GlowCouldNotLoad { reload++ }
            is GlowLoad.Loaded -> if (load.value.isEmpty()) {
                // Nobody to glow with at all, or people who simply have nothing live right now.
                val none = s.relationship.isEmpty()
                GlowUnavailable(if (none) "No Glows yet" else "No live stories",
                    if (none) "Open somebody's profile and choose Glow to see their stories here."
                    else "Nobody you have a Glow with has posted in the last 24 hours.",
                    icon = { GlowMark(48.dp, tint = c.secondary) })
            } else {
                val nav = LocalPeopleNav.current
                val scope = rememberCoroutineScope()
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Margin, end = Margin, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(Gutter), verticalArrangement = Arrangement.spacedBy(Gutter)) {
                    items(load.value, key = { it.person.id }) { card ->
                        GlowStoryCardView(card, seenOf(card.person.id),
                            onOpen = { scope.launch { opener.group = Glow.storyGroup(card.person) } },
                            onFace = { nav.contact(card.person.id) },
                            onMessage = { nav.chat(Wire.directCid(me, card.person.id)) })
                    }
                }
            }
        }
    }
    GlowViewerHost(me, opener, conversations)
}

