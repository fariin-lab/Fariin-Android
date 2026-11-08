package com.fariin.app.ui.thread

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.LinkPreviews
import com.fariin.app.ui.components.SkeletonBlock
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * The link card above the composer while typing (iOS ComposerBannerView `.link`): picture 44
 * (corners 7), title 12 semibold, description 11 secondary, host 11 tertiary, the round X on the
 * right, a hairline underneath; 14 in, 12 from the right, 8 above and below. While the page loads,
 * skeleton blocks stand where the picture, title, description and host will be.
 *
 * Typing waits 0.4 s for the address to settle; a paste (the text jumps by more than one
 * character) fetches at once. The X closes the card for that link; the message then goes
 * without one. Nothing is fetched with Link Previews off, while editing, or where a card would be
 * refused ([allowed] false: the knock, links turned off for me in a group).
 */
@Composable
internal fun LinkDraftBar(cid: String, me: String, text: String, allowed: Boolean) {
    val ctx = LocalContext.current
    remember { LinkPreviews.init(ctx) }
    val url = remember(text) { LinkPreviews.firstUrl(text) }
    val lastLen = remember { intArrayOf(text.length) }
    val pasted = text.length - lastLen[0] > 1
    SideEffect { lastLen[0] = text.length }
    var shown by remember { mutableStateOf<LinkPreviews.Draft?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var closed by remember { mutableStateOf(0) }
    val ok = allowed && LinkPreviews.allowedIn(CryptoHub.listedConversation(cid), me)
    LaunchedEffect(url, ok, closed) {
        if (url == null) { shown = null; loading = null; LinkPreviews.suppress(cid, null); return@LaunchedEffect }
        if (!ok || !LinkPreviews.enabled() || url == LinkPreviews.suppressed(cid)) { shown = null; loading = null; return@LaunchedEffect }
        if (shown?.url == url) return@LaunchedEffect
        shown = null
        LinkPreviews.cached(url)?.let { shown = it; return@LaunchedEffect }
        if (!pasted) delay(400)   // let typing settle
        loading = url
        val d = LinkPreviews.draft(url)
        loading = null
        shown = d   // null: no card for this page, the skeleton just goes
    }
    val card = shown
    AnimatedVisibility(card != null || loading != null,
        enter = expandVertically(tween(200)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(200))) {
        LinkDraftRow(card, onClose = {
            LinkPreviews.suppress(cid, card?.url ?: loading)
            shown = null
            loading = null
            closed++
        })
    }
}

@Composable
private fun LinkDraftRow(d: LinkPreviews.Draft?, onClose: () -> Unit) {
    val c = Fariin.colors
    val thumb = remember(d) { d?.jpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (d == null) {
                SkeletonBlock(44.dp, 44.dp, radius = 7.dp)
                Spacer(Modifier.width(10.dp))
                BoxWithConstraints(Modifier.weight(1f)) {
                    val w = maxWidth
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonBlock(w * 0.62f, 10.dp, radius = 4.dp)
                        SkeletonBlock(w * 0.9f, 8.dp, radius = 4.dp)
                        SkeletonBlock(w * 0.38f, 7.dp, radius = 4.dp)
                    }
                }
            } else {
                if (thumb != null) {
                    Image(thumb, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)).background(c.placeholderFill))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.desc.isNotEmpty()) Text(d.desc, fontSize = 11.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.host, fontSize = 11.sp, color = c.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark_circle_fill), contentDescription = "Dismiss", tint = c.secondary, modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(0.5.dp).background(c.separator))
    }
}
