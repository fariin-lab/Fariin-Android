package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.fariin.app.ui.theme.Fariin

/**
 * A person's or group's picture. No photo (or one this viewer may not see) is the one grey
 * person silhouette, never initials or a coloured letter (owner, 2026-09-16; iOS
 * AvatarPalette.placeholderDisc: secondarySystemFill disc, white glyph at 0.52 of the side).
 * A channel is drawn as a rounded square, as on iOS (`avatarSquircle`).
 */
@Composable
fun Avatar(photoUrl: String?, size: Dp, modifier: Modifier = Modifier, squircle: Boolean = false) {
    val shape = if (squircle) RoundedCornerShape(size * 0.3f) else CircleShape
    var failed by remember(photoUrl) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(shape).background(Fariin.colors.placeholderFill),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl.isNullOrEmpty() || failed) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = Color.White,
                // Material Person fills 16 of its 24 units, so 0.78 gives the iOS glyph at 0.52.
                modifier = Modifier.size(size * 0.78f),
            )
        } else {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                onError = { failed = true },
            )
        }
    }
}

