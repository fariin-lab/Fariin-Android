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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * Where the pressed message sits on screen (px), and which side it hangs on. [live] reads where the
 * row is NOW: the keyboard goes down under the menu and comes back on close, the chat moves with
 * it, and the copy has to land on the row where it ends up, not where it was pressed.
 */
internal class MenuSource(val rect: Rect, val alignRight: Boolean, val live: () -> Rect? = { null })

/** The pressed row's source, its live place read from the same coordinates while they last. */
internal fun menuSourceOf(lc: androidx.compose.ui.layout.LayoutCoordinates, alignRight: Boolean): MenuSource {
    fun rectOf(c: androidx.compose.ui.layout.LayoutCoordinates) =
        Rect(c.positionOnScreen(), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
    return MenuSource(rectOf(lc), alignRight) { if (lc.isAttached) rectOf(lc) else null }
}

/** One row of the overlay's card. */
internal class MenuRow(val title: String, val icon: Painter, val destructive: Boolean, val countdown: String?, val run: () -> Unit,
                       /** The admin panel's green "New" pill after the title. */
                       val isNew: Boolean = false)

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
 * Android: a popup window stretched over the WHOLE display, under the status bar and the gesture
 * bar. Left to itself the popup is only as tall as the chat's visible frame, which stops at the
 * navigation bar and, with the keys up, at the keyboard: the composer strip stayed undimmed, and
 * with the keys up the card was cut to the strip above them and lost its last rows (owner's
 * Pixel 5 photos, 2026-11-07). The keyboard is a window above every window of ours, so it cannot
 * be blurred over as on iOS: it goes down when the menu opens and comes back when the menu is
 * dismissed (an action that wants it, Reply or Edit, raises it itself). The popup does not take
 * focus, so the composer keeps it and the keys can come back; back closes the menu through the
 * activity's back dispatcher instead.
 *
 * Behind it the chat is dimmed, not blurred (owner 2026-11-10: spare the GPU).
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
    /** The copy has landed: the real message may show again (called before [onDismiss]). */
    onLanded: () -> Unit = {},
) {
    val c = Fariin.colors
    val density = LocalDensity.current
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
    // Not the keyboard: it goes down as the menu opens (below), and the menu gets the whole screen.
    val chatView = LocalView.current
    val raw = androidx.core.view.ViewCompat.getRootWindowInsets(chatView)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
    val edgeTop = raw?.top ?: 0; val edgeBottom = raw?.bottom ?: 0
    val edgeLeft = raw?.left ?: 0; val edgeRight = raw?.right ?: 0

    // The keys go down while the menu is up and come back on a plain dismiss (tap outside, back,
    // a reaction).
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val keysWereUp = remember {
        androidx.core.view.ViewCompat.getRootWindowInsets(chatView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
    }
    LaunchedEffect(Unit) { if (keysWereUp) keyboard?.hide() }

    fun close(keysBack: Boolean = true, then: () -> Unit = {}) {
        if (closing) return
        closing = true
        startScale = 1f
        // First, so the chat rises with the keys while the copy springs home (it lands on the
        // row's live place, see homeDy).
        if (keysBack && keysWereUp) keyboard?.show()
        scope.launch { barAlpha.animateTo(0f, tween(200)) }
        scope.launch { scrim.animateTo(0f, tween(400, easing = EaseInOut)) }
        scope.launch {
            open.animateTo(0f, springSpec)
            // The real message shows again first, and the chat draws it under the copy, before the
            // menu's window goes: removing both in one frame left the row empty for a frame
            // (owner's video, 2026-11-05).
            onLanded()
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            onDismiss(); then()
        }
    }

    // No blur behind the menu (owner 2026-11-10, to spare the GPU): a full-screen blur cost the
    // Pixel 5 about 7 ms of GPU a frame while the menu opened. A dim alone, a little deeper than the
    // tint that sat over the blur.

    androidx.activity.compose.BackHandler(enabled = !closing) { close() }

    // How far the row has moved since the press, read on every frame of the close (the keyboard
    // moves the chat under the menu). A row of another height is not ours any more (a recycled
    // list slot): then home is where it was pressed.
    fun homeDy(): Float {
        if (!closing) return 0f
        val now = source.live() ?: return 0f
        return if (abs(now.height - source.rect.height) < 1f) now.top - source.rect.top else 0f
    }

    LaunchedEffect(Unit) {
        // No buzz here: the bubble's hold already played it at the press (combinedClickableLong).
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
        // usePlatformDefaultWidth: measured by its own window (the whole display, set below), not
        // by the chat's visible frame. No gesture exclusion, so the back swipe still closes it.
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
            excludeFromSystemGesture = false, clippingEnabled = false, usePlatformDefaultWidth = true),
    ) {
        val popupRoot = LocalView.current.rootView
        LaunchedEffect(popupRoot) { stretchOverDisplay(popupRoot) }
        var origin by remember { mutableStateOf<Offset?>(null) }
        val tint = if (c.dark) Color.Black.copy(alpha = 0.6f) else Color(0f, 0.039f, 0.149f, 0.34f)
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

            // A long message: shrunk to fit as iOS does while that keeps it readable (60% or more);
            // longer than that, the slice that was on screen where it was pressed, at full size and
            // as tall as the room between the emoji bar and the menu (shrinking the whole of it to
            // 15% made it unreadable, Pixel 2026-11-05).
            val full = source.rect.translate(-o.x, -o.y)
            val room = (bottom - top) - (if (hasBar) barH + gap else 0f) - gap * 2 - (rows.size * rowH + inset * 2)
            val maxH = room.coerceAtLeast((bottom - top) * 0.25f)
            val src = if (full.height * 0.6f <= maxH) full else {
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
                Modifier.offset { IntOffset(src.left.roundToInt(), (src.top + homeDy() * (1 - p) + travel * p).roundToInt()) }
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
                Box(Modifier.offset { IntOffset(b.left.roundToInt(), (b.top + (homeDy() - travel) * (1 - p)).roundToInt()) }
                    .graphicsLayer { alpha = barAlpha.value }) {
                    ReactionBar(side, barH, mine, delayMs = if (abs(travel) > 0.5f) 100 else 0,
                        onPick = { e -> close { onReact(e) } },
                        onMore = { picker = true })
                }
            }

            val m = f.menu
            Box(
                Modifier.offset { IntOffset(m.left.roundToInt(), (m.top + (homeDy() - travel) * (1 - p)).roundToInt()) }
                    .requiredSize(with(density) { m.width.toDp() }, with(density) { m.height.toDp() })
                    .graphicsLayer {
                        val s = 0.2f + 0.8f * p
                        scaleX = s; scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(if (source.alignRight) 1f else 0f, 0f)
                    },
            ) { ActionsCard(rows, onPick = { r -> close(keysBack = false) { r.run() } }) }
        }
    }
    if (picker) ReactionPickerSheet(mine, onDismiss = { picker = false }) { e -> picker = false; close { onReact(e) } }
}

