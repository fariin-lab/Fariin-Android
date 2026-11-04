package com.fariin.app.ui.components

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

/**
 * A row's tap the way Android list views press an item (and the reference app's RecyclerListView,
 * studied 2026-11-04): Android's ripple grows from the finger's point and the click runs after the
 * system's pressed-state duration, so the grey is seen before the next page covers the row. In a
 * scrolling list a quick tap's press is held back to tell it from a scroll, so on release the row
 * is pressed again from where the finger was.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.listPress(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val press = remember { MutableInteractionSource() }
    var lastDown by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(press) { press.interactions.collect { if (it is PressInteraction.Press) lastDown = it.pressPosition } }
    val scope = rememberCoroutineScope()
    combinedClickable(interactionSource = press, indication = ripple(), enabled = enabled, onLongClick = onLongClick,
        onClick = {
            scope.launch {
                val p = PressInteraction.Press(lastDown)
                press.emit(p)
                delay(android.view.ViewConfiguration.getPressedStateDuration().toLong())
                onClick()
                delay(200)
                press.emit(PressInteraction.Release(p))
            }
        })
}
