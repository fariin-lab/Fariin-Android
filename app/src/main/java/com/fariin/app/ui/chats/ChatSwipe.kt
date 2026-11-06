package com.fariin.app.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
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
    // The row follows the finger directly: one number, written in the drag callback and read only
    // while placing and drawing (owner 2026-11-06: "not smooth, it keeps jumping" — each move used to
    // launch its own coroutine, and those could land out of order).
    var x by remember(id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var widthPx by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    val armed by remember(id) { androidx.compose.runtime.derivedStateOf { -x > widthPx / 3f } }
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var settleJob by remember(id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(enabled) { if (!enabled) { settleJob?.cancel(); x = 0f } }
    fun settle(to: Float, velocity: Float, then: (suspend () -> Unit)? = null) {
        settleJob?.cancel()
        settleJob = scope.launch {
            androidx.compose.animation.core.animate(x, to, velocity,
                spring(dampingRatio = if (to == 0f) 0.85f else 1f, stiffness = if (to == 0f) 500f else 700f)) { v, _ -> x = v }
            then?.invoke()
        }
    }
    val c = com.fariin.app.ui.theme.Fariin.colors
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx() }
    val maxRadiusPx = with(density) { 20.dp.toPx() }
    val liftPx = with(density) { 3.dp.toPx() }
    // Our design (studied two Android lists, copied neither): the pulled row becomes a rounded card
    // that lifts a little, and the action is its own capsule growing out of the right edge, 8 dp from
    // the row. Short of the mark it is tonal (pale blue, blue mark: "this will spring back"); past it,
    // solid blue with a white mark that pops, and the phone ticks ("let go and it is archived").
    val tone by androidx.compose.animation.core.animateFloatAsState(if (armed) 1f else 0f,
        spring(dampingRatio = 0.8f, stiffness = 700f), label = "swipeTone")
    val pop by androidx.compose.animation.core.animateFloatAsState(if (armed) 1.12f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 600f), label = "swipePop")
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }.clipToBounds()) {
        // The capsule: as wide as the uncovered space less the gap, laid out and drawn from x only.
        // 4 dp above and below (owner 2026-11-06: "ours looks thin"; fuller, still not the full row).
        Box(Modifier.matchParentSize().padding(vertical = 4.dp, horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.fillMaxHeight()
                    .layout { m, k ->
                        val w = (-x - gapPx * 2).coerceAtLeast(0f).roundToInt()
                        val p = m.measure(k.copy(minWidth = w, maxWidth = w))
                        layout(w, p.height) { p.place(0, 0) }
                    }
                    .graphicsLayer {
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50); clip = true
                        alpha = if (x < -gapPx * 2) 1f else 0f
                    }
                    .drawBehind {
                        drawRect(androidx.compose.ui.graphics.lerp(c.blue.copy(alpha = 0.16f), c.blue, tone))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.graphicsLayer {
                    scaleX = pop; scaleY = pop
                    // The mark fades in once there is room for it.
                    alpha = ((-x - with(density) { 56.dp.toPx() }) / with(density) { 24.dp.toPx() }).coerceIn(0f, 1f)
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    val ink = androidx.compose.ui.graphics.lerp(c.blue, Color.White, tone)
                    Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(x.roundToInt(), 0) }
                // The row as a card while it is pulled: corners round and it lifts with the drag.
                .graphicsLayer {
                    val t = (-x / (maxRadiusPx * 4f)).coerceIn(0f, 1f)
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(maxRadiusPx * t)
                    clip = x < 0f
                    shadowElevation = liftPx * t
                }
                // Opaque, so only the part the finger uncovers shows the action.
                .background(c.bg)
                .then(if (!enabled) Modifier else Modifier.pointerInput(id) {
                    // Never steals a scroll (owner 2026-11-06): the row only takes the finger once it has
                    // moved past the touch slop clearly to the LEFT, at least twice as far sideways as up
                    // or down. Anything else (up, down, diagonal, rightward) is left to the list.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var tx = 0f; var ty = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (!ch.pressed) {
                                if (dragging) {
                                    val v = tracker.calculateVelocity().x
                                    // Gone: the row stays off-screen until the list drops it; if it is still here
                                    // a moment later (the change did not happen), it comes back.
                                    if (armed || v < -2500f) settle(-widthPx, v) {
                                        onAction()
                                        kotlinx.coroutines.delay(1500)
                                        androidx.compose.animation.core.animate(x, 0f, 0f,
                                            spring(dampingRatio = 0.85f, stiffness = 500f)) { v, _ -> x = v }
                                    } else settle(0f, v)
                                }
                                break
                            }
                            val d = ch.position - ch.previousPosition
                            if (!dragging) {
                                if (ch.isConsumed) break      // the list is already scrolling
                                tx += d.x; ty += d.y
                                if (kotlin.math.abs(tx) > slop || kotlin.math.abs(ty) > slop) {
                                    if (tx < 0f && kotlin.math.abs(tx) > kotlin.math.abs(ty) * 2f) {
                                        dragging = true; settleJob?.cancel(); ch.consume()
                                    } else break
                                }
                            } else {
                                x = (x + d.x).coerceIn(-widthPx, 0f)
                                ch.consume()
                            }
                        }
                        if (dragging && x != 0f && settleJob?.isActive != true) settle(0f, 0f)   // cancelled mid-drag
                    }
                }),
        ) { content() }
    }
}
