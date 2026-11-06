package com.fariin.app.ui.thread

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.fariin.app.data.Message
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The iOS send flight, the Compose way (iOS NativeMessageList.flyOwnSendIntoPlace). When this
 * phone sends a text or a voice note, its new bubble rises out of the composer row, behind the
 * composer, into its place at the bottom of the chat, and settles from a little smaller to full
 * size on a spring.
 *
 * iOS numbers: 0.3 s, vertical curve (0.199, 0.011, 0.279, 0.910), landing scale 0.94, then a
 * spring of stiffness 420 and damping 22 (mass 1, a damping ratio of about 0.54).
 *
 * Safety: only the row this phone just sent moves, once, found by its row id. Everything is a
 * graphicsLayer transform, so the list's layout never changes. Any unknown (no composer place,
 * the row not on screen, a place that makes no sense) means no motion: the bubble just shows.
 */
@Stable
class SendMotion {
    enum class Kind { TEXT, VOICE }

    private class Arm(val kind: Kind, val origin: Rect, val before: Set<String>, val atMs: Long)

    private var arm: Arm? = null
    private val flown = HashSet<String>()

    /** The composer's row (field or record bar) in root coordinates; null until it is laid out. */
    var composerRow: Rect? = null

    /**
     * A send is about to happen. [existing] are the rows on screen before it, so the new row is
     * the one not among them. [enabled] false (animations off) clears any arm.
     */
    fun arm(kind: Kind, existing: List<Message>, enabled: Boolean) {
        val o = composerRow
        arm = if (!enabled || o == null || !o.isUsable()) null
        else Arm(kind, o, existing.mapTo(HashSet(existing.size)) { it.rowId }, SystemClock.uptimeMillis())
    }

    /**
     * The flight for [m], or null. Asked once when its row is first composed: the newest row,
     * written by me, new since the arm, within a few seconds of it, and the chat at the bottom.
     */
    fun claim(m: Message, me: String, isNewest: Boolean, atBottom: Boolean): Flight? {
        val a = arm ?: return null
        if (m.rowId in flown) return null
        if (SystemClock.uptimeMillis() - a.atMs > WINDOW_MS) { arm = null; return null }
        if (!isNewest || me.isEmpty() || m.authorId != me || m.rowId in a.before) return null
        arm = null
        flown.add(m.rowId)
        return if (atBottom) Flight(a.kind, a.origin) else null
    }

    /** One bubble in flight. Its live place is kept from layout; the draw reads it every frame. */
    @Stable
    class Flight internal constructor(val kind: Kind, private val origin: Rect) {
        internal val progress = Animatable(0f)
        internal val scale = Animatable(START_SCALE)
        private var bounds by mutableStateOf<Rect?>(null)

        internal fun place(c: LayoutCoordinates) {
            // Landed: the place no longer matters, so later scrolling writes nothing.
            if (progress.value >= 1f && !progress.isRunning) return
            if (!c.isAttached) { bounds = null; return }
            val r = c.boundsInRoot()
            bounds = if (r.isUsable()) r else null
        }

        /** How far below its place the bubble is drawn at [p] (0 = start, 1 = landed). */
        internal fun dropAt(p: Float): Float {
            val r = bounds ?: return 0f
            val d = origin.center.y - r.center.y
            // The row must sit above the composer and within a screen's reach of it.
            if (!d.isFinite() || d <= 0f || d > origin.bottom) return 0f
            return d * (1f - VerticalCurve.transform(p.coerceIn(0f, 1f)))
        }

        internal suspend fun run() = coroutineScope {
            launch { progress.animateTo(1f, tween(DURATION_MS, easing = VerticalCurve)) }
            launch { scale.animateTo(1f, spring(dampingRatio = DAMPING_RATIO, stiffness = STIFFNESS)) }
        }
    }

    companion object {
        const val DURATION_MS = 300
        /** iOS sendVerticalCurve. */
        val VerticalCurve = CubicBezierEasing(0.19919473f, 0.010644531f, 0.27920937f, 0.91025390f)
        /** iOS lands at 0.94; the bubble starts a touch smaller here, as it has no pill shape to shrink from. */
        const val START_SCALE = 0.9f
        /** iOS settle spring: stiffness 420, damping 22, mass 1. */
        const val STIFFNESS = 420f
        const val DAMPING_RATIO = 0.54f
        /** A sent row that shows up later than this after the tap is not flown. */
        const val WINDOW_MS = 5_000L

        private fun Rect.isUsable(): Boolean =
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width > 0f && height > 0f
    }
}

/** Whether the system "Remove animations" (animator duration scale 0) is off. Public settings API only. */
@Composable
fun systemAnimationsOn(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}

/**
 * The flight for row [m], decided once per row and never again for the same id. [atBottom] is
 * read without subscribing, so scrolling never recomposes the row for this.
 */
@Composable
fun rememberSendFlight(motion: SendMotion, m: Message, me: String, isNewest: Boolean, atBottom: () -> Boolean): SendMotion.Flight? {
    val f = remember(m.rowId) {
        Snapshot.withoutReadObservation { motion.claim(m, me, isNewest, atBottom()) }
    }
    if (f != null) LaunchedEffect(f) { f.run() }
    return f
}

/** Draws the row on its way up from the composer; a plain pass-through when [f] is null. */
@Composable
fun Modifier.sendFlight(f: SendMotion.Flight?): Modifier {
    if (f == null) return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // My bubbles sit at the end side, so the growth is anchored at that bottom corner.
    val pivot = if (rtl) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
    return this
        .onGloballyPositioned { f.place(it) }
        .graphicsLayer {
            translationY = f.dropAt(f.progress.value)
            val s = f.scale.value
            scaleX = s; scaleY = s
            transformOrigin = pivot
        }
}
