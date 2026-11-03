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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.animation.core.animateFloat

import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.heightIn
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.aspectRatio
import com.fariin.app.R
import com.fariin.app.call.CallManager
import com.fariin.app.call.SharePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack

// iOS keeps the call screens flat black (no gradient, no blurred photo: its 2026-07-11 decision).
private val Backdrop = Color.Black
private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
/** iOS noAnswerBar's tint (0x22C55E). */
private val NoAnswerGreen = Color(0xFF22C55E)
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
 * and one dark capsule: share, camera, speaker, mute, end. A ring shows Decline and Accept instead.
 *
 * Screen share (iOS shareLayout, owner 2026-10-10): while I share, or they do, the call is a rounded
 * card under the header ("You're sharing your screen" with Stop Sharing, or their screen whole) with
 * the two people as tiles under it; their screen opens full screen from the card's corner button.
 */
@Composable
fun CallScreen(
    ui: CallUi,
    onAccept: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit,
    onMute: () -> Unit, onRoute: (AudioRoute) -> Unit, onCamera: () -> Unit, onFlip: () -> Unit,
    onMinimize: (() -> Unit)? = null, onShare: () -> Unit = {},
    onCallAgain: () -> Unit = {}, onVoiceMessage: () -> Unit = {}, onCloseNoAnswer: () -> Unit = {},
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.status.connectedAtMs) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ui.status
    val connected = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING
    val remoteVideo = ui.remoteVideo.takeIf { ui.remoteCameraOn && s.phase != CallPhase.INCOMING }
    val demoVideo = ui.peerUid == "demo" && ui.remoteCameraOn && s.phase == CallPhase.ACTIVE
    // Calling on video: my own preview fills the screen until they pick up (iOS showLocalFull).
    val localFull = ui.cameraOn && s.phase == CallPhase.OUTGOING
    val anyVideo = ui.cameraOn || ui.remoteCameraOn || ui.sharing || ui.remoteScreen
    val chrome = rememberChrome(connected && anyVideo)
    // The header and the capsule fade: in over 0.2 s, away over 0.28 s, ease in and out (iOS).
    val shown by animateFloatAsState(if (chrome.visible) 1f else 0f,
        tween(if (chrome.visible) 200 else 280, easing = EaseInOut), label = "chrome")
    val local = ui.localVideo
    val cardUp = ui.cameraOn && !localFull && s.phase != CallPhase.INCOMING && s.phase != CallPhase.ENDED
    // A tap on my card swaps the two pictures: mine big, theirs in the card (iOS isLocalExpanded).
    var swapped by remember { mutableStateOf(false) }
    LaunchedEffect(cardUp) { if (!cardUp) swapped = false }
    // Their screen on the stage (their camera's track carries it), or the demo's made-up one.
    val theirScreen = ui.remoteScreen && connected && (remoteVideo != null || ui.peerUid == "demo")
    var shareFull by remember { mutableStateOf(false) }
    LaunchedEffect(theirScreen) { if (!theirScreen) shareFull = false }
    val shareLayout = connected && (ui.sharing || (theirScreen && !shareFull))
    // A share (mine or theirs) starts with the controls hidden; a tap brings them (iOS, 2026-10-10).
    LaunchedEffect(shareLayout) { if (shareLayout) chrome.visible = false }
    LaunchedEffect(shareLayout) { if (shareLayout) swapped = false }
    val mineBig = !shareLayout && !shareFull && (localFull || (swapped && cardUp))
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        // One renderer for the big picture whoever is in it, so a swap only moves the tracks.
        val bigTrack = if (shareLayout || shareFull) null else if (mineBig) local else remoteVideo
        val bigDemo = !shareLayout && !shareFull && bigTrack == null && (if (mineBig) swapped else demoVideo)
        when {
            bigTrack != null -> VideoView(bigTrack, mirror = mineBig && ui.frontCamera, modifier = Modifier.fillMaxSize())
            bigDemo && mineBig -> DemoVideo(Modifier.fillMaxSize(), MeA, MeB)
            bigDemo -> DemoVideo(Modifier.fillMaxSize(), ThemA, ThemB)
        }
        val showFace = bigTrack == null && !bigDemo && !shareLayout && !shareFull
        // Their screen full screen: whole, pinch to zoom, a tap shows or hides the controls.
        if (shareFull) ShareStage(remoteVideo, Modifier.fillMaxSize(), zoomable = true) { chrome.tap(true) }
        // Above the video, under every control: a tap shows or hides the controls.
        else Box(Modifier.fillMaxSize().plainTap { chrome.tap(connected && anyVideo) })
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
                CallPhase.ENDED -> if (ui.noAnswer) NoAnswerCard(s, onCallAgain, onVoiceMessage, onCloseNoAnswer)
                    else Spacer(Modifier.height(BarHeight))
                else -> ControlBar(ui, chrome, onEnd, onMute, onRoute, onCamera, onShare, Modifier.alpha(shown))
            }
            Spacer(Modifier.navigationBarsPadding().height(22.dp))
        }
        // Controls away: the first tap anywhere only brings them back (hidden buttons take no taps).
        if (!chrome.visible) Box(Modifier.fillMaxSize().plainTap { chrome.touch() }.semantics { contentDescription = "Show call controls" })
        // The share card and the two people, above the catch-all so Stop Sharing works with the
        // controls away too; it never reaches under the header or the capsule while they show.
        if (shareLayout) ShareLayout(ui, chrome, remoteVideo, local, onStop = { chrome.touch(); onShare() },
            onFull = { chrome.touch(); shareFull = true })
        // Back from their full-screen share to the card.
        if (shareFull) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 16.dp, bottom = 22.dp + BarHeight + 14.dp)
            .alpha(shown), contentAlignment = Alignment.BottomEnd) {
            if (chrome.visible) HeaderCircle(Icons.Filled.FullscreenExit, "Exit full screen", 40.dp) { chrome.touch(); shareFull = false }
        }
        // My camera: a card at the bottom corner, smaller while the controls are away (iOS pipLayer).
        // Above the catch-all tap, so it can be dragged and tapped while the controls are away too.
        if (cardUp && !shareLayout) {
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
        SharePill(ui)
    }
}

