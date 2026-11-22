package com.fariin.app.data

/**
 * Why the screen is kept on right now (iOS SleepBlocker reasons, like "voice-play" and
 * "voice-record"). The screen stays on while ANY reason is held, so one feature ending
 * never lets the screen sleep under another that still runs.
 */
class AwakeReasons {
    private val held = mutableSetOf<String>()

    val on: Boolean get() = held.isNotEmpty()

    /** True when this hold turned the screen-on state from off to on. */
    fun hold(reason: String): Boolean {
        val was = on
        held.add(reason)
        return !was && on
    }

    /** True when this release turned the screen-on state from on to off. */
    fun release(reason: String): Boolean {
        val was = on
        held.remove(reason)
        return was && !on
    }
}
