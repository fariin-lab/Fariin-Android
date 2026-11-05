package com.fariin.app.ui.thread

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.wrapContentSize
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.fariin.app.data.MessageRules
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the pressed message sits on screen (px), and which side it hangs on. */
internal data class MenuSource(val rect: Rect, val alignRight: Boolean)

/** One row of the overlay's card. */
internal class MenuRow(val title: String, val icon: Painter, val destructive: Boolean, val countdown: String?, val run: () -> Unit)

/** The strip after the quick six: iOS CMReactionBar.extraEmojis, fifteen in all (owner 2026-09-28). */
private val ExtraEmojis = listOf(
    "🥹", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "😢", "🎉", "🤩", "🙏", "👌", "😍", "💯", "🤣",
    "😭", "😘", "🤗", "🫡", "🤝", "👀", "😇", "😅", "🙈", "💔", "😡", "🤬", "😴", "🤨",
    "😐", "🤓", "👻", "🤡", "💩", "🥳", "😏", "🙄", "😬", "✅", "💪", "🌹", "⚡", "🏆", "🍾",
)
private const val STRIP_COUNT = 15

/**
 * The long-press menu over the chat, iOS CMOverlay: the screen blurs and dims, the message lifts
 * where it is (moving only as far as it must for everything to fit), the reaction capsule sits
 * 12 above it and the actions card 12 below, each lined up with the message's own side.
 *
 * iOS numbers kept (messages use its "launched" motion): open spring 0.4 s at damping 0.8, the
 * message starting at the 0.95 squeeze, the card growing from 0.2 out of the corner nearest the
 * message; the background in over 0.2 s; the emojis rising 24 one after another, 0.2 s ease-in,
 * 0.01 s apart, held 0.1 s when the message has to travel. Close: the same spring home, the card
 * back to 0.2, the bar fading over 0.2 s, the background over 0.4 s. Screen edges 8 (or the system
 * bars), card 250 wide with 45 rows and 33 corners, bar 56 tall (50 on a narrow phone).
 *
 * Android: a full-screen focusable popup, so system back closes it. The blur is the chat's own
 * screen blurred in place (a RenderEffect on the activity's content, Android 12 and up; older
 * phones get the tint alone), always taken off when the menu goes. Not the window blur-behind:
 * on a real Pixel and the emulator that left the display showing stale frames after the menu.
 */
