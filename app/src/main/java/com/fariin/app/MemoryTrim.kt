package com.fariin.app

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One place that gives memory back (cheap phones, 2 GB): when the app leaves the screen
 * (TRIM_MEMORY_UI_HIDDEN) or the phone asks for memory at a higher level, every in-memory cache
 * that registered here is emptied, and the picture loader's memory cache with them. Nothing on
 * disk is touched; a cache simply fills again on the next use.
 *
 * A cache registers once, by name ([register]); a second register under the same name replaces the
 * first. Code that keeps its own pictures (the sticker code, for one) can adopt it later with
 * `MemoryTrim.register("stickers") { cache.evictAll() }`.
 *
 * Also holds the phone's per-app memory class, so a fixed-size cache can be scaled to it ([scaled]).
 */
object MemoryTrim {
    private class Hook(val name: String, val run: (level: Int) -> Unit)
    private val hooks = CopyOnWriteArrayList<Hook>()

    /** The normal per-app heap in MB (ActivityManager.memoryClass), set at app start. */
    @Volatile var memoryClassMb: Int = REFERENCE_MB
        private set

    fun init(c: android.content.Context) {
        memoryClassMb = runCatching {
            (c.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        }.getOrNull()?.takeIf { it > 0 } ?: REFERENCE_MB
    }

    /** Adds (or replaces) the hook called with the trim level when memory should be given back. */
    fun register(name: String, hook: (level: Int) -> Unit) {
        synchronized(hooks) {
            hooks.removeAll { it.name == name }
            hooks.add(Hook(name, hook))
        }
    }

    fun unregister(name: String) { synchronized(hooks) { hooks.removeAll { it.name == name } } }

    /** Names registered now (tests and logs). */
    fun names(): List<String> = hooks.map { it.name }

    /** Runs every hook when [level] asks for it; one failing hook never stops the others. */
    fun trim(level: Int) {
        if (!shouldClear(level)) return
        for (h in hooks) runCatching { h.run(level) }
    }

    /** UI_HIDDEN (20) and above: the app is out of sight or the phone is short. Below it the app is
     *  on screen and the caches are what keep it smooth. */
    fun shouldClear(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    /** [baseBytes] is right for a 256 MB heap; a smaller heap gets its share, never under a quarter. */
    fun scaled(baseBytes: Int, memoryClass: Int = memoryClassMb): Int = scaledBytes(baseBytes, memoryClass)

    fun scaledBytes(baseBytes: Int, memoryClass: Int): Int =
        (baseBytes.toLong() * memoryClass / REFERENCE_MB).coerceIn(baseBytes / 4L, baseBytes.toLong()).toInt()

    private const val REFERENCE_MB = 256

    /** Registered by FariinApp. */
    val callbacks: ComponentCallbacks2 = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = trim(level)
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
