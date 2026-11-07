package com.fariin.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fariin.app.R
import com.fariin.app.data.ChatColorSpec
import com.fariin.app.data.ChatLookStore
import com.fariin.app.data.ChatWallpaper
import com.fariin.app.data.WallpaperGradient
import com.fariin.app.data.AnimatedWallpaper
import com.fariin.app.data.AnimatedWallpapers
import com.fariin.app.perf.rememberPowerState
import com.fariin.app.ui.theme.Fariin
import android.os.Build
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Draws a chat wallpaper (iOS ChatWallpaperBackground + GradientWallpaperView): the plain page for
 * None, the four-corner mesh with the shared doodle sheet inked over it for a theme, a library photo
 * (black 0.20 over it in dark mode only), or a plain colour. Every tile, preview and chat goes
 * through [drawWallpaper], so none of them can drift from the others.
 */

/** [chatWallpaper] for callers outside this package (one qualified name, no import). */
fun chatWallpaperOf(cid: String): Modifier = Modifier.chatWallpaper(cid)

/** The wallpaper behind a chat; it also tells the bubbles which chat is open (ChatLookStore.openCid). */
fun Modifier.chatWallpaper(cid: String): Modifier = composed {
    val ctx = LocalContext.current
    val c = Fariin.colors
    ChatLookStore.version.intValue
    val w = ChatLookStore.wallpaper(ctx, cid)
    SideEffect { ChatLookStore.openCid.value = cid }
    DisposableEffect(cid) { onDispose { if (ChatLookStore.openCid.value == cid) ChatLookStore.openCid.value = null } }
    // The doodle sheet only for a wallpaper that draws it: decoding it costs 10 ms on the opening frame.
    val pattern = if (drawsPattern(w)) WallpaperArt.pattern(ctx) else null
    val photo = rememberWallpaperPhoto(ctx, (w as? ChatWallpaper.Photo)?.id)
    // This one runs the chat's clock; the header and list slices only read it, so they move together.
    val motion = rememberWallpaperMotion(w, preview = false, clock = ChatWallpaperClock.phase, drive = true)
    val dim = ChatLookStore.wallpaperDim(ctx)
    motion.isolate { Modifier.drawBehind {
        drawWallpaper(w, c.dark, c.bg, pattern, photo, topAnchored = true, motion = motion)
        if (w != ChatWallpaper.None) drawWallpaperDim(dim.floatValue)
    } }
}

/**
 * The owner's wallpaper brightness ([ChatLookStore.wallpaperDim]) over a drawn wallpaper, on top of
 * the dark-mode photo scrim. Callers skip it for the plain no-wallpaper background.
 */
fun DrawScope.drawWallpaperDim(amount: Float) {
    if (amount > 0f) drawRect(Color.Black.copy(alpha = amount.coerceAtMost(ChatLookStore.DIM_MAX)))
}

/**
 * The top slice of a chat's wallpaper, for the header strip: drawn as if it filled the window (so
 * a gradient lines up with the chat's own, no seam) and clipped to this element.
 */
fun chatWallpaperStripOf(cid: String): Modifier = Modifier.composed {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val rootH = androidx.compose.ui.platform.LocalView.current.rootView.height.toFloat()
    ChatLookStore.version.intValue
    val w = ChatLookStore.wallpaper(ctx, cid)
    val pattern = if (drawsPattern(w)) WallpaperArt.pattern(ctx) else null
    val photo = rememberWallpaperPhoto(ctx, (w as? ChatWallpaper.Photo)?.id)
    val motion = rememberWallpaperMotion(w, preview = false, clock = ChatWallpaperClock.phase, drive = false)
    val dim = ChatLookStore.wallpaperDim(ctx)
    motion.isolate { Modifier.drawBehind {
        val own = size
        val full = androidx.compose.ui.geometry.Size(own.width, maxOf(rootH, own.height))
        clipRect(0f, 0f, own.width, own.height) {
            drawContext.size = full
            try { drawWallpaper(w, c.dark, c.bg, pattern, photo, topAnchored = true, motion = motion) } finally { drawContext.size = own }
        }
        // The same dimness as the chat under it, so the strip shows no seam.
        if (w != ChatWallpaper.None) drawWallpaperDim(dim.floatValue)
    } }
}

/** Whether [w] draws the doodle sheet: a patterned theme, or animated Doodles. */
private fun drawsPattern(w: ChatWallpaper): Boolean =
    w.theme?.pattern == true || (w as? ChatWallpaper.Animated)?.wallpaper?.look == com.fariin.app.data.AnimatedLook.DOODLES

