package com.fariin.app.ui.glow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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

/** The sheet and the refusal alert for [door]; place once on the page. */
@Composable
fun GlowDoorHost(door: GlowDoor) {
    if (door.intro) GlowIntroSheet(door.name, onGlow = { door.give() }, onDismiss = { door.intro = false })
    GlowWriteFailed(door.failed) { door.failed = false }
}

/** The action row's Glow circle, in the row's own look (a 52 floating circle, 13 sp label). */
@Composable
fun GlowActionTile(door: GlowDoor) {
    val c = Fariin.colors
    val s by Glow.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val glowing = s.glowing.contains(door.uid)
    val label = if (s.known && glowing) "Remove Glowing" else "Glow Story"
    Box {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp))
                .clickable(enabled = s.known) { if (glowing) menu = true else door.intro = true }
                .padding(4.dp).alpha(if (s.known) 1f else 0.35f)
                .semantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(52.dp).floating(), contentAlignment = Alignment.Center) {
                GlowMark(24.dp, filled = s.known && glowing)
            }
            Spacer(Modifier.height(6.dp))
            Text("Glow", fontSize = 13.sp, color = c.label)
        }
        RemoveGlowingMenu(menu, onDismiss = { menu = false }) { menu = false; door.remove() }
    }
}

/** The page bar's overflow holding "Glow Story" (or "Remove Glowing"), the second door. */
@Composable
fun GlowOverflow(door: GlowDoor) {
    val c = Fariin.colors
    val s by Glow.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val glowing = s.known && s.glowing.contains(door.uid)
    Box {
        FloatingIcon(Icons.Filled.MoreVert, contentDescription = "More", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
            if (glowing) DropdownMenuItem(text = { Text("Remove Glowing", color = c.red) },
                onClick = { open = false; door.remove() }, leadingIcon = { GlowMark(20.dp, filled = true, tint = c.red) })
            else DropdownMenuItem(text = { Text("Glow Story", color = if (s.known) c.label else c.secondary) }, enabled = s.known,
                onClick = { open = false; door.intro = true }, leadingIcon = { GlowMark(20.dp, tint = if (s.known) c.label else c.secondary) })
        }
    }
}
