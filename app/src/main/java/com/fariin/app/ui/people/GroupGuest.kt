package com.fariin.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelPreview
import com.fariin.app.channel.ChannelService
import com.fariin.app.data.PublicGroups
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * A public group opened by someone not in it (iOS PublicGroupGuest, owner 2026-11-11: Explore opens
 * the group itself, not a small sheet). The chat shows its messages when the rules let an outsider
 * read them (public, not end-to-end, history open), with a Join Group bar where the composer goes.
 * Joining turns the same page into the member's chat.
 */
object GroupGuestPreviews {
    /** Non-channel previews from publicGroupPreview, filled by the chat page's first ask. */
    val cache = mutableStateMapOf<String, ChannelPreview>()
    /** The server's words when it says no (banned, closed): Join stays grey with them. */
    val refused = mutableStateMapOf<String, String>()

    /** A refusal keeps its words, and the last saved preview so the page still shows the group. */
    fun noteRefused(cid: String, text: String) {
        refused[cid] = text
        if (cache[cid] == null) ChannelService.savedPreview(cid)?.takeIf { !it.isChannel }?.let { cache[cid] = it.copy(isMember = false) }
    }

    /**
     * A public group I am not in opens as its chat, Join at the foot (iOS "THE CHAT, ALWAYS"), as
     * Explore does. Only when its preview can't be had (offline, refused) does the join sheet open.
     */
    suspend fun open(cid: String, openChat: (String) -> Unit, openChannel: (String) -> Unit, openSheet: (String) -> Unit) {
        if (cache[cid] != null && refused[cid] == null) { openChat(cid); return }
        when (val r = ChannelService.preview(cid)) {
            is ChannelService.PreviewResult.Found -> {
                if (r.preview.isChannel) { openChannel(cid); return }
                if (!r.preview.isMember) cache[cid] = r.preview
                refused.remove(cid)
                openChat(cid)
            }
            is ChannelService.PreviewResult.Refused -> { noteRefused(cid, r.text); openSheet(cid) }
            is ChannelService.PreviewResult.Failed -> openSheet(cid)
        }
    }

    /** True for a join error that sticks (the server's "banned" words), kept like a refusal. */
    fun keepIfBanned(cid: String, words: String?): Boolean {
        if (words == null || !words.contains("banned", ignoreCase = true)) return false
        refused[cid] = words
        return true
    }
}

data class GroupGuestUi(
    val preview: ChannelPreview,
    val joining: Boolean,
    val requested: Boolean,
    val problem: String?,
    /** Banned or closed: Join is grey and these words say why. */
    val refused: String?,
    val onJoin: () -> Unit,
)

/** Null unless this is a public group (not a channel) that I am not in. */
@Composable
fun rememberGroupGuest(cid: String, me: String, doc: Map<String, Any?>, isChannel: Boolean, left: Boolean, onJoined: () -> Unit): GroupGuestUi? {
    // Just left (its Undo still running, or done): the copies on this phone may still list me, so
    // they do not count; the page is a guest's again, with Join (owner 2026-11-11).
    val inDoc = !left && me in ((doc["users"] as? List<*>) ?: emptyList<Any>())
    val listed = !left && com.fariin.app.data.CryptoHub.listedConversation(cid)?.users?.contains(me) == true
    val p = GroupGuestPreviews.cache[cid]
    // Opened as a member before, so no preview was asked for: ask now.
    LaunchedEffect(cid, left) {
        if (!left || isChannel || GroupGuestPreviews.cache[cid] != null) return@LaunchedEffect
        val r = ChannelService.preview(cid)
        if (r is ChannelService.PreviewResult.Found && !r.preview.isChannel) GroupGuestPreviews.cache[cid] = r.preview.copy(isMember = false)
    }
    var joining by remember(cid) { mutableStateOf(false) }
    var requested by remember(cid) { mutableStateOf(false) }
    var problem by remember(cid) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // A plain public group read from outside: its texts are not sealed.
    LaunchedEffect(p) { if (p != null) com.fariin.app.data.CryptoHub.notePlainGroup(cid) }
    if (isChannel || inDoc || listed || p == null || p.isChannel || (p.isMember && !left)) return null
    return GroupGuestUi(p, joining, requested || p.requested, problem, GroupGuestPreviews.refused[cid]) {
        if (joining || GroupGuestPreviews.refused[cid] != null) return@GroupGuestUi
        // Still inside the leave's Undo: nothing went to the server, so Join simply takes it back.
        if (com.fariin.app.data.LeaveUndo.isPending(cid)) { com.fariin.app.data.LeaveUndo.undo(); onJoined(); return@GroupGuestUi }
        joining = true; problem = null
        scope.launch {
            try {
                if (PublicGroups.join(cid) == "requested") requested = true else onJoined()
            } catch (e: Exception) {
                // Banned sticks: Join goes grey with the server's words, not a retry.
                if (!GroupGuestPreviews.keepIfBanned(cid, e.message)) problem = e.message ?: "Couldn't join. Try again."
            } finally { joining = false }
        }
    }
}

/** Where the composer goes for a guest: Join Group (or Request to Join), solid like Follow. */
@Composable
fun GroupGuestJoinBar(g: GroupGuestUi) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // A refusal (banned, closed) first: it is why Join is grey.
        val line = g.refused ?: g.problem ?: if (g.requested) "An admin will review your request." else null
        line?.let { Text(it, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp)) }
        val title = when {
            g.requested -> "Request Sent"
            g.preview.needsApproval -> "Request to Join"
            else -> "Join Group"
        }
        val grey = g.requested || g.refused != null
        val enabled = !grey && !g.joining
        val shape = RoundedCornerShape(25.dp)
        Box(Modifier.fillMaxWidth().height(50.dp).clip(shape)
            .background(if (grey) c.card else c.label)
            .clickable(enabled = enabled, onClick = g.onJoin), contentAlignment = Alignment.Center) {
            com.fariin.app.ui.components.BusyContent(g.joining, c.bg) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (grey) c.secondary else c.bg)
            }
        }
    }
}

/** No messages a guest may read (an older sealed group, or history hidden from newcomers). */
@Composable
fun GroupGuestEmpty(g: GroupGuestUi, modifier: Modifier) {
    val c = Fariin.colors
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        com.fariin.app.ui.components.Avatar(g.preview.photoUrl, 88.dp, kind = com.fariin.app.ui.components.AvatarKind.of(true, false))
        Text(g.preview.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
        if (g.preview.description.isNotEmpty()) Text(g.preview.description, fontSize = 15.sp, color = c.secondary,
            textAlign = TextAlign.Center, maxLines = 5, modifier = Modifier.padding(top = 6.dp))
        Text("You need to join this group to view its messages.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp))
    }
}