/**
 * The one small pill under the header (iOS topPill): "Amina is sharing their screen" once when
 * their share begins (3 s), or my share's notice when it ended without my Stop (2.5 s).
 */
@Composable
private fun SharePill(ui: CallUi) {
    var text by remember { mutableStateOf<String?>(null) }
    val first = ui.peerName.substringBefore(' ')
    LaunchedEffect(ui.remoteScreen) {
        if (ui.remoteScreen) { text = "$first is sharing their screen"; delay(3_000); text = null }
    }
    LaunchedEffect(ui.shareNotice) {
        val n = ui.shareNotice ?: return@LaunchedEffect
        text = n; delay(2_500); text = null
        CallManager.clearShareNotice()
    }
    val a by animateFloatAsState(if (text != null) 1f else 0f, tween(300, easing = EaseInOut), label = "pill")
    var last by remember { mutableStateOf("") }
    text?.let { last = it }
    if (a > 0f) Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.alpha(a).height(32.dp).darkGlass(CircleShape).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(last, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * iOS shareLayoutView: the card under the header and the two people under it, on a spring when the
 * controls come and go (they rise to the status bar and sink to the bottom edge without them).
 */
@Composable
private fun ShareLayout(ui: CallUi, chrome: Chrome, remote: VideoTrack?, local: VideoTrack?, onStop: () -> Unit, onFull: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val topIn = insets.calculateTopPadding()
    val bottomIn = insets.calculateBottomPadding()
    val spring = spring<Dp>(dampingRatio = 0.9f, stiffness = 322.3f)
    // Clear of the header: its name and clock, and the "Muted" line while they are muted.
    val header = if (ui.remoteMuted) 108.dp else 86.dp
    val top by animateDpAsState(if (chrome.visible) topIn + header else topIn + 8.dp, spring, label = "shareTop")
    val bottom by animateDpAsState(if (chrome.visible) bottomIn + 22.dp + BarHeight + 14.dp else maxOf(bottomIn, 12.dp) + 4.dp, spring, label = "shareBottom")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileH = minOf(150.dp, maxOf(96.dp, maxHeight * 0.17f))
        Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = top, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareCard(ui, chrome, remote, onStop, onFull, Modifier.weight(1f).fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Them: their camera, never their screen (it rides on that same track). Me: my live camera.
                ShareTile(remote.takeIf { ui.remoteCameraOn && !ui.remoteScreen }, false, ui.peerName, ui.peerPhoto, tileH) { chrome.tap(true) }
                ShareTile(local.takeIf { ui.cameraOn && !ui.sharing }, ui.frontCamera, "You", null, tileH) { chrome.tap(true) }
            }
        }
    }
}

