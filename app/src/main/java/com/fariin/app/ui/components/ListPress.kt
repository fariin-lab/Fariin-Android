package com.fariin.app.ui.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.graphics.drawscope.clipRect

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
    // Where the finger went down and whether it travelled: plain fields, read only in the callbacks.
    // They are set by the watcher below (it used to be a collector of the press stream per row: one
    // more coroutine for every row a scroll brings in).
    val touch = remember { Touch() }
    val scope = rememberCoroutineScope()
    // A finger that travelled past the touch slop was a swipe, not a tap. The tap detector only
    // gives up when the finger leaves the row or a scroll takes it, so a sideways swipe across a
    // chat row opened the chat (WhatsApp comparison 2026-11-04). Watched, never consumed.
    val slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touch.down = down.position
            touch.moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) touch.moved = true
                if (!change.pressed) break
            }
        }
    }.combinedClickable(interactionSource = press, indication = ListRipple, enabled = enabled,
        // A slow sideways drag inside the row outlasts the long-press timeout (the detector only
        // gives up when the finger leaves the row), so it selected a chat. Moved = not a press.
        onLongClick = onLongClick?.let { lc -> { if (!touch.moved) lc() } },
        onClick = {
            if (touch.moved) return@combinedClickable
            scope.launch {
                val p = PressInteraction.Press(touch.down)
                press.emit(p)
                // Opens at once and the row stays grey while the page slides over it (iOS; owner's Pixel
                // 2026-11-09: the 0.2 s hold before the chat came in felt slow once the page slid from
                // the tap, and quick taps piled up behind it).
                onClick()
                delay(PRESS_HOLD_MS + 200)
                press.emit(PressInteraction.Release(p))
            }
        })
}

private class Touch { var down = Offset.Zero; var moved = false }

/**
 * The row's press ripple, drawn by the row itself: the same grow from the finger as Android's
 * ripple (start at 30 % of the row, out past its corners in 225 ms, the centre drifting to the
 * middle; in over 75 ms, out over 150 ms; the content colour at 10 %, clipped to the row).
 *
 * Android's own ripple runs on the drawing thread and draws the whole window again on every one of
 * its frames, by itself. A tap on a chat row starts it in the same frame the chat is built, so the
 * two fought for that thread and its frames were the late ones (Pixel 5 trace 2026-11-15: two
 * 24 to 26 ms frames of the ripple's own on every chat open). Drawn here, it moves with the app's
 * frames and redraws only the row.
 */
internal object ListRipple : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        ListRippleNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x5112
}

private class ListRippleNode(private val source: androidx.compose.foundation.interaction.InteractionSource) :
    Modifier.Node(), androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.CompositionLocalConsumerModifierNode {
    private val grow = androidx.compose.animation.core.Animatable(0f)
    private val shown = androidx.compose.animation.core.Animatable(0f)
    private var origin = Offset.Zero
    private var pressJob: kotlinx.coroutines.Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> press(i.pressPosition)
                    is PressInteraction.Release, is PressInteraction.Cancel -> release()
                }
            }
        }
    }

    private fun press(at: Offset) {
        origin = at
        pressJob?.cancel()
        pressJob = coroutineScope.launch {
            grow.snapTo(0f); shown.snapTo(0f)
            launch { shown.animateTo(1f, androidx.compose.animation.core.tween(RIPPLE_IN_MS, easing = androidx.compose.animation.core.LinearEasing)) }
            grow.animateTo(1f, androidx.compose.animation.core.tween(RIPPLE_GROW_MS, easing = androidx.compose.animation.core.LinearEasing))
        }
    }

    private fun release() {
        val pressing = pressJob
        coroutineScope.launch {
            pressing?.join()   // the ripple finishes growing before it goes, as Android's does
            shown.animateTo(0f, androidx.compose.animation.core.tween(RIPPLE_OUT_MS, easing = androidx.compose.animation.core.LinearEasing))
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
        val a = shown.value
        if (a <= 0f) return
        val t = grow.value
        val c = currentValueOf(androidx.compose.material3.LocalContentColor)
        val start = maxOf(size.width, size.height) * 0.3f
        val end = Offset(size.width, size.height).getDistance() / 2f + 10.dp.toPx()
        val centre = Offset(origin.x + (size.width / 2f - origin.x) * t, origin.y + (size.height / 2f - origin.y) * t)
        clipRect {
            drawCircle(c.copy(alpha = c.alpha * RIPPLE_ALPHA * a), radius = start + (end - start) * t, center = centre)
        }
    }
}

private const val RIPPLE_IN_MS = 75
private const val RIPPLE_GROW_MS = 225
private const val RIPPLE_OUT_MS = 150
/** Material's pressed state layer. */
private const val RIPPLE_ALPHA = 0.1f
