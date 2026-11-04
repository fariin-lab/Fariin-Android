package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatsNow
import com.fariin.app.media.VoicePlayer
import com.fariin.app.ui.theme.Fariin

/**
 * A voice message still open after its chat was left (iOS VoiceNoteBar): pause or play, who it is
 * from over "Voice Message", the chat's speed, and close. The progress runs along the bottom edge.
 * Shown only away from the chat it belongs to; a tap on it goes back there.
 */
@Composable
fun VoiceNoteBar(here: String?, me: String, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val s by VoicePlayer.state.collectAsState()
    val cid = s.cid ?: return
    if (s.id == null || cid == here) return
    val c = Fariin.colors
    val conv = ChatsNow.find(cid)
    val name = when {
        s.author == me -> "You"
        conv == null -> "Voice message"
        else -> conv.names[s.author]?.takeIf { it.isNotEmpty() } ?: conv.displayName(me)
    }
    val rate = VoicePlayer.rate(cid)
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
        .background(c.card).clickable { onOpen(cid) }) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { VoicePlayer.playPause() }, contentAlignment = Alignment.Center) {
                Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) "Pause" else "Play",
                    tint = c.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Voice Message", fontSize = 11.sp, color = c.secondary, maxLines = 1)
            }
            Text(com.fariin.app.media.MediaRules.rateLabel(rate), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { VoicePlayer.cycleRate(cid) }.padding(horizontal = 8.dp, vertical = 6.dp))
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { VoicePlayer.stop() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Close", tint = c.secondary, modifier = Modifier.size(18.dp))
            }
        }
        val f = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(2.dp).background(c.accent))
    }
}
