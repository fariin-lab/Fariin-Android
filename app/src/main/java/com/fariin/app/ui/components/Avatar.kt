package com.fariin.app.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
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
           kind: AvatarKind = AvatarKind.PERSON,
           /**
            * What was really drawn (iOS AvatarView.onPhotoResolved): true with the bitmap once a photo
            * is on screen, false for the silhouette. A profile's photo viewer opens only on a photo.
            */
           onResolved: ((Boolean, androidx.compose.ui.graphics.ImageBitmap?) -> Unit)? = null) {
    val shape = if (squircle) RoundedCornerShape(size * 0.3f) else CircleShape
    var failed by remember(photoUrl) { mutableStateOf(false) }
    if (onResolved != null && (photoUrl.isNullOrEmpty() || failed)) {
        val report by androidx.compose.runtime.rememberUpdatedState(onResolved)
        androidx.compose.runtime.LaunchedEffect(photoUrl, failed) { report(false, null) }
    }
    Box(
        modifier.size(size).clip(shape).background(Fariin.colors.placeholderFill),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl.isNullOrEmpty() || failed) {
            Icon(
                when (kind) {
                    AvatarKind.PERSON -> androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_person_fill))
                    AvatarKind.GROUP -> androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_person2_fill))
                    // The iPhone's megaphone, not the one with sound lines (owner 2026-11-07).
                    AvatarKind.CHANNEL -> androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_channel_megaphone)
                },
                contentDescription = null,
                tint = Color.White,
                // Material Person fills 16 of its 24 units, so 0.78 gives the iOS glyph at 0.52; the
                // group and megaphone glyphs are wider, so they sit a little smaller.
                modifier = Modifier.size(size * if (kind == AvatarKind.PERSON) 0.78f else 0.6f),
            )
        } else {
            // A big face (a profile, an info page) first draws the small copy the chat list already
            // holds in memory, at once; the sharp one lands on it. Asked at its own size alone it
            // could not use that smaller copy and came a few frames into the page's slide
            // (owner's Pixel 2026-11-10, call info page).
            if (size > BIG_FACE) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val px: Int = with(androidx.compose.ui.platform.LocalDensity.current) { SMALL_FACE.roundToPx() }
                val small: coil3.request.ImageRequest = remember(photoUrl, px) {
                    coil3.request.ImageRequest.Builder(ctx).data(photoUrl).size(coil3.size.Size(px, px)).build()
                }
                AsyncImage(model = small, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            }
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                onError = { failed = true },
                onSuccess = if (onResolved == null) null else ({ s ->
                    onResolved(true, (s.result.image as? coil3.BitmapImage)?.bitmap?.asImageBitmap())
                }),
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

/** Faces bigger than this draw the list's small copy first (see Avatar). */
private val BIG_FACE = androidx.compose.ui.unit.Dp(64f)
/** The size asked for that small copy: no bigger than any list row's face, so memory has it. */
private val SMALL_FACE = androidx.compose.ui.unit.Dp(40f)