@Composable
internal fun MessageMenuOverlay(
    source: MenuSource,
    rows: List<MenuRow>,
    reactable: Boolean,
    mine: String?,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
    preview: @Composable () -> Unit,
    /** The copy is on screen: only now may the real message hide (no frame with neither). */
    onLifted: () -> Unit = {},
) {
    val c = Fariin.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val open = remember { Animatable(0f) }          // the spring: message, card
    val scrim = remember { Animatable(0f) }         // the background
    val barAlpha = remember { Animatable(1f) }
    var startScale by remember { mutableStateOf(0.95f) }
    var closing by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val springSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 250f)
    // The screen's edges, read from the chat's own window: the menu's window does not get them,
    // and the card ran under the gesture bar (owner's Pixel recording, 2026-11-03).
    // Raw from the view: the chat screen has already consumed the Compose insets on the way down.
    val raw = androidx.core.view.ViewCompat.getRootWindowInsets(LocalView.current)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            or androidx.core.view.WindowInsetsCompat.Type.ime())
    val edgeTop = raw?.top ?: 0; val edgeBottom = raw?.bottom ?: 0
    val edgeLeft = raw?.left ?: 0; val edgeRight = raw?.right ?: 0

    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        startScale = 1f
        scope.launch { barAlpha.animateTo(0f, tween(200)) }
        scope.launch { scrim.animateTo(0f, tween(400, easing = EaseInOut)) }
        scope.launch { open.animateTo(0f, springSpec); onDismiss(); then() }
    }

    // The blur, on the background's own clock as iOS's blur view alpha is.
    val content = (LocalView.current.rootView as? android.view.ViewGroup)?.getChildAt(0)
    val blurPx = with(density) { 20.dp.toPx() }
    if (Build.VERSION.SDK_INT >= 31 && content != null && !weakPhone(LocalContext.current)) {
        LaunchedEffect(content) {
            // Four steps, not a new blur every frame: measured on the Pixel 5 over six menus, the
            // per-frame blur took the 90th-percentile frame from 15 to 32 ms (GPU 5 -> 14); four
            // steps keep it at 21 ms (GPU 7) and still read as a fade under the tint.
            androidx.compose.runtime.snapshotFlow { kotlin.math.ceil(scrim.value * 4f) / 4f }.collect { v ->
                content.setRenderEffect(if (v < 0.01f) null
                    else android.graphics.RenderEffect.createBlurEffect(blurPx * v, blurPx * v, android.graphics.Shader.TileMode.CLAMP))
            }
        }
        androidx.compose.runtime.DisposableEffect(content) { onDispose { content.setRenderEffect(null) } }
    }

    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        launch { scrim.animateTo(1f, tween(200, easing = EaseInOut)) }
        open.animateTo(1f, springSpec)
    }

    // A focusable popup over the whole window, not a dialog: a dialog window fades in the system's
    // dark dim before anything of ours can draw (the grey flash in the owner's recording). Back
    // and taps outside still close it.
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                               layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize) = IntOffset.Zero
            }
        },
        onDismissRequest = { close() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        var origin by remember { mutableStateOf<Offset?>(null) }
        val tint = if (c.dark) Color.Black.copy(alpha = 0.6f) else Color(0f, 0.039f, 0.149f, 0.2f)
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .onGloballyPositioned { origin = it.positionOnScreen() }
                .graphicsLayer { alpha = 1f }
                .background(tint.copy(alpha = tint.alpha * scrim.value))
                .pointerInput(Unit) { detectTapGestures { close() } },
        ) {
            val o = origin ?: return@BoxWithConstraints
            LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; onLifted() }
            val pad = with(density) { 8.dp.toPx() }
            val gap = with(density) { 12.dp.toPx() }
            val top = max(edgeTop.toFloat(), pad)
            val bottom = with(density) { maxHeight.toPx() } - max(edgeBottom.toFloat(), pad)
            val left = max(edgeLeft.toFloat(), pad)
            val right = with(density) { maxWidth.toPx() } - max(edgeRight.toFloat(), pad)

            val narrow = LocalConfiguration.current.screenWidthDp <= 375
            val barH = with(density) { (if (narrow) 50.dp else 56.dp).toPx() }
            val side = barH - with(density) { 12.dp.toPx() }
            val barW = side * 7 + with(density) { 12.dp.toPx() }     // six showing, then the "+"
            val rowH = with(density) { 45.dp.toPx() }
            val inset = with(density) { 10.dp.toPx() }
            val cardW = with(density) { 250.dp.toPx() }
            val hasBar = reactable

            // A message taller than about half the screen is shown in part, at full size: the slice
            // that was on screen where it was pressed (shrinking the whole of it made it unreadable).
            val full = source.rect.translate(-o.x, -o.y)
            val maxH = (bottom - top) * 0.45f
            val src = if (full.height <= maxH) full else {
                val t = max(full.top, top).coerceAtMost(full.bottom - maxH)
                Rect(full.left, t, full.right, t + maxH)
            }
            val shift = full.top - src.top
            val f = remember(src, top, bottom) {
                Frames.compute(src, source.alignRight, hasBar, barW, barH, cardW,
                    rows.size * rowH + inset * 2, gap, Rect(left, top, right, bottom))
            }
            val p = open.value
            val travel = f.preview.top - src.top

            // The message: lifted in place, scaled only when nothing else would fit.
            val scale = startScale + (f.scale - startScale) * p
            Box(
                Modifier.offset { IntOffset(src.left.roundToInt(), (src.top + travel * p).roundToInt()) }
                    // A message taller than the screen: requiredSize alone centres the oversize copy
                    // on the screen-sized slot, which pushed its top far above the screen and the
                    // shrunk copy never showed (Pixel 2026-11-05). Pinned to the top-left instead.
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(with(density) { src.width.toDp() }, with(density) { src.height.toDp() })
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        transformOrigin = TransformOrigin(if (source.alignRight) 1f else 0f, 0f)
                        clip = shift != 0f
                    }
                    .pointerInput(Unit) { detectTapGestures { close() } },
            ) {
                if (shift == 0f) preview()
                else Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset(0, shift.roundToInt()) }
                    .requiredSize(with(density) { full.width.toDp() }, with(density) { full.height.toDp() })) { preview() }
            }

            if (hasBar) {
                val b = f.bar!!
                Box(Modifier.offset { IntOffset(b.left.roundToInt(), (b.top - travel * (1 - p)).roundToInt()) }
                    .graphicsLayer { alpha = barAlpha.value }) {
                    ReactionBar(side, barH, mine, delayMs = if (abs(travel) > 0.5f) 100 else 0,
                        onPick = { e -> close { onReact(e) } },
                        onMore = { picker = true })
                }
            }

            val m = f.menu
            Box(
                Modifier.offset { IntOffset(m.left.roundToInt(), (m.top - travel * (1 - p)).roundToInt()) }
                    .requiredSize(with(density) { m.width.toDp() }, with(density) { m.height.toDp() })
                    .graphicsLayer {
                        val s = 0.2f + 0.8f * p
                        scaleX = s; scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(if (source.alignRight) 1f else 0f, 0f)
                    },
            ) { ActionsCard(rows, onPick = { r -> close { r.run() } }) }
        }
    }
    if (picker) ReactionPickerSheet(mine, onDismiss = { picker = false }) { e -> picker = false; close { onReact(e) } }
}

