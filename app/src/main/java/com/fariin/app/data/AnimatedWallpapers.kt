package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/*
 * ANIMATED CHAT WALLPAPERS (iOS AnimatedWallpapers.swift, owner 2026-11-06). Each look is drawn live by
 * a shader (ui/components/AnimatedWallpaperShaders.kt), no pictures or video. Stored as "a:<id>"
 * (ChatWallpaper.Animated). Local for a 1:1 chat; in a group an admin can set one for everyone.
 *
 * The motion is a circle: the shader's phase goes 0..2 pi once per loop, so the last frame of a loop
 * is the first frame of the next and the clock never grows large.
 */

enum class AnimatedLook { LIQUID_METAL, WATER, LIQUID_GLOW, LIQUID_EDGE, AURORA }

/** One animated wallpaper. [period] is seconds for one full loop; [palette] is Aurora's colours (0 green, 1 purple, 2 pink). */
data class AnimatedWallpaper(val id: String, val name: String, val look: AnimatedLook, val period: Float, val palette: Int = 0) {
    /** iOS topIsDark: is the top of the screen dark in this mode? A moving ground has no still picture
     *  to measure, so it says so itself; the chat header's words turn light over a dark top. */
    fun topIsDark(dark: Boolean): Boolean = look == AnimatedLook.AURORA || dark
}

object AnimatedWallpapers {
    /** iOS AnimatedWallpapers.all, same ids, names, order and loop lengths. */
    val all: List<AnimatedWallpaper> = listOf(
        AnimatedWallpaper("liquid-metal", "Liquid Metal", AnimatedLook.LIQUID_METAL, 90f),
        AnimatedWallpaper("water", "Water", AnimatedLook.WATER, 60f),
        AnimatedWallpaper("liquid-glow", "Liquid Glow", AnimatedLook.LIQUID_GLOW, 40f),
        AnimatedWallpaper("liquid-edge", "Liquid Edge", AnimatedLook.LIQUID_EDGE, 50f),
        AnimatedWallpaper("aurora-green", "Aurora Green", AnimatedLook.AURORA, 75f, 0),
        AnimatedWallpaper("aurora-purple", "Aurora Purple", AnimatedLook.AURORA, 75f, 1),
        AnimatedWallpaper("aurora-pink", "Aurora Pink", AnimatedLook.AURORA, 75f, 2),
    )

    fun find(id: String): AnimatedWallpaper? = all.firstOrNull { it.id == id }

    /** Frames a second: the chat at most 30, a picker tile at most 20 (iOS). */
    const val CHAT_FPS = 30
    const val TILE_FPS = 20

    private const val TWO_PI = (2 * Math.PI).toFloat()

    /**
     * Whether a wallpaper moves: the Animated Wallpapers setting is on, Power Saving "Animations" is
     * allowed (that already counts Battery Saver), and the phone's own animations are not removed.
     * Being on screen and resumed is the caller's part (the frame loop only runs then).
     */
    fun moves(setting: Boolean, powerAnimations: Boolean, systemAnimations: Boolean): Boolean =
        setting && powerAnimations && systemAnimations

    /** The phase after [seconds] more of a [period]-second loop, kept in 0..2 pi. */
    fun advance(phase: Float, seconds: Float, period: Float): Float {
        if (period <= 0f || seconds <= 0f) return phase
        val p = (phase + seconds / period * TWO_PI) % TWO_PI
        return if (p < 0f) p + TWO_PI else p
    }

    /** Whether a new frame is due at [now] (nanos) after the last drawn at [last], for [fps]. A little slack so 60 Hz vsync lands on 30. */
    fun frameDue(last: Long, now: Long, fps: Int): Boolean = last == 0L || now - last >= 1_000_000_000L / fps - 4_000_000L

    // Settings > Appearance > Animated Wallpapers. Off: every animated wallpaper is a still frame.

    private const val PREFS = "fariin_look"
    const val ENABLED_KEY = "animatedWallpapers"
    /** Read by the drawing, so flipping the switch takes effect at once. */
    val enabled = mutableStateOf(true)
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        enabled.value = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)
    }

    fun setEnabled(c: Context, on: Boolean) {
        loaded = true
        enabled.value = on
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, on).apply()
    }
}