/** iOS shareCard: my share's card with Stop Sharing, or their screen whole with the full-screen button. */
@Composable
private fun ShareCard(ui: CallUi, chrome: Chrome, remote: VideoTrack?, onStop: () -> Unit, onFull: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(ShareCardFill).plainTap { chrome.tap(true) }) {
        if (ui.sharing) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                if (ui.sharePhase == SharePhase.STARTING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Starting…", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Icon(painterResource(R.drawable.ic_share_screen), null, tint = Color.White, modifier = Modifier.size(44.dp))
                    Text("You're sharing your screen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Row(Modifier.height(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onStop)
                        .padding(horizontal = 22.dp).semantics { contentDescription = "Stop Sharing" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Sharing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            ShareStage(remote, Modifier.fillMaxSize(), zoomable = false) { chrome.tap(true) }
            Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                HeaderCircle(Icons.Filled.Fullscreen, "Full screen", 40.dp, onFull)
            }
        }
    }
}

/**
 * Their shared screen, whole (never cropped, never mirrored), on a TextureView so the card and the
 * full-screen stage can move and scale it. [zoomable]: pinch and pan, a double tap goes back to 1x.
 * The demo call draws its made-up picture instead.
 */
@Composable
private fun ShareStage(track: VideoTrack?, modifier: Modifier, zoomable: Boolean, onTap: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val gestures = if (zoomable) Modifier
        .pointerInput(Unit) {
            detectTransformGestures { _, p, z, _ ->
                scale = (scale * z).coerceIn(1f, 4f)
                val maxX = size.width * (scale - 1) / 2
                val maxY = size.height * (scale - 1) / 2
                pan = if (scale == 1f) Offset.Zero else Offset((pan.x + p.x).coerceIn(-maxX, maxX), (pan.y + p.y).coerceIn(-maxY, maxY))
            }
        }
        .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = 1f; pan = Offset.Zero }) }
    else Modifier.plainTap(onTap)
    Box(modifier.then(gestures).semantics { contentDescription = "Shared screen" }) {
        val m = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
        if (track != null) ScreenVideoView(track, m) else DemoScreen(m)
    }
}

/** The demo call's made-up shared screen: a phone-shaped page, whole, on the dark stage. */
@Composable
private fun DemoScreen(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight(0.92f).aspectRatio(9f / 19.5f).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFECEFF4), Color(0xFFC9D2DE)))))
    }
}

/** One person under the share card (iOS shareTile): their live camera, else their photo. */
@Composable
private fun ShareTile(track: VideoTrack?, mirror: Boolean, name: String, photo: String?, height: Dp, onTap: () -> Unit) {
    Box(Modifier.size(height * 0.74f, height).clip(RoundedCornerShape(22.dp)).background(ShareTileFill).plainTap(onTap)
        .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        if (track != null) CardVideoView(track, mirror, Modifier.fillMaxSize())
        else Avatar(photo, minOf(64.dp, height * 0.42f))
    }
}

