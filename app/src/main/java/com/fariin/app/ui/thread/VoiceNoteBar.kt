package com.fariin.app.ui.thread

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.R

/**
 * A voice message still open after its chat was left (iOS VoiceNoteBar): pause or play, who it is
 * from over "Voice Message", the chat's speed, and close. The progress runs along the bottom edge.
 * Shown only away from the chat it belongs to; a tap on it goes back there, at the note.
 */
@Composable
fun VoiceNoteBar(here: String?, me: String, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val live by VoicePlayer.state.collectAsState()
    // It fades and opens with its height (the list's or chat's space following), and closes the same
    // way, still showing the note while it leaves (iOS pops 117).
    // Not for a note the person paused on its bubble before leaving: they were done with it (iOS barVisible).
    // Never over a locked app (the lock page is in front; this keeps the name off it while it comes up).
    val locked = com.fariin.app.security.AppLock.enabled() && !com.fariin.app.security.AppLock.unlocked
    val showing = live.cid != null && live.id != null && live.cid != here && !(live.pausedByUser && !live.playing) && !locked
    val held = androidx.compose.runtime.remember { arrayOf(live) }
    if (showing) held[0] = live
    com.fariin.app.ui.components.Appear(showing, modifier) { VoiceNoteBarBody(held[0], me, Modifier, onOpen) }
}

@Composable
private fun VoiceNoteBarBody(s: VoicePlayer.State, me: String, modifier: Modifier, onOpen: (String) -> Unit) {
    val cid = s.cid ?: return
    val c = Fariin.colors
    val conv = ChatsNow.find(cid)
    // iOS PlayerAccessory: the chat's name over "Voice message · <sender>" ("You" for mine).
    val chatName = conv?.displayName(me).orEmpty()
    val name = chatName.ifEmpty { "Voice message" }
    val sender = com.fariin.app.data.MusicRules.noteSender(mine = s.author == me, channel = conv?.isChannel == true,
        group = conv?.isGroup == true, chatName = chatName, memberName = conv?.names?.get(s.author)?.takeIf { it.isNotEmpty() },
        personName = conv?.name(me).orEmpty())
    val noteLine = com.fariin.app.data.MusicRules.noteSubtitle(sender, name)
    // A song (iOS PlayerAccessory with isMusic): its picture, title and artist, Next inside a playlist, no speed.
    val music = s.music
    val rate = VoicePlayer.rate(cid)
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
        // iOS PlayerAccessory tap: back to the chat AT the playing message (the jump the shared
        // media's Go to Message uses: the chat scrolls to it and lights it once it is open).
        .background(c.card).clickable {
            s.messageId?.let { com.fariin.app.data.QuoteStore.pendingJump.value = com.fariin.app.data.QuoteStore.Jump(cid, it) }
            onOpen(cid)
        }
        // iOS: a sideways drag anywhere on the bar moves the note to that point of its width.
        .pointerInput(s.id) {
            val id = s.id ?: return@pointerInput
            detectHorizontalDragGestures { ch, _ ->
                ch.consume()
                if (size.width > 0) VoicePlayer.seek(id, ch.position.x / size.width)
            }
        }) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { VoicePlayer.playPause() }, contentAlignment = Alignment.Center) {
                Icon(if (s.playing) ImageVector.cachedVector(R.drawable.ic_sf_pause_fill) else ImageVector.cachedVector(R.drawable.ic_sf_play_fill), if (s.playing) "Pause" else "Play",
                    tint = c.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(6.dp))
            if (music != null) {
                val art = rememberArt(music.art)
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    if (art != null) androidx.compose.foundation.Image(art, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize())
                    else Icon(ImageVector.cachedVector(R.drawable.ic_sf_music_note), null, tint = c.accent, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(music?.title ?: name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (music != null) music.artist.ifEmpty { "Song" } else noteLine, fontSize = 11.sp, color = c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (music == null) Text(com.fariin.app.media.MediaRules.rateLabel(rate), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { VoicePlayer.cycleRate(cid) }.semantics { contentDescription = "Playback speed"; stateDescription = com.fariin.app.media.MediaRules.rateLabel(rate) }.padding(horizontal = 8.dp, vertical = 6.dp))
            if (music?.hasNext == true) Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { VoicePlayer.nextTrack() }
                .semantics { contentDescription = "Next song" }, contentAlignment = Alignment.Center) {
                Icon(ImageVector.cachedVector(R.drawable.ic_sf_forward_end_fill), null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { VoicePlayer.stop() }, contentAlignment = Alignment.Center) {
                Icon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close", tint = c.secondary, modifier = Modifier.size(18.dp))
            }
        }
        val f = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(2.dp).background(c.accent))
    }
}
