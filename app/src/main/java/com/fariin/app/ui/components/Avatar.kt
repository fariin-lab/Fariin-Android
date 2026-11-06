package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
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
 * A channel is drawn as a rounded square, as on iOS (`avatarSquircle`). A group with no photo shows
 * a group glyph and a channel a megaphone instead of the person ([kind]); people keep the person.
 */
@Composable
fun Avatar(photoUrl: String?, size: Dp, modifier: Modifier = Modifier, squircle: Boolean = false,
           kind: AvatarKind = AvatarKind.PERSON) {
    val shape = if (squircle) RoundedCornerShape(size * 0.3f) else CircleShape
    var failed by remember(photoUrl) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(shape).background(Fariin.colors.placeholderFill),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl.isNullOrEmpty() || failed) {
            Icon(
                when (kind) {
                    AvatarKind.PERSON -> Icons.Filled.Person
                    AvatarKind.GROUP -> Icons.Filled.Groups
                    AvatarKind.CHANNEL -> Icons.Filled.Campaign
                },
                contentDescription = null,
                tint = Color.White,
                // Material Person fills 16 of its 24 units, so 0.78 gives the iOS glyph at 0.52; the
                // group and megaphone glyphs are wider, so they sit a little smaller.
                modifier = Modifier.size(size * if (kind == AvatarKind.PERSON) 0.78f else 0.6f),
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


/** What an empty [Avatar] stands for: a person, a group or a channel. */
enum class AvatarKind {
    PERSON, GROUP, CHANNEL;

    companion object {
        fun of(isGroup: Boolean, isChannel: Boolean): AvatarKind = when {
            isChannel -> CHANNEL
            isGroup -> GROUP
            else -> PERSON
        }
    }
}
