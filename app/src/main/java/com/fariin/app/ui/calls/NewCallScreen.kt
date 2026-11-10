package com.fariin.app.ui.calls

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import com.fariin.app.ui.components.listPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.BlockList
import com.fariin.app.data.Conversation
import com.fariin.app.ui.call.CallActivity
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.R

/** One row of the New Call page: a person from my chats, or a group I may call. */
data class CallTarget(val id: String, val name: String, val photo: String?, val uid: String?, val groupCid: String?, val updatedAt: Double)

object NewCallRules {
    /**
     * People from my 1:1 chats, newest chat first, then the groups I may start a call in (a
     * member; an admin when the group says admins only). Not: channels, chats with deleted
     * accounts, people I blocked, my own notes chat, requests still waiting for my answer.
     */
    fun targets(chats: List<Conversation>, me: String, blocked: Set<String>): Pair<List<CallTarget>, List<CallTarget>> {
        val people = chats.asSequence()
            .filter { !it.isGroup && !it.isChannel }
            .mapNotNull { c ->
                val other = c.otherUid(me)
                if (other.isEmpty() || other == me || c.isDeletedAccount(other) || other in blocked) null
                else CallTarget(c.id, c.displayName(me), c.displayPhoto(me), other, null, c.updatedAtMillis)
            }
            .distinctBy { it.uid }
            .sortedByDescending { it.updatedAt }
            .toList()
        val groups = chats.asSequence()
            .filter { it.mayStartCall(me) }
            .map { CallTarget(it.id, it.displayName(me), it.displayPhoto(me), null, it.id, it.updatedAtMillis) }
            .sortedByDescending { it.updatedAt }
            .toList()
        return people to groups
    }

    /** People picked for one call besides me: the rules' member cap for a multi-person call. */
    const val MAX_PICK = com.fariin.app.call.GroupCallWire.ADHOC_MAX_MEMBERS - 1

    /** A tap on a person's row: picked comes off, else goes on at the end (nothing past [max]). */
    fun toggle(picked: List<String>, uid: String, max: Int = MAX_PICK): List<String> = when {
        uid.isEmpty() -> picked
        uid in picked -> picked - uid
        picked.size >= max -> picked
        else -> picked + uid
    }

    /** The picked people still on the list (a chat gone or blocked drops out), in the order picked. */
    fun chosen(people: List<CallTarget>, picked: List<String>): List<CallTarget> {
        val byUid = people.filter { it.uid != null }.associateBy { it.uid!! }
        return picked.distinct().mapNotNull { byUid[it] }
    }

    /** What the strip's call buttons start: nothing, a 1:1 call, or a multi-person call. */
    sealed interface Plan {
        data object None : Plan
        data class One(val t: CallTarget) : Plan
        data class Many(val people: List<com.fariin.app.call.CallPerson>) : Plan
    }

    fun plan(chosen: List<CallTarget>): Plan = when (chosen.size) {
        0 -> Plan.None
        1 -> Plan.One(chosen[0])
        else -> Plan.Many(chosen.map { com.fariin.app.call.CallPerson(it.uid ?: "", it.name, it.photo) })
    }

    fun search(list: List<CallTarget>, q: String): List<CallTarget> {
        val t = q.trim().removePrefix("@")
        return if (t.isEmpty()) list else list.filter { it.name.contains(t, ignoreCase = true) }
    }
}

/**
 * New Call, the Calls tab's floating button: a top app bar with a search button, "Create a call
 * link" first, then the people from my chats (recent first), then my groups. People are PICKED
 * (a circle on each row); the picked ones line up in a strip under the bar with a video and a
 * voice button. One person picked is a 1:1 call (CallActivity.startDial), two or more a
 * multi-person call (CallActivity.startAdhoc). A group row calls the group at once (startGroup).
 */
