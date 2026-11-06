package com.fariin.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One button behind a chat row (iOS UIContextualAction): its word, picture and colour. */
internal class SwipeAction(val label: String, val icon: Painter, val fill: Color, val run: () -> Unit)

/** iOS system colours the chat list's swipe buttons use (ChatListTable). */
internal object SwipeColors {
    val blue = Color(0xFF007AFF)
    val orange = Color(0xFFFF9500)
    val gray = Color(0xFF8E8E93)
    val red = Color(0xFFFF3B30)
    val indigo = Color(0xFF5856D6)
}

/** The one row whose buttons are showing; opening another closes it (iOS table behaviour). */
private object OpenSwipe { var id by mutableStateOf<String?>(null) }

private val ButtonWidth = 74.dp

/**
 * A chat row that slides sideways to show buttons, as the iOS chat list does: swipe right for
 * [leading] (Read/Unread, Pin), left for [trailing] (Mute, Delete, Archive, outermost last).
 * Let go past half the buttons and they stay open; a tap on the open row closes it. The drag only
 * starts once the finger has moved sideways past the touch slop, so the list still scrolls, and the
 * row's own tap is dropped after a swipe (listPress). [enabled] false (selecting, searching) turns
 * it off and closes it.
 */
@Composable
internal fun SwipeActionsRow(
    id: String,
    enabled: Boolean,
    leading: List<SwipeAction>,
    trailing: List<SwipeAction>,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val unit = with(density) { ButtonWidth.toPx() }
    val maxL = unit * leading.size
    val maxR = unit * trailing.size
    val offset = remember(id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = 500f)
    fun close() { scope.launch { offset.animateTo(0f, settle) }; if (OpenSwipe.id == id) OpenSwipe.id = null }
    LaunchedEffect(OpenSwipe.id) { if (OpenSwipe.id != id && offset.value != 0f) offset.animateTo(0f, settle) }
    LaunchedEffect(enabled) { if (!enabled) { offset.snapTo(0f); if (OpenSwipe.id == id) OpenSwipe.id = null } }

    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds()) {
        val x = offset.value
        if (x > 0f) ActionStrip(leading, Modifier.align(Alignment.CenterStart).width(with(density) { x.toDp() })) { a -> close(); a.run() }
        if (x < 0f) ActionStrip(trailing, Modifier.align(Alignment.CenterEnd).width(with(density) { (-x).toDp() })) { a -> close(); a.run() }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(if (!enabled) Modifier else Modifier.pointerInput(id, maxL, maxR) {
                    detectHorizontalDragGestures(
                        onDragStart = { OpenSwipe.id = id },
                        onDragEnd = {
                            val v = offset.value
                            val target = when {
                                v > maxL / 2 -> maxL
                                v < -maxR / 2 -> -maxR
                                else -> 0f
                            }
                            scope.launch { offset.animateTo(target, settle) }
                            if (target == 0f && OpenSwipe.id == id) OpenSwipe.id = null
                        },
                        onDragCancel = { close() },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(-maxR, maxL)) }
                    }
                }),
        ) {
            content()
            // While the buttons show, a tap on the row only closes them (it does not open the chat).
            if (offset.value != 0f) Box(Modifier.matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
        }
    }
}

@Composable
private fun ActionStrip(actions: List<SwipeAction>, modifier: Modifier, onPick: (SwipeAction) -> Unit) {
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.Start) {
        actions.forEach { a ->
            Column(
                Modifier.weight(1f).fillMaxHeight().background(a.fill).clickable(onClickLabel = a.label) { onPick(a) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(a.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(a.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}


/**
 * The Android chat-list swipe (owner 2026-11-06: "keep what Android people expect"): one action,
 * as Google's own lists do. Drag the row left; past a third of its width the archive mark grows and
 * the phone ticks; let go there and the row slides off and [onAction] runs (the caller shows Undo).
 * Short of it, the row springs back. Only to the left: a swipe from the left edge is Android's back
 * gesture. The iOS button tray ([SwipeActionsRow]) stays for reference but the list no longer uses it.
 */
@Composable
internal fun SwipeToActRow(
    id: String,
    enabled: Boolean,
    label: String,
    icon: Painter,
    fill: Color,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offset = remember(id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var widthPx by remember { mutableStateOf(1f) }
    var armed by remember(id) { mutableStateOf(false) }
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = 500f)
    LaunchedEffect(enabled) { if (!enabled) offset.snapTo(0f) }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds()
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }) {
        val x = offset.value
        if (x < 0f) Box(Modifier.matchParentSize().background(fill), contentAlignment = Alignment.CenterEnd) {
            val grow by androidx.compose.animation.core.animateFloatAsState(if (armed) 1.15f else 1f, label = "swipeMark")
            Column(Modifier.padding(end = 24.dp).graphicsLayer { scaleX = grow; scaleY = grow }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(if (!enabled) Modifier else Modifier.pointerInput(id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) scope.launch {
                                offset.animateTo(-widthPx, spring(dampingRatio = 1f, stiffness = 900f))
                                onAction()
                                offset.snapTo(0f)
                            } else scope.launch { offset.animateTo(0f, settle) }
                            armed = false
                        },
                        onDragCancel = { armed = false; scope.launch { offset.animateTo(0f, settle) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch {
                            val v = (offset.value + dx).coerceIn(-widthPx, 0f)
                            offset.snapTo(v)
                            val now = -v > widthPx / 3f
                            if (now != armed) { armed = now; if (now) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                        }
                    }
                }),
        ) { content() }
    }
}

