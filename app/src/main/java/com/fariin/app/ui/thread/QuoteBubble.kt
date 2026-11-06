package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fariin.app.data.QuoteCard
import com.fariin.app.data.QuoteRules
import com.fariin.app.data.QuoteStore
import com.fariin.app.ui.theme.Fariin

/**
 * A quoted channel post in a bubble (iOS quoteBody, owner 2026-11-06): the article card filled from
 * the server's answer for this reader. Until the first answer lands it shows quietly as
 * "Channel post / Loading…", never broken, and never the raw id. A tap opens the post in its
 * channel when this reader may see it; otherwise a short notice says why not.
 */
@Composable
internal fun QuoteCardBubble(qid: String, isMe: Boolean) {
    val cards by QuoteStore.cards.collectAsState()
    // Every draw asks: a miss, or an answer older than two minutes, is fetched with the others on screen.
    SideEffect { QuoteStore.card(qid) }
    val card = cards[qid]
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    val image = QuoteRules.image(card)
    ArticleShape(QuoteRules.heading(card), QuoteRules.body(card), QuoteRules.footer(card), isMe,
        quiet = card == null,
        onClick = {
            val c = card ?: return@ArticleShape
            val notice = QuoteRules.tapNotice(c)
            if (notice != null) { com.fariin.app.ui.components.Snack.show(notice); return@ArticleShape }
            if (c.cid.isEmpty()) return@ArticleShape
            // Opened as a thread: a follower sees the channel, a public visitor its posts from outside.
            if (c.mid.isNotEmpty()) QuoteStore.pendingJump.value = QuoteStore.Jump(c.cid, c.mid)
            channelNav.open(c.cid)
        },
        image = image?.let { url -> {
            Box(Modifier.fillMaxWidth().height(170.dp).background(Fariin.colors.secondary.copy(alpha = 0.15f))) {
                coil3.compose.AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        } },
    )
}

/** The quote's picture in the bar above the composer (36 square, like a reply's). */
@Composable
internal fun QuoteThumb(card: QuoteCard, side: Dp = 36.dp) {
    val url = QuoteRules.image(card) ?: card.photoUrl.ifEmpty { null } ?: return
    coil3.compose.AsyncImage(url, null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(Fariin.colors.secondary.copy(alpha = 0.15f)))
}
