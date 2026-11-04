package com.fariin.app.ui.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a tapped row shows its grey before the click runs. */
const val PRESS_HOLD_MS = 200L

/**
 * A row's tap the way Android list views press an item (and the reference app's RecyclerListView,
 * studied 2026-11-04): Android's ripple grows from the finger's point and the click runs after the
 * system's pressed-state duration, so the grey is seen before the next page covers the row. In a
 * scrolling list a quick tap's press is held back to tell it from a scroll, so on release the row
 * is pressed again from where the finger was. The click waits [PRESS_HOLD_MS].
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.listPress(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val press = remember { MutableInteractionSource() }
    var lastDown by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(press) { press.interactions.collect { if (it is PressInteraction.Press) lastDown = it.pressPosition } }
    val scope = rememberCoroutineScope()
    // A finger that travelled past the touch slop was a swipe, not a tap. The tap detector only
    // gives up when the finger leaves the row or a scroll takes it, so a sideways swipe across a
    // chat row opened the chat (WhatsApp comparison 2026-11-04). Watched, never consumed.
    var moved by remember { mutableStateOf(false) }
    val slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) moved = true
                if (!change.pressed) break
            }
        }
    }.combinedClickable(interactionSource = press, indication = ripple(), enabled = enabled, onLongClick = onLongClick,
        onClick = {
            if (moved) return@combinedClickable
            scope.launch {
                val p = PressInteraction.Press(lastDown)
                press.emit(p)
                // Held as long as the big Android messenger holds it (owner's video, 2026-11-04: the
                // whole row grey for about 0.2 s before the chat came in), so the ripple fills the row.
                delay(PRESS_HOLD_MS)
                onClick()
                delay(200)
                press.emit(PressInteraction.Release(p))
            }
        })
}
