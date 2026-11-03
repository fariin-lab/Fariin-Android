package com.fariin.app.ui.call

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import io.livekit.android.renderer.TextureViewRenderer
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack

// iOS keeps the call screens flat black (no gradient, no blurred photo: its 2026-07-11 decision).
private val Backdrop = Color.Black
private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
// The dark glass of iOS's call screens: a light veil on black with a faint white rim.
private val Glass = Color.White.copy(alpha = 0.14f)
private val GlassRim = Color.White.copy(alpha = 0.18f)
private val GlassOn = Color.White
// The capsule under the buttons (iOS: dark ultra-thin material, black 25% over it, white 12% rim).
private val CapsuleFill = Color(0xFF1A1A1C).copy(alpha = 0.82f)
private val CapsuleRim = Color.White.copy(alpha = 0.12f)
private val TileFill = Color(0xFF1F1F21)

/** A dark glass surface: the veil and the rim, clipped to [shape]. */
private fun Modifier.darkGlass(shape: Shape, fill: Color = Glass): Modifier =
    clip(shape).background(fill).border(0.5.dp, GlassRim, shape)

/** A tap with no ripple, for the screen-wide show/hide surface. */
@Composable
private fun Modifier.plainTap(onTap: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)

/**
 * The controls that step aside on a video call (iOS autoHideAfter): up when the call connects, gone
 * 5 s later, back on a tap anywhere; a tap while they show sends them away. Every button press
 * restarts the clock ([touch]). Only while there is a picture to clear the way for ([allowed]).
 */
private class Chrome {
    var visible by mutableStateOf(true)
    var poke by mutableIntStateOf(0)
    /** A finger is dragging my card: no hiding under it (iOS: the clock stops for the drag). */
    var held by mutableStateOf(false)
    fun touch() { visible = true; poke++ }
    fun tap(allowed: Boolean) { if (!visible) touch() else if (allowed) visible = false }
}

@Composable
private fun rememberChrome(allowed: Boolean): Chrome {
    val c = remember { Chrome() }
    LaunchedEffect(allowed, c.visible, c.poke, c.held) {
        if (!allowed) { c.visible = true; return@LaunchedEffect }
        if (c.visible && !c.held) { delay(5_000); c.visible = false }
    }
    return c
}

/**
 * The 1:1 call screen after iOS CallView: a header (minimize at the start, the bold name and the
 * stage words or clock in the middle, "Muted" between them while they are muted), their video full
 * screen or their big photo, my camera in a card at the bottom corner with the flip glyph on it,
 * and one dark capsule: camera, speaker, mute, end. A ring shows Decline and Accept instead.
 */
