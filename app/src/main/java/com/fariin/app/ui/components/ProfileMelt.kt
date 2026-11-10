package com.fariin.app.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fariin.app.perf.PerfClass
import com.fariin.app.perf.PowerSaving

/**
 * The profile photo melts into the camera hole as the page scrolls up (owner 2026-11-10, the
 * reference app's profile; the idea of the MIT-licensed expo-scroll-metaball by rit3zh, drawn here
 * with our own code). The photo follows the scroll up, shrinks toward the hole's size, darkens, and
 * near the end a blur with an alpha threshold (the "metaball" trick) joins it to a black drop over the
 * hole, so the two flow together like liquid. Pulling back down brings it out the same way.
 *
 * Android 13+ on a phone that is not low-end: the liquid join. Otherwise: the photo shrinks and
 * fades on the same path, nothing heavier. A phone with no camera hole melts into a small dot at the
 * top centre.
 *
 * Use: [rememberProfileMelt]; [profileMeltSource] on the page's photo (it hides that photo while the
 * melt draws its copy); [ProfileMeltOverlay] once, over the whole page, last in its Box.
 */
class ProfileMeltState internal constructor(val scroll: ScrollState) {
    /** The photo's centre in window pixels with the page at its top, and its side in pixels. */
    internal var restCenter by mutableStateOf(Offset.Unspecified)
    internal var side by mutableFloatStateOf(0f)
    /** The overlay's own top-left in window pixels (the page may sit inside a sliding frame). */
    internal var origin by mutableStateOf(Offset.Zero)
    /** Where the photo goes, in window pixels (set by the overlay). */
    internal var targetY by mutableFloatStateOf(0f)

