package com.fariin.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fariin.app.call.AudioRoute
import com.fariin.app.call.CallMachine
import com.fariin.app.call.CallRouting
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import com.fariin.app.call.CallPhase
import com.fariin.app.call.CallUi
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallUi
import com.fariin.app.call.GroupMember
import com.fariin.app.call.RtcEngine
import com.fariin.app.ui.components.Avatar
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack

private val Backdrop = Brush.verticalGradient(listOf(Color(0xFF1C2A3A), Color(0xFF0B0F14)))
private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
private val Glass = Color.White.copy(alpha = 0.16f)
private val GlassOn = Color.White

/**
 * The 1:1 call screen after iOS CallView: the other person's video full screen when their camera
 * is on (otherwise their photo, name and the stage words or clock), my camera small in the top
 * corner, and one dark capsule of round buttons at the bottom: camera, flip (while the camera is
 * on), mute, speaker, and the red end button. A ring shows Decline and Accept instead.
 */
@Composable
fun CallScreen(
    ui: CallUi,
    onAccept: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit,
    onMute: () -> Unit, onRoute: (AudioRoute) -> Unit, onCamera: () -> Unit, onFlip: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.status.connectedAtMs) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ui.status
    val remoteVideo = ui.remoteVideo.takeIf { ui.remoteCameraOn && s.phase != CallPhase.INCOMING }
    val demoVideo = ui.peerUid == "demo" && ui.remoteCameraOn && s.phase == CallPhase.ACTIVE
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        if (remoteVideo != null) VideoView(remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
        else if (demoVideo) DemoVideo(Modifier.fillMaxSize(), Color(0xFF3A5A40), Color(0xFF1B2B1F))
        val showFace = remoteVideo == null && !demoVideo
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (showFace) 72.dp else 24.dp))
            if (showFace) Avatar(ui.peerPhoto, 120.dp)
            Spacer(Modifier.height(16.dp))
            Text(ui.peerName, color = Color.White, fontSize = if (showFace) 28.sp else 20.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            Text(if (ui.held && s.live) "On hold" else CallMachine.label(s, now), color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp)
            if (ui.remoteMuted && s.phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = "${ui.peerName.substringBefore(' ')} is muted" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Muted", color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            when (s.phase) {
                CallPhase.INCOMING -> IncomingButtons(s.video, onAccept, onDecline)
                CallPhase.ENDED -> Spacer(Modifier.height(96.dp))
                else -> ControlBar(ui, onEnd, onMute, onRoute, onCamera, onFlip)
            }
            Spacer(Modifier.height(24.dp))
        }
        // My camera, small, top right (iOS local preview).
        if (ui.cameraOn && s.phase != CallPhase.INCOMING && s.phase != CallPhase.ENDED) {
            val m = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopEnd).size(width = 104.dp, height = 150.dp)
                .clip(RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            val local = ui.localVideo
            if (local != null) VideoView(local, mirror = ui.frontCamera, overlay = true, modifier = m)
            else DemoVideo(m, Color(0xFF6B7A8F), Color(0xFF2E3A48))
        }
    }
}

@Composable
private fun DemoVideo(modifier: Modifier, a: Color, b: Color) {
    Box(modifier.background(Brush.linearGradient(listOf(a, b))))
}

@Composable
private fun IncomingButtons(video: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        LabeledCircle(Icons.Filled.CallEnd, "Decline", Red, onDecline)
        LabeledCircle(if (video) Icons.Filled.Videocam else Icons.Filled.Call, "Accept", Green, onAccept)
    }
}

