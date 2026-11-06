package com.fariin.app.ui.thread

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.fariin.app.data.Message
import com.fariin.app.media.MediaRules
import com.fariin.app.media.OnceBurn
import com.fariin.app.media.ViewedOnce
import com.fariin.app.media.VoicePlayer
import com.fariin.app.media.VoicePreview
import com.fariin.app.media.VoiceRecordRules
import com.fariin.app.ui.theme.Fariin
import java.io.File

/**
 * A one-time voice note (iOS OneTimeVoicePill): a pill with the 1 mark, never the inline player.
 * The receiver's tap opens [OneTimeVoicePage]; once that page has played it, the pill reads
 * "Played" and does nothing. A note the server burned (`consumed`) is "Played" too. The sender's
 * own pill is inert, as on iOS (a sender cannot replay a one-time note).
 */
@Composable
internal fun OneTimeVoicePill(m: Message, cid: String, isMe: Boolean, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val epoch by ViewedOnce.epoch.collectAsState()
    val seen = remember(m.id, epoch, m.consumed) { m.consumed || ViewedOnce.contains(ctx, m.id) }
    val spent = !isMe && seen
    val canOpen = !isMe && !spent && m.audioUrl != null && m.sendState == null
    var open by remember(m.id) { mutableStateOf(false) }
    val ink = if (isMe) Color.White else c.label
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = canOpen) { open = true }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (spent) Color.Transparent else ink.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (spent) Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_block), null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                else Text("1", color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(VoiceRecordRules.onceLabel(isMe, spent, m.duration ?: 0.0), fontSize = 16.sp,
                fontStyle = if (spent) FontStyle.Italic else FontStyle.Normal,
                color = if (spent) ink.copy(alpha = 0.7f) else ink)
            if (m.sendState == Message.SendState.SENDING) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.align(Alignment.End)) { meta() }
    }
    if (open) OneTimeVoicePage(m, cid) { open = false }
}

/**
 * The room where the one listen happens (iOS OneTimeVoicePage): full screen and black, kept out
 * of screenshots and screen recordings. It plays as it opens, forward only (no seeking back, no
 * replay), and closes by itself at the end. Leaving it is what spends the note on this phone;
 * the server burn is asked for as soon as the note is in hand. The decrypted copy is deleted
 * when the page goes.
 */
@Composable
private fun OneTimeVoicePage(m: Message, cid: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val media = chatMedia()
    val player = remember { VoicePreview() }
    val st by player.state.collectAsState()
    var failed by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var heard by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { }
        }
        LaunchedEffect(m.id) {
            // The shared engine must not talk over the room.
            VoicePlayer.stop()
            val url = m.audioUrl
            val f = url?.let { media.file(it, m.enc) }
            if (f == null || !player.load(f, rewindAtEnd = false)) { failed = true; return@LaunchedEffect }
            file = f
            heard = true
            // Only the receiver opens this page (the pill is inert for its sender); the function
            // also refuses the author and groups.
            OnceBurn.burnVoice(cid, m.id)
            player.play()
        }
        // Ran to the end: the room closes (iOS: "when the voice is complete, close the page").
        LaunchedEffect(st.ended) { if (st.ended) onClose() }
        DisposableEffect(Unit) {
            onDispose {
                player.release()
                file?.delete()
                if (heard) ViewedOnce.mark(ctx, m.id)
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(52.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(58.dp).border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("1", color = Color.White.copy(alpha = 0.9f), fontSize = 28.sp, fontWeight = FontWeight.Light)
                }
                Spacer(Modifier.height(22.dp))
                Text("One-time voice message", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(22.dp))
                if (failed) {
                    Text("Could not load this voice message.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                } else {
                    Row(
                        Modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp).fillMaxWidth()
                            .heightIn(min = 64.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2B2B2B))
                            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color.White)
                                .clickable(enabled = st.ready) {
                                    // One listen: pause and go on, never again from the top.
                                    if (st.playing) player.pause() else if (!st.ended) player.play()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!st.ready) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            else Icon(if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (st.playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Waveform(m.waveform.ifEmpty { List(28) { 35 } }, st.progress, Color.White, Modifier.fillMaxWidth().height(20.dp)) { f ->
                                val target = (st.durationMs * f).toLong()
                                if (VoiceRecordRules.onceSeekAllowed(target, st.positionMs)) player.seek(f)
                            }
                            Spacer(Modifier.height(6.dp))
                            val label = if (st.playing || st.positionMs > 0) MediaRules.durationLabel(st.positionMs / 1000.0)
                                else MediaRules.durationLabel(if (st.durationMs > 0) st.durationMs / 1000.0 else m.duration ?: 0.0)
                            Text(label, style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), fontFeatureSettings = "tnum"))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("It's gone when you leave this screen", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 26.dp))
            }
            Box(
                Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp).size(36.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}
