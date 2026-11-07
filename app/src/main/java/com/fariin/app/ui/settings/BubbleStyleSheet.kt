package com.fariin.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin

/**
 * The Bubble Style picker as a sheet over the chat (owner 2026-11-07): the long-press menu on my own
 * text opens it. The same chooser as Settings > Bubble Style, with a round X and Save in its top strip;
 * the strip and the grabber move the sheet. It opens at 70% of the screen, the chat above it.
 */
object BubbleStyleSheet {
    /** Set by the menu row; the chat screen's [Host] shows the sheet while it is true. */
    val open = mutableStateOf(false)

    @Composable
    fun Host(cid: String?) {
        // Leaving the chat with the sheet up must not reopen it in the next chat.
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { open.value = false } }
        if (open.value) Sheet(cid, onDismiss = { open.value = false })
    }

    /** The same sheet opened from elsewhere (the chat wallpaper picker's "See all"). */
    @Composable
    fun Show(cid: String?, onDismiss: () -> Unit) = Sheet(cid, onDismiss)
}

@Composable
private fun Sheet(cid: String?, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onDismiss)
    // Opens at 70% of the screen with the chat above it (owner 2026-11-07); the grabber or the top
    // strip pulls it to full or down to close. The sheet's top at full sits 10 dp under the status bar.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowH = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    motion.mid = (windowH * 0.3f - statusTop - with(density) { 10.dp.toPx() }).coerceAtLeast(1f)
    // At the 70% stop the sheet's lower part is below the screen: the grid's end pads by that much
    // so its last row still scrolls into view.
    val hidden = with(density) { motion.mid.toDp() }
    FariinSheetDialog(motion, handle = true, fill = c.bgSecondary) { _ ->
        BubbleStyleChooser(
            bottomPad = 16.dp + hidden + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            onSaved = { motion.close() },
            cid = cid,
            bar = { dirty, save ->
                // Under the grabber (FariinSheet draws it in the top strip).
                Box(Modifier.fillMaxWidth().sheetDrag(motion).padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 10.dp)) {
                    // The round X the other sheets use (owner 2026-11-07), not a "Cancel" word.
                    com.fariin.app.ui.components.FloatingIcon(androidx.compose.material.icons.Icons.Filled.Close, "Cancel",
                        onClick = { motion.close() }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
                    Text("Bubble Style", Modifier.align(Alignment.Center), fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, color = c.label)
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                        com.fariin.app.ui.components.FloatingTextAction("Save", enabled = dirty) { save() }
                    }
                }
            },
        )
    }
}