@Composable
fun NewCallScreen(me: String, chats: List<Conversation>, onBack: () -> Unit, onCreateLink: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val blockSnap by BlockList.state.collectAsState()
    val blocked = blockSnap.entries.keys
    val (people, groups) = remember(chats, me, blocked) { NewCallRules.targets(chats, me, blocked) }
    val shownPeople = NewCallRules.search(people, q)
    val shownGroups = NewCallRules.search(groups, q)
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val chosen = NewCallRules.chosen(people, picked)
    BackHandler(enabled = searching) { searching = false; q = "" }

    fun call(t: CallTarget, video: Boolean) {
        if (com.fariin.app.call.CallManager.ui.value.status.live) { CallActivity.startRing(ctx); return }
        val g = t.groupCid
        if (g != null) CallActivity.startGroup(ctx, g, t.name, video, start = true)
        else CallActivity.startDial(ctx, t.uid ?: return, t.name, t.photo, video)
    }

    fun callPicked(video: Boolean) {
        when (val p = NewCallRules.plan(chosen)) {
            NewCallRules.Plan.None -> return
            is NewCallRules.Plan.One -> call(p.t, video)
            is NewCallRules.Plan.Many -> {
                if (com.fariin.app.call.CallManager.ui.value.status.live) { CallActivity.startRing(ctx); return }
                CallActivity.startAdhoc(ctx, p.people, video)
            }
        }
        picked = emptyList()
    }

    // A glass page as the others (owner 2026-11-07: every page's back in liquid glass). The picked
    // strip rides in the bar, so it stays put while the list scrolls under it.
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    com.fariin.app.ui.components.GlassColumnPage(
        under = com.fariin.app.ui.components.underBar(list), modifier = Modifier.background(c.bg), page = c.bg,
        bar = { Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            // The floating back circle and the search capsule, as the chat list's search.
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                com.fariin.app.ui.components.FloatingBack(onBack = { searching = false; q = "" }, contentDescription = "Close search")
                Spacer(Modifier.width(10.dp))
                com.fariin.app.ui.components.FloatingSearchField(q, { q = it }, "Search", Modifier.weight(1f).focusRequester(focus))
            }
        } else {
            com.fariin.app.ui.components.FloatingTopBar("New Call", onBack = onBack) {
                com.fariin.app.ui.components.FloatingIcon(ImageVector.vectorResource(R.drawable.ic_search_ios), "Search", onClick = { searching = true })
            }
        }
        AnimatedVisibility(chosen.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            PickedStrip(chosen, onUnpick = { t -> picked = picked - (t.uid ?: "") }, onVoice = { callPicked(false) }, onVideo = { callPicked(true) })
        }
        } },
    ) { top ->
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = top)) {
            if (q.isBlank()) {
                item(key = "link") {
                    // On a floating rounded card, as the chat list's Archived row.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .floating(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .clickable(onClick = onCreateLink).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_link), null, tint = c.accent, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Create a call link", fontSize = 17.sp, color = c.label, fontWeight = FontWeight.Medium)
                            Text("Share a link for a call on Fariin", fontSize = 14.sp, color = c.secondary)
                        }
                    }
                }
            }
            if (shownPeople.isNotEmpty()) {
                item(key = "h-people") { Header(if (q.isBlank()) "Recent chats" else "People") }
                items(shownPeople, key = { "p-" + it.id }) { t ->
                    PersonPickRow(t, on = t.uid in picked) { picked = NewCallRules.toggle(picked, t.uid ?: "") }
                }
            }
            if (shownGroups.isNotEmpty()) {
                item(key = "h-groups") { Header("Groups") }
                items(shownGroups, key = { "g-" + it.id }) { t -> TargetRow(t, onVoice = { call(t, false) }, onVideo = { call(t, true) }) }
            }
            if (shownPeople.isEmpty() && shownGroups.isEmpty()) {
                item(key = "empty") {
                    if (q.isBlank()) com.fariin.app.ui.components.IllustratedEmpty(com.fariin.app.ui.components.LineArt.Phone,
                        "No one to call yet", "People you chat with will appear here.")
                    // Worded and placed as the other searches (chat list, Calls, Settings).
                    else androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 120.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("No Results", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.label)
                        Text("There were no results for \"${q.trim()}\".\nTry a new search.", fontSize = 15.sp, color = c.secondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Fariin.colors.accent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp))
}

