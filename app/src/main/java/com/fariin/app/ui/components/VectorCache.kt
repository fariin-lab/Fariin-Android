package com.fariin.app.ui.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource

/**
 * The app's drawn glyphs, read from their XML once per process and kept. ImageVector.vectorResource
 * parses the file again in every new composable that asks for it, so a chat's first frame parsed
 * one file per icon in every bubble it built (a call row's glyph alone was ~2 ms on the cheap test
 * phone, trace 2026-11-16), and so did every list row and page. A built ImageVector never changes,
 * so one copy serves every screen. Keyed by night mode too, for a drawable with a night variant.
 */
object VectorCache {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, ImageVector>()

    fun get(ctx: android.content.Context, @DrawableRes id: Int, uiMode: Int): ImageVector {
        val key = (id.toLong() shl 8) or (uiMode and Configuration.UI_MODE_NIGHT_MASK).toLong()
        return map[key] ?: ImageVector.vectorResource(ctx.theme, ctx.resources, id).also { map[key] = it }
    }
}

/** [ImageVector.vectorResource], parsed once per app run (see [VectorCache]). */
@Composable
fun ImageVector.Companion.cachedVector(@DrawableRes id: Int): ImageVector =
    VectorCache.get(LocalContext.current, id, LocalConfiguration.current.uiMode)