@Composable
fun CallScreen(
    ui: CallUi,
    onAccept: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit,
    onMute: () -> Unit, onRoute: (AudioRoute) -> Unit, onCamera: () -> Unit, onFlip: () -> Unit,
    onMinimize: (() -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.status.connectedAtMs) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ui.status
    val connected = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING
    val remoteVideo = ui.remoteVideo.takeIf { ui.remoteCameraOn && s.phase != CallPhase.INCOMING }
    val demoVideo = ui.peerUid == "demo" && ui.remoteCameraOn && s.phase == CallPhase.ACTIVE
    // Calling on video: my own preview fills the screen until they pick up (iOS showLocalFull).
    val localFull = ui.cameraOn && s.phase == CallPhase.OUTGOING
    val chrome = rememberChrome(connected && (ui.cameraOn || ui.remoteCameraOn))
    // The header and the capsule fade: in over 0.2 s, away over 0.28 s, ease in and out (iOS).
    val shown by animateFloatAsState(if (chrome.visible) 1f else 0f,
        tween(if (chrome.visible) 200 else 280, easing = EaseInOut), label = "chrome")
    val local = ui.localVideo
    val cardUp = ui.cameraOn && !localFull && s.phase != CallPhase.INCOMING && s.phase != CallPhase.ENDED
    // A tap on my card swaps the two pictures: mine big, theirs in the card (iOS isLocalExpanded).
    var swapped by remember { mutableStateOf(false) }
    LaunchedEffect(cardUp) { if (!cardUp) swapped = false }
    val mineBig = localFull || (swapped && cardUp)
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        // One renderer for the big picture whoever is in it, so a swap only moves the tracks.
        val bigTrack = if (mineBig) local else remoteVideo
        val bigDemo = bigTrack == null && (if (mineBig) swapped else demoVideo)
        when {
            bigTrack != null -> VideoView(bigTrack, mirror = mineBig && ui.frontCamera, modifier = Modifier.fillMaxSize())
            bigDemo && mineBig -> DemoVideo(Modifier.fillMaxSize(), MeA, MeB)
            bigDemo -> DemoVideo(Modifier.fillMaxSize(), ThemA, ThemB)
        }
        val showFace = bigTrack == null && !bigDemo
        // Above the video, under every control: a tap shows or hides the controls.
        Box(Modifier.fillMaxSize().plainTap { chrome.tap(connected && (ui.cameraOn || ui.remoteCameraOn)) })
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = if (ui.held && s.live) "On hold" else CallMachine.label(s, now)
            CallHeader(
                name = ui.peerName, status = status,
                muted = ui.remoteMuted && connected, mutedLabel = "${ui.peerName.substringBefore(' ')} is muted",
                left = if (onMinimize != null && s.phase != CallPhase.INCOMING && s.phase != CallPhase.ENDED) {
                    { HeaderCircle(Icons.Filled.CloseFullscreen, "Minimize call", 48.dp) { chrome.touch(); onMinimize() } }
                } else null,
                modifier = Modifier.alpha(shown),
            )
            Spacer(Modifier.weight(1f))
            if (showFace) {
                Avatar(ui.peerPhoto, 180.dp, Modifier
                    .shadow(26.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .semantics { contentDescription = ui.peerName })
                Spacer(Modifier.weight(1f))
            }
            when (s.phase) {
                CallPhase.INCOMING -> IncomingButtons(s.video, onAccept, onDecline)
                CallPhase.ENDED -> Spacer(Modifier.height(BarHeight))
                else -> ControlBar(ui, chrome, onEnd, onMute, onRoute, onCamera, Modifier.alpha(shown))
            }
            Spacer(Modifier.navigationBarsPadding().height(22.dp))
        }
        // Controls away: the first tap anywhere only brings them back (hidden buttons take no taps).
        if (!chrome.visible) Box(Modifier.fillMaxSize().plainTap { chrome.touch() }.semantics { contentDescription = "Show call controls" })
        // My camera: a card at the bottom corner, smaller while the controls are away (iOS pipLayer).
        // Above the catch-all tap, so it can be dragged and tapped while the controls are away too.
        if (cardUp) {
            SelfCard(grown = chrome.visible, chrome = chrome,
                label = if (swapped) ui.peerName else "Your video",
                onFlip = if (!swapped && local != null) { { chrome.touch(); onFlip() } } else null,
                // TWO STAGES (iOS tapTile): the small card only brings the controls back; a tap on the
                // grown card swaps the pictures, and the next one swaps them back.
                onTap = { if (chrome.visible) swapped = !swapped; chrome.touch() },
            ) {
                val m = Modifier.fillMaxSize()
                val cardTrack = if (swapped) remoteVideo else local
                when {
                    cardTrack != null -> CardVideoView(cardTrack, mirror = !swapped && ui.frontCamera, modifier = m)
                    !swapped -> DemoVideo(m, MeA, MeB)
                    demoVideo -> DemoVideo(m, ThemA, ThemB)
                    // Their camera is off: their photo holds the card (iOS tileContent).
                    else -> Box(m.background(TileFill), contentAlignment = Alignment.Center) { Avatar(ui.peerPhoto, 64.dp) }
                }
            }
        }
    }
}

