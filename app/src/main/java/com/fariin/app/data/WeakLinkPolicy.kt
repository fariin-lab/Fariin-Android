package com.fariin.app.data

/**
 * The pause and resume DECISION for the 1:1 weak-signal video fallback (iOS WeakLinkPolicy).
 *
 * Group calls do not need it: their layers step down in size, so a weak leg loses sharpness, not the
 * call. A 1:1 call has no such floor, so video keeps fighting the voice until neither works.
 *
 * Pure: no clock, no side effects. `nowMs` is passed in, so a test runs fifteen seconds in an instant.
 * The input is the selected pair's availableOutgoingBitrate, WebRTC's own estimate, which already
 * folds in loss and round-trip time (iOS reads nothing else for the camera).
 */
class WeakLinkPolicy(
    /** Below this the link can not carry even the lowest useful video. */
    val floorBps: Double = 50_000.0,
    /** Slow both ways on purpose: a bare threshold makes video blink on and off. */
    val pauseAfterMs: Long = 5_000,
    /** Slower still, so video only comes back when it will hold. */
    val resumeAfterMs: Long = 10_000,
) {
    enum class Decision { NONE, PAUSE, RESUME }

    var poorSinceMs: Long? = null; private set
    var goodSinceMs: Long? = null; private set

    /** Forget both windows (the camera worked by hand, a share, hold, a path move). */
    fun reset() { poorSinceMs = null; goodSinceMs = null }

    /**
     * `paused` is the CURRENT state, so this never asks for what is already done. A null or zero
     * bitrate is no estimate yet (the pair is still forming): it decides nothing and starts both
     * windows again, since unknown treated as bad would blank a healthy call as it connects.
     */
    fun evaluate(bitrateBps: Double?, paused: Boolean, nowMs: Long): Decision {
        if (bitrateBps == null || bitrateBps <= 0.0) { reset(); return Decision.NONE }
        if (bitrateBps < floorBps) {
            goodSinceMs = null
            val since = poorSinceMs ?: nowMs
            poorSinceMs = since
            if (!paused && nowMs - since >= pauseAfterMs) return Decision.PAUSE
        } else {
            poorSinceMs = null
            val since = goodSinceMs ?: nowMs
            goodSinceMs = since
            if (paused && nowMs - since >= resumeAfterMs) return Decision.RESUME
        }
        return Decision.NONE
    }

    companion object {
        /** How often the call reads the stats (iOS linkMonitor: every 2 s). */
        const val SAMPLE_EVERY_MS = 2_000L

        /**
         * iOS applyLinkQuality: no verdict while the path is moving. During Reconnecting, the 10 s
         * after an ICE restart, or the 8 s after the path changed, a low estimate is the old path
         * dying, not the new one being weak.
         */
        fun moving(reconnecting: Boolean, restartAtMs: Long?, transitionAtMs: Long?, nowMs: Long): Boolean =
            reconnecting ||
                (restartAtMs != null && nowMs - restartAtMs < 10_000) ||
                (transitionAtMs != null && nowMs - transitionAtMs < 8_000)

        /** A pause made before a real move (a recovery) is stale once the move is over: video comes back. */
        fun staleAfterMove(pausedAtMs: Long?, movedAtMs: Long?): Boolean =
            pausedAtMs != null && movedAtMs != null && movedAtMs > pausedAtMs
    }
}
