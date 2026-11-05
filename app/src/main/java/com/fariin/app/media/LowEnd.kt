package com.fariin.app.media

import android.app.ActivityManager
import android.content.Context

/**
 * Cheap phones: what changes on a low-RAM device (Android Go, about 1 GB) so pictures never push
 * the app out of memory. Pure rules are kept apart for the unit tests.
 */
object LowEnd {
    fun isLowRam(c: Context): Boolean = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice || am.memoryClass <= 128
    }.getOrDefault(false)

    /** The long-press menu's blur is left out (tint only) on low-RAM phones and on any phone under
     *  about 4 GB: blur is GPU work every frame the menu moves (measured, MessageMenuOverlay). */
    fun skipBlur(c: Context): Boolean = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (isLowRam(c)) return@runCatching true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        info.totalMem < 3_500L * 1024 * 1024   // phones sold as "4 GB" report about 3.6-3.8 GB
    }.getOrDefault(true)

    /** Share of the app's memory class the picture cache may hold. */
    fun memoryCachePercent(lowRam: Boolean): Double = if (lowRam) 0.15 else 0.25

    /** Largest side a full-screen photo is decoded at: the screen's long side, at most 2048 (1440 on low RAM). */
    fun viewerSide(screenLongPx: Int, lowRam: Boolean): Int = minOf(screenLongPx.coerceAtLeast(480), if (lowRam) 1440 else 2048)
}