/** The picked people under the bar, each with an x to take them off, and the two call buttons. */
@Composable
private fun PickedStrip(chosen: List<CallTarget>, onUnpick: (CallTarget) -> Unit, onVoice: () -> Unit, onVideo: () -> Unit) {
    val c = Fariin.colors
    // Anchored at its END (owner's reference, 2026-11-07): laid out from the right, newest first, so
    // the newest pick is always at the right edge and the oldest slide off the left (a swipe brings
    // them back); while they fit they stay at the left. A pick: the row slides left one slot
    // (150 ms), then the face grows in from 20 %. A removal: the face shrinks away and the row
    // slides right to close the gap, a hidden one coming back in at the left.
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val shown = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val newest = chosen.lastOrNull()?.id
    // A face is forgotten only once it is unpicked (scrolled out of view, it comes back as it was).
    LaunchedEffect(chosen) { val ids = chosen.map { it.id }.toSet(); shown.keys.retainAll(ids) }
    LaunchedEffect(newest, chosen.size) {
        if (newest != null && shown[newest] != true) rowState.animateScrollToItem(0)
    }
    val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(150)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
        LazyRow(Modifier.weight(1f), state = rowState, reverseLayout = true,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Start,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            items(chosen.asReversed(), key = { "c-" + it.id }) { t ->
                // A face seen before keeps its size; a new one waits for the slide, then grows in.
                // Owner's recording 2026-11-07, frame by frame: a 20 % dot sat still for 0.2 s (it waited
                // for a slide that only happens once the row is full), then popped to full size in three
                // frames. Now nothing shows until it grows; it waits only when the row really slides;
                // and it grows on a soft spring from 20 % (~0.25 s).
                val grow = remember { androidx.compose.animation.core.Animatable(if (shown[t.id] == true) 1f else 0f) }
                LaunchedEffect(t.id) {
                    if (grow.value < 1f) {
                        if (rowState.canScrollForward || rowState.canScrollBackward) kotlinx.coroutines.delay(150)
                        grow.snapTo(0.2f)
                        grow.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 520f))
                    }
                    shown[t.id] = true
                }
                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressed by press.collectIsPressedAsState()
                Column(Modifier.animateItem(fadeInSpec = null, placementSpec = slide,
                        fadeOutSpec = androidx.compose.animation.core.tween(150))
                    .graphicsLayer { val k = grow.value; scaleX = k; scaleY = k; alpha = if (k <= 0f) 0f else minOf(1f, k * 1.6f) }
                    .width(66.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable(interactionSource = press, indication = null, onClickLabel = "Remove ${t.name}") { onUnpick(t) }
                    .graphicsLayer { alpha = if (pressed) 0.6f else 1f }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(54.dp)) {
                        Avatar(t.photo, 52.dp, Modifier.align(Alignment.Center))
                        Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(c.bg).padding(2.dp)
                            .clip(CircleShape).background(c.tertiary), contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = c.bg, modifier = Modifier.size(11.dp))
                        }
                    }
                    Text(t.name.substringBefore(' ').ifEmpty { t.name }, fontSize = 12.sp, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StripCallButton(com.fariin.app.R.drawable.ic_call_video, if (chosen.size > 1) "Video call with ${chosen.size} people" else "Video call", onVideo)
            Spacer(Modifier.width(10.dp))
            StripCallButton(com.fariin.app.R.drawable.ic_call_voice, if (chosen.size > 1) "Voice call with ${chosen.size} people" else "Voice call", onVoice)
        }
    }
}

@Composable
private fun StripCallButton(icon: Int, label: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent).clickable(onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(ImageVector.vectorResource(icon), null, tint = c.onAccent, modifier = Modifier.size(22.dp))
    }
}

/** A person on New Call: tapping the row picks them; the circle on the right says so. */
@Composable
private fun PersonPickRow(t: CallTarget, on: Boolean, onToggle: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().listPress(onClick = onToggle).semantics { selected = on }
        .padding(start = 16.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(t.photo, 46.dp)
        Spacer(Modifier.width(14.dp))
        Text(t.name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        val ring = Modifier.size(24.dp).clip(CircleShape)
        if (on) Box(ring.background(c.accent), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        } else Box(ring.border(1.5.dp, c.tertiary, CircleShape))
    }
}

@Composable
private fun TargetRow(t: CallTarget, onVoice: () -> Unit, onVideo: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().listPress(onClick = onVoice).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(t.photo, 46.dp, squircle = t.groupCid != null, kind = com.fariin.app.ui.components.AvatarKind.of(t.groupCid != null, false))
        Spacer(Modifier.width(14.dp))
        Text(t.name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onVoice) {
            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), if (t.groupCid != null) "Group voice call" else "Voice call", tint = c.accent)
        }
        IconButton(onClick = onVideo) {
            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), if (t.groupCid != null) "Group video call" else "Video call", tint = c.accent)
        }
    }
}
