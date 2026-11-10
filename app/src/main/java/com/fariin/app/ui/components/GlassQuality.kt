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

    /**
     * The liquid glass (lens refraction, from the backdrop library) only on a high-end phone with
     * Android 13+. Its shader cost 17 ms of drawing per frame on the Pixel as a mid-range phone while
     * the tab bar moved (2026-11-10), some 60 ms on one; mid-range phones get the frosted glass.
     */
    fun liquid(glass: Boolean): Boolean = glass && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        PowerSaving.state.value.deviceClass == PerfClass.HIGH
}
