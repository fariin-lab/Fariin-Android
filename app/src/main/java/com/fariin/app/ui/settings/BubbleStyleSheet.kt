package com.fariin.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * text opens it. The same chooser as Settings > Bubble Style, with Cancel and Save in its top strip;
 * the strip also drags the sheet down.
 */
object BubbleStyleSheet {
    /** Set by the menu row; the chat screen's [Host] shows the sheet while it is true. */
    val open = mutableStateOf(false)

    @Composable
    fun Host() {
        // Leaving the chat with the sheet up must not reopen it in the next chat.
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { open.value = false } }
        if (open.value) Sheet(onDismiss = { open.value = false })
    }
}

@Composable
private fun Sheet(onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onDismiss)
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        BubbleStyleChooser(
            bottomPad = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            onSaved = { motion.close() },
            bar = { dirty, save ->
                Box(Modifier.fillMaxWidth().sheetDrag(motion).padding(horizontal = 8.dp, vertical = 10.dp)) {
                    TextButton({ motion.close() }, Modifier.align(Alignment.CenterStart)) {
                        Text("Cancel", fontSize = 17.sp, color = c.accent)
                    }
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