    /**
     * 0 with the photo still out, rising to 1 over [fadePx] once the page has scrolled [extraPx] past
     * the point where the photo is gone: for a compact title and the bar's ground to come in.
     */
    fun collapsed(extraPx: Float, fadePx: Float): Float {
        if (!restCenter.isSpecified) return 0f
        val gone = restCenter.y - targetY
        return ((scroll.value - gone - extraPx) / fadePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}

@Composable
fun rememberProfileMelt(scroll: ScrollState): ProfileMeltState = remember(scroll) { ProfileMeltState(scroll) }

/** On the page's own photo: measures where it rests, and hides it while the melt shows its copy. */
fun Modifier.profileMeltSource(m: ProfileMeltState): Modifier = this
    .onGloballyPositioned {
        val p = it.positionInWindow()
        m.side = it.size.width.toFloat()
        // Where it would be with the page scrolled to the top.
        m.restCenter = Offset(p.x + it.size.width / 2f, p.y + it.size.height / 2f + m.scroll.value)
    }
    .graphicsLayer { alpha = if (m.scroll.value > 0 && m.restCenter.isSpecified) 0f else 1f }

/** The camera hole in window pixels: its centre and diameter, or null when the screen has none. */
@Composable
private fun rememberCameraHole(): Pair<Offset, Float>? {
    val view = LocalView.current
    return remember(view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@remember null
        val rects = view.rootWindowInsets?.displayCutout?.boundingRects ?: return@remember null
        val r = rects.filter { it.top <= 0 || it.top < 40 }.minByOrNull { it.top } ?: return@remember null
        // A hole is about square; a wide notch is not a hole to melt into.
        if (r.width() > r.height() * 2.2f) return@remember null
        Offset(r.exactCenterX(), r.exactCenterY()) to minOf(r.width(), r.height()).toFloat() * 0.8f
    }
}

/** Melt into the camera hole (true) or the top centre (false, the owner's choice). */
private const val MELT_TO_CAMERA = false

private const val MELT_SKSL = """
uniform shader content;
half4 main(float2 p) {
    half4 c = content.eval(p);
    // The metaball threshold: blurred shapes that overlap pass 0.5 together and join.
    half a = smoothstep(0.42, 0.58, c.a);
    if (c.a < 0.001) return half4(0.0);
    return half4(c.rgb / c.a * a, a);
}
"""

/**
 * The melting copy of the photo, drawn over the page only while the page is between its top and the
 * point where the photo has gone into the hole. [photo] draws the picture at [photoSize] (the page's
 * own avatar composable); [squircle] for a channel's rounded square.
 */
@Composable
fun ProfileMeltOverlay(m: ProfileMeltState, photoSize: Dp, squircle: Boolean = false, photo: @Composable () -> Unit) {
    val density = LocalDensity.current
    // Always the top centre (owner 2026-11-10: cameras sit in different places, some at the side;
    // go to the middle like the reference app). The camera-hole target is kept behind MELT_TO_CAMERA.
    val hole = if (MELT_TO_CAMERA) rememberCameraHole() else null
    val view = LocalView.current
    val topInset = remember(view) { (view.rootWindowInsets?.systemWindowInsetTop ?: 0).toFloat() }
    val liquid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        PowerSaving.state.value.deviceClass != PerfClass.LOW
    // The reference app (owner's screen recording, 2026-11-10): a black drop hangs from the screen's
    // top edge, centred, and the photo is drawn up into it on a thin neck. The drop's centre sits a
    // little above the edge, so only its lower part shows.
    val targetSide = hole?.second ?: with(density) { 40.dp.toPx() }
    val target = hole?.first ?: Offset(view.width / 2f, -targetSide * 0.2f)
    m.targetY = target.y
    // How far the page scrolls before the photo is gone: from where it rests up to the hole.
    val travel by remember(target) {
        derivedStateOf { if (m.restCenter.isSpecified) (m.restCenter.y - target.y).coerceAtLeast(1f) else 1f }
    }
    val showing by remember { derivedStateOf { m.restCenter.isSpecified && m.scroll.value > 0 && m.scroll.value < travel } }
    // Let go part way and it settles, in or out, like the reference app: never left half melted.
    androidx.compose.runtime.LaunchedEffect(m.scroll, travel) {
        androidx.compose.runtime.snapshotFlow { m.scroll.isScrollInProgress }.collect { moving ->
            val v = m.scroll.value
            if (!moving && m.restCenter.isSpecified && v > 0 && v < travel) {
                m.scroll.animateScrollTo(if (v < travel * 0.5f) 0 else travel.toInt() + 1,
                    androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f))
            }
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { m.origin = it.positionInWindow() }) {
        if (!showing) return@Box
        val shader = remember { if (liquid) RuntimeShader(MELT_SKSL) else null }
        val shape = if (squircle) RoundedCornerShape(photoSize * 0.27f) else CircleShape
        // Only the strip from the top down past the photo's resting place: a full-screen layer under
        // a blur cost a whole screen of work on every frame of the scroll.
        val stripH = with(density) { ((m.restCenter.y - m.origin.y) + photoSize.toPx()).coerceAtLeast(1f).toDp() }
        // And only the column the photo travels up (it rises straight to the top centre), not the
        // screen's width: the blur's cost goes with the area (Pixel: 22 ms frames full width).
        val stripW = photoSize + 56.dp
        val stripLeft = (m.restCenter.x - m.origin.x) - with(density) { stripW.toPx() } / 2f
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(stripLeft.toInt(), 0) }.width(stripW).height(stripH)
            .graphicsLayer {
                val p = (m.scroll.value / travel).coerceIn(0f, 1f)
                if (shader != null) {
                    // The join only near the end, so most of the way the photo stays sharp and no
                    // effect runs at all.
                    // Blurring early, while still big (the reference blurs it from the first third).
                    val k = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    // In 2 dp steps: a new blur size each frame made the graphics chip set up new
                    // work every frame; a few fixed sizes are set up once and reused.
                    val step = 2.dp.toPx()
                    val blur = (kotlin.math.round(k * k * 18.dp.toPx() / step) * step).coerceAtLeast(0.5f)
                    renderEffect = if (k <= 0f) null else RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL),
                    ).asComposeRenderEffect()
                } else {
                    alpha = 1f - ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
                }
            }
            .drawBehind {
                // The drop over the camera hole, growing out to meet the photo as it nears.
                if (shader != null) {
                    val p = (m.scroll.value / travel).coerceIn(0f, 1f)
                    val k = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val r = targetSide / 2f * (0.4f + 0.8f * k)
                    drawCircle(Color.Black, r, target - m.origin - Offset(stripLeft.toInt().toFloat(), 0f))
                }
            }) {
            val sidePx = with(density) { photoSize.toPx() }
            Box(Modifier.size(photoSize)
                .graphicsLayer {
                    val p = (m.scroll.value / travel).coerceIn(0f, 1f)
                    val restLocal = m.restCenter - m.origin
                    // The centre goes from where the page carries it to the hole, a little ahead of
                    // the scroll near the end so it is drawn in rather than merely passing.
                    val pull = p * p
                    val cx = restLocal.x + (target.x - m.origin.x - restLocal.x) * pull
                    val cy = (restLocal.y - m.scroll.value) + ((target.y - m.origin.y) - (restLocal.y - m.scroll.value)) * pull
                    val s = 1f + (targetSide / sidePx - 1f) * p
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    translationX = cx - sidePx / 2f - stripLeft.toInt()
                    translationY = cy - sidePx / 2f
                    scaleX = s; scaleY = s
                }
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    // Darkens as it goes, so it reads as sinking into the black hole.
                    val p = (m.scroll.value / travel).coerceIn(0f, 1f)
                    val d = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
                    if (d > 0f) drawRect(Color.Black.copy(alpha = d * 0.85f))
                }) { photo() }
        }
    }
}
