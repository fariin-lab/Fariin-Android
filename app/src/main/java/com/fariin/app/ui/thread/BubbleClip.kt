package com.fariin.app.ui.thread

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * Where a bubble is clipped to its run shape.
 *
 * A run's bubble has mixed corners (18 on the outside, 6 where it joins the next one). Before
 * Android 11 a layer cannot clip to that outline itself, so Compose clips it with a path in the
 * PARENT's drawing instead. When a new message joins the run, the bubble above it changes corners
 * (its bottom corner goes from 18 to 6) and only the bubble's own layer is updated; the parent's
 * old clip stayed, and the whole bubble drew nothing from then on: a blank gap of the right height
 * until the chat was opened again (low-end emulator, Android 8.1, 2026-11-15). Every phone on
 * Android 10 or older had it, whatever its speed class.
 *
 * There the clip is drawn by the bubble itself, with a path made from its current shape and size,
 * so a corner change redraws it. Android 11 and newer keep the layer clip (one step on the GPU).
 */
internal object BubbleClipRules {
    /** The first Android version whose layers clip to any outline (API 30). */
    const val LAYER_CLIPS_ANY_OUTLINE = 30

    /** True when the bubble must clip in its own drawing (Android 10 and older). */
    fun clipInDraw(sdk: Int): Boolean = sdk < LAYER_CLIPS_ANY_OUTLINE
}

/** Clips a bubble to [shape]; see [BubbleClipRules]. */
internal fun Modifier.bubbleClip(shape: Shape): Modifier =
    if (!BubbleClipRules.clipInDraw(Build.VERSION.SDK_INT)) clip(shape)
    else drawWithCache {
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawWithContent { clipPath(outline) { this@onDrawWithContent.drawContent() } }
    }
