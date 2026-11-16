package com.fariin.app.data

/**
 * The size a chat's photo wallpaper is kept at in memory. The library keeps the picture at up to
 * 2560 on its long side (for the group upload and the settings pages), and the chat used that copy:
 * a 4:3 photo was a 2560 x 1920 bitmap, 19.7 MB, on a 720 x 1600 screen. On the low-end emulator
 * (Android 8.1) that filled most of the GPU's picture cache, so the GIF stills and avatars were sent
 * to the GPU again on every frame (2026-11-15).
 *
 * The chat draws the picture to fill the screen (centred across, top-anchored), so a copy that just
 * covers the screen looks the same. Every phone gets that copy; a LOW phone also drops the sides
 * that the fill cuts off anyway.
 */
object WallpaperFit {
    /** The plan for one picture: decode with [sample], scale to [w] x [h], keep [cropW] across (centred). */
    data class Plan(val sample: Int, val w: Int, val h: Int, val cropW: Int)

    /** The largest power-of-two decode step that still leaves the picture covering the screen. */
    fun sample(srcW: Int, srcH: Int, screenW: Int, screenH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= screenW && srcH / (s * 2) >= screenH) s *= 2
        return s
    }

    /**
     * For a picture decoded at [w] x [h]: the size that covers the screen, never larger than it is
     * (no upscaling), and how much of its width to keep when [cropSides].
     */
    fun plan(srcW: Int, srcH: Int, screenW: Int, screenH: Int, cropSides: Boolean): Plan? {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return null
        val sample = sample(srcW, srcH, screenW, screenH)
        val w = srcW / sample; val h = srcH / sample
        val s = minOf(1f, maxOf(screenW.toFloat() / w, screenH.toFloat() / h))
        val outW = Math.round(w * s).coerceAtLeast(1)
        val outH = Math.round(h * s).coerceAtLeast(1)
        val cropW = if (cropSides) minOf(outW, screenW) else outW
        return Plan(sample, outW, outH, cropW)
    }
}
