package com.fariin.app.ui.glow

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.GlowRules
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/*
 * Glow's own look, stated once (iOS GlowStyle). The accent is the app's own black and white, never a
 * hue of its own; anything filled with it takes its label from onAccent. The mark is his drawing:
 * filled means the relationship exists, outline is the action and the empty state.
 */

@Composable
fun GlowMark(size: Dp, filled: Boolean = false, tint: Color = Fariin.colors.label, modifier: Modifier = Modifier) {
    Icon(painterResource(if (filled) R.drawable.ic_glow_fill else R.drawable.ic_glow), contentDescription = null,
        tint = tint, modifier = modifier.size(size))
}

/** The chat list's plain row avatar (iOS GlowStyle.rowAvatar). */
val GlowRowAvatar = 56.dp

/** "Glow back": the accent filled, 92 wide at least, 34 tall. */
@Composable
fun GlowFilledButton(title: String, compact: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(
        Modifier.then(if (compact) Modifier else Modifier.defaultMinSize(minWidth = 92.dp).height(34.dp))
            .clip(CircleShape).background(c.accent).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, fontSize = if (compact) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent) }
}

/**
 * "Glowing": a drawn 1 pt border over a 6% fill, the pair to [GlowFilledButton]. Removing a glow
 * asks first (his ruling): the capsule opens a menu with the red "Remove Glowing" row.
 */
@Composable
fun GlowingCapsule(compact: Boolean = false, onRemove: () -> Unit) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.then(if (compact) Modifier else Modifier.defaultMinSize(minWidth = 92.dp).height(34.dp))
                .clip(CircleShape).background(c.label.copy(alpha = 0.06f))
                .border(1.dp, c.label.copy(alpha = 0.35f), CircleShape)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Glowing", fontSize = if (compact) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = c.label) }
        RemoveGlowingMenu(open, onDismiss = { open = false }) { open = false; onRemove() }
    }
}

/** The one-row menu every remove door shares: the filled mark and "Remove Glowing", in red. */
@Composable
fun RemoveGlowingMenu(open: Boolean, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val c = Fariin.colors
    DropdownMenu(open, onDismissRequest = onDismiss, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
        DropdownMenuItem(text = { Text("Remove Glowing", color = c.red) }, onClick = onRemove,
            trailingIcon = { GlowMark(20.dp, filled = true, tint = c.red) })
    }
}

/** A refused give or remove; the set has already been rolled back. */
@Composable
fun GlowWriteFailed(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val c = Fariin.colors
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Couldn't update") }, text = { Text("Try again in a moment.") },
        confirmButton = { TextButton(onDismiss) { Text("OK", color = c.accent) } }, containerColor = c.card)
}

/** A screen's empty or failed state (iOS ContentUnavailableView): a mark, a title, a line, a button. */
@Composable
fun GlowUnavailable(title: String, body: String, icon: (@Composable () -> Unit)? = null,
                    action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Column(modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        icon?.invoke()
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.clip(CircleShape).background(c.accent).clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(action, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
            }
        }
    }
}

/** "Could not load" with Try Again, the same words on every Glow page. */
@Composable
fun GlowCouldNotLoad(onRetry: () -> Unit) = GlowUnavailable(
    "Could not load", "Check your connection and try again.",
    icon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_wifi_slash), contentDescription = null, tint = Fariin.colors.secondary, modifier = Modifier.size(44.dp)) },
    action = "Try Again", onAction = onRetry,
)

/**
 * The sheet that explains a Glow before you GIVE one (iOS GlowIntroSheet): his illustration, the
 * title, three facts, one wide "Glow" button. Never in front of a remove: taking a glow back hands
 * nobody anything new, so it needs no explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlowIntroSheet(name: String, onGlow: () -> Unit, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { then -> scope.launch { state.hide() }.invokeOnCompletion { then() } }
    // An x and no drag handle: two ways to say the same thing in one corner (iOS).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, shape = SheetShape, containerColor = c.card,
        dragHandle = null) {
        Column(Modifier.fillMaxWidth().height(430.dp).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                Image(painterResource(R.drawable.glow_intro_art), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(32.dp).clip(CircleShape)
                    .background(c.label.copy(alpha = 0.12f)).clickable { close(onDismiss) },
                    contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = c.label, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What a Glow does", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Text(GlowRules.introBody(name), fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f).defaultMinSize(minHeight = 16.dp))
            // "Glow", the action itself, not a link: pressing it gives the glow.
            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(c.accent)
                .clickable { onGlow(); close(onDismiss) }, contentAlignment = Alignment.Center) {
                Text("Glow", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