// The demo call's made-up pictures: theirs green, mine slate.
private val ThemA = Color(0xFF3A5A40)
private val ThemB = Color(0xFF1B2B1F)
private val MeA = Color(0xFF6B7A8F)
private val MeB = Color(0xFF2E3A48)

/** iOS's ease in and out (`.easeInOut`). */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
/** iOS `.spring(duration: 0.4)`: no bounce. The card's size and home move on it as one relayout. */
private val CardSpring = spring<Float>(dampingRatio = 1f, stiffness = 246.7f)
/** iOS PipTileDrag's snap to a corner: `.spring(response: 0.35, dampingFraction: 0.78)`. */
private val SnapSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 322.3f)
/** The card at full size with the controls up, and small without (iOS: a 240 and 140 pt box, 9:16). */
private val CardBig = DpSize(135.dp, 240.dp)
private val CardSmall = DpSize(79.dp, 140.dp)

/**
 * My card (iOS pipLayer + PipTileDrag). [grown] with the controls up: 135 by 240 above the capsule;
 * without, 79 by 140 near the edge, size and home on one spring. A drag carries it anywhere and the
 * release snaps it to the nearest corner, judged by where the throw was going. The picture inside
 * is drawn at the full size and scaled, so the video surface is never resized while it moves.
 */