/**
 * The menu's popup window over the whole display: full size, laid out in screen space through the
 * status bar, the navigation bar and the cutout, and never resized for the keyboard. The popup
 * keeps this same params object and only moves it afterwards, so once is enough.
 */
private fun stretchOverDisplay(root: android.view.View) {
    fun apply() {
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (Build.VERSION.SDK_INT >= 30) {
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { (root.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, lp) }
    }
    if (root.isAttachedToWindow) apply() else root.post { if (root.isAttachedToWindow) apply() }
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

/**
 * iOS CMActionsCard: 250 wide, 45 rows, 10 above and below, icon 24 at 24 in, 12 to the words.
 * Press and slide without lifting (owner 2026-11-08, as iOS): a grey pill follows the finger from
 * row to row with a light tick on each new row, and lifting on a row picks it. A card long enough
 * to scroll keeps plain taps, so it can still scroll.
 */
@Composable
internal fun ActionsCard(rows: List<MenuRow>, onPick: (MenuRow) -> Unit) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(33.dp)
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val rowPx = with(density) { 45.dp.toPx() }
    val padPx = with(density) { 10.dp.toPx() }
    var pressed by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val pill = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latestRows by androidx.compose.runtime.rememberUpdatedState(rows)
    fun tick() {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            else android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    val slide = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (scroll.maxValue > 0) return@awaitEachGesture   // a scrolling card: the rows' own taps
            fun rowAt(y: Float): Int {
                val i = ((y - padPx) / rowPx).toInt()
                return if (y < padPx || i !in latestRows.indices) -1 else i
            }
            down.consume()
            pressed = rowAt(down.position.y)
            if (pressed >= 0) scope.launch { pill.snapTo(pressed.toFloat()) }
            var last = down.position
            while (true) {
                // Before the rows see it (Initial), so a plain tap is picked once, here.
                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                last = ch.position
                ch.consume()
                if (!ch.pressed) break
                val inside = last.x >= 0 && last.x <= size.width
                val r = if (inside) rowAt(last.y) else -1
                if (r != pressed) {
                    if (r >= 0) {
                        tick()
                        if (pressed < 0) scope.launch { pill.snapTo(r.toFloat()) }
                        else scope.launch { pill.animateTo(r.toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 900f)) }
                    }
                    pressed = r
                }
            }
            val pick = pressed
            pressed = -1
            if (pick in latestRows.indices) onPick(latestRows[pick])
        }
    }
    Box(Modifier.fillMaxSize().floating(shape)) {
        // The grey pill under the finger, gliding between rows.
        if (pressed >= 0) Box(Modifier.fillMaxWidth().height(45.dp)
            .graphicsLayer { translationY = padPx + pill.value * rowPx - scroll.value }
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(22.dp)).background(c.label.copy(alpha = 0.08f)))
        Column(Modifier.fillMaxSize().then(slide).verticalScroll(scroll).padding(vertical = 10.dp)) {
            for (r in rows) {
                val src = remember { MutableInteractionSource() }
                val tapped by src.collectIsPressedAsState()
                val tint = if (r.destructive) c.red else c.label
                Box(Modifier.fillMaxWidth().height(45.dp)
                    .clickable(interactionSource = src, indication = null) { onPick(r) }) {
                    if (tapped) Box(Modifier.matchParentSize().padding(horizontal = 10.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(22.dp)).background(c.label.copy(alpha = 0.08f)))
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(r.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(if (r.isNew) Modifier.weight(1f, fill = false) else Modifier) {
                            Text(r.title, color = tint, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Whole-width digits, so the line does not shiver as the seconds change.
                            r.countdown?.let { Text(it, color = tint, fontSize = 13.sp, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum")) }
                        }
                        if (r.isNew) com.fariin.app.ui.components.NewPill(Modifier.padding(start = 8.dp))
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
    val retired = rememberRetired()
    val strip = remember(mine, retired) {
        val quick = MessageRules.QUICK_REACTIONS
        val list = (quick + ExtraEmojis.filter { it !in quick }).take(STRIP_COUNT).toMutableList()
        if (mine != null && mine !in list && !retired(mine)) list.add(mine)
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
                    // Ease-OUT, no travel hold: with ease-in after a 0.1 s hold the emojis stayed near invisible
                    // for ~10 frames and the bar read as an empty white capsule (owner's video, 2026-11-05).
                    if (i < 6) LaunchedEffect(Unit) { v.animateTo(1f, tween(180, delayMillis = i * 15, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
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
        LaunchedEffect(Unit) { v.animateTo(1f, tween(180, delayMillis = 90, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
        Box(Modifier.size(side).graphicsLayer { alpha = v.value; translationY = rise * (1 - v.value) }.clickable(onClick = onMore),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(side * 0.84f).clip(CircleShape).background(c.label.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_plus_ios), contentDescription = "More reactions", tint = c.label, modifier = Modifier.size(20.dp))
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

