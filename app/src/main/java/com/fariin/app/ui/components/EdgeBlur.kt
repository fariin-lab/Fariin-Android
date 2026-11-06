package com.fariin.app.ui.components

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Easing
import dev.chrisbanes.haze.HazeProgressive

/**
 * The scroll-edge blur in stages, as iOS (owner's iPhone photo 2026-11-05: right under the bar the
 * rows are frosted, lower down only softened, then sharp). A real variable blur, not one blur faded
 * in and out: full strength over the top [solid] part of the strip, then the radius eases down to
 * nothing at its lower edge. Used by the Chats bar, Settings and the chat header.
 */
fun edgeStages(solid: Float): HazeProgressive.LinearGradient =
    HazeProgressive.verticalGradient(
        easing = Easing { t -> if (t <= solid) 0f else EaseInOutCubic.transform(((t - solid) / (1f - solid)).coerceIn(0f, 1f)) },
        startIntensity = 1f,
        endIntensity = 0f,
        preferPerformance = false,
    )

/**
 * The edge glass's strength. Owner 2026-11-05: "when you open a chat it must be there", as iOS. On
 * the screen's first moments (opening, its first layout) it follows at once, so a chat or tab opens
 * with its blur already on; after that it fades in and out over 220 ms as rows go under the bar.
 */
@androidx.compose.runtime.Composable
fun rememberEdgeAlpha(under: Boolean, startOn: Boolean = false): androidx.compose.runtime.State<Float> {
    // [startOn]: a chat opens at its newest message with the older ones under the header, but on the
    // very first frame its list is not measured yet and says "nothing under" (one sharp frame, seen
    // in a recording). With nothing really under it, glass over the plain page shows nothing anyway.
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (under || startOn) 1f else 0f) }
    val born = androidx.compose.runtime.remember { android.os.SystemClock.uptimeMillis() }
    androidx.compose.runtime.LaunchedEffect(under) {
        val target = if (under) 1f else 0f
        if (android.os.SystemClock.uptimeMillis() - born < 700) a.snapTo(target)
        else a.animateTo(target, androidx.compose.animation.core.tween(220))
    }
    return a.asState()
}
