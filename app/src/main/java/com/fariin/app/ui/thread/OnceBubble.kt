package com.fariin.app.ui.thread

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.media.ViewedOnce
import com.fariin.app.ui.theme.Fariin

/** The accent of an incoming view-once still waiting to be opened (iOS systemGreen). */
internal val OnceGreen = Color(0xFF34C759)

/**
 * The view-once mark: a dashed ring, with a 1 inside until it is opened. Drawn in one Canvas
 * pass; nothing in it moves, so a scrolling list only redraws it.
 */
@Composable
internal fun DashedOnceRing(color: Color, showOne: Boolean, side: Dp = 22.dp, stroke: Dp = 1.5.dp,
    digitSize: TextUnit = 11.sp) {
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(side)) {
            val w = stroke.toPx()
            val r = (size.minDimension - w) / 2f
            // Ten even dashes round the ring, so the joint never shows a short one.
            val seg = (2f * Math.PI.toFloat() * r) / 10f
            drawCircle(color, radius = r,
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(seg * 0.62f, seg * 0.38f))))
        }
        if (showOne) Text("1", color = color, fontSize = digitSize, fontWeight = FontWeight.Bold)
    }
}

/**
 * The view-once bubble body (photo and voice alike). Unopened it is the bigger card: a 40dp ring
 * with the 1, "Photo"/"Voice" and a hint line with the time and ticks at its right. Opened it is
 * one short line: the 24dp empty ring, "Opened" in italic, the time. The bubble around it pads
 * 14 x 7; the extra inner padding here brings the card to about 18 x 14 and 190 wide.
 */
@Composable
internal fun OnceBubbleRow(kind: OnceRules.Kind, opened: Boolean, isMe: Boolean, sending: Boolean,
    onOpen: (() -> Unit)?, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val styled = LocalStyledInk.current
    val ink = styled ?: if (isMe) Color.White else c.label
    val sub = styled?.copy(alpha = 0.7f) ?: if (isMe) Color.White.copy(alpha = 0.7f) else c.secondary
    val ring = when {
        OnceRules.ringIsAccent(isMe, opened) -> OnceGreen
        opened -> sub
        else -> ink
    }
    val tap = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
    if (opened) {
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            DashedOnceRing(ring, showOne = false, side = 24.dp)
            Spacer(Modifier.width(9.dp))
            Text(OnceRules.label(kind, true), fontSize = 17.sp, maxLines = 1, fontStyle = FontStyle.Italic, color = sub)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.align(Alignment.Bottom).padding(top = 6.dp)) { meta() }
        }
        return
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).then(tap)
            .widthIn(min = 162.dp).padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DashedOnceRing(ring, showOne = true, side = 40.dp, stroke = 2.dp, digitSize = 18.sp)
        Spacer(Modifier.width(14.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(OnceRules.label(kind, false), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = ink)
                if (sending) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(OnceRules.hint(kind, isMe), fontSize = 13.sp, maxLines = 1, color = sub)
                Spacer(Modifier.width(14.dp))
                meta()
            }
        }
    }
}

/**
 * A view-once photo. The receiver's tap opens [OneTimePhotoPage]; the moment the picture is in
 * hand it is spent on this phone and burned on the server, and the bubble reads "Opened". My own
 * one is never openable and turns "Opened" when the server burns it (`consumed`). [onView] is kept
 * for the call site; a view-once photo no longer goes through the shared media viewer.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun ViewOnceBody(m: Message, cid: String, isMe: Boolean, onView: (Viewing) -> Unit, meta: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val epoch by ViewedOnce.epoch.collectAsState()
    val opened = remember(m.id, epoch, m.consumed, isMe) {
        OnceRules.opened(isMe, m.consumed, ViewedOnce.contains(ctx, m.id))
    }
    val sending = m.sendState == Message.SendState.SENDING
    val canOpen = OnceRules.canOpen(isMe, opened, m.imageUrl != null, m.sendState != null)
    var open by remember(m.id) { mutableStateOf(false) }
    OnceBubbleRow(OnceRules.Kind.PHOTO, opened, isMe, sending, if (canOpen) ({ open = true }) else null, meta)
    if (open) OneTimePhotoPage(m, cid) { open = false }
}
