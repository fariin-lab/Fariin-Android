package com.fariin.app.ui.thread

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.withFrameNanos
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
 * spring of stiffness 420 and damping 22 (mass 1, a damping ratio of about 0.54). The rise here
 * runs on [GlideCurve] (see there), and the list glides up under it on the same clock.
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
    /** The row whose flight the list glides along with (see [glidesFor]). */
    private var gliding: String? = null

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
        if (!atBottom) return null
        gliding = m.rowId
        return Flight(a.kind, a.origin)
    }

    /**
     * Whether my newest row [rowId] may still get a flight: a send is armed and its row not yet
     * placed, or the row already took one. The list then waits for the flight's start and glides
     * up with it (ThreadScreen's stick-to-newest effect) instead of jumping in one frame.
     */
    fun mayFly(rowId: String): Boolean = arm != null || gliding == rowId

    /** True once when [rowId] took a flight: the list glides up with it. */
    fun glidesFor(rowId: String): Boolean = (gliding == rowId).also { if (it) gliding = null }

    /** One bubble in flight. Its live place is kept from layout; the draw reads it every frame. */
    @Stable
    class Flight internal constructor(val kind: Kind, private val origin: Rect) {
        internal val progress = Animatable(0f)
        internal val scale = Animatable(START_SCALE)
        private var bounds by mutableStateOf<Rect?>(null)
        /** The composer-to-row distance when the flight starts; fixed from then on (see [run]). */
        private var startDrop: Float? = null

        internal fun place(c: LayoutCoordinates) {
            // Landed: the place no longer matters, so later scrolling writes nothing.
            if (progress.value >= 1f && !progress.isRunning) return
            if (!c.isAttached) { bounds = null; return }
            val r = c.boundsInRoot()
            bounds = if (r.isUsable()) r else null
        }

        /** The composer-to-row distance at the row's current place, or null when it makes no sense. */
        private fun liveDrop(): Float? {
            val r = bounds ?: return null
            val d = origin.center.y - r.center.y
            // Within a screen's reach of the composer. The row may start a little below the
            // composer's middle: it waits under the composer until the list glides it up.
            return if (d.isFinite() && kotlin.math.abs(d) <= origin.bottom) d else null
        }

        /**
         * How far below its place the bubble is drawn at [p] (0 = start, 1 = landed). The distance
         * is fixed when the flight starts, while the list glides the row itself up on the same
         * curve: place + fixed drop * (1 - curve) then runs from the composer to the landing in one
         * line, and lands exactly where the row is.
         */
        internal fun dropAt(p: Float): Float {
            val d = startDrop ?: liveDrop() ?: return 0f
            return d * (1f - GlideCurve.transform(p.coerceIn(0f, 1f)))
        }

        internal suspend fun run() = coroutineScope {
            // The frame that adds the new row is the heavy one; starting in it lost that time and
            // the first visible step was a jump. Two quiet frames first, the same two the list
            // waits (ThreadScreen), so both start on the same frame.
            withFrameNanos { }
            withFrameNanos { }
            startDrop = liveDrop()   // none yet (the row not placed): the live place decides, as before
            launch { progress.animateTo(1f, tween(DURATION_MS, easing = GlideCurve)) }
            launch { scale.animateTo(1f, spring(dampingRatio = DAMPING_RATIO, stiffness = STIFFNESS)) }
        }
    }

    companion object {
        const val DURATION_MS = 300
        /**
         * The rise and the list's glide under it, one curve for both so the bubble's path stays one
         * line. iOS's vertical curve sits nearly still for its first fifth (owner's frame-by-frame
         * look at the iOS recording reads as one decelerating move, the field morph covers that
         * start there); without a morph that stillness read as a pause under the composer here.
         * The same quick-out, soft-landing curve as a received message's glide, no overshoot.
         */
        val GlideCurve = ArrivalMotion.Curve
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
