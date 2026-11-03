package com.fariin.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One button behind a chat row (iOS UIContextualAction). */
internal class SwipeAction(val title: String, val icon: Painter, val fill: Color, val run: () -> Unit)

/** iOS ChatListTable's colours: systemBlue, systemOrange, systemGray, systemRed, systemIndigo. */
internal object SwipeColors {
    val blue = Color(0xFF007AFF)
    val orange = Color(0xFFFF9500)
    val gray = Color(0xFF8E8E93)
    val red = Color(0xFFFF3B30)
    val indigo = Color(0xFF5856D6)
}

private val ButtonWidth = 74.dp

/**
 * iOS ChatListTable's swipe actions on a chat row: to the right, [leading] (Read or Unread, Pin);
 * to the left, [trailing] (Archive, Delete, Mute), the first of each set nearest the edge. A swipe
 * past half the row runs the first action at once (performsFirstActionWithFullSwipe); otherwise
 * the row stays open on its buttons until a tap, another swipe or a tap on the row closes it.
 * Long-press select stays the Android way in; this is the second way, as on iOS.
 */
@Composable
internal fun SwipeActionsRow(
    leading: List<SwipeAction>, trailing: List<SwipeAction>, enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(0f) }
    val width = remember { floatArrayOf(0f) }
    val btn = with(density) { ButtonWidth.toPx() }
    val openLeft = trailing.size * btn
    val openRight = leading.size * btn
    val settle = spring<Float>(dampingRatio = 1f, stiffness = 600f)
    val armed = remember { booleanArrayOf(false) }

    fun close() = scope.launch { x.animateTo(0f, settle) }
    fun fire(a: SwipeAction) { close(); a.run() }

    Box(Modifier.onSizeChanged { width[0] = it.width.toFloat() }) {
        // The buttons under the row, revealed by the drag.
        if (x.value > 0f && leading.isNotEmpty()) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.Start) {
                leading.forEach { a -> ActionButton(a, Modifier.width(with(density) { (x.value / leading.size).toDp() })) { fire(a) } }
            }
        }
        if (x.value < 0f && trailing.isNotEmpty()) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                // Shown right to left from the edge: the first action sits at the far right.
                trailing.reversed().forEach { a -> ActionButton(a, Modifier.width(with(density) { (-x.value / trailing.size).toDp() })) { fire(a) } }
            }
        }
        Box(
            Modifier.offset { IntOffset(x.value.roundToInt(), 0) }
                .then(if (!enabled) Modifier else Modifier.draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d ->
                        val next = (x.value + d).coerceIn(if (trailing.isEmpty()) 0f else -width[0], if (leading.isEmpty()) 0f else width[0])
                        scope.launch { x.snapTo(next) }
                        // A light tick when a full swipe would fire, as the iOS row does.
                        val full = abs(next) > width[0] / 2
                        if (full != armed[0]) { armed[0] = full; if (full) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                    },
                    onDragStopped = { v ->
                        val now = x.value
                        armed[0] = false
                        when {
                            now < -width[0] / 2 -> trailing.firstOrNull()?.let { fire(it) }
                            now > width[0] / 2 -> leading.firstOrNull()?.let { fire(it) }
                            now < -openLeft / 2 || v < -1200f -> x.animateTo(-openLeft, settle)
                            now > openRight / 2 || v > 1200f -> x.animateTo(openRight, settle)
                            else -> x.animateTo(0f, settle)
                        }
                    },
                )),
        ) {
            content()
            // A tap on an open row closes it instead of opening the chat: a cover over the row
            // takes the touch before the row's own tap can.
            if (x.value != 0f) Box(Modifier.matchParentSize().clickable(indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { close() })
        }
    }
}

@Composable
private fun ActionButton(a: SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().background(a.fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(a.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text(a.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
