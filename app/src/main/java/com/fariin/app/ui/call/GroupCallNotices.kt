package com.fariin.app.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallUi
import com.fariin.app.call.GroupNotice
import com.fariin.app.call.GroupNoticeQueue
import com.fariin.app.call.NoticePerson
import com.fariin.app.perf.rememberPowerState
import com.fariin.app.ui.components.Avatar
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * iOS GroupCallView `notes`: stacked under the header, who joined or left (GroupCallStatusBanner)
 * and the call's short notes (GroupCallTopToast, "Ana is sharing their screen", "Couldn't answer").
 * Both are the same small centred pill, slide down from the top and back up; with Power Saving's
 * animations off they only fade. They take no touches.
 */
@Composable
internal fun GroupCallNotes(g: GroupCallUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        JoinLeaveBanner(g)
        TopToast(g.toast)
    }
}

@Composable
private fun JoinLeaveBanner(g: GroupCallUi) {
    val queue = remember { GroupNoticeQueue() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    var notice by remember { mutableStateOf<GroupNotice?>(null) }
    val people = g.members.filter { !it.local && it.id.isNotEmpty() }.associate { it.id to NoticePerson(it.id, it.name, it.photo) }
    val connected = g.joined && !g.reconnecting
    LaunchedEffect(people, connected) { if (queue.update(people, connected)) wake.trySend(Unit) }
    LaunchedEffect(Unit) {
        // A fixed window from the first change, so a burst becomes one notice; then each waiting
        // notice in turn: slide in, hold, slide out (iOS 0.35 s / 2 s / 0.35 s).
        for (x in wake) {
            delay(GroupNoticeQueue.BATCH_MS)
            while (true) {
                val n = queue.next() ?: break
                notice = n
                delay(GroupNoticeQueue.SLIDE_MS + GroupNoticeQueue.HOLD_MS)
                notice = null
                delay(GroupNoticeQueue.SLIDE_MS.toLong())
            }
        }
    }
    DisposableEffect(Unit) { onDispose { queue.reset() } }
    // iOS: my own poor network outranks a join/leave notice.
    val poor = g.members.firstOrNull { it.local }?.poor == true && connected
    NoticeSlot(if (poor) GroupNotice("Poor connection") else notice)
}

@Composable
private fun TopToast(text: String?) {
    // iOS showToast: four seconds, then it goes (a newer note starts its own four).
    LaunchedEffect(text) { if (text != null) { delay(GroupNoticeQueue.TOAST_MS); GroupCall.clearToast() } }
    NoticeSlot(text?.let { GroupNotice(it) })
}

@Composable
private fun NoticeSlot(item: GroupNotice?) {
    val motion = rememberPowerState().animations
    // The last notice stays drawn while it slides away.
    var last by remember { mutableStateOf(item) }
    if (item != null) last = item
    val ms = GroupNoticeQueue.SLIDE_MS
    AnimatedVisibility(
        visible = item != null,
        enter = if (motion) slideInVertically(tween(ms, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(ms)) else fadeIn(tween(ms)),
        exit = if (motion) slideOutVertically(tween(ms, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(ms)) else fadeOut(tween(ms)),
    ) {
        last?.let { NoticeCard(it) }
    }
}

/**
 * iOS GroupCallNoticeCard: a small centred capsule, semibold white words on a dark backdrop, a
 * 26 dp photo when it is about one person, at least 36 dp tall, 24 dp clear of the screen's sides.
 */
@Composable
private fun NoticeCard(n: GroupNotice) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(Color(0xFF1C1C1E).copy(alpha = 0.82f))
                .heightIn(min = 36.dp)
                .padding(start = if (n.avatar == null) 14.dp else 5.dp, end = 14.dp, top = 5.dp, bottom = 5.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            n.avatar?.let {
                Avatar(it.photo, 26.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(n.text, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}
