package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/*
 * ANIMATED CHAT WALLPAPERS (iOS AnimatedWallpapers.swift, owner 2026-11-06). Each look is drawn live by
 * a shader (ui/components/AnimatedWallpaperShaders.kt), no pictures or video, except Doodles, which is
 * the app's own doodle sheet moved about (transforms only). Stored as "a:<id>" (ChatWallpaper.Animated).
 * Local for a 1:1 chat; in a group an admin can set one for everyone.
 *
 * The motion is a circle: the shader's phase goes 0..2 pi once per loop, so the last frame of a loop
 * is the first frame of the next and the clock never grows large.
 */

enum class AnimatedLook { LIQUID_METAL, WATER, LIQUID_GLOW, LIQUID_EDGE, AURORA, STARS, PARTICLES, DOODLES }

/**
 * One animated wallpaper. [period] is seconds for one full loop. [variant] picks within a look: Aurora's
 * colours (0 green, 1 purple, 2 pink), the particle kind (0 snow, 1 rain, 2 glowing dots), or which
 * patterned theme Doodles drifts over (0 the first patterned theme in ChatWallpapers.all).
 */
data class AnimatedWallpaper(val id: String, val name: String, val look: AnimatedLook, val period: Float, val variant: Int = 0) {
    /** iOS topIsDark: is the top of the screen dark in this mode? A moving ground has no still picture
     *  to measure, so it says so itself; the chat header's words turn light over a dark top. Aurora and
     *  Stars are a night sky in both modes; the rest follow the mode. */
    fun topIsDark(dark: Boolean): Boolean = look == AnimatedLook.AURORA || look == AnimatedLook.STARS || dark

    /** The patterned theme Doodles draws its ground from, or null for any other look. */
    val doodleTheme: WallpaperGradient? get() =
        if (look != AnimatedLook.DOODLES) null else ChatWallpapers.all.filter { it.pattern }.getOrNull(variant)

    /**
     * How much smaller than the screen the shader is drawn, then scaled up (iOS b20f7cdb): half for the
     * soft looks and the particles, Stars at full size so its small points stay sharp, half in a
     * picker tile. Owner 2026-11-06 "low quality": a quarter showed as soft steps on the light grounds.
     * Doodles is no shader, drawn at full size (1).
     */
    fun scale(preview: Boolean): Float = when {
        look == AnimatedLook.DOODLES -> 1f
        preview -> 2f
        look == AnimatedLook.STARS -> 1f
        else -> 2f   // the soft liquid, aurora and particle looks: half the screen, scaled up
    }

