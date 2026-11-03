package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin

/**
 * The chat screen's floating controls (iOS glass: the back circle, the call capsule, the "+", the
 * message pill and the mic). Android has no system glass, so it is the card colour with the same
 * hairline rim and a soft shadow, which is what the glass reads as on a white chat.
 */
@Composable
fun Modifier.floating(shape: Shape = CircleShape, fill: Color? = null): Modifier {
    val c = Fariin.colors
    return this
        .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.10f))
        .clip(shape)
        .background(fill ?: c.card)
        // The rim is barely there, as the glass edge is: the text colour at 7% (dark in light, light in dark).
        .border(0.5.dp, c.label.copy(alpha = 0.07f), shape)
}
