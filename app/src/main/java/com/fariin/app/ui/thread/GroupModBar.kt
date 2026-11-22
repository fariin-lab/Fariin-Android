package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * iOS groupModBar (ThreadView composerNotice): a group that is ended, suspended or disabled has no
 * composer; in its place, on the composer's own edges, a full-width glass card (26 corners) with the
 * notice centred in it, medium weight, secondary colour.
 */
@Composable
internal fun GroupModBar(text: String) {
    val c = Fariin.colors
    val rest = rememberComposerRestInsets()
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(rest)
            .padding(start = COMPOSER_SIDE, end = COMPOSER_SIDE, top = 8.dp, bottom = 8.dp)
            .floating(RoundedCornerShape(26.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp))
}

/**
 * iOS groupModHidesChat page: a group Fariin suspended (not over) or closed shows no messages, only
 * why, on a plain opaque page in the middle of the chat. It takes every touch, so nothing under it
 * can be pressed. A group its owner ended keeps its messages and never shows this.
 */
@Composable
internal fun GroupUnavailablePage(reason: String, modifier: androidx.compose.ui.Modifier) {
    val c = Fariin.colors
    androidx.compose.foundation.layout.Box(modifier.background(c.bg)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_sf_shield_fill),
                null, tint = c.secondary, modifier = Modifier.size(40.dp))
            Text("This group is unavailable", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            if (reason.isNotEmpty()) Text(reason, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * iOS RejoinGroupBar: a public group I LEFT, open in its chat. Leaving is never a ban, so where the
 * composer was, the same Join pill as a guest's. The chat list brings the group back once the server
 * adds me (FormerGroups.noteList), and the chat follows it; no longer than 15 s of spinning.
 */
@Composable
internal fun RejoinGroupBar(cid: String) {
    val c = Fariin.colors
    var joining by androidx.compose.runtime.remember(cid) { androidx.compose.runtime.mutableStateOf(false) }
    var requestSent by androidx.compose.runtime.remember(cid) { androidx.compose.runtime.mutableStateOf(false) }
    var joinError by androidx.compose.runtime.remember(cid) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var removed by androidx.compose.runtime.remember(cid) { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        val line = when {
            removed -> null
            joinError != null -> joinError
            requestSent -> "Your request is waiting for an admin."
            else -> null
        }
        if (removed) Text("You were removed from this group", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        line?.let { Text(it, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp)) }
        if (!removed) {
            val shape = RoundedCornerShape(25.dp)
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(50.dp).clip(shape)
                .background(if (requestSent) c.card else c.label)
                .clickable(enabled = !requestSent && !joining) {
                    joining = true; joinError = null
                    scope.launch {
                        try {
                            if (com.fariin.app.data.PublicGroups.join(cid) == "requested") {
                                requestSent = true; joining = false
                            } else {
                                // Spins until the chat list brings the group back (this bar then goes).
                                kotlinx.coroutines.delay(15_000)
                                if (joining) { joining = false; joinError = "The group didn't open. Try again." }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val words = e.message ?: "Couldn't join. Try again."
                            // Removed by an admin: that sticks, so no button to tap again (iOS).
                            if (words.contains("banned", ignoreCase = true)) removed = true
                            joinError = words
                            joining = false
                        }
                    }
                }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                com.fariin.app.ui.components.BusyContent(joining, c.bg) {
                    Text(if (requestSent) "Request Sent" else "Join Group", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = if (requestSent) c.secondary else c.bg)
                }
            }
        }
    }
}