@Composable
private fun SelfCard(grown: Boolean, chrome: Chrome, label: String, onFlip: (() -> Unit)?, onTap: () -> Unit,
                     content: @Composable () -> Unit) {
    val t by animateFloatAsState(if (grown) 1f else 0f, CardSpring, label = "card")
    // The corner as fractions of the travel: x 0 = the end edge, 1 = the start edge; y 0 = bottom, 1 = top.
    val fx = remember { Animatable(0f) }
    val fy = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bars = WindowInsets.systemBars
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = lerp(CardSmall.width, CardBig.width, t)
        val h = lerp(CardSmall.height, CardBig.height, t)
        val px = with(density) {
            val wPx = w.toPx(); val hPx = h.toPx()
            val gutter = 12.dp.toPx()
            val endX = constraints.maxWidth - bars.getRight(this, LayoutDirection.Ltr) - gutter - wPx
            val startX = bars.getLeft(this, LayoutDirection.Ltr) + gutter
            // Home clears the capsule with the controls up (iOS barClear: bar + 56), else 12 off the edge.
            val bottomY = constraints.maxHeight - bars.getBottom(this) - lerp(12.dp, BarHeight + 56.dp, t).toPx() - hPx
            // The top corner sits under the header (iOS headerClear: safe top + 76).
            val topY = minOf(bars.getTop(this) + 76.dp.toPx(), bottomY)
            floatArrayOf(if (rtl) startX else endX, if (rtl) endX else startX, bottomY, topY)
        }
        val travel by rememberUpdatedState(px)
        val shape = RoundedCornerShape(18.dp)
        Box(Modifier
            .offset { IntOffset(lerp(px[0], px[1], fx.value).roundToInt(), lerp(px[2], px[3], fy.value).roundToInt()) }
            .size(w, h)
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape).border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .pointerInput(Unit) {
                val vt = VelocityTracker()
                var path = Offset.Zero
                fun settle() {
                    chrome.held = false
                    val v = vt.calculateVelocity()
                    val spanX = travel[1] - travel[0]; val spanY = travel[3] - travel[2]
                    // Where the throw was going (UIKit's projection at the normal deceleration rate).
                    val goX = if (spanX == 0f) 0f else fx.value + v.x * 0.499f / spanX
                    val goY = if (spanY == 0f) 0f else fy.value + v.y * 0.499f / spanY
                    scope.launch { fx.animateTo(if (goX > 0.5f) 1f else 0f, SnapSpring, if (spanX == 0f) 0f else v.x / spanX) }
                    scope.launch { fy.animateTo(if (goY > 0.5f) 1f else 0f, SnapSpring, if (spanY == 0f) 0f else v.y / spanY) }
                }
                detectDragGestures(
                    onDragStart = { chrome.held = true; vt.resetTracking(); path = Offset.Zero },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                ) { change, d ->
                    change.consume()
                    path += d
                    vt.addPosition(change.uptimeMillis, path)
                    val spanX = travel[1] - travel[0]; val spanY = travel[3] - travel[2]
                    scope.launch {
                        if (spanX != 0f) fx.snapTo((fx.value + d.x / spanX).coerceIn(0f, 1f))
                        if (spanY != 0f) fy.snapTo((fy.value + d.y / spanY).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .semantics { contentDescription = label; onClick { onTap(); true } }) {
            // Drawn at the big size and scaled down: the size moves every frame, the surface never does.
            Box(Modifier.requiredSize(CardBig).graphicsLayer {
                scaleX = w / CardBig.width; scaleY = h / CardBig.height
            }) { content() }
            // The flip glyph belongs to my live camera's card (iOS: no flip in the bar).
            if (onFlip != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).darkGlass(CircleShape)
                .clickable(onClick = onFlip).semantics { contentDescription = "Flip camera" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Cameraswitch, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The control bar's height: the 56 circle and 12 above and below (iOS CallView.barHeight). */
private val BarHeight = 80.dp

/**
 * The call header (iOS topBar): a circle at the start, the 22 sp bold name over the 15 sp line in
 * the middle, the same room kept free at the end (or [right]) so the name stays centred, over a
 * soft dark scrim that keeps white text legible on bright video.
 */
@Composable
private fun CallHeader(
    name: String, status: String, muted: Boolean, mutedLabel: String,
    left: (@Composable () -> Unit)?, modifier: Modifier = Modifier,
    right: (@Composable () -> Unit)? = null, side: Dp = 48.dp,
) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            .statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        // Top-aligned: the circle centres on the name and clock, and "Muted" grows the block
        // downward without moving it (iOS headerButtons guide).
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = (48.dp - side) / 2).size(side)) { left?.invoke() }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            // Their mute is the word on its own line between the name and the clock (iOS, 2026-10-10).
            if (muted) Text("Muted", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.semantics { contentDescription = mutedLabel })
            Text(status, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(top = (48.dp - side) / 2).size(side)) { right?.invoke() }
    }
}

/** A header button: a white glyph in a dark glass circle. */
@Composable
private fun HeaderCircle(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).darkGlass(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun DemoVideo(modifier: Modifier, a: Color, b: Color) {
    Box(modifier.background(Brush.linearGradient(listOf(a, b))))
}

@Composable
private fun IncomingButtons(video: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        LabeledCircle(Icons.Filled.CallEnd, "Decline", Red, onDecline)
        LabeledCircle(if (video) Icons.Filled.Videocam else Icons.Filled.Call, "Accept", Green, onAccept)
    }
}

@Composable
private fun LabeledCircle(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).darkGlass(CircleShape, color).clickable(onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** The dark capsule the round buttons sit in (iOS controlBar background). */
@Composable
private fun ControlCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.padding(horizontal = 12.dp).clip(CircleShape).background(CapsuleFill).border(1.dp, CapsuleRim, CircleShape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** iOS order: camera, speaker, mute next to end under the right thumb. Flip is on my camera's card. */
@Composable
private fun ControlBar(ui: CallUi, chrome: Chrome, onEnd: () -> Unit, onMute: () -> Unit, onRoute: (AudioRoute) -> Unit,
                       onCamera: () -> Unit, modifier: Modifier = Modifier) {
    ControlCapsule(modifier) {
        val size = 56.dp
        Circle(if (ui.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, active = !ui.cameraOn,
            label = if (ui.cameraOn) "Turn camera off" else "Turn camera on", size = size) { chrome.touch(); onCamera() }
        SpeakerButton(ui, size) { chrome.touch(); onRoute(it) }
        Circle(if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic, active = ui.muted,
            label = if (ui.muted) "Unmute" else "Mute", size = size) { chrome.touch(); onMute() }
        EndCircle("End call", size, onEnd)
    }
}

@Composable
private fun EndCircle(label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).darkGlass(CircleShape, Red).clickable(onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
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

/** iOS callCircle: dark glass when off, solid white with a dark glyph when the switch is on. */
@Composable
private fun Circle(icon: ImageVector, active: Boolean, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).then(if (active) Modifier.clip(CircleShape).background(GlassOn) else Modifier.darkGlass(CircleShape))
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
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

/**
 * A WebRTC track drawn into a TextureView, for my card. The card's size moves on a spring; a
 * SurfaceView there drops its frames and flickers ("rejecting buffer" on every frame, Pixel 5,
 * 2026-11-03), while a TextureView is drawn with the rest of the screen and takes the scale, the
 * rounded clip and the drag like any other view.
 */
@Composable
private fun CardVideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
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

/**
 * A group call after iOS GroupCallView: the header (the title over "N in call", the people button
 * at the end), the stage (a grid that fills the room, me first as "You"; a tap on someone makes them
 * large with everyone else in a strip under them, a tap on the large one goes back), and the
 * capsule: camera, mute, end. The header and capsule step aside on video as the 1:1 screen does.
 * [GroupCallUi.members] lists me first (GroupCall.refresh puts the local participant at the head).
 */
@Composable
fun GroupCallScreen(g: GroupCallUi, onMic: () -> Unit, onCamera: () -> Unit, onLeave: () -> Unit) {
    var focus by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf(false) }
    val meId = g.members.firstOrNull()?.id
    LaunchedEffect(g.members.map { it.id }) { if (focus != null && g.members.none { it.id == focus }) focus = null }
    val allowed = g.joined && !people && (g.cameraOn || g.members.any { it.video != null })
    val chrome = rememberChrome(allowed)
    val shown by animateFloatAsState(if (chrome.visible) 1f else 0f, tween(280), label = "chrome")
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = if (g.waiting) "Waiting to be let in" else if (g.connecting) "Connecting…"
                else if (g.joined && g.members.size <= 1) "No one else is here"
                else if (g.joined) "${g.members.size} in call" else " "
            CallHeader(
                name = g.title, status = status, muted = false, mutedLabel = "", left = null, side = 44.dp,
                right = { HeaderCircle(Icons.Filled.People, "Participants", 44.dp) { chrome.touch(); people = true } },
                modifier = Modifier.alpha(shown),
            )
            Spacer(Modifier.height(2.dp))
            Box(Modifier.weight(1f).fillMaxWidth().plainTap { chrome.tap(allowed) }) {
                GroupStage(g.members, meId, focus, onTile = { id ->
                    chrome.touch()
                    // My own tile on the grid does nothing (iOS); a tap on the large one goes back.
                    focus = if (focus == id) null else if (id == meId && focus == null) null else id
                })
            }
            Spacer(Modifier.height(16.dp))
            ControlCapsule(Modifier.alpha(shown)) {
                Circle(if (g.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, !g.cameraOn,
                    if (g.cameraOn) "Turn camera off" else "Turn camera on", 56.dp) { chrome.touch(); onCamera() }
                Circle(if (g.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, !g.micOn, if (g.micOn) "Mute" else "Unmute", 56.dp) {
                    chrome.touch(); onMic()
                }
                EndCircle("End call", 56.dp, onLeave)
            }
        }
        if (!chrome.visible) Box(Modifier.fillMaxSize().plainTap { chrome.touch() }.semantics { contentDescription = "Show call controls" })
        if (people) ParticipantsSheet(g, meId) { people = false }
    }
}

private const val GridInset = 6
private const val GridSpacing = 6

/**
 * The stage. One person large while [focus] names someone (strip of 96 squares under them); else
 * the grid: one column up to two people, then two, rows sharing the height (up to three rows, so six
 * tiles fill the room; more scroll in fixed rows, as Android has no strip for the rest yet).
 */
@Composable
private fun GroupStage(members: List<GroupMember>, meId: String?, focus: String?, onTile: (String) -> Unit) {
    val big = members.firstOrNull { it.id == focus }
    if (big != null) {
        Column(Modifier.fillMaxSize().padding(GridInset.dp)) {
            MemberTile(big, big.id == meId, Modifier.weight(1f).fillMaxWidth(), large = true) { onTile(big.id) }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(members.filter { it.id != big.id }, key = { it.id }) { m ->
                    MemberTile(m, m.id == meId, Modifier.size(96.dp), strip = true) { onTile(m.id) }
                }
            }
        }
        return
    }
    val cols = if (members.size <= 2) 1 else 2
    val rows = (members.size + cols - 1) / cols
    if (rows <= 3) {
        Column(Modifier.fillMaxSize().padding(GridInset.dp), verticalArrangement = Arrangement.spacedBy(GridSpacing.dp)) {
            members.chunked(cols).forEach { row ->
                // A shorter last row keeps the full rows' tile width, centred.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridSpacing.dp)) {
                    val gap = (cols - row.size) / 2f
                    if (gap > 0) Spacer(Modifier.weight(gap))
                    row.forEach { m ->
                        MemberTile(m, m.id == meId, Modifier.fillMaxHeight().weight(1f)) { onTile(m.id) }
                    }
                    if (gap > 0) Spacer(Modifier.weight(gap))
                }
            }
        }
    } else {
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = GridInset.dp),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing.dp), verticalArrangement = Arrangement.spacedBy(GridSpacing.dp)) {
            items(members, key = { it.id }) { m -> MemberTile(m, m.id == meId, Modifier.fillMaxWidth().height(220.dp)) { onTile(m.id) } }
        }
    }
}

