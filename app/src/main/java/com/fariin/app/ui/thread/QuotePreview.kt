package com.fariin.app.ui.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fariin.app.R
import com.fariin.app.data.QuoteCard
import com.fariin.app.data.QuoteRules
import com.fariin.app.data.QuoteStore
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.components.Smooth
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/** A quoted post that has a picture: its text may be hidden (iOS 2026-11-21). A text-only post has no preview. */
internal fun quoteCanHide(card: QuoteCard): Boolean =
    card.state == QuoteCard.State.OK && card.kind in setOf("photo", "video", "album") && QuoteRules.image(card) != null

/** The kind's word for the bar while the text is hidden: "Photo", "Video", "Album". */
internal fun quoteKindWord(card: QuoteCard): String = when (card.kind) { "video" -> "Video"; "album" -> "Album"; else -> "Photo" }

/**
 * THE QUOTE PREVIEW (iOS QuotePreviewOverlay, 2026-11-21): a tap on the quote bar shows the post as
 * it will be sent, my words (a faint "Your words appear here" when empty) and the quote, in a card
 * resting at the bottom of the chat. It is not a dialog: nothing takes the keyboard, so the keys stay
 * up and the card sits just above them. The hide button (the owner's ic_quote_hide icon, filled with
 * the action colour while the text is hidden) sends the post's picture as mine with the channel on a
 * chip (`quote.mediaOnly`); X, Done and a tap on the dimmed chat close it and keep the choice.
 */
@Composable
internal fun QuotePreview(draft: QuoteStore.Draft, words: String, hidden: Boolean, bubbleFill: Color,
                          onToggle: (Boolean) -> Unit, onClose: () -> Unit) {
    val c = Fariin.colors
    // Over the whole window, which the keyboard has already shortened: the card's bottom is the keys' top.
    val whole = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                                           popupContentSize: IntSize): IntOffset = IntOffset(0, 0)
        }
    }
    val inAnim = remember { Animatable(if (Smooth.on) 0f else 1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { inAnim.animateTo(1f, Smooth.soft()) }
    fun close() {
        if (!Smooth.on) { onClose(); return }
        scope.launch { inAnim.animateTo(0f, Smooth.fadeSpec()); onClose() }
    }
    Popup(popupPositionProvider = whole, onDismissRequest = { close() },
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = false)) {
        androidx.activity.compose.BackHandler { close() }
        Box(Modifier.fillMaxSize()) {
            // The chat dims behind it; a tap there closes it.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = inAnim.value }.background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                .graphicsLayer {
                    alpha = inAnim.value
                    translationY = (1f - inAnim.value) * 60.dp.toPx()
                    val s = 0.94f + 0.06f * inAnim.value; scaleX = s; scaleY = s
                }
                .clip(RoundedCornerShape(32.dp)).background(c.card).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(c.label.copy(alpha = 0.08f)).clickable { close() },
                        contentAlignment = Alignment.Center) {
                        Icon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close", tint = c.label, modifier = Modifier.size(15.dp))
                    }
                    Text(if (hidden) "Quote text hidden" else "Your followers will see the quote like this",
                        fontSize = 13.sp, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    if (quoteCanHide(draft.card)) {
                        val fill = if (hidden) c.accent else c.label.copy(alpha = 0.08f)
                        Box(Modifier.size(38.dp).clip(CircleShape).background(fill).clickable { onToggle(!hidden) }
                            .semantics { contentDescription = if (hidden) "Show quote text" else "Hide quote text" },
                            contentAlignment = Alignment.Center) {
                            Icon(ImageVector.cachedVector(R.drawable.ic_quote_hide), null,
                                tint = if (hidden) c.onAccent else c.label, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // My bubble as it will go.
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), contentAlignment = Alignment.CenterEnd) {
                    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(bubbleFill).padding(8.dp)
                        .then(com.fariin.app.ui.components.SmoothSizeModifier)) {
                        val ink = Color.White
                        com.fariin.app.ui.components.Swap(hidden, label = "quotePreview") { h ->
                            Column {
                                if (h) PreviewPicture(draft, ink)
                                Text(words.ifEmpty { "Your words appear here" }, fontSize = 16.sp,
                                    color = if (words.isEmpty()) ink.copy(alpha = 0.55f) else ink,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                                if (!h) PreviewCard(draft, ink)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(50.dp).clip(CircleShape).background(c.accent)
                    .clickable { close() }, contentAlignment = Alignment.Center) {
                    Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                }
            }
        }
    }
}

/** The quoted post's picture as my own, the channel on a chip (what readers see with the text hidden). */
@Composable
private fun PreviewPicture(d: QuoteStore.Draft, ink: Color) {
    val card = d.card
    val local = QuoteStore.picture(QuoteRules.pictureKey(d))
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = 0.1f))) {
        coil3.compose.AsyncImage(rememberQuotePicture(card, local ?: QuoteRules.image(card)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
            .padding(start = 3.dp, end = 9.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(card.photoUrl.ifEmpty { null }, 20.dp, squircle = true, kind = AvatarKind.CHANNEL)
            Spacer(Modifier.width(6.dp))
            Text(QuoteRules.heading(card), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
}

/** The quote card under my words (its text shown). */
@Composable
private fun PreviewCard(d: QuoteStore.Draft, ink: Color) {
    val card = d.card
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = 0.12f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(QuoteRules.heading(card), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(QuoteRules.body(card), fontSize = 14.sp, color = ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (QuoteRules.image(card) != null) QuoteThumb(card, QuoteRules.pictureKey(d))
    }
}
