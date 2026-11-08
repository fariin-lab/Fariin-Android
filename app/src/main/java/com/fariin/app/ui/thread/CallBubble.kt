package com.fariin.app.ui.thread

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.StickerGifRules
import com.fariin.app.data.StickerGifRules.CallIcon
import com.fariin.app.data.ThreadLogic
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.R

/**
 * A call record in the chat (iOS CallBubbleView + MessageRowLayout.call): 60 tall, corners 16, a
 * 34 disc with the call's glyph 14 in, 11 to a bold status (16) over a muted detail (13), at least
 * 150 wide and at most 70% of the row. Mine takes my bubble colour with white words; a missed call
 * I received is red. Tap = "Call <name>?" first (never dials on a stray tap); a live row offers nothing.
 * In a group, someone else's call carries their face beside it.
 */
@Composable
internal fun CallBubble(
    m: Message, me: String, is24h: Boolean, myFill: Color, theirFill: Color,
    peerUid: String?, title: String, photoUrl: String?,
    callerName: String? = null, callerPhoto: String? = null,
) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val now = System.currentTimeMillis().toDouble()
    val look = StickerGifRules.callLook(m, me, (now - m.createdAtMillis) / 1000.0, ThreadLogic.timeLabel(m.createdAtMillis, is24h))
    var asking by remember { mutableStateOf(false) }
    val accent = c.blue
    val red = c.red
    val iconTint = if (look.missedIncoming) red else if (look.mine) Color.White else accent
    val disc = if (look.mine) Color.White.copy(alpha = 0.22f) else (if (look.missedIncoming) red else accent).copy(alpha = 0.14f)
    val icon = when (look.icon) {
        CallIcon.VIDEO -> ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video)
        CallIcon.VIDEO_MISSED -> ImageVector.vectorResource(R.drawable.ic_call_video_off)
        CallIcon.PHONE_IN -> ImageVector.vectorResource(R.drawable.ic_sf_arrow_down_left)
        CallIcon.PHONE_OUT -> ImageVector.vectorResource(R.drawable.ic_sf_arrow_up_right)
    }
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.7f).dp
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (look.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (callerName != null) {
            Avatar(callerPhoto, 34.dp)
            Spacer(Modifier.width(6.dp))
        }
        Row(
            Modifier.widthIn(min = 150.dp, max = maxW).height(60.dp).clip(shape)
                .background(if (look.mine) myFill else theirFill)
                .let { if (!look.mine && c.dark) it.border(0.5.dp, c.secondary.copy(alpha = 0.25f), shape) else it }
                .clickable(enabled = !look.live && peerUid != null) { asking = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.widthIn(min = 122.dp)) {
                Text(look.status, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (look.mine) Color.White else c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(look.detail, fontSize = 13.sp, color = if (look.mine) Color.White.copy(alpha = 0.75f) else c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (asking && peerUid != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(StickerGifRules.callBackTitle(look.video)) },
            text = { Text(StickerGifRules.callBackQuestion(look.video, title)) },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    com.fariin.app.ui.call.CallActivity.startDial(ctx, peerUid, title, photoUrl, video = look.video)
                }) { Text("Call") }
            },
        )
    }
}