/**
 * One person's tile (iOS GroupCallTileView): video filling it, or their round photo centred on the
 * dark tile; the name ("You" on mine) in a small dark pill at the bottom start with the slashed mic
 * inside it while muted (a lone round badge on strip tiles); a green rim and glow while speaking.
 */
@Composable
private fun MemberTile(m: GroupMember, isMe: Boolean, modifier: Modifier, large: Boolean = false, strip: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val glow = if (m.speaking) Modifier.shadow(10.dp, shape, clip = false, ambientColor = Green, spotColor = Green) else Modifier
    BoxWithConstraints(modifier.then(glow).clip(shape).background(TileFill)
        .then(if (m.speaking) Modifier.border(2.dp, Green.copy(alpha = 0.95f), shape) else Modifier)
        .clickable(onClick = onClick)
        .semantics { contentDescription = listOfNotNull(if (isMe) "You" else m.name, "muted".takeIf { !m.micOn }, "speaking".takeIf { m.speaking }).joinToString(", ") },
        contentAlignment = Alignment.Center) {
        val v = m.video
        val w = maxWidth
        val avatar = when { w > 180.dp -> 112.dp; w > 102.dp -> 96.dp; w > 48.dp -> w - 36.dp; else -> 16.dp }
            .coerceAtMost((maxHeight - 16.dp).coerceAtLeast(16.dp))
        if (v != null) LiveKitVideo(v, Modifier.fillMaxSize()) else Avatar(null, avatar)
        val inset = if (w >= 170.dp) 8.dp else 4.dp
        if (!strip && w >= 90.dp) {
            Row(Modifier.align(Alignment.BottomStart).padding(inset).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.micOn && !large) { Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                Text(if (isMe) "You" else m.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (!m.micOn) {
            Box(Modifier.align(Alignment.BottomStart).padding(inset).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The people in the call (iOS GroupCallParticipantsSheet's "In call" list): photo, name, mute mark. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(g: GroupCallUi, meId: String?, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1E), contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(g.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("In call", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(" · ${g.members.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.06f))) {
                g.members.forEach { m ->
                    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(null, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(if (m.id == meId) "You" else m.name,
                            color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(if (m.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, if (m.micOn) null else "Muted",
                            tint = Color.White.copy(alpha = if (m.micOn) 0.35f else 0.9f), modifier = Modifier.size(20.dp))
                    }
                }
            }
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
