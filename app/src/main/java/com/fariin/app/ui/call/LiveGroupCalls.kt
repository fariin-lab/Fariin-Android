package com.fariin.app.ui.call

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallWords
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.Conversation
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/** A group's call that is going (groupCalls/{cid}.active). */
data class LiveCall(val cid: String, val video: Boolean, val joined: List<String>)

/**
 * iOS LiveGroupCalls: the live calls in my groups, for the Calls tab. One listener on each group's
 * own call document (there is no cross-group query), for at most 40 groups.
 */
object LiveGroupCalls {
    private const val CAP = 40
    private val regs = mutableMapOf<String, ListenerRegistration>()
    private val byCid = mutableMapOf<String, LiveCall>()
    private val _calls = MutableStateFlow<List<LiveCall>>(emptyList())
    val calls: StateFlow<List<LiveCall>> = _calls

    fun sync(groups: List<Conversation>) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val want = groups.filter { it.isGroup && !it.isChannel }.take(CAP).map { it.id }.toSet()
        regs.keys.filter { it !in want }.forEach { regs.remove(it)?.remove(); byCid.remove(it) }
        want.filter { it !in regs }.forEach { cid ->
            regs[cid] = FirebaseFirestore.getInstance().collection("groupCalls").document(cid).addSnapshotListener { s, _ ->
                if (cid !in regs) return@addSnapshotListener
                val d = s?.data
                if (d?.get("active") == true) byCid[cid] = LiveCall(cid, d["video"] == true, (d["joined"] as? List<*>)?.filterIsInstance<String>().orEmpty())
                else byCid.remove(cid)
                publish()
            }
        }
        publish()
    }

    private fun publish() { _calls.value = byCid.values.sortedBy { it.cid } }

    fun stopAll() { regs.values.forEach { it.remove() }; regs.clear(); byCid.clear(); _calls.value = emptyList() }

    /** The last answer of groupCallPeek per call, so a row starts with faces (iOS lastPeek). */
    private val lastPeek = mutableMapOf<String, List<String>>()

    /** Who is in the call, from the media server (groupCallPeek), every 15 s while shown. */
    @Composable
    fun rememberPeople(cid: String, fallback: List<String>): List<String> {
        var people by remember(cid) { mutableStateOf(lastPeek[cid] ?: emptyList()) }
        LaunchedEffect(cid) {
            val fn = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("groupCallPeek")
            while (true) {
                try {
                    val list = ((fn.call(mapOf("cid" to cid)).await().data as? Map<*, *>)?.get("people") as? List<*>).orEmpty()
                    val uids = list.mapNotNull { (it as? Map<*, *>)?.get("uid") as? String }
                    lastPeek[cid] = uids
                    people = uids
                } catch (_: Exception) {}
                delay(15_000)
            }
        }
        return people.ifEmpty { fallback }
    }
}

/**
 * iOS LiveGroupCallRow on the Calls tab: the group's 46 photo with a green live dot, its name,
 * "Voice Chat · N participants", up to three faces, and a blue JOIN pill (RETURN for the call I am
 * in). Only the pill joins.
 */
@Composable
fun LiveGroupCallRow(live: LiveCall, conv: Conversation?) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val mine by GroupCall.ui.collectAsState()
    val isMine = mine.cid == live.cid
    val people = LiveGroupCalls.rememberPeople(live.cid, live.joined)
    val title = conv?.title?.takeIf { it.isNotEmpty() } ?: "Group"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(conv?.avatarUrl, 46.dp, kind = com.fariin.app.ui.components.AvatarKind.of(true, conv?.isChannel == true))
            Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(0xFF34C759)).border(2.dp, c.bg, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(GroupCallWords.rowSubtitle(live.video, people.size), fontSize = 15.sp, color = c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Faces(people, conv, 26, -8, c.bg)
        Spacer(Modifier.width(8.dp))
        JoinPill(if (isMine) "RETURN" else "JOIN", if (isMine) "Return to the call in $title" else "Join the call in $title") {
            CallActivity.startGroup(ctx, live.cid, title, video = false)
        }
    }
}

@Composable
private fun JoinPill(text: String, label: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.height(32.dp).clip(CircleShape).background(c.accent).clickable(onClick = onClick)
        .semantics { contentDescription = label }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = c.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Faces(uids: List<String>, conv: Conversation?, size: Int, overlap: Int, rim: Color?) {
    if (uids.isEmpty()) return
    Row {
        uids.take(3).forEachIndexed { i, u ->
            Box(Modifier.offset(x = (overlap * i).dp).then(if (rim != null) Modifier.border(2.dp, rim, CircleShape) else Modifier)) {
                Avatar(conv?.photos?.get(u), size.dp)
            }
        }
    }
}

/**
 * iOS CallBarOrPin: while a group's call runs and I am not in it, the bar takes the pinned bar's
 * place at the top of the chat: up to three faces (the call glyph on green until the list comes),
 * "Voice Chat" / "Video Chat" over "N participants", and a blue JOIN; the whole bar joins. Otherwise
 * [pin], the pinned bar as before.
 */
@Composable
fun ThreadLiveCallBar(cid: String, isGroup: Boolean, title: String, pin: @Composable () -> Unit) {
    var live by remember(cid) { mutableStateOf<LiveCall?>(null) }
    var conv by remember(cid) { mutableStateOf<Conversation?>(null) }
    val signedIn = FirebaseAuth.getInstance().currentUser != null
    if (isGroup && signedIn && cid.isNotEmpty()) {
        DisposableEffect(cid) {
            val reg = FirebaseFirestore.getInstance().collection("groupCalls").document(cid).addSnapshotListener { s, _ ->
                val d = s?.data
                live = if (d?.get("active") == true) LiveCall(cid, d["video"] == true, (d["joined"] as? List<*>)?.filterIsInstance<String>().orEmpty()) else null
            }
            onDispose { reg.remove() }
        }
        LaunchedEffect(cid) {
            conv = try {
                val d = FirebaseFirestore.getInstance().collection("conversations").document(cid).get().await().data
                d?.let { Conversation(id = cid, names = (it["names"] as? Map<*, *>).orEmpty().entries.associate { e -> e.key.toString() to e.value.toString() },
                    photos = (it["photos"] as? Map<*, *>).orEmpty().entries.associate { e -> e.key.toString() to e.value.toString() }) }
            } catch (_: Exception) { null }
        }
    }
    val g by GroupCall.ui.collectAsState()
    val l = live
    val shown = l != null && g.cid != cid
    AnimatedContent(shown, transitionSpec = { (slideInVertically { -it } + fadeIn()) togetherWith fadeOut() }, label = "callbar") { s ->
        if (s && l != null) CallBar(l, conv, title) else pin()
    }
}

@Composable
private fun CallBar(live: LiveCall, conv: Conversation?, title: String) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val people = LiveGroupCalls.rememberPeople(live.cid, live.joined)
    val heading = GroupCallWords.liveKind(live.video)
    val sub = GroupCallWords.barSubtitle(people.size)
    val shape = RoundedCornerShape(26.dp)
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp).fillMaxWidth().heightIn(min = 52.dp).floating(shape)
        .clickable { CallActivity.startGroup(ctx, live.cid, title, video = false) }
        .semantics { contentDescription = "$heading in $title, $sub. Join" }
        .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (people.isEmpty()) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                Icon(if (live.video) Icons.Filled.Videocam else Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else Faces(people, conv, 34, -10, null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(heading, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1)
            Text(sub, fontSize = 12.sp, color = c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.heightIn(min = 32.dp).clip(CircleShape).background(c.accent).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text("JOIN", color = c.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
