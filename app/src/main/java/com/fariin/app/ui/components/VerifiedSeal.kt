package com.fariin.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * The verified badge: the seal in [tint] with a white tick on top. No white disc behind it: on a
 * dark page that disc showed as a glaring white ring (owner 2026-11-09). Light mode looks the same.
 */
@Composable
fun VerifiedSeal(tint: Color, modifier: Modifier = Modifier, label: String? = "Verified") {
    Box(modifier) {
        Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill), contentDescription = label, tint = tint, modifier = Modifier.matchParentSize())
        Icon(ImageVector.vectorResource(R.drawable.ic_verified_tick), contentDescription = null, tint = Color.White, modifier = Modifier.matchParentSize())
    }
}
