package com.fariin.app.media

/**
 * The decisions of the mic gesture and the locked recording, kept free of Android so they are
 * tested (iOS ThreadView updateHoldRecording / endHoldRecording / sendRecording).
 *
 * - Slide left past the cancel line: the note is binned.
 * - Slide up past the lock line: the recording locks and goes on with the finger off.
 * - A quick, still tap (under [TAP_MS], moved under the slop): it locks too. That is hands-free
 *   recording, the owner's own ask; the hold path is unchanged.
 * - A hold released under [MediaRules.VOICE_MIN_SECONDS] cannot make a note: binned with the hint.
 * - Anything else sends.
 */
object VoiceRecordRules {
    enum class Release { LOCK, CANCEL, TOO_SHORT, SEND }

    /** iOS: 0.5 s, Apple's long-press threshold; a tap a hair longer still reads as a tap. */
    const val TAP_MS = 500L

    /** How far up (dp) the finger goes to lock; the lock target floats about this far above the mic. */
    const val LOCK_DP = 86f

    /** How far left (dp) the finger goes to cancel. */
    const val CANCEL_DP = 120f

    /** A tap that travels further than this (dp) is a drag toward lock or cancel. */
    const val SLOP_DP = 10f

    /** One AAC frame at 48 kHz (1024 samples), in microseconds: the gap between stitched stretches. */
    const val AAC_FRAME_US = 1024L * 1_000_000L / 48_000L

    fun cancels(dx: Float, cancelPx: Float): Boolean = dx < -cancelPx

    fun locks(dy: Float, lockPx: Float): Boolean = dy < -lockPx

    /** How far along the lift is (0 at the mic, 1 at the lock), for the lock target's look. */
    fun lockProgress(dy: Float, lockPx: Float): Float =
        if (lockPx <= 0f) 0f else (-dy / lockPx).coerceIn(0f, 1f)

    /**
     * The finger came up without crossing either line. [heldMs] is the wall clock from touch-down
     * (not the recorder's clock, which can lag a cold start), [movedPx] the furthest it travelled,
     * [elapsed] what the recorder has.
     */
    fun onRelease(heldMs: Long, movedPx: Float, slopPx: Float, elapsed: Double): Release = when {
        heldMs < TAP_MS && movedPx < slopPx -> Release.LOCK
        elapsed < MediaRules.VOICE_MIN_SECONDS -> Release.TOO_SHORT
        else -> Release.SEND
    }

    /** Send from the locked bar: under a second there is nothing to send (iOS sendRecording). */
    fun canSendLocked(elapsed: Double): Boolean = elapsed >= MediaRules.VOICE_MIN_SECONDS

    /** Seconds left for the next stretch, so all stretches together stay inside the note limit. */
    fun remainingSeconds(banked: Double): Int =
        maxOf(1, MediaRules.VOICE_NOTE_SECONDS - banked.toInt())

    /** Where the next stretch's first sample goes: one frame after the last one written. */
    fun nextOffsetUs(lastSampleUs: Long, wroteAny: Boolean, previousOffsetUs: Long): Long =
        if (wroteAny) lastSampleUs + AAC_FRAME_US else previousOffsetUs

    /**
     * One-time listen (iOS OneTimeVoicePage): the note plays forward only, so a seek may move
     * the playhead on, never back.
     */
    fun onceSeekAllowed(targetMs: Long, currentMs: Long): Boolean = targetMs > currentMs

    /** The receiver's pill (iOS OneTimeVoicePill.label). */
    fun onceLabel(isMe: Boolean, spent: Boolean, durationSeconds: Double): String = when {
        isMe -> "Voice message"
        spent -> "Played"
        else -> "Voice message · " + MediaRules.durationLabel(durationSeconds)
    }

    /** The chat list marker for a one-time note (iOS sendAudio). */
    const val ONCE_PREVIEW = "🎤 One-time voice message"

    fun preview(duration: Double, once: Boolean): String =
        if (once) ONCE_PREVIEW else MediaRules.voicePreview(duration)
}
