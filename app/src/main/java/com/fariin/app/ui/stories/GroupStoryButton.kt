package com.fariin.app.ui.stories

import com.fariin.app.ui.components.GlassAlert
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.fariin.app.R
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.fariin.app.data.GroupStoryCompose
import com.fariin.app.data.GroupStoryRights
import com.fariin.app.system.SystemRouter
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.theme.Fariin

/**
 * ADD GROUP STORY, top right of a group's or channel's page (iOS GroupInfoView toolbar, owner
 * 2026-10-23 / 2026-11-08): shown to whoever may post stories there (the owner, an admin with Add
 * story) WHATEVER THE SIZE. Below the minimum the tap explains when stories open; otherwise it opens
 * the story camera with this group already the audience of the last sheet. [onLeave] closes the page
 * (the camera lives on the Stories tab).
 */
@Composable
fun GroupStoryButton(me: String, cid: String, onLeave: () -> Unit) {
    val c = Fariin.colors
    val state by produceState<GroupStoryRights.ButtonState?>(null, me, cid) { value = GroupStoryRights.buttonState(me, cid) }
    val s = state ?: return
    var tooSmall by remember { mutableStateOf(false) }
    FloatingIcon(painterResource(R.drawable.ic_new_story), if (s.target.isChannel) "Add channel story" else "Add group story", onClick = {
        if (!s.bigEnough) tooSmall = true
        else {
            GroupStoryCompose.preset = s.target
            onLeave()
            SystemRouter.pending.value = SystemRouter.NEW_STORY
        }
    })
    if (tooSmall) {
        val ch = s.target.isChannel
        GlassAlert(onDismissRequest = { tooSmall = false }, containerColor = c.card,
            title = { Text(if (ch) "Channel stories aren't open yet" else "Group stories aren't open yet") },
            text = { Text(if (ch) "Stories open when this channel reaches ${s.need} followers. It has ${s.have} now."
                          else "Stories open when this group reaches ${s.need} members. It has ${s.have} now.") },
            confirmButton = { TextButton({ tooSmall = false }) { Text("OK", color = c.accent) } })
    }
    // Two buttons, not one shape (iOS ToolbarSpacer between Add Story and Edit).
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
}