@Composable
private fun LabeledCircle(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun ControlBar(ui: CallUi, onEnd: () -> Unit, onMute: () -> Unit, onRoute: (AudioRoute) -> Unit, onCamera: () -> Unit, onFlip: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val size = 56.dp
        Circle(if (ui.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, active = !ui.cameraOn,
            label = if (ui.cameraOn) "Turn camera off" else "Turn camera on", size = size, onClick = onCamera)
        if (ui.cameraOn) Circle(Icons.Filled.Cameraswitch, active = false, label = "Flip camera", size = size, onClick = onFlip)
        Circle(if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic, active = ui.muted,
            label = if (ui.muted) "Unmute" else "Mute", size = size, onClick = onMute)
        SpeakerButton(ui, size, onRoute)
        Box(Modifier.size(size).clip(CircleShape).background(Red).clickable(onClick = onEnd)
            .semantics { contentDescription = "End call" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/**
 * The speaker button. With only the phone and the speaker it switches between them; with a
 * headset or Bluetooth in, it opens the list of places the sound can go (the iOS route picker).
 */
@Composable
private fun SpeakerButton(ui: CallUi, size: Dp, onRoute: (AudioRoute) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val r = ui.route
    val icon = when (r.current) {
        AudioRoute.BLUETOOTH -> Icons.Filled.BluetoothAudio
        AudioRoute.HEADSET -> Icons.Filled.Headphones
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }
    val label = if (CallRouting.speakerTap(r) is CallRouting.Tap.Picker) "Audio: " + CallRouting.label(r.current, r.bluetoothName) else "Speaker"
    Box {
        Circle(icon, active = CallRouting.speakerLit(r), label = label, size = size, onClick = {
            when (val t = CallRouting.speakerTap(r)) {
                is CallRouting.Tap.Switch -> onRoute(t.to)
                CallRouting.Tap.Picker -> open = true
            }
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (route in CallRouting.pickerList(r)) {
                DropdownMenuItem(
                    text = { Text(CallRouting.label(route, r.bluetoothName)) },
                    leadingIcon = { Icon(when (route) {
                        AudioRoute.BLUETOOTH -> Icons.Filled.BluetoothAudio
                        AudioRoute.HEADSET -> Icons.Filled.Headphones
                        AudioRoute.EARPIECE -> Icons.Filled.PhoneInTalk
                        AudioRoute.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
                    }, null) },
                    trailingIcon = { if (route == r.current) Icon(Icons.Filled.Check, "In use") },
                    onClick = { open = false; onRoute(route) },
                )
            }
        }
    }
}

/** The small picture-in-picture window: their video, else mine, else their photo. */
@Composable
fun PipScreen(ui: CallUi) {
    Box(Modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
        val remote = ui.remoteVideo.takeIf { ui.remoteCameraOn }
        val local = ui.localVideo.takeIf { ui.cameraOn }
        when {
            remote != null -> VideoView(remote, mirror = false, modifier = Modifier.fillMaxSize())
            ui.peerUid == "demo" && ui.remoteCameraOn -> DemoVideo(Modifier.fillMaxSize(), Color(0xFF3A5A40), Color(0xFF1B2B1F))
            local != null -> VideoView(local, mirror = ui.frontCamera, modifier = Modifier.fillMaxSize())
            else -> Avatar(ui.peerPhoto, 64.dp)
        }
    }
}

/** iOS callCircle: glass when off, white with a dark glyph when the switch is on. */
@Composable
private fun Circle(icon: ImageVector, active: Boolean, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(if (active) GlassOn else Glass).clickable(onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

/** A WebRTC track drawn into a SurfaceViewRenderer (the in-call video). */
@Composable
fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier = Modifier, overlay: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { r ->
            r.setMirror(mirror)
            val old = r.tag as? VideoTrack
            if (old !== track) {
                try { old?.removeSink(r) } catch (_: Exception) {}
                track.addSink(r)
                r.tag = track
            }
        },
        onRelease = { r ->
            try { (r.tag as? VideoTrack)?.removeSink(r) } catch (_: Exception) {}
            r.release()
        },
    )
}

// ====================================================================== group room

/** A group call: a grid of members (video when their camera is on, else the grey face), and Mic, Camera, Leave. */
@Composable
fun GroupCallScreen(g: GroupCallUi, onMic: () -> Unit, onCamera: () -> Unit, onLeave: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Text(g.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (g.waiting) "Waiting for the host to let you in" else if (g.connecting) "Connecting…" else "${g.members.size} in call", color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(g.members, key = { it.id }) { m -> MemberTile(m) }
            }
            Row(Modifier.padding(16.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Circle(if (g.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, !g.cameraOn,
                    if (g.cameraOn) "Turn camera off" else "Turn camera on", 56.dp, onCamera)
                Circle(if (g.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, !g.micOn, if (g.micOn) "Mute" else "Unmute", 56.dp, onMic)
                Box(Modifier.size(56.dp).clip(CircleShape).background(Red).clickable(onClick = onLeave)
                    .semantics { contentDescription = "Leave call" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MemberTile(m: GroupMember) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().height(180.dp).clip(shape).background(Color.White.copy(alpha = 0.08f))
        .then(if (m.speaking) Modifier.border(2.dp, Green, shape) else Modifier), contentAlignment = Alignment.Center) {
        val v = m.video
        if (v != null) LiveKitVideo(v, Modifier.fillMaxSize()) else Avatar(null, 72.dp)
        Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!m.micOn) { Icon(Icons.Filled.MicOff, "Muted", tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(m.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** A LiveKit camera track in a tile. */
@Composable
private fun LiveKitVideo(track: io.livekit.android.room.track.VideoTrack, modifier: Modifier) {
    val room = GroupCall.room() ?: return
    AndroidView(modifier = modifier, factory = { ctx ->
        io.livekit.android.renderer.SurfaceViewRenderer(ctx).also { room.initVideoRenderer(it) }
    }, update = { r ->
        val old = r.tag as? io.livekit.android.room.track.VideoTrack
        if (old !== track) { old?.removeRenderer(r); track.addRenderer(r); r.tag = track }
    }, onRelease = { r ->
        (r.tag as? io.livekit.android.room.track.VideoTrack)?.removeRenderer(r)
        r.release()
    })
}
