package com.fariin.app.call

import com.fariin.app.perf.PerfClass

/**
 * How much video a group call sends and receives, by how strong the phone is (pure, unit tested;
 * iOS has the same rules, fariin-plans/to-ios/2026-11-15-group-call-low-end.md).
 *
 *  - A LOW phone receives at most [LOW_LIVE_TILES] cameras: the pinned person, the speaker, then the
 *    latest speakers, then whoever came first. Everyone else shows their photo and their camera is
 *    not downloaded at all.
 *  - A LOW phone asks for the small layer (at most [LOW_SMALL_TILE_MAX] on the long side) for every
 *    tile except the big one; other phones ask for the layer that fits the tile's real size.
 *  - My own camera: LOW 480p at 15 fps with 2 layers, AVERAGE 540p at 24 fps, HIGH as the SDK has it.
 */
object GroupVideoRules {
    const val LOW_LIVE_TILES = 4
    const val LOW_SMALL_TILE_MAX = 320

    /**
     * The remote cameras to receive. [withCamera] is everyone whose camera is on, in the order they
     * came; [lastSpoke] is when each person last held the speaker ring (ms).
     */
    fun liveVideo(withCamera: List<String>, focus: String?, speaker: String?, lastSpoke: Map<String, Long>, cap: Int): Set<String> {
        if (withCamera.size <= cap) return withCamera.toSet()
        val order = LinkedHashSet<String>()
        if (focus != null && focus in withCamera) order += focus
        if (speaker != null && speaker in withCamera) order += speaker
        withCamera.filter { it in lastSpoke }.sortedByDescending { lastSpoke[it] }.forEach { order += it }
        withCamera.forEach { order += it }
        return order.take(cap).toSet()
    }

    /** A tile's size as the server is told it: scaled down so its long side is at most [maxSide]. */
    fun capSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxSide || long <= 0) return w to h
        val k = maxSide.toFloat() / long
        return maxOf(1, (w * k).toInt()) to maxOf(1, (h * k).toInt())
    }

    /**
     * My camera. [width] x [height] is what the camera is asked for (landscape, as cameras list it);
     * the capturer takes the nearest size the camera offers, so a camera with no 480p gets 360p.
     * [twoLayers] = a full layer and one small one (instead of three). Null = the SDK's own.
     */
    data class Camera(val width: Int, val height: Int, val fps: Int, val maxBitrate: Int, val twoLayers: Boolean)

    fun camera(cls: PerfClass): Camera? = when (cls) {
        PerfClass.LOW -> Camera(854, 480, 15, 500_000, twoLayers = true)
        PerfClass.AVERAGE -> Camera(960, 540, 24, 900_000, twoLayers = false)
        PerfClass.HIGH -> null
    }
}

/**
 * When I stop receiving other people's cameras to keep the sound going: my network has been poor for
 * [POOR_HOLD_MS], or the phone says it is SEVERE hot or worse. "Turn video back on" brings them back
 * and the gate stays out of the way until the cause has cleared once. A pause that nobody undoes
 * lifts by itself when the cause has been gone for [CLEAR_HOLD_MS].
 */
class ReceiveVideoGate {
    companion object {
        const val POOR_HOLD_MS = 10_000L
        const val CLEAR_HOLD_MS = 30_000L
    }

    var paused = false
        private set
    private var poorSince: Long? = null
    private var clearSince: Long? = null
    private var userOn = false

    /** Fed about once a second. Returns true when [paused] changed. */
    fun update(now: Long, poor: Boolean, hot: Boolean): Boolean {
        val before = paused
        poorSince = if (poor) poorSince ?: now else null
        val cause = hot || (poorSince?.let { now - it >= POOR_HOLD_MS } == true)
        val clear = !poor && !hot
        if (userOn && clear) userOn = false
        if (!paused && !userOn && cause) { paused = true; clearSince = null }
        if (paused) {
            clearSince = if (clear) clearSince ?: now else null
            if (clearSince?.let { now - it >= CLEAR_HOLD_MS } == true) { paused = false; clearSince = null }
        }
        return paused != before
    }

    /** "Turn video back on". */
    fun resume() { paused = false; userOn = true; clearSince = null }

    fun reset() { paused = false; poorSince = null; clearSince = null; userOn = false }
}
