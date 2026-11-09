package com.fariin.app.ui.glow

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Glow
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.GlassActionCircle
import androidx.compose.foundation.layout.fillMaxWidth
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The two doors to one action on somebody's profile (iOS ContactInfoView glowActionButton and
 * glowMenuItem): the Glow circle in the action row, and "Glow Story" in the overflow. Giving goes
 * through the intro sheet; taking back opens a menu and asks. Neither state shows until the
 * relationship is known: before the listeners answer, everybody reads as not glowed.
 */

@Stable
class GlowDoor(val uid: String, val name: String, private val scope: CoroutineScope) {
    var intro by mutableStateOf(false)
    var failed by mutableStateOf(false)
    fun give() { scope.launch { if (!Glow.give(uid)) failed = true } }
    fun remove() { scope.launch { if (!Glow.remove(uid)) failed = true } }
}

@Composable
fun rememberGlowDoor(uid: String, name: String): GlowDoor {
    val scope = rememberCoroutineScope()
    return remember(uid, name) { GlowDoor(uid, name, scope) }
}

/**
 * My Profile opened from a chat (iOS ThreadView showMyProfile): my own card or username link tapped
 * in a message. Outside the tab shell, so it reads my profile and my stories itself.
 */
@Composable
fun MyProfileRoute(me: String, conversations: List<com.fariin.app.data.Conversation>, onBack: () -> Unit) {
    val prof = remember(me) { com.fariin.app.data.MyProfileRepository().listen(me) }.collectAsState(initial = null).value
    val convs by androidx.compose.runtime.rememberUpdatedState(conversations)
    val profNow by androidx.compose.runtime.rememberUpdatedState(prof)
    val stories = remember(me) {
        com.fariin.app.data.StoriesRepository().listen(me, { convs }, { profNow?.name?.ifEmpty { null } ?: "You" }, { profNow?.photoUrl })
    }.collectAsState(initial = com.fariin.app.data.StoriesState()).value
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    ProvideMyStories(stories, conversations) { GlowProfilePage(me, prof, onEdit = nav.editProfile, onBack = onBack) }
}

/** The sheet and the refusal alert for [door]; place once on the page. */
@Composable
fun GlowDoorHost(door: GlowDoor) {
    if (door.intro) GlowIntroSheet(door.name, onGlow = { door.give() }, onDismiss = { door.intro = false })
    GlowWriteFailed(door.failed) { door.failed = false }
}

/** The action row's Glow circle, in the row's own look (the shared glass circle, iOS PosterActionIcon). */
@Composable
fun GlowActionTile(door: GlowDoor, modifier: Modifier = Modifier) {
    val s by Glow.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val glowing = s.glowing.contains(door.uid)
    val label = if (s.known && glowing) "Remove Glowing" else "Glow Story"
    Box(modifier.semantics { contentDescription = label }) {
        // iOS draws "ic_glow" at 0.42 of the 60 circle.
        GlassActionCircle("Glow", Modifier.fillMaxWidth(), dimmed = !s.known, enabled = s.known,
            onClick = { if (glowing) menu = true else door.intro = true }) { col ->
            GlowMark(60.dp * 0.42f, filled = s.known && glowing, tint = col)
        }
        RemoveGlowingMenu(menu, onDismiss = { menu = false }) { menu = false; door.remove() }
    }
}

/** The page bar's overflow holding "Glow Story" (or "Remove Glowing"), the second door; [more] adds the
 *  page's own items under it (the contact page's Change Wallpaper), given a close for the menu. */
@Composable
fun GlowOverflow(door: GlowDoor, more: @Composable (close: () -> Unit) -> Unit = {}) {
    val c = Fariin.colors
    val s by Glow.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val glowing = s.known && s.glowing.contains(door.uid)
    Box {
        FloatingIcon(ImageVector.vectorResource(R.drawable.ic_more_ios), contentDescription = "More", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
            if (glowing) DropdownMenuItem(text = { Text("Remove Glowing", color = c.red) },
                onClick = { open = false; door.remove() }, trailingIcon = { GlowMark(20.dp, filled = true, tint = c.red) })
            else DropdownMenuItem(text = { Text("Glow Story", color = if (s.known) c.label else c.secondary) }, enabled = s.known,
                onClick = { open = false; door.intro = true }, trailingIcon = { GlowMark(20.dp, tint = if (s.known) c.label else c.secondary) })
            more { open = false }
        }
    }
}