    /** Frames a second (iOS, owner 2026-11-06 "the big messengers never eat battery"): 15 in a chat,
     *  Rain 24 (its streaks need it), Doodles 12, a picker tile 12. */
    fun fps(preview: Boolean): Int = when {
        preview || look == AnimatedLook.DOODLES -> AnimatedWallpapers.TILE_FPS
        look == AnimatedLook.PARTICLES && variant == 1 -> AnimatedWallpapers.RAIN_FPS
        else -> AnimatedWallpapers.CHAT_FPS
    }
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
        // Stage two: space, particles, doodles. The particle loops are long because each layer falls
        // whole repeats of its rows per loop (see particleRows), and long loops keep that slow.
        AnimatedWallpaper("stars", "Stars", AnimatedLook.STARS, 60f),
        AnimatedWallpaper("snow", "Snow", AnimatedLook.PARTICLES, 160f, 0),
        AnimatedWallpaper("rain", "Rain", AnimatedLook.PARTICLES, 20f, 1),
        AnimatedWallpaper("glow-dots", "Glowing Dots", AnimatedLook.PARTICLES, 480f, 2),
        AnimatedWallpaper("doodles-1", "Doodles", AnimatedLook.DOODLES, 40f, 0),
        AnimatedWallpaper("doodles-2", "Doodles 2", AnimatedLook.DOODLES, 40f, 1),
        AnimatedWallpaper("doodles-3", "Doodles 3", AnimatedLook.DOODLES, 40f, 2),
    )

    fun find(id: String): AnimatedWallpaper? = all.firstOrNull { it.id == id }

    const val CHAT_FPS = 15
    const val RAIN_FPS = 24
    const val TILE_FPS = 12

    private const val TWO_PI = (2 * Math.PI).toFloat()

    /**
     * Whether a wallpaper moves: only the phone's own "Remove animations" stills it (iOS: Reduce Motion).
     * No app switch, and Battery Saver does not stop it (owner 2026-11-06). Being on screen and resumed
     * is the caller's part (the frame loop only runs then).
     */
    fun moves(systemAnimations: Boolean): Boolean = systemAnimations

    /** The phase after [seconds] more of a [period]-second loop, kept in 0..2 pi. */
    fun advance(phase: Float, seconds: Float, period: Float): Float {
        if (period <= 0f || seconds <= 0f) return phase
        val p = (phase + seconds / period * TWO_PI) % TWO_PI
        return if (p < 0f) p + TWO_PI else p
    }

    /** The longest step one frame may take. The clock only counts while the picture is moving, and a
     *  frame that comes late (a stall) moves on by this at most, so the picture never jumps. */
    const val MAX_STEP = 0.25f

    /** Seconds the loop moves on between two drawn frames [last] and [now] (nanos); 0 for the first. */
    fun step(last: Long, now: Long): Float =
        if (last == 0L || now <= last) 0f else ((now - last) / 1e9f).coerceAtMost(MAX_STEP)

    /** Whether a new frame is due at [now] (nanos) after the last drawn at [last], for [fps]. A little slack so 60 Hz vsync lands on 30. */
    fun frameDue(last: Long, now: Long, fps: Int): Boolean = last == 0L || now - last >= 1_000_000_000L / fps - 4_000_000L

    /**
     * Particles: the rows a particle's randomness is read from repeat every this many cells, and a
     * layer falls exactly rows x (1 + layer) cells per loop, so the last frame of a loop shows the same
     * particles as the first. More than a screen of cells, so the repeat never shows. [kind] 0 snow,
     * 1 rain, 2 glowing dots. The shader is built from these numbers.
     */
    fun particleRows(kind: Int): Int = when (kind) { 0 -> 24; 1 -> 48; else -> 16 }

    /** Cells a particle layer falls in one loop: whole repeats of its rows. */
    fun particleFall(kind: Int, layer: Int): Int = particleRows(kind) * (1 + layer)

    /** A cell's row folded into 0 until rows (the shader's cell.y - rows * floor(cell.y / rows)). */
    fun wrapRow(row: Float, rows: Int): Float = row - rows * kotlin.math.floor(row / rows)

    /** Particle layers drawn for an intensity: Low 1, Medium 2, High 3. */
    fun particleLayers(intensity: Int): Int = 1 + intensity.coerceIn(0, 2)

    /**
     * Doodles: the left edge of the soft light band, in widths of the screen, at [t] 0..1 through the
     * loop. From -1.4 (fully off the left, the band and its tilt included) to +1.6 (fully off the
     * right), so its jump back at the loop's end is never seen.
     */
    fun doodleBandX(t: Float): Float = t * 3f - 1.4f

    // Settings > Appearance > Animated Wallpapers. Off: every animated wallpaper is a still frame.

    private const val PREFS = "fariin_look"
    const val ENABLED_KEY = "animatedWallpapers"
    /** Snow, rain and glowing dots: 0 Low, 1 Medium (default), 2 High (iOS key). */
    const val INTENSITY_KEY = "animatedWallpapers.intensity"
    val INTENSITY_NAMES = listOf("Low", "Medium", "High")
    /** Read by the drawing, so flipping the switch takes effect at once. */
    val enabled = mutableStateOf(true)
    val intensity = mutableIntStateOf(1)
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // No switch and no amount any more (owner 2026-11-06): always on, particles at Medium. An old
        // saved "off" or "Low/High" is ignored.
        enabled.value = true
        intensity.intValue = 1
    }

    fun setEnabled(c: Context, on: Boolean) {
        load(c)
        enabled.value = on
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, on).apply()
    }

    fun setIntensity(c: Context, value: Int) {
        load(c)
        val v = value.coerceIn(0, 2)
        intensity.intValue = v
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(INTENSITY_KEY, v).apply()
    }
}