/**
 * The colour scheme the chat header's words take over this chat's wallpaper (iOS
 * WallpaperBlur.headerBackdrop): true = light words, false = dark words, null = no wallpaper (the
 * app's own). An animated wallpaper says itself whether its top is dark; any other is measured: the
 * mean luminance of the band the header sits on, light words below 0.179 (where white's and black's
 * contrast are equal), dark words above. Before, only animated wallpapers were asked, so a bright
 * photo or theme in dark mode kept light-grey words on a light picture.
 */
@Composable
fun chatHeaderDark(cid: String): Boolean? {
    val ctx = LocalContext.current
    ChatLookStore.version.intValue
    val dark = com.fariin.app.ui.theme.Fariin.colors.dark
    val w = ChatLookStore.wallpaper(ctx, cid)
    val photo = rememberWallpaperPhoto(ctx, (w as? ChatWallpaper.Photo)?.id)
    return when (w) {
        ChatWallpaper.None -> null
        is ChatWallpaper.Animated -> w.wallpaper?.topIsDark(dark)
        is ChatWallpaper.Solid -> HeaderBackdrop.luminance(w.hex) < HeaderBackdrop.SWITCH
        is ChatWallpaper.Gradient, is ChatWallpaper.Preset -> w.theme?.let { g ->
            remember(g.id, dark) { HeaderBackdrop.band(WallpaperArt.mesh(g, dark), 0f)?.let { it < HeaderBackdrop.SWITCH } }
        }
        // The photo darkened by 0.20 black in dark mode (drawWallpaper): 0.8 in sRGB, about 0.61 in light.
        is ChatWallpaper.Photo -> photo?.let { p ->
            remember(p, dark) { HeaderBackdrop.band(p, if (dark) 0.39f else 0f)?.let { it < HeaderBackdrop.SWITCH } }
        }
    }
}

/** The header's measured backdrop (iOS WallpaperBlur.averageLuminance). */
internal object HeaderBackdrop {
    const val SWITCH = 0.179f

    private fun lin(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    fun luminance(rgb: Long): Float {
        val r = ((rgb shr 16) and 0xFF).toInt(); val g = ((rgb shr 8) and 0xFF).toInt(); val b = (rgb and 0xFF).toInt()
        return 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)
    }

    /** Mean luminance of the picture's top band (the status bar and header over a top-anchored
     *  fill, about the top sixth), boxed down to 4 x 4 as iOS does; [dim] darkens it by that share.
     *  Null when the picture cannot be read (the app's own scheme then stands). */
    fun band(img: ImageBitmap, dim: Float): Float? = runCatching {
        val bmp = img.asAndroidBitmap()
        val h = (bmp.height / 6).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, 0, 0, bmp.width, h), 4, 4, true)
        var total = 0f
        for (y in 0 until 4) for (x in 0 until 4) total += luminance(small.getPixel(x, y).toLong() and 0xFFFFFF)
        total / 16f * (1f - dim)
    }.getOrNull()
}

/** Whether this chat draws a wallpaper (the thread's top and bottom fades step aside for it). */
@Composable
fun hasChatWallpaper(cid: String): Boolean {
    val ctx = LocalContext.current
    ChatLookStore.version.intValue
    return ChatLookStore.wallpaper(ctx, cid) != com.fariin.app.data.ChatWallpaper.None
}

/** The bubble colour for the open chat, or null for the app's blue (ThreadScreen myFill). */
@Composable
fun openChatBubbleColor(): Color? {
    val ctx = LocalContext.current
    ChatLookStore.version.intValue
    val cid = ChatLookStore.openCid.value ?: return null
    return ChatLookStore.color(ctx, cid)?.let { Color(0xFF000000 or com.fariin.app.data.ChatLookRules.flatHex(it)) }
}

/**
 * Draws [w] into the whole draw area. An animated one draws the frame [motion] is on (the first
 * frame without it); reading it here, in the draw phase, redraws without recomposing anything.
 */
fun DrawScope.drawWallpaper(
    w: ChatWallpaper, dark: Boolean, plain: Color, pattern: ImageBitmap?, photo: ImageBitmap?,
    topAnchored: Boolean = false, motion: WallpaperMotion? = null,
) {
    when (w) {
        is ChatWallpaper.Animated -> {
            val a = w.wallpaper
            if (a == null) drawRect(plain)
            else if (a.look == com.fariin.app.data.AnimatedLook.DOODLES) drawDoodles(a, dark, pattern, motion?.phase ?: 0f, plain)
            else drawAnimated(a, dark, motion?.phase ?: 0f, motion?.layer, motion?.scale ?: 1f)
        }
        ChatWallpaper.None -> drawRect(plain)
        is ChatWallpaper.Solid -> drawRect(Color(0xFF000000 or w.hex))
        is ChatWallpaper.Gradient, is ChatWallpaper.Preset -> {
            val g = w.theme
            if (g == null) drawRect(plain) else drawTheme(g, dark, pattern)
        }
        is ChatWallpaper.Photo -> {
            if (photo == null) { drawRect(plain); return }
            drawFill(photo, topAnchored, null)
            if (dark) drawRect(Color.Black.copy(alpha = 0.20f))
        }
    }
}

