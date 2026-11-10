package com.fariin.app.ui.components

import com.fariin.app.perf.PerfClass
import com.fariin.app.perf.PowerSaving

/**
 * How finely the frosted glass is computed (Haze's input scale). On a mid-range phone (owner
 * 2026-11-10: a friend's cheap phone lagged; on the Pixel the blur was a quarter of every frame)
 * the glass is blurred from a smaller copy of what is under it: the same frosted look, since a blur
 * throws the fine detail away anyway, at a fraction of the graphics work. Fast phones keep [base].
 */
object GlassQuality {
    fun scale(base: Float): Float =
        if (PowerSaving.state.value.deviceClass == PerfClass.HIGH) base else base * 0.6f
}
