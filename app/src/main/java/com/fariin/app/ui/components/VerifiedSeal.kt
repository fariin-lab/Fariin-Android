package com.fariin.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fariin.app.R

/**
 * The verified badge: the seal in [tint] with a white tick on top. No white disc behind it: on a
 * dark page that disc showed as a glaring white ring (owner 2026-11-09). Light mode looks the same.
 *
 * One node that paints both drawings (was two Icons: two layouts and two semantics nodes per badge,
 * on every chat row; the Oppo fling profile showed ~54 ms in it).
 */
@Composable
fun VerifiedSeal(tint: Color, modifier: Modifier = Modifier, label: String? = "Verified") {
    val seal = rememberVectorPainter(ImageVector.cachedVector(R.drawable.ic_sf_checkmark_seal_fill))
    val tick = rememberVectorPainter(ImageVector.cachedVector(R.drawable.ic_verified_tick))
    val sealTint = remember(tint) { ColorFilter.tint(tint) }
    Spacer(modifier
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .drawBehind {
            with(seal) { draw(size, colorFilter = sealTint) }
            with(tick) { draw(size, colorFilter = WhiteTint) }
        })
}

private val WhiteTint = ColorFilter.tint(Color.White)