/**
 * One animated look. Android 13+: its shader, drawn into [layer] at 1/[scale] of the size and scaled
 * up (the looks are soft, so the lower resolution does not show; iOS does the same), or straight at
 * full size without a layer. Older phones: the CPU-made first frame.
 */
fun DrawScope.drawAnimated(a: AnimatedWallpaper, dark: Boolean, phase: Float, layer: GraphicsLayer?, scale: Float) {
    if (size.width < 1f || size.height < 1f) return
    // Read in the draw phase, so a new intensity redraws at once.
    val intensity = AnimatedWallpapers.intensity.intValue
    val full = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        drawImage(AnimatedStill.bitmap(a, size.width / size.height, dark, intensity), dstSize = full, filterQuality = FilterQuality.Low)
        return
    }
    if (layer == null || scale <= 1f) {
        drawRect(ShaderBrush(AnimatedShaders.get(a, size.width, size.height, phase, dark, intensity)))
        return
    }
    val small = IntSize(ceil(size.width / scale).toInt().coerceAtLeast(1), ceil(size.height / scale).toInt().coerceAtLeast(1))
    val brush = ShaderBrush(AnimatedShaders.get(a, small.width.toFloat(), small.height.toFloat(), phase, dark, intensity))
    // Offscreen: the layer is rendered at its own small size, then the scale stretches that picture.
    layer.compositingStrategy = CompositingStrategy.Offscreen
    layer.pivotOffset = Offset.Zero
    layer.scaleX = size.width / small.width
    layer.scaleY = size.height / small.height
    layer.record(small) { drawRect(brush) }
    drawLayer(layer)
}

/**
 * Doodles (iOS): the theme's ground, the doodle sheet a little larger than the screen (so no edge
 * shows) drifting on a small circle, and a soft light band crossing once per loop. Only one bitmap
 * moved about, no shader, so it moves on every Android version and is the cheapest look.
 */
fun DrawScope.drawDoodles(a: AnimatedWallpaper, dark: Boolean, pattern: ImageBitmap?, phase: Float, plain: Color) {
    val g = a.doodleTheme
    if (g == null || size.width < 1f || size.height < 1f) { drawRect(plain); return }
    val w = size.width; val h = size.height
    drawImage(WallpaperArt.mesh(g, dark), dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Low)
    clipRect(0f, 0f, w, h) {
        if (pattern != null) {
            val iw = pattern.width.toFloat(); val ih = pattern.height.toFloat()
            if (iw > 0 && ih > 0) {
                // scaledToFill, then 1.12 larger, its centre on a circle 14 x 10 dp.
                val s = max(w / iw, h / ih) * 1.12f
                val dw = iw * s; val dh = ih * s
                val cx = w / 2 + cos(phase) * 14.dp.toPx(); val cy = h / 2 + sin(phase) * 10.dp.toPx()
                val ink = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.16f)
                drawImage(pattern, srcOffset = IntOffset.Zero, srcSize = IntSize(iw.toInt(), ih.toInt()),
                    dstOffset = IntOffset((cx - dw / 2).toInt(), (cy - dh / 2).toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()),
                    colorFilter = ColorFilter.tint(ink, BlendMode.SrcIn), filterQuality = FilterQuality.Low)
            }
        }
        // The light passing over: a band 0.8 wide and 1.6 tall, tilted 20 degrees, fully off screen at both ends.
        val t = phase / (2 * Math.PI).toFloat()
        val left = w * AnimatedWallpapers.doodleBandX(t); val top = -h * 0.3f
        val bw = w * 0.8f; val bh = h * 1.6f
        val glow = Color.White.copy(alpha = if (dark) 0.06f else 0.10f)
        rotate(20f, pivot = Offset(left + bw / 2, top + bh / 2)) {
            drawRect(Brush.linearGradient(listOf(Color.Transparent, glow, Color.Transparent),
                start = Offset(left, top), end = Offset(left + bw, top + bh)),
                topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(bw, bh))
        }
    }
}

