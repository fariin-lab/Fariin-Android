package com.fariin.app.ui.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.ThreadLogic
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The floating day pill (iOS NativeMessageList datePill): a glass capsule at the top centre with
 * the day of the topmost message on screen, shown while the list scrolls (from the first time the
 * reader drags it) and faded out 1.2 s after the scrolling stops. It reads the scroll position on
 * its own, so a scroll redraws only this pill, and only when the day changes.
 */
@Composable
internal fun FloatingDayPill(listState: LazyListState, messages: List<Message>, nowMillis: Double, modifier: Modifier = Modifier) {
    val list by rememberUpdatedState(messages)
    // The topmost message on screen is the LAST visible item of the reversed list (the rows past the
    // messages, the spinner and the welcome cards, are left out).
    val topRow by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index < list.size }?.index }
    }
    val label = remember(topRow, messages, nowMillis) {
        val idx = topRow ?: return@remember null
        list.getOrNull(list.size - 1 - idx)?.let { ThreadLogic.dayLabel(it.createdAtMillis, nowMillis) }
    }
    var revealed by remember { mutableStateOf(false) }   // iOS didReveal: not on the open's own scrolls
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) revealed = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress && revealed }.distinctUntilChanged().collectLatest { moving ->
            if (moving) shown = true else { delay(1_200); shown = false }
        }
    }
    val alpha by animateFloatAsState(if (shown && label != null) 1f else 0f, tween(if (shown) 150 else 250), label = "dayPill")
    if (alpha == 0f || label == null) return
    Text(
        label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label,
        modifier = modifier.graphicsLayer { this.alpha = alpha }.floating(RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