/** iOS CMOverlay.computeFrames: bar above, card below, shifted to fit, the message shrunk last. */
internal object Frames {
    data class Result(val preview: Rect, val scale: Float, val bar: Rect?, val menu: Rect)

    fun compute(src: Rect, alignRight: Boolean, hasBar: Boolean, barW: Float, barH: Float, cardW: Float,
                cardNatural: Float, gap: Float, content: Rect): Result {
        val minScale = 0.1f
        val pt = gap / 12f   // one iOS point in px
        val reserved = (if (hasBar) barH + gap else 0f) + gap + 44f * pt
        val menuH = min(cardNatural, max(88f * pt, content.height - reserved))
        var pv = src
        fun stack(p: Rect): Triple<Rect?, Rect, Pair<Float, Float>> {
            val bar = if (hasBar) {
                val x = if (alignRight) p.right - barW else p.left
                Rect(x, p.top - gap - barH, x + barW, p.top - gap)
            } else null
            val mx = if (alignRight) p.right - cardW else p.left
            val menu = Rect(mx, p.bottom + gap, mx + cardW, p.bottom + gap + menuH)
            return Triple(bar, menu, (bar?.top ?: p.top) to menu.bottom)
        }
        var s = stack(pv)
        if (s.third.second > content.bottom) { pv = pv.translate(0f, content.bottom - s.third.second); s = stack(pv) }
        if (s.third.first < content.top) { pv = pv.translate(0f, content.top - s.third.first); s = stack(pv) }
        var scale = 1f
        val group = s.third.second - s.third.first
        if (group > content.height) {
            scale = max((pv.height - (group - content.height)) / pv.height, minScale)
            val w = pv.width * scale
            val h = pv.height * scale
            val x = if (alignRight) pv.right - w else pv.left
            val y = max(content.top + (if (hasBar) barH + gap else 0f), pv.top)
            pv = Rect(x, y, x + w, y + h)
            s = stack(pv)
            if (s.third.second > content.bottom) { pv = pv.translate(0f, content.bottom - s.third.second); s = stack(pv) }
        }
        val bar = s.first?.let { b -> val x = b.left.coerceIn(content.left, max(content.left, content.right - b.width)); Rect(x, b.top, x + b.width, b.bottom) }
        val mn = s.second.let { m -> val x = m.left.coerceIn(content.left, max(content.left, content.right - m.width)); Rect(x, m.top, x + m.width, m.bottom) }
        // The overlay draws the message at its own size and scales it from the top corner, so the
        // returned rect keeps the source's size with the moved top.
        return Result(Rect(src.left, pv.top, src.right, pv.top + src.height), scale, bar, mn)
    }
}