/**
 * Where an animated wallpaper is in its loop, and the layer it draws through. [phase] is read in the
 * draw phase only. For any other wallpaper it is a still nothing: phase 0, no layer, no clock.
 */
class WallpaperMotion internal constructor(private val clock: FloatState?, val layer: GraphicsLayer?, val scale: Float) {
    val animated: Boolean get() = clock != null
    val phase: Float get() = clock?.floatValue ?: 0f

    /**
     * The wallpaper drawn by [draw] in a layer of its own, and what it sits behind in another, so a
     * new frame re-draws only the wallpaper, never the chat's rows. Plain [draw] when not animated.
     */
    fun isolate(draw: () -> Modifier): Modifier =
        if (!animated) draw() else Modifier.graphicsLayer().then(draw()).graphicsLayer()
}

/** The open chat's clock: the chat ground runs it, the header and list slices read it, so they agree. */
object ChatWallpaperClock {
    val phase = mutableFloatStateOf(0f)
}

/**
 * The motion for [w]. With [drive] the loop advances while ALL of these hold: [w] is animated, the
 * phone has not removed animations (no app switch, and Battery Saver does not stop it: owner
 * 2026-11-06), this is on screen (composed), the screen is resumed and
 * [animates] (a picker tile that is not the chosen one shows a still frame, to save battery). At most
 * 15 frames a second in a chat (Rain 24), 12 in a picker tile ([preview]) and for Doodles, never above the
 * display's own rate.
 */
@Composable
fun rememberWallpaperMotion(
    w: ChatWallpaper, preview: Boolean = true, clock: MutableFloatState? = null, drive: Boolean = true, animates: Boolean = true,
): WallpaperMotion {
    val a = (w as? ChatWallpaper.Animated)?.wallpaper ?: return remember { WallpaperMotion(null, null, 1f) }
    val ctx = LocalContext.current
    AnimatedWallpapers.load(ctx)
    val phase = clock ?: remember(a.id) { mutableFloatStateOf(0f) }
    val scale = a.scale(preview)
    // Doodles draws at full size with no shader, so it needs no layer of its own.
    val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && scale > 1f) rememberGraphicsLayer() else null
    // Also still on a low-end phone, under Battery Saver, or with Power Saving > Animations off
    // (owner 2026-11-07: animations must not drain the battery or lag cheap phones). The still
    // picture is the same wallpaper, so nothing looks missing.
    val power = com.fariin.app.perf.rememberPowerState()
    val moving = animates && power.animations && AnimatedWallpapers.moves(com.fariin.app.ui.thread.systemAnimationsOn()) &&
        AnimatedWallpapers.hasMotion(a.look, shaders = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    val fps = a.fps(preview)
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    if (drive) LaunchedEffect(a.id, moving, fps, owner) {
        if (!moving) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The clock counts only while moving: each start begins with no step, so after a pause
            // (Power Saving, the background, a still frame) it carries on from the frame it stopped
            // on, never jumping on by however long it was stopped. A late frame moves MAX_STEP at most.
            var last = 0L
            val wait = (1000L / fps - 12L).coerceAtLeast(0L)
            while (true) {
                withFrameNanos { now ->
                    if (AnimatedWallpapers.frameDue(last, now, fps)) {
                        phase.floatValue = AnimatedWallpapers.advance(phase.floatValue, AnimatedWallpapers.step(last, now), a.period)
                        last = now
                    }
                }
                // Sleep most of the gap, so a 120 Hz screen is not woken 120 times a second for 30 frames.
                if (wait > 0) delay(wait)
            }
        }
    }
    return remember(phase, layer, scale) { WallpaperMotion(phase, layer, scale) }
}

/** One theme: the mesh ground, then the doodles as the ground darkened (light) or lightened (dark). */
fun DrawScope.drawTheme(g: WallpaperGradient, dark: Boolean, pattern: ImageBitmap?) {
    drawImage(WallpaperArt.mesh(g, dark), dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Low)
    if (g.pattern && pattern != null) {
        val ink = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.15f)
        drawFill(pattern, false, ColorFilter.tint(ink, BlendMode.SrcIn))
    }
}

/** scaledToFill, clipped: centred, or pinned to the top so a shorter box keeps the same crop. */
private fun DrawScope.drawFill(img: ImageBitmap, topAnchored: Boolean, filter: ColorFilter?) {
    val iw = img.width.toFloat(); val ih = img.height.toFloat()
    if (iw <= 0 || ih <= 0 || size.width <= 0 || size.height <= 0) return
    val s = max(size.width / iw, size.height / ih)
    val sw = size.width / s; val sh = size.height / s
    val sx = (iw - sw) / 2
    val sy = if (topAnchored) 0f else (ih - sh) / 2
    drawImage(img, srcOffset = IntOffset(sx.toInt(), sy.toInt()), srcSize = IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()), colorFilter = filter, filterQuality = FilterQuality.Low)
}

