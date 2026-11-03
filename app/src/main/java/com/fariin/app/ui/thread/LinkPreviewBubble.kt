package com.fariin.app.ui.thread

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.StickerGifRules
import com.fariin.app.media.AutoDownload
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The link card a message carries (iOS LinkPreviewBubbleView, `.article` shape): drawn from what
 * the SENDER fetched and sealed, so this phone never contacts the site. Full bubble width, corners
 * 12, the bubble's tint at 10% behind it; a 170 picture on top when there is one, then the title
 * (13 semibold, 2 lines), the description (12, 2 lines, 75%) and the host (11, 55%), 10 in.
 * Tap opens the link.
 */
@Composable
internal fun LinkPreviewCard(lp: Message.LinkPreview, isMe: Boolean) {
    val c = Fariin.colors
    val ink = if (isMe) Color.White else c.label
    val tint = if (isMe) Color.White else c.blue
    val uri = LocalUriHandler.current
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.10f)).clickable {
                // My own username link opens My Profile (iOS openProfileHandle); any other link as before.
                val h = com.fariin.app.data.QrRules.handleFrom(lp.url)
                if (h == null) { runCatching { uri.openUri(lp.url) }; return@clickable }
                scope.launch {
                    if (usernameIsMine(h)) nav.myProfile() else runCatching { uri.openUri(lp.url) }
                }
            },
    ) {
        if (lp.imageUrl != null) {
            Box(Modifier.fillMaxWidth().height(170.dp).background(c.secondary.copy(alpha = 0.15f))) {
                if (lp.imageEnc != null) {
                    val got = rememberGatedFile(lp.imageUrl, lp.imageEnc, AutoDownload.Kind.PHOTOS)
                    val px = with(LocalDensity.current) { 170.dp.roundToPx() }
                    rememberBitmap(got.file, px)?.let {
                        Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                } else {
                    coil3.compose.AsyncImage(lp.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (lp.title.isNotEmpty()) {
                Text(lp.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                Spacer(Modifier.height(2.dp))
            }
            if (lp.desc.isNotEmpty()) {
                Text(lp.desc, fontSize = 12.sp, color = ink.copy(alpha = 0.75f), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                Spacer(Modifier.height(2.dp))
            }
            Text(StickerGifRules.cardHost(lp.url), fontSize = 11.sp, color = ink.copy(alpha = 0.55f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Whether `usernames/{h}` is mine (a released name is nobody's). False when it cannot be read. */
private suspend fun usernameIsMine(h: String): Boolean {
    val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return false
    val key = com.fariin.app.data.Usernames.lookupKey(h) ?: return false
    return runCatching {
        val d = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("usernames").document(key).get().await()
        d.getString("uid") == me && d.get("releasedAt") == null
    }.getOrDefault(false)
}
