package com.fariin.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.data.AppFeatures
import com.fariin.app.data.Conversation
import com.fariin.app.data.Glow
import com.fariin.app.data.GlowLoad
import com.fariin.app.data.GlowPerson
import com.fariin.app.data.GlowRules
import com.fariin.app.data.Story
import com.fariin.app.data.StoryService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FloatingTopBar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** One row of the Glow notifications page (iOS GlowEvent). */
data class GlowEvent(
    val id: String,
    val person: GlowPerson,
    val kind: Kind,
    /** The emoji of a love. */
    val emoji: String = "",
    val atMs: Long,
    val storyThumb: String? = null,
) {
    enum class Kind { GLOWED, LOVED, ACCEPTED_REQUEST }
    val isGlow: Boolean get() = kind == Kind.GLOWED
    val isLove: Boolean get() = kind == Kind.LOVED
}

/**
 * iOS GlowEventsLoader: who glowed me, who reacted to my live stories, and who accepted a message
 * request I sent, newest first. A failed glow read fails the page; a story whose viewers could not
 * be read is skipped, not counted as nobody.
 */
suspend fun loadGlowEvents(me: String, mine: List<Story>, conversations: List<Conversation>): GlowLoad<List<GlowEvent>> = coroutineScope {
    val out = mutableListOf<GlowEvent>()
    val glows = try { Glow.fetchRecentGlowers() } catch (_: Exception) { return@coroutineScope GlowLoad.Failed }
    val dates = glows.toMap()
    val people = Glow.people(glows.map { it.first }, dates)
    val resolved = (people as? GlowLoad.Loaded)?.value.orEmpty()
    for ((uid, at) in glows) {
        val p = resolved.firstOrNull { it.id == uid } ?: continue
        out.add(GlowEvent("glow-$uid", p, GlowEvent.Kind.GLOWED, atMs = at))
    }
    if (me.isNotEmpty()) {
        val who: (String) -> Pair<String, String?> = { u ->
            val c = conversations.firstOrNull { !it.isGroup && it.users.contains(u) }
            (c?.names?.get(u) ?: "") to c?.photos?.get(u)
        }
        val service = StoryService(me)
        mine.map { s -> async { s to service.fetchViewers(s.id, who) } }.awaitAll().forEach { (s, viewers) ->
            for (v in viewers ?: return@forEach) {
                val r = v.reaction.orEmpty()
                if (r.isEmpty()) continue
                out.add(GlowEvent("love-${s.id}-${v.uid}", GlowPerson(v.uid, v.name, "", v.photoUrl, v.viewedAtMs),
                    GlowEvent.Kind.LOVED, emoji = r, atMs = v.viewedAtMs, storyThumb = s.listThumb))
            }
        }
        // Accepted requests I started (not by a Chat Key), from the chats already on this phone.
        val snap = runCatching {
            FirebaseFirestore.getInstance().collection("conversations").whereArrayContains("users", me).get(Source.CACHE).await()
        }.getOrNull()
        for (d in snap?.documents.orEmpty()) {
            if (d.getString("type") == "group" || d.getString("startedBy") != me || d.getBoolean("accepted") != true) continue
            if (d.getString("acceptedVia") == "pin") continue
            val at = d.getTimestamp("acceptedAt")?.toDate()?.time ?: continue
            val other = (d.get("users") as? List<*>)?.filterIsInstance<String>()?.firstOrNull { it != me && it.isNotEmpty() } ?: continue
            val by = d.getString("acceptedBy").orEmpty()
            if (by.isNotEmpty() && by != other) continue
            val names = d.get("names") as? Map<*, *>
            val photos = d.get("photos") as? Map<*, *>
            out.add(GlowEvent("accepted-${d.id}", GlowPerson(other, names?.get(other) as? String ?: "", "", photos?.get(other) as? String, at),
                GlowEvent.Kind.ACCEPTED_REQUEST, atMs = at))
        }
    }
    GlowLoad.Loaded(out.sortedByDescending { it.atMs })
}

private enum class Chip(val title: String) { ALL("All"), GLOWERS("Glowers"), LOVES("Loves") }