/** A bubble fill for a chat colour: the gradient on its reference angle, else the solid. */
fun ChatColorSpec.brush(angled: Boolean = false): Brush {
    if (!isGradient) return androidx.compose.ui.graphics.SolidColor(Color(0xFF000000 or colors[0]))
    // iOS ChatColorSpec.fill: top to bottom, the first colour on top. Only the wallpaper preview tilts it.
    if (!angled) return Brush.verticalGradient(colors.map { Color(0xFF000000 or it) })
    val a = (angleDegrees - 180) / 180 * Math.PI
    val vx = sin(a).toFloat(); val vy = (-cos(a)).toFloat()
    return object : androidx.compose.ui.graphics.ShaderBrush() {
        // The angle is kept in real pixels (not stretched by a wide bubble), the line long enough to span the box.
        override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
            val half = abs(size.width / 2 * vx) + abs(size.height / 2 * vy)
            val cx = size.width / 2; val cy = size.height / 2
            return androidx.compose.ui.graphics.LinearGradientShader(
                from = Offset(cx + vx * half, cy + vy * half),
                to = Offset(cx - vx * half, cy - vy * half),
                colors = colors.map { Color(0xFF000000 or it) },
            )
        }
    }
}

/** The bitmaps: the doodle sheet (decoded once) and one small mesh per theme and appearance. */
object WallpaperArt {
    @Volatile private var sheet: ImageBitmap? = null
    private val meshes = HashMap<String, ImageBitmap>()

    fun pattern(c: Context): ImageBitmap? = sheet ?: synchronized(this) {
        sheet ?: runCatching {
            // The sheet is an alpha mask (white ink on clear); half size is plenty for hairlines.
            val o = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeResource(c.resources, R.drawable.wp_pattern, o).asImageBitmap()
        }.getOrNull().also { sheet = it }
    }

    /** The 3 x 3 mesh with its off-centre interior point, filled bilinearly into a small bitmap. */
    fun mesh(g: WallpaperGradient, dark: Boolean): ImageBitmap = synchronized(meshes) {
        meshes.getOrPut(g.id + dark) {
            val cols = g.meshColors(dark)
            val w = 36; val h = 72
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                px[y * w + x] = meshAt(cols, x / (w - 1f), y / (h - 1f)).toInt() or (0xFF shl 24)
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    /** The colour at (u, v) in 0..1: which of the four patches, then a bilinear blend inside it. */
    internal fun meshAt(cols: List<Long>, u: Float, v: Float): Long {
        val cx = WallpaperGradient.CENTRE_X; val cy = WallpaperGradient.CENTRE_Y
        // The middle lines bend through the interior point (edges stay at 0.5).
        val xb = 0.5f + (cx - 0.5f) * (if (v <= cy) v / cy else (1 - v) / (1 - cy)).coerceIn(0f, 1f)
        val yb = 0.5f + (cy - 0.5f) * (if (u <= cx) u / cx else (1 - u) / (1 - cx)).coerceIn(0f, 1f)
        val col = if (u <= xb) 0 else 1
        val row = if (v <= yb) 0 else 1
        val s = if (col == 0) u / xb else (u - xb) / (1 - xb)
        val t = if (row == 0) v / yb else (v - yb) / (1 - yb)
        fun at(r: Int, c: Int) = cols[(row + r) * 3 + col + c]
        return lerp(lerp(at(0, 0), at(0, 1), s), lerp(at(1, 0), at(1, 1), s), t)
    }

    private fun lerp(a: Long, b: Long, f: Float): Long {
        val t = f.coerceIn(0f, 1f)
        fun ch(sh: Int) = ((((a shr sh) and 0xFF) * (1 - t)) + (((b shr sh) and 0xFF) * t)).toLong().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * The wallpaper photo, read off the main thread (battery/lag audit 2026-11-05: a full-size decode in
 * the opening frame stalled cheap phones). From memory at once when it was shown before.
 */
@androidx.compose.runtime.Composable
private fun rememberWallpaperPhoto(ctx: android.content.Context, id: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (id == null) return null
    val app = ctx.applicationContext
    return androidx.compose.runtime.produceState(ChatLookStore.cachedImage(id)?.asImageBitmap(), id, ChatLookStore.version.intValue) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChatLookStore.libraryImage(app, id) }?.asImageBitmap()
    }.value
}
