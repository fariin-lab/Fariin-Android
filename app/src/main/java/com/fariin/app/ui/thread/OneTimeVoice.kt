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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import com.fariin.app.R

/**
 * A one-time voice note, never the inline player: the one-line view-once bubble ([OnceBubbleRow],
 * "Voice" with the 1 ring, then "Opened"). The receiver's tap opens [OneTimeVoicePage]. My own
 * note is inert (a sender cannot replay it) and turns "Opened" when the server burns it.
 */
@Composable
internal fun OneTimeVoicePill(m: Message, cid: String, isMe: Boolean, meta: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val epoch by ViewedOnce.epoch.collectAsState()
    val opened = remember(m.id, epoch, m.consumed, isMe) {
        OnceRules.opened(isMe, m.consumed, ViewedOnce.contains(ctx, m.id))
    }
    val canOpen = OnceRules.canOpen(isMe, opened, m.audioUrl != null, m.sendState != null)
    var open by remember(m.id) { mutableStateOf(false) }
    OnceBubbleRow(OnceRules.Kind.VOICE, opened, isMe, m.sendState == Message.SendState.SENDING,
        if (canOpen) ({ open = true }) else null, meta)
    if (open) OneTimeVoicePage(m, cid) { open = false }
}

/**
 * The player where the one listen happens: full screen and black, kept out of screenshots and
 * screen recordings (FLAG_SECURE). It plays as it opens; inside, play/pause and drag or tap the
 * wave to any point, back or forward, as often as wanted. It closes by itself when playback
 * reaches the end; X closes it sooner, and so does going to the background. Leaving it is what
 * spends the note on this phone, for good; the server burn is asked for as soon as the note is in hand. The decrypted
 * copy is deleted when the page goes.
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
    /** Where a finger is dragging the wave to, or null; the seek happens on release. */
    var drag by remember { mutableStateOf<Float?>(null) }

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
        CloseWhenBackgrounded(onClose)
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
                DashedOnceRing(Color.White.copy(alpha = 0.9f), showOne = true, side = 58.dp, stroke = 2.dp, digitSize = 26.sp)
                Spacer(Modifier.height(22.dp))
                Text("View once voice message", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                                    if (st.playing) player.pause() else if (!st.ended) player.play()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!st.ready) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            else Icon(if (st.playing) ImageVector.vectorResource(R.drawable.ic_sf_pause_fill) else ImageVector.vectorResource(R.drawable.ic_sf_play_fill),
                                if (st.playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            // Tap or drag anywhere on the wave, back or forward, as often as wanted
                            // while the page is open (owner 2026-11-07).
                            val shown = drag ?: st.progress
                            Waveform(m.waveform.ifEmpty { List(28) { 35 } }, shown, Color.White,
                                Modifier.fillMaxWidth().height(28.dp).pointerInput(st.ready) {
                                    if (!st.ready) return@pointerInput
                                    detectHorizontalDragGestures(
                                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                                        onDragEnd = { drag?.let { player.seek(it) }; drag = null },
                                        onDragCancel = { drag = null },
                                    ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                                }) { f -> if (st.ready) player.seek(f) }
                            Spacer(Modifier.height(6.dp))
                            val label = when {
                                drag != null -> MediaRules.durationLabel(st.durationMs * (drag ?: 0f) / 1000.0)
                                st.playing || st.positionMs > 0 -> MediaRules.durationLabel(st.positionMs / 1000.0)
                                else -> MediaRules.durationLabel(if (st.durationMs > 0) st.durationMs / 1000.0 else m.duration ?: 0.0)
                            }
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
            ) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}
