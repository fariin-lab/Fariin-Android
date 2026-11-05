package com.fariin.app.ui.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.fariin.app.data.Message

/**
 * Where each photo and video bubble sits on screen, so the full-screen viewer can grow out of it
 * and shrink back into it (iOS MediaOpenRects, as [com.fariin.app.ui.stories.StoryOrigins] does for
 * story cards). Rects are in SCREEN pixels: the viewer is its own dialog window.
 *
 * Keys are the viewer page's own key ([com.fariin.app.data.ViewerRules.Page.key]): the message id
 * for a single photo or video, "<messageId>-<index>" for an album tile ([keyOf]).
 * [hiddenKey] is the tile the viewer has lifted out of the chat: it stays empty while its picture
 * is up, and the hole follows the page on screen.
 */
object MediaOrigins {
    val rects = mutableStateMapOf<String, Rect>()
    /** Corner radius of each tile, in px. */
    val radii = mutableStateMapOf<String, Float>()
    var hiddenKey by mutableStateOf<String?>(null)

    fun keyOf(m: Message, albumIndex: Int? = null): String = if (albumIndex == null) m.id else "${m.id}-$albumIndex"
}

/**
 * A zoom source: writes this element's screen rect and [radius] under [key] while [active], takes
 * it back when it goes (only if it is still the one written, so a second source of the same key
 * keeps its own), and is invisible while [hidden] says so.
 */
@Composable
internal fun Modifier.heroSource(
    key: String?, radius: Dp, rects: SnapshotStateMap<String, Rect>, radii: SnapshotStateMap<String, Float>,
    active: Boolean = true, hidden: () -> Boolean,
): Modifier {
    if (key == null) return this
    val view = LocalView.current
    val rPx = with(LocalDensity.current) { radius.toPx() }
    val last = remember(key) { arrayOfNulls<Rect>(1) }
    val written = remember(key) { arrayOfNulls<Rect>(1) }
    fun write(r: Rect) { rects[key] = r; radii[key] = rPx; written[0] = r }
    // Turned on (a tap): claim the slot at once with where this element last was.
    if (active) SideEffect { last[0]?.let { if (written[0] != it) write(it) } }
    DisposableEffect(key, active) {
        onDispose {
            val w = written[0]
            if (w != null && rects[key] == w) { rects.remove(key); radii.remove(key) }
            written[0] = null
        }
    }
    return onGloballyPositioned { lc ->
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val p = lc.positionInWindow()
        val r = Rect(p.x + loc[0], p.y + loc[1], p.x + loc[0] + lc.size.width, p.y + loc[1] + lc.size.height)
        last[0] = r
        if (active) write(r)
    }.graphicsLayer { alpha = if (hidden()) 0f else 1f }
}

/**
 * A story card in a chat (a story reply, a story reaction, a mention): the story viewer zooms out of
 * it and back into it. It holds the group's slot only while its own viewer is [open], so a card
 * further up the chat for the same story, or the chat list's ring, keeps its own; the 14 corners.
 */
@Composable
internal fun Modifier.storyCardSource(groupKey: String, open: Boolean): Modifier =
    heroSource(groupKey, androidx.compose.ui.unit.Dp(14f), com.fariin.app.ui.stories.StoryOrigins.rects,
        com.fariin.app.ui.stories.StoryOrigins.radii, active = open) {
        open && com.fariin.app.ui.stories.StoryOrigins.hiddenKey == groupKey
    }

/**
 * One frame of the viewer's current page: translation and scale of the full-screen page about its
 * centre, the part of it shown ([clip], in the page's own pixels) with [radius] screen px corners,
 * the black's alpha and the picture's own.
 */
internal data class HeroPose(
    val tx: Float, val ty: Float, val scale: Float, val clip: Rect, val radius: Float,
    val bg: Float, val alpha: Float = 1f,
) {
    companion object {
        fun identity(box: Size) = HeroPose(0f, 0f, 1f, Rect(Offset.Zero, box), 0f, 1f)

        /**
         * The page laid over [anchor] (window px) the way the bubble shows it: the picture's fit
         * frame (of [aspect] w/h, or the anchor's own when unknown) scaled to cover the anchor,
         * centred on it, cropped to it, with the tile's corners.
         */
        fun onto(anchor: Rect, box: Size, aspect: Float?, radius: Float): HeroPose {
            val a = (aspect?.takeIf { it > 0f && it.isFinite() } ?: (anchor.width / anchor.height)).coerceIn(0.05f, 20f)
            val fw: Float; val fh: Float
            if (a > box.width / box.height) { fw = box.width; fh = box.width / a } else { fh = box.height; fw = box.height * a }
            val s = maxOf(anchor.width / fw, anchor.height / fh)
            val c = Offset(box.width / 2f, box.height / 2f)
            val clip = Rect(c.x - anchor.width / s / 2f, c.y - anchor.height / s / 2f, c.x + anchor.width / s / 2f, c.y + anchor.height / s / 2f)
            return HeroPose(anchor.center.x - c.x, anchor.center.y - c.y, s, clip, radius, 0f)
        }

        fun lerp(a: HeroPose, b: HeroPose, t: Float): HeroPose {
            fun l(x: Float, y: Float) = x + (y - x) * t
            return HeroPose(
                l(a.tx, b.tx), l(a.ty, b.ty), l(a.scale, b.scale),
                Rect(l(a.clip.left, b.clip.left), l(a.clip.top, b.clip.top), l(a.clip.right, b.clip.right), l(a.clip.bottom, b.clip.bottom)),
                l(a.radius, b.radius), l(a.bg, b.bg), l(a.alpha, b.alpha),
            )
        }
    }
}

/** [HeroPose.clip] with its corners, as a layer outline (in the page's own pixels, so radius / scale). */
internal class HeroClip(private val clip: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(clip, CornerRadius(radius)))
}