/**
 * The Glow notifications page (iOS GlowNotificationsView): All / Glowers / Loves chips over the
 * rows, an unread dot on what came after my last visit, Glow back or Glowing on a glow row and my
 * story's thumbnail on a love. Leaving the page marks everything seen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlowNotificationsPage(
    me: String,
    mine: List<Story>,
    conversations: List<Conversation>,
    onOpenPerson: (String) -> Unit,
    onOpenMyStory: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Fariin.colors
    BackHandler(onBack = onBack)
    // The line between read and unread is where it stood on arrival, not where leaving moves it.
    val seenAtOpen = remember { Glow.seenUpTo.value }
    DisposableEffect(Unit) { onDispose { Glow.markSeen() } }
    var chip by rememberSaveable { mutableStateOf(Chip.ALL) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var load by remember { mutableStateOf<GlowLoad<List<GlowEvent>>>(GlowLoad.Loading) }
    LaunchedEffect(reload) {
        if (!refreshing) load = GlowLoad.Loading
        load = loadGlowEvents(me, mine, conversations)
        refreshing = false
    }
    val glowOn = AppFeatures.glow
    fun matches(e: GlowEvent): Boolean {
        if (!glowOn && e.isGlow) return false
        return when (chip) {
            Chip.ALL -> true
            Chip.GLOWERS -> e.isGlow
            Chip.LOVES -> e.isLove
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            FloatingTopBar("Notifications", onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val l = load) {
                    GlowLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.secondary)
                    GlowLoad.Failed -> GlowCouldNotLoad { reload++ }
                    is GlowLoad.Loaded -> {
                        val rows = l.value.filter(::matches)
                        if (rows.isEmpty()) GlowUnavailable(
                            when (chip) { Chip.ALL -> "Nothing yet"; Chip.GLOWERS -> "No Glows yet"; Chip.LOVES -> "No loves yet" },
                            when (chip) {
                                Chip.GLOWERS -> "When somebody glows you, it appears here."
                                Chip.LOVES -> "When somebody loves one of your stories, it appears here."
                                Chip.ALL -> "Glows and reactions to your stories appear here."
                            },
                            icon = {
                                if (chip == Chip.LOVES) Icon(Icons.Outlined.FavoriteBorder, null, tint = c.label, modifier = Modifier.size(44.dp))
                                else GlowMark(48.dp)
                            },
                        ) else PullToRefreshBox(refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                                item(key = "chips") {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (ch in Chip.entries) {
                                            if (!glowOn && ch == Chip.GLOWERS) continue
                                            val on = chip == ch
                                            Box(
                                                Modifier.then(if (on) Modifier.clip(CircleShape).background(c.accent) else Modifier.floating(CircleShape).background(c.card, CircleShape))
                                                    .clip(CircleShape).clickable { chip = ch }.padding(horizontal = 18.dp, vertical = 10.dp),
                                            ) {
                                                Text(ch.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.onAccent else c.label)
                                            }
                                        }
                                    }
                                }
                                items(rows, key = { it.id }) { e ->
                                    EventRow(e, unread = e.atMs > seenAtOpen, onOpenPerson = { onOpenPerson(e.person.id) }, onOpenMyStory = onOpenMyStory)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: GlowEvent, unread: Boolean, onOpenPerson: () -> Unit, onOpenMyStory: () -> Unit) {
    val c = Fariin.colors
    val glow by Glow.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenPerson), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Avatar(e.person.photoUrl, GlowRowAvatar)
                // Unread: a small dot just outside the face's leading edge (iOS offset -11).
                if (unread) Box(Modifier.align(Alignment.CenterStart).offset(x = (-11).dp).size(6.dp).clip(CircleShape).background(c.accent))
            }
            Spacer(Modifier.width(12.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(e.person.name) }
                append(when (e.kind) {
                    GlowEvent.Kind.GLOWED -> " glowed at you."
                    GlowEvent.Kind.LOVED -> " reacted ${e.emoji} to your story."
                    GlowEvent.Kind.ACCEPTED_REQUEST -> " accepted your message request."
                })
                withStyle(SpanStyle(color = c.secondary)) { append("  •  " + GlowRules.stampText(e.atMs, now)) }
            }, fontSize = 15.sp, color = c.label, maxLines = 3, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
        }
        val thumb = e.storyThumb
        if (!thumb.isNullOrEmpty()) {
            AsyncImage(thumb, contentDescription = "Your story", contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp, 58.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray.copy(alpha = 0.2f))
                    .clickable(onClick = onOpenMyStory))
        } else if (e.isGlow) {
            if (glow.glowing.contains(e.person.id)) GlowingCapsule(compact = true) { scope.launch { if (!Glow.remove(e.person.id)) failed = true } }
            else GlowFilledButton("Glow back", compact = true) { scope.launch { if (!Glow.give(e.person.id)) failed = true } }
        }
    }
    GlowWriteFailed(failed) { failed = false }
}
