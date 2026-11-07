package com.fariin.app.ui.thread

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fariin.app.data.Message

/**
 * A message from someone else that arrives while the reader sits at the newest: its bubble rises
 * a short way from below, grows a touch from its bottom corner on the tail side and fades in, on
 * one smooth curve. The rows above already glide up to make room (the list's placement motion).
 *
 * Same skip rules as iOS (NativeMessageList glide): only a LIVE arrival, never the rows that land
 * when the chat opens (the phone's copy, then the server's first answer); only at the newest, not
 * while the reader is up in history; not while a finger or a fling moves the list. Several rows
 * landing in one update move together, like one. My own rows never use this: my sends have their
 * own flight (SendMotion.kt).
 *
 * Everything is a graphicsLayer transform on the one new row, so the list's layout never changes
 * and nothing else recomposes per frame.
 */
@Stable
class ArrivalMotion {
    private var prevIds: Set<String>? = null
    private var prevLast: String? = null
    private var prevLive = false
    /** Rows allowed to glide, with the time they were seen arriving. */
    private val pending = HashMap<String, Long>()

    /**
     * The list changed. Called from composition before the list lays out, so [atBottom] and
     * [scrolling] still describe the screen as the reader saw it before the new rows.
     */
    fun note(messages: List<Message>, live: Boolean, me: String, atBottom: Boolean, scrolling: Boolean, enabled: Boolean) {
        val ids = ArrivalRules.arrivals(prevIds, prevLast, prevLive, messages, me, atBottom, scrolling)
        if (enabled && ids.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            ids.forEach { pending[it] = now }
        }
        prevIds = messages.mapTo(HashSet(messages.size)) { it.rowId }
        prevLast = messages.lastOrNull()?.rowId
        prevLive = live
    }

    /** The glide for [rowId], or null. Once per row, and only soon after it arrived. */
    fun claim(rowId: String): Glide? {
        val at = pending.remove(rowId) ?: return null
        return if (SystemClock.uptimeMillis() - at <= WINDOW_MS) Glide() else null
    }

    /** One arriving bubble; the draw reads [progress] every frame, nothing recomposes. */
    @Stable
    class Glide internal constructor() {
        internal val progress = Animatable(0f)
        internal suspend fun run() { progress.animateTo(1f, tween(DURATION_MS, easing = Curve)) }
    }

    companion object {
        const val DURATION_MS = 300
        /** A smooth ease-out with no overshoot: quick to leave, long soft landing. */
        val Curve = CubicBezierEasing(0.22f, 0.8f, 0.3f, 1f)
        /** How far below its place the bubble starts. */
        val RISE = 18.dp
        const val START_SCALE = 0.92f
        /** A row first drawn later than this after it arrived (scrolled to, say) just shows. */
        const val WINDOW_MS = 1_500L
    }
}

/** The pure part: which rows of an update count as a live arrival that may glide. */
object ArrivalRules {
    /**
     * The row ids in [next] that glide: the new rows at the newest end, written by someone else,
     * when the update is a plain append (the old newest row sits right above them) to a list that
     * was already live, with the reader at the newest and not scrolling. Anything else is empty.
     */
    fun arrivals(
        prevIds: Set<String>?, prevLast: String?, prevLive: Boolean, next: List<Message>, me: String,
        atBottom: Boolean, scrolling: Boolean,
    ): List<String> {
        if (prevIds == null || prevLast == null || !prevLive || !atBottom || scrolling || me.isEmpty()) return emptyList()
        var fresh = 0
        while (fresh < next.size && next[next.size - 1 - fresh].rowId !in prevIds) fresh++
        if (fresh == 0 || fresh == next.size) return emptyList()
        // A plain append: the old newest row is the one right above the new ones.
        if (next[next.size - 1 - fresh].rowId != prevLast) return emptyList()
        return next.subList(next.size - fresh, next.size).filter { it.authorId != me }.map { it.rowId }
    }
}

/** The glide for row [m], decided once per row. Reads nothing that scrolling changes. */
@Composable
fun rememberArrivalGlide(motion: ArrivalMotion, m: Message): ArrivalMotion.Glide? {
    val g = remember(m.rowId) { Snapshot.withoutReadObservation { motion.claim(m.rowId) } }
    if (g != null) LaunchedEffect(g) { g.run() }
    return g
}

/** Draws the arriving row on its way into place; a plain pass-through when [g] is null. */
@Composable
fun Modifier.arrivalGlide(g: ArrivalMotion.Glide?): Modifier {
    if (g == null) return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Their bubbles sit at the start side, so the growth comes out of that bottom corner (the tail).
    val pivot = if (rtl) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
    return this.graphicsLayer {
        val p = g.progress.value
        translationY = ArrivalMotion.RISE.toPx() * (1f - p)
        val s = ArrivalMotion.START_SCALE + (1f - ArrivalMotion.START_SCALE) * p
        scaleX = s; scaleY = s
        // The fade finishes a little before the move, so the bubble is solid as it lands.
        alpha = (p * 1.4f).coerceAtMost(1f)
        transformOrigin = pivot
    }
}