/** iOS CMActionsCard: 250 wide, 45 rows, 10 above and below, icon 24 at 24 in, 12 to the words. */
@Composable
private fun ActionsCard(rows: List<MenuRow>, onPick: (MenuRow) -> Unit) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(33.dp)
    Column(Modifier.fillMaxSize().floating(shape).verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        for (r in rows) {
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val tint = if (r.destructive) c.red else c.label
            Box(Modifier.fillMaxWidth().height(45.dp)
                .clickable(interactionSource = src, indication = null) { onPick(r) }) {
                if (pressed) Box(Modifier.matchParentSize().padding(horizontal = 10.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(22.dp)).background(c.label.copy(alpha = 0.08f)))
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(r.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(r.title, color = tint, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Whole-width digits, so the line does not shiver as the seconds change.
                        r.countdown?.let { Text(it, color = tint, fontSize = 13.sp, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum")) }
                    }
                }
            }
        }
    }
}

/**
 * iOS CMReactionBar: a capsule with the quick six showing and the rest of the fifteen a swipe
 * away, then the "+" for any emoji. Mine sits on a soft disc the size of its slot.
 */
@Composable
private fun ReactionBar(sidePx: Float, heightPx: Float, mine: String?, delayMs: Int, onPick: (String) -> Unit, onMore: () -> Unit) {
    val c = Fariin.colors
    val density = LocalDensity.current
    val side = with(density) { sidePx.toDp() }
    val strip = remember(mine) {
        val quick = MessageRules.QUICK_REACTIONS
        val list = (quick + ExtraEmojis.filter { it !in quick }).take(STRIP_COUNT).toMutableList()
        if (mine != null && mine !in list) list.add(mine)
        list
    }
    val emojiSize = with(density) { (sidePx * 0.72f).toSp() }
    val rise = with(density) { 24.dp.toPx() }
    Row(
        Modifier.height(with(density) { heightPx.toDp() }).floating(CircleShape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(side * 6).clip(RoundedCornerShape(side / 2))) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                strip.forEachIndexed { i, e ->
                    val v = remember { Animatable(if (i < 6) 0f else 1f) }
                    if (i < 6) LaunchedEffect(Unit) { v.animateTo(1f, tween(200, delayMillis = delayMs + i * 10, easing = EaseIn)) }
                    Box(
                        Modifier.size(side).graphicsLayer { alpha = v.value; translationY = rise * (1 - v.value) }
                            .clip(CircleShape)
                            .background(if (e == mine) c.label.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { onPick(e) },
                        contentAlignment = Alignment.Center,
                    ) { ReactionGlyph(e, emojiSize, with(density) { (sidePx * 0.8f).toDp() }) }
                }
            }
        }
        val v = remember { Animatable(0f) }
        LaunchedEffect(Unit) { v.animateTo(1f, tween(200, delayMillis = delayMs + 60, easing = EaseIn)) }
        Box(Modifier.size(side).graphicsLayer { alpha = v.value; translationY = rise * (1 - v.value) }.clickable(onClick = onMore),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(side * 0.84f).clip(CircleShape).background(c.label.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "More reactions", tint = c.label, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The "+": every emoji the bar knows, in one sheet (iOS morePickerTarget). */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ReactionPickerSheet(mine: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Fariin.colors
    val all = remember { (MessageRules.QUICK_REACTIONS + ExtraEmojis).distinct() }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = com.fariin.app.ui.components.sheetFill,
        shape = com.fariin.app.ui.components.SheetShape,
    ) {
        LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            items(all) { e ->
                Box(Modifier.size(52.dp).clip(CircleShape)
                    .background(if (e == mine) c.label.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { onPick(e) }, contentAlignment = Alignment.Center) { Emoji3D(e, 30.sp, 36.dp) }
            }
        }
    }
}

/** Under 4 GB, or the system's low-RAM flag: the menu gets the tint alone, no blur (lag on cheap phones). */
private fun weakPhone(ctx: android.content.Context): Boolean {
    val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return true
    if (am.isLowRamDevice) return true
    val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return info.totalMem < 3_500L * 1024 * 1024   // phones sold as "4 GB" report about 3.6-3.8 GB
}
