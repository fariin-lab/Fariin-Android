package com.fariin.app.ui.chats

import com.fariin.app.ui.components.GlassDropdown
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.GroupInfoCircles
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.SheetCard
import com.fariin.app.ui.components.SheetRow
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin

/**
 * The chat list's timed mute (iOS MainShell chat menu: Mute for 1 hour / 8 hours / 1 week / Mute
 * Always). The end it picks is the `mutedBy.<me>` stamp every client reads (GroupInfoCircles).
 */
object MuteFor {
    /** iOS's chat list order; null hours = Always. */
    val CHOICES: List<Pair<String, Double?>> =
        listOf("Mute for 1 hour" to 1.0, "Mute for 8 hours" to 8.0, "Mute for 1 week" to 168.0, "Mute Always" to null)

    /**
     * ChatService.setMute for a channel I follow: on while the end is ahead; Always (past five years
     * out) is stored as 0 = no end.
     */
    fun channelEntry(until: Double, nowMs: Double): Pair<Boolean, Double> {
        val on = until > nowMs
        val always = until > nowMs + 5 * 365 * 86_400_000.0
        return on to if (!on || always) 0.0 else until
    }

    fun until(hours: Double?, nowMs: Long = System.currentTimeMillis()): Double = GroupInfoCircles.muteUntil(hours, nowMs)

    /** "Muted" / "Muted for 1 hour" for the snackbar after a pick. */
    fun doneWord(label: String): String = if (label == "Mute Always") "Muted" else "Muted for " + label.removePrefix("Mute for ")
}

/** The selection bar's Mute: a menu under the icon. */
@Composable
internal fun MuteForMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (label: String, until: Double) -> Unit) {
    val c = Fariin.colors
    GlassDropdown(expanded, onDismissRequest = onDismiss, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
        Text(GroupInfoCircles.MUTE_HEADER, fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        MuteFor.CHOICES.forEach { (label, hours) ->
            DropdownMenuItem(text = { Text(label, color = c.label) }, onClick = { onDismiss(); onPick(label, MuteFor.until(hours)) })
        }
    }
}

/** A swiped row's Mute: the same choices in a bottom sheet (the button has nothing to hang a menu on). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MuteForSheet(onDismiss: () -> Unit, onPick: (label: String, until: Double) -> Unit) {
    val c = Fariin.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = sheetFill) {
        Text(GroupInfoCircles.MUTE_HEADER, fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
        SheetCard(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            MuteFor.CHOICES.forEachIndexed { i, (label, hours) ->
                SheetRow(label, icon = null, last = i == MuteFor.CHOICES.lastIndex) { onDismiss(); onPick(label, MuteFor.until(hours)) }
            }
        }
    }
}
