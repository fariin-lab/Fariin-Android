package com.fariin.app.ui.calls

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    fun search(list: List<CallTarget>, q: String): List<CallTarget> {
        val t = q.trim().removePrefix("@")
        return if (t.isEmpty()) list else list.filter { it.name.contains(t, ignoreCase = true) }
    }
}

/**
 * New Call, the Calls tab's floating button, laid out the way Android messengers do it: a top app
 * bar with a search button, "Create a call link" first, then the people from my chats (recent
 * first), each with a voice and a video button, then my groups. A tap dials exactly as the chat
 * header's buttons do (CallActivity.startDial / startGroup).
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
    BackHandler(enabled = searching) { searching = false; q = "" }

    fun call(t: CallTarget, video: Boolean) {
        if (com.fariin.app.call.CallManager.ui.value.status.live) { CallActivity.startRing(ctx); return }
        val g = t.groupCid
        if (g != null) CallActivity.startGroup(ctx, g, t.name, video, start = true)
        else CallActivity.startDial(ctx, t.uid ?: return, t.name, t.photo, video)
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
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
                com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Search, "Search", onClick = { searching = true })
            }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (q.isBlank()) {
                item(key = "link") {
                    // On a floating rounded card, as the chat list's Archived row.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .floating(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .clickable(onClick = onCreateLink).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy), null, tint = c.accent)
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
                items(shownPeople, key = { "p-" + it.id }) { t -> TargetRow(t, onVoice = { call(t, false) }, onVideo = { call(t, true) }) }
            }
            if (shownGroups.isNotEmpty()) {
                item(key = "h-groups") { Header("Groups") }
                items(shownGroups, key = { "g-" + it.id }) { t -> TargetRow(t, onVoice = { call(t, false) }, onVideo = { call(t, true) }) }
            }
            if (shownPeople.isEmpty() && shownGroups.isEmpty()) {
                item(key = "empty") {
                    if (q.isBlank()) com.fariin.app.ui.components.IllustratedEmpty(com.fariin.app.ui.components.LineArt.Phone,
                        "No one to call yet", "People you chat with will appear here.")
                    else Text("No results for \"${q.trim()}\"", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
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