/** A WebRTC track drawn whole into a TextureView (a shared screen: aspect fit, never mirrored). */
@Composable
private fun ScreenVideoView(track: VideoTrack, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        update = { r ->
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

// iOS Color(white: 0.12) for the share card, 0.18 for the tiles under it.
private val ShareCardFill = Color(0xFF1F1F1F)
private val ShareTileFill = Color(0xFF2E2E2E)

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

/**
 * iOS noAnswerBar: under "No answer" / "Couldn't connect", one card with the way forward as a wide
 * green button (Call again, or Try again after a failed connect), and Voice message and Close under it.
 */
@Composable
private fun NoAnswerCard(s: com.fariin.app.call.CallStatus, onCallAgain: () -> Unit, onVoiceMessage: () -> Unit, onClose: () -> Unit) {
    val retry = if (s.endReason == com.fariin.app.call.EndReason.FAILED) "Try again" else "Call again"
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().darkGlass(RoundedCornerShape(30.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(NoAnswerGreen).clickable(onClick = onCallAgain)
                .semantics { contentDescription = retry },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (s.video) Icons.Filled.Videocam else Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(retry, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NoAnswerSecondary(Icons.Filled.Mic, "Voice message", Modifier.weight(1f), onVoiceMessage)
            NoAnswerSecondary(Icons.Filled.Close, "Close", Modifier.weight(1f), onClose)
        }
    }
}

@Composable
private fun NoAnswerSecondary(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).darkGlass(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
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

/** iOS order: share, camera, speaker, mute next to end under the right thumb. Flip is on my camera's card. */
@Composable
private fun ControlBar(ui: CallUi, chrome: Chrome, onEnd: () -> Unit, onMute: () -> Unit, onRoute: (AudioRoute) -> Unit,
                       onCamera: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    ControlCapsule(modifier) {
        val size = 56.dp
        val sharingOn = ui.sharePhase != SharePhase.OFF
        ShareCircle(lit = ui.sharePhase == SharePhase.LIVE, on = sharingOn, enabled = sharingOn || ui.canStartShare, size = size) {
            chrome.touch(); onShare()
        }
        // Dimmed while a share of mine runs or is being started (iOS shareBlocksCamera).
        val camOk = ui.cameraOn || !sharingOn
        Box(Modifier.alpha(if (camOk) 1f else 0.4f)) {
            Circle(if (ui.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, active = !ui.cameraOn,
                label = if (ui.cameraOn) "Turn camera off" else "Turn camera on", size = size) { chrome.touch(); if (camOk) onCamera() }
        }
        SpeakerButton(ui, size) { chrome.touch(); onRoute(it) }
        Circle(if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic, active = ui.muted,
            label = if (ui.muted) "Unmute" else "Mute", size = size) { chrome.touch(); onMute() }
        EndCircle("End call", size, onEnd)
    }
}

/**
 * Share Screen (iOS shareCircle): dimmed = not yet (no connected call, or they are sharing), glass =
 * ready, solid white with a dark glyph = my screen is really going out. Stopping is never disabled.
 */
@Composable
private fun ShareCircle(lit: Boolean, on: Boolean, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val label = if (lit) "Stop Sharing" else if (on) "Cancel Sharing" else "Share Screen"
    Box(Modifier.alpha(if (enabled) 1f else 0.4f).size(size)
        .then(if (lit) Modifier.clip(CircleShape).background(GlassOn) else Modifier.darkGlass(CircleShape))
        .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_share_screen), null, tint = if (lit) Color.Black else Color.White, modifier = Modifier.size(size * 0.42f))
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

/**
 * The picture-in-picture window, as the iOS one carries the whole call: their video big with mine
 * in a corner tile (mine big while theirs is off), else their photo with the name and the stage
 * words or clock. A mic-off badge while they are muted, and one on my tile (or by the name) while I am.
 */
@Composable
fun PipScreen(ui: CallUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.status.connectedAtMs) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ui.status
    val connected = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING
    val status = if (ui.held && s.live) "On hold" else CallMachine.label(s, now)
    val theirMute = ui.remoteMuted && connected
    Box(Modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
        val remote = ui.remoteVideo.takeIf { ui.remoteCameraOn }
        val local = ui.localVideo.takeIf { ui.cameraOn }
        val demoThem = ui.peerUid == "demo" && ui.remoteCameraOn
        val bigIsMine = remote == null && !demoThem && local != null
        when {
            remote != null -> VideoView(remote, mirror = false, modifier = Modifier.fillMaxSize(), fit = ui.remoteScreen)
            demoThem -> DemoVideo(Modifier.fillMaxSize(), ThemA, ThemB)
            local != null -> VideoView(local, mirror = ui.frontCamera, modifier = Modifier.fillMaxSize())
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Avatar(ui.peerPhoto, 56.dp, Modifier.semantics { contentDescription = ui.peerName })
                Spacer(Modifier.height(6.dp))
                Text(ui.peerName.substringBefore(' '), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
                if (ui.muted) PipMicOff("You are muted", Modifier.padding(top = 4.dp))
            }
        }
        val video = remote != null || demoThem || local != null
        if (video) {
            // The stage words or clock along the bottom over the video.
            Text(status, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            // My tile in the corner while their video is big.
            if (!bigIsMine && local != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp, 64.dp).clip(RoundedCornerShape(8.dp))) {
                VideoView(local, mirror = ui.frontCamera, modifier = Modifier.fillMaxSize(), overlay = true)
            }
            if (ui.muted) PipMicOff("You are muted", Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        if (theirMute) PipMicOff("${ui.peerName.substringBefore(' ')} is muted", Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

/** The small round mic-off badge of the picture-in-picture window. */
@Composable
private fun PipMicOff(label: String, modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(14.dp))
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
fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier = Modifier, overlay: Boolean = false, fit: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
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
 * A group call after iOS GroupCallView: the header (the title over the status line, the people
 * button at the end), the stage, the link creator's request cards, and the capsule: camera, mute,
 * end. The header and capsule step aside on video as the 1:1 screen does. A group chat's call
 * with nobody else here yet (and my camera off) shows the group's photo on black with the pulse
 * while it rings (iOS groupWaiting + GroupCallRingingView). [GroupCallUi.members] lists me first.
 */
@Composable
fun GroupCallScreen(g: GroupCallUi, onMic: () -> Unit, onCamera: () -> Unit, onLeave: () -> Unit, onShare: () -> Unit = {}) {
    var focus by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf(false) }
    val me = g.members.firstOrNull { it.local } ?: g.members.firstOrNull()
    val meId = me?.id
    val remotes = g.members.filter { it.id != meId }
    LaunchedEffect(g.members.map { it.id }) { if (focus != null && remotes.none { it.id == focus }) focus = null }
    // iOS: no pin in the two-person look (the other person is the whole stage already).
    LaunchedEffect(remotes.size) { if (remotes.size < 2) focus = null }
    val allowed = g.joined && !people && g.requests.isEmpty() && (g.cameraOn || g.members.any { it.video != null || it.screen != null })
    // A shared screen is what the room is looking at: its presenter takes the stage unless I picked
    // someone (iOS GroupCallStage: the user's pin, then the presenter, else the grid).
    val presenter = remotes.firstOrNull { it.screen != null }?.id
    var announced by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(presenter) {
        val p = remotes.firstOrNull { it.id == presenter }
        if (p != null && announced != p.id) { announced = p.id; GroupCall.say("${p.name.substringBefore(' ')} is sharing their screen") }
        if (p == null) announced = null
    }
    val chrome = rememberChrome(allowed)
    val shown by animateFloatAsState(if (chrome.visible) 1f else 0f, tween(280), label = "chrome")
    val waitingScreen = g.linkRoom == null && !g.cameraOn && !g.waiting && (g.connecting || (g.joined && remotes.isEmpty()))
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        if (waitingScreen) GroupWaiting(g.groupPhoto, pulse = g.calling)
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = com.fariin.app.call.GroupCallWords.subtitle(g.waiting, g.connecting, g.reconnecting, g.joined, g.calling, g.members.size)
            CallHeader(
                name = g.title, status = status, muted = false, mutedLabel = "", left = null, side = 44.dp,
                right = { HeaderCircle(Icons.Filled.People, "Participants", 44.dp) { chrome.touch(); people = true } },
                modifier = Modifier.alpha(shown),
            )
            Spacer(Modifier.height(2.dp))
            Box(Modifier.weight(1f).fillMaxWidth().plainTap { chrome.tap(allowed) }) {
                if (!waitingScreen) GroupStage(g.members, meId, g.activeSpeaker, focus ?: presenter, g.sharing, onTile = { id ->
                    chrome.touch()
                    // My own tile does nothing (iOS); a tap on the large one goes back.
                    focus = if ((focus ?: presenter) == id || id == meId || (remotes.size < 2 && presenter == null)) null else id
                })
            }
            if (g.linkCreator && g.requests.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CallLinkRequestStack(g.requests, Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.height(16.dp))
            ControlCapsule(Modifier.alpha(shown)) {
                val shareOn = g.sharing || g.sharePending
                ShareCircle(lit = g.sharing, on = shareOn, enabled = shareOn || (g.joined && presenter == null), size = 56.dp) {
                    chrome.touch(); onShare()
                }
                Circle(if (g.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, !g.cameraOn,
                    if (g.cameraOn) "Turn camera off" else "Turn camera on", 56.dp) { chrome.touch(); onCamera() }
                Circle(if (g.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, !g.micOn, if (g.micOn) "Mute" else "Unmute", 56.dp) {
                    chrome.touch(); onMic()
                }
                EndCircle("End call", 56.dp, onLeave)
            }
        }
        if (!chrome.visible) Box(Modifier.fillMaxSize().plainTap { chrome.touch() }.semantics { contentDescription = "Show call controls" })
        if (people) GroupCallParticipantsSheet(g, meId, onFocus = { id -> if (remotes.size >= 2) focus = id; people = false }) { people = false }
    }
}

/**
 * iOS GroupCallRingingView as a group chat's call uses it while nobody has come: the group's
 * photo (160) a little above the middle on plain black, two white rings 1.2 s apart pulsing out of
 * it (each 2.4 s, ease-out, to 1.5 x, fading) while the call rings. No words: the header says them.
 */
@Composable
fun GroupWaiting(photo: String?, pulse: Boolean, size: Dp = 160.dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        tween(2400, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
            if (pulse) for (i in 0..1) {
                val p = (phase + i * 0.5f) % 1f
                val e = 1 - (1 - p) * (1 - p)
                Box(Modifier.size(size).graphicsLayer { scaleX = 1 + 0.5f * e; scaleY = 1 + 0.5f * e; alpha = 0.9f * (1 - e) }
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape))
            }
            Box(Modifier.shadow(26.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))) {
                Avatar(photo, size, Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
            }
        }
    }
}

/** iOS GroupCallMotion.layout: spring(response 0.38, damping 1). */
private fun <T> stageSpring() = spring<T>(dampingRatio = 1f, stiffness = 273f)

/**
 * The stage (iOS GroupCallGridView, GroupCallFocusView, GroupCallSelfView). Alone: my tile fills
 * it. One other: they fill it and my camera is the 90 x 160 card at the bottom right (a tap makes it
 * 170 x 300). Two or more: the grid (GroupCallLayout, me first) with the rest in the 96 strip under
 * it, the active speaker always on the grid. A tapped person goes large over the strip of everyone
 * else. Every tile moves to its new frame on the one stage spring.
 */
@Composable
private fun GroupStage(members: List<GroupMember>, meId: String?, speaker: String?, focus: String?, iAmSharing: Boolean, onTile: (String) -> Unit) {
    val me = members.firstOrNull { it.id == meId }
    val remotes = members.filter { it.id != meId }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val big = remotes.firstOrNull { it.id == focus }
        when {
            remotes.isEmpty() -> if (me != null) MemberTile(me, true, false, Modifier.fillMaxSize(), alone = true, iAmSharing = iAmSharing) {}
            big != null -> FocusStage(big, members.filter { it.id != big.id }, meId, speaker, iAmSharing, onTile)
            else -> {
                val full = com.fariin.app.call.GroupCallLayout.capacity(w, h)
                val cells = if (remotes.size >= 2) members.size else remotes.size
                val needsStrip = cells > full
                val gridH = if (needsStrip) h - (com.fariin.app.call.GroupCallMetrics.STRIP_HEIGHT - com.fariin.app.call.GroupCallMetrics.INSET) else h
                val cap = com.fariin.app.call.GroupCallLayout.capacity(w, gridH)
                val ids = com.fariin.app.call.GroupStagePlan.gridIds(meId, remotes.map { it.id }, speaker, cap)
                val layout = com.fariin.app.call.GroupCallLayout.grid(ids, w, gridH)
                val byId = members.associateBy { it.id }
                ids.forEach { id ->
                    val f = layout.frames[id] ?: return@forEach
                    val m = byId[id] ?: return@forEach
                    androidx.compose.runtime.key(id) {
                        val x by animateDpAsState(f.x.dp, stageSpring(), label = "x")
                        val y by animateDpAsState(f.y.dp, stageSpring(), label = "y")
                        val tw by animateDpAsState(f.w.dp, stageSpring(), label = "w")
                        val th by animateDpAsState(f.h.dp, stageSpring(), label = "h")
                        MemberTile(m, m.id == meId, m.id == speaker && m.id != meId, Modifier.offset(x, y).size(tw, th), iAmSharing = iAmSharing) { onTile(m.id) }
                    }
                }
                if (layout.overflow.isNotEmpty()) {
                    Strip(layout.overflow.mapNotNull { byId[it] }, meId, speaker, iAmSharing, onTile,
                        Modifier.align(Alignment.BottomStart).offset(y = 0.dp))
                }
                if (remotes.size == 1 && me != null) SelfCard(me, w, h, iAmSharing, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** One person large (iOS GroupCallFocusView) over the strip of everyone else, me among them. */
@Composable
private fun FocusStage(big: GroupMember, others: List<GroupMember>, meId: String?, speaker: String?, iAmSharing: Boolean, onTile: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MemberTile(big, false, big.id == speaker, Modifier.weight(1f).fillMaxWidth().padding(com.fariin.app.call.GroupCallMetrics.INSET.dp), large = true) { onTile(big.id) }
        Strip(others, meId, speaker, iAmSharing, onTile, Modifier)
    }
}

/** iOS GroupCallStripView: 96 squares 4 apart, 16 from the leading edge, 12 under it. */
@Composable
private fun Strip(people: List<GroupMember>, meId: String?, speaker: String?, iAmSharing: Boolean, onTile: (String) -> Unit, modifier: Modifier) {
    val m = com.fariin.app.call.GroupCallMetrics
    LazyRow(modifier.fillMaxWidth().height(m.STRIP_HEIGHT.dp).padding(top = m.INSET.dp, bottom = m.STRIP_INSET.dp),
        horizontalArrangement = Arrangement.spacedBy(m.STRIP_SPACING.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = m.STRIP_LEADING.dp, end = m.STRIP_LEADING.dp)) {
        items(people, key = { it.id }) { p ->
            MemberTile(p, p.id == meId, p.id == speaker && p.id != meId, Modifier.size(m.STRIP_TILE.dp), strip = true, iAmSharing = iAmSharing) { if (p.id != meId) onTile(p.id) }
        }
    }
}

/** My card with one other person here (iOS GroupCallSelfView): 90 x 160, a tap grows it on a 0.3 s spring. */
@Composable
private fun SelfCard(me: GroupMember, stageW: Float, stageH: Float, iAmSharing: Boolean, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val (tw, th) = com.fariin.app.call.GroupStagePlan.selfSize(expanded, stageW, stageH)
    val anim = spring<Dp>(dampingRatio = 1f, stiffness = 439f)
    val w by animateDpAsState(tw.dp, anim, label = "selfW")
    val h by animateDpAsState(th.dp, anim, label = "selfH")
    val shape = RoundedCornerShape(com.fariin.app.call.GroupCallMetrics.TILE_CORNER.dp)
    Box(modifier.padding(end = com.fariin.app.call.GroupCallMetrics.SELF_TRAILING.dp, bottom = com.fariin.app.call.GroupCallMetrics.STRIP_INSET.dp)
        .shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))) {
        MemberTile(me, true, false, Modifier.size(w, h), pip = true, iAmSharing = iAmSharing) { expanded = !expanded }
    }
}

/**
 * One person's tile (iOS GroupCallTileView): video filling it, or the camera-off look (a dark
 * gradient with their round photo centred, sized by iOS avatarSize); the name ("You" on mine) in
 * a dark pill at the bottom start with the slashed mic inside it while muted (a lone round badge
 * on strip and pip tiles); the poor-network mark at the top start; the green rim and glow fading
 * in over 0.2 s on the active speaker (iOS SpeakerGlow).
 */
@Composable
private fun MemberTile(
    m: GroupMember, isMe: Boolean, activeSpeaker: Boolean, modifier: Modifier,
    large: Boolean = false, strip: Boolean = false, pip: Boolean = false, alone: Boolean = false, iAmSharing: Boolean = false,
    onClick: () -> Unit,
) {
    val metrics = com.fariin.app.call.GroupCallMetrics
    val shape = RoundedCornerShape(if (alone) 0.dp else metrics.TILE_CORNER.dp)
    val glow by animateFloatAsState(if (activeSpeaker) 1f else 0f, tween(200), label = "glow")
    Box(modifier) {
        if (glow > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = glow }
            .shadow(14.dp, shape, clip = false, ambientColor = Green.copy(alpha = 0.35f), spotColor = Green.copy(alpha = 0.75f)))
        BoxWithConstraints(Modifier.matchParentSize().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF383838), Color(0xFF1F1F1F))))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(if (isMe) "You" else m.name, m.role.badge?.lowercase(), "speaking".takeIf { activeSpeaker },
                    "muted".takeIf { !m.micOn }, "camera off".takeIf { m.video == null }, "presenting".takeIf { m.screen != null },
                    "poor connection".takeIf { m.poor }).joinToString(", ")
            },
            contentAlignment = Alignment.Center) {
            val w = maxWidth.value
            val hh = maxHeight.value
            val v = m.video
            // The large tile shows a presenter's screen whole (never cropped, never mirrored); a strip
            // shows their face, not a second unreadable copy of the screen (iOS preferScreen).
            val scr = m.screen?.takeIf { large }
            if (scr != null) LiveKitVideo(scr, Modifier.fillMaxSize(), fit = true)
            else if (v != null) LiveKitVideo(v, Modifier.fillMaxSize(), mirror = isMe)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(m.photo, com.fariin.app.call.GroupStagePlan.avatarSize(w, hh).dp)
                if (alone) {
                    Spacer(Modifier.height(10.dp))
                    Text(m.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            val inset = if (w >= 170) 8.dp else 4.dp
            if (!strip && !pip && !alone && w >= 90) {
                Row(Modifier.align(Alignment.BottomStart).padding(inset).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!m.micOn && !large) { Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(if (isMe) "You" else m.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (!m.micOn && !large) {
                val compact = pip && w < 60
                Box(Modifier.align(Alignment.BottomStart).padding(inset).size(if (compact) 16.dp else 28.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(if (compact) 9.dp else 16.dp))
                }
            }
            // I am sharing my screen (owner 2026-10-28): my own tile says so (iOS sharingCard).
            if (isMe && iAmSharing) Column(Modifier.fillMaxSize().background(ShareCardFill.copy(alpha = 0.92f)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                Icon(painterResource(R.drawable.ic_share_screen), null, tint = Color.White, modifier = Modifier.size(if (w >= 150) 32.dp else 20.dp))
                if (w >= 150) Text("You're sharing your screen", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp))
            }
            if (m.poor && !(pip && w < 60)) {
                Box(Modifier.align(if (large) Alignment.TopEnd else Alignment.TopStart).padding(inset).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SignalWifiStatusbarConnectedNoInternet4, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        if (glow > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = glow }
            .border(metrics.SPEAKING_BORDER.dp, Green.copy(alpha = 0.95f), shape))
    }
}

/**
 * The people in the call (iOS GroupCallParticipantsSheet): always dark, full height, "Participants"
 * ("Waiting for others" while I am alone) with a close button at the end; the link creator's
 * "N waiting to join" row; then "In call · N": a 36 photo with a thin green ring on the active
 * speaker, the name (semibold while speaking, "You" on mine), the Host / Admin badge under it,
 * and only the marks that say something: poor network, camera on, muted. A tap on someone shows
 * them large and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCallParticipantsSheet(g: GroupCallUi, meId: String?, onFocus: (String) -> Unit, onClose: () -> Unit) {
    var showRequests by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black, contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp)) {
                Text(com.fariin.app.call.GroupCallWords.sheetTitle(g.members.size <= 1), color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            if (g.linkCreator && g.requests.isNotEmpty()) {
                SheetSection {
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable { showRequests = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PersonAdd, null, tint = Color(0xFF0A84FF), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(com.fariin.app.call.GroupCallWords.waitingTitle(g.requests.size), color = Color(0xFF0A84FF), fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(Modifier.padding(horizontal = 32.dp, vertical = 6.dp)) {
                Text("In call", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(" · ${g.members.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            SheetSection {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(g.members, key = { it.id }) { m ->
                        val mine = m.id == meId
                        val speaking = m.id == g.activeSpeaker && !mine
                        val ring by animateFloatAsState(if (speaking) 1f else 0f, tween(200), label = "ring")
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .then(if (mine) Modifier else Modifier.clickable { onFocus(m.id) })
                            .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Avatar(m.photo, 36.dp)
                                Box(Modifier.size(44.dp).graphicsLayer { alpha = ring }.border(2.dp, Green, CircleShape))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (mine) "You" else m.name, color = Color.White, fontSize = 17.sp,
                                    fontWeight = if (speaking) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                m.role.badge?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
                            }
                            Spacer(Modifier.width(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (m.poor) Icon(Icons.Filled.SignalWifiStatusbarConnectedNoInternet4, "Poor connection", tint = Color(0xFFFF9F0A), modifier = Modifier.size(20.dp))
                                if (m.video != null) Icon(Icons.Filled.Videocam, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                if (!m.micOn) Icon(Icons.Filled.MicOff, "Muted", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showRequests) CallLinkBulkRequestsSheet(g.requests) { showRequests = false }
}

/** iOS inset grouped section on the dark sheet. */
@Composable
private fun SheetSection(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1E))) { content() }
}

/**
 * A LiveKit camera track in a tile, on a TextureView renderer: tiles change size on the stage
 * spring and the self card grows, and a TextureView scales with its layout where a SurfaceView
 * would tear or flash.
 */
@Composable
private fun LiveKitVideo(track: io.livekit.android.room.track.VideoTrack, modifier: Modifier, mirror: Boolean = false, fit: Boolean = false) {
    val room = GroupCall.room() ?: return
    AndroidView(modifier = modifier, factory = { ctx ->
        TextureViewRenderer(ctx).also {
            room.initVideoRenderer(it)
            it.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }, update = { r ->
        r.setMirror(mirror)
        val old = r.tag as? io.livekit.android.room.track.VideoTrack
        if (old !== track) { old?.removeRenderer(r); track.addRenderer(r); r.tag = track }
    }, onRelease = { r ->
        (r.tag as? io.livekit.android.room.track.VideoTrack)?.removeRenderer(r)
        r.release()
    })
}
